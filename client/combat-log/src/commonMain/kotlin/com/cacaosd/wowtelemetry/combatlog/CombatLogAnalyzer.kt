package com.cacaosd.wowtelemetry.combatlog

import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.CombatEvent
import com.cacaosd.wowtelemetry.domain.CombatLogVersionEvent
import com.cacaosd.wowtelemetry.domain.CombatantInfoEvent
import com.cacaosd.wowtelemetry.domain.CommonHeader
import com.cacaosd.wowtelemetry.domain.DamageShieldEvent
import com.cacaosd.wowtelemetry.domain.EncounterEndEvent
import com.cacaosd.wowtelemetry.domain.EncounterStartEvent
import com.cacaosd.wowtelemetry.domain.EnvironmentalDamageEvent
import com.cacaosd.wowtelemetry.domain.HasCommonHeader
import com.cacaosd.wowtelemetry.domain.HasSpellPrefix
import com.cacaosd.wowtelemetry.domain.RangeDamageEvent
import com.cacaosd.wowtelemetry.domain.SpellAbsorbedEvent
import com.cacaosd.wowtelemetry.domain.SpellCastSuccessEvent
import com.cacaosd.wowtelemetry.domain.SpellDamageEvent
import com.cacaosd.wowtelemetry.domain.SpellHealEvent
import com.cacaosd.wowtelemetry.domain.SpellInstakillEvent
import com.cacaosd.wowtelemetry.domain.SpellInterruptEvent
import com.cacaosd.wowtelemetry.domain.SpellPeriodicDamageEvent
import com.cacaosd.wowtelemetry.domain.SpellPeriodicHealEvent
import com.cacaosd.wowtelemetry.domain.SpellSummonEvent
import com.cacaosd.wowtelemetry.domain.SwingDamageEvent
import com.cacaosd.wowtelemetry.domain.UnitDiedEvent

/**
 * Deterministic running statistics over processed combat events. Counts only party, raid and
 * own-character sources/targets. Not thread-safe; callers serialize access.
 *
 * Damage calculation uses the community standard formula:
 *   effectiveDamage = max(0, baseAmount + blocked + resisted - max(0, overkill))
 * SWING_DAMAGE_LANDED, *_SUPPORT, and DAMAGE_SPLIT events are excluded.
 *
 * Healing uses:
 *   effectiveHealing = max(0, amount - overheal)
 * plus non-excluded SPELL_ABSORBED shield amounts (excluding cheat-death and stagger mechanics).
 *
 * Pet damage, healing, and interrupts are attributed to their owner.
 */
class CombatLogAnalyzer(
    private val maxEncounters: Int = MAX_ENCOUNTERS,
) {
    private val petRegistry = PetRegistry()
    private val playerMap = LinkedHashMap<String, CombatPlayer>()
    private var localProfile: CharacterProfile? = null
    private var overall = Stats(petRegistry)
    private val encounters = ArrayDeque<EncounterStats>()
    private var current: EncounterStats? = null

    fun setCharacterProfile(profile: CharacterProfile?) {
        localProfile = profile
        if (profile != null && profile.characterName.isNotBlank() && profile.characterClass.isNotBlank()) {
            for ((guid, player) in playerMap.entries.toList()) {
                if (player.name.equals(profile.characterName, ignoreCase = true) && player.className == null) {
                    playerMap[guid] = player.copy(className = profile.characterClass)
                }
            }
        }
    }

    val players: List<CombatPlayer>
        get() {
            val profName = localProfile?.characterName
            return playerMap.values.sortedWith(
                compareBy<CombatPlayer> { !(profName != null && it.name.equals(profName, ignoreCase = true)) }
                    .thenBy { it.name }
            )
        }

    fun reset() {
        petRegistry.clear()
        playerMap.clear()
        overall = Stats(petRegistry)
        encounters.clear()
        current = null
    }

    fun accept(combatEvent: CombatEvent) {
        val event = combatEvent.event
        if (event is CombatLogVersionEvent) {
            petRegistry.clear()
        }

        learnMetadata(event)

        if (event is EncounterStartEvent) {
            current?.let(::archive)
            current = EncounterStats(
                event.encounterName,
                event.encounterId,
                combatEvent.timestamp,
                event.groupSize,
                petRegistry
            )
        }
        overall.accept(combatEvent)
        current?.stats?.accept(combatEvent)
        if (event is EncounterEndEvent) {
            current?.let {
                it.endTimestamp = combatEvent.timestamp
                it.success = event.success
                it.durationMs = event.durationMs
                archive(it)
            }
            current = null
        }
    }

    val eventCount: Long get() = overall.eventCount

    val hasData: Boolean get() = eventCount > 0

    /** True between an `ENCOUNTER_START` and its `ENCOUNTER_END`. */
    val hasOpenEncounter: Boolean get() = current != null

    val firstTimestamp: String? get() = overall.firstTimestamp

    val lastTimestamp: String? get() = overall.lastTimestamp

    /** Compact text for the LLM: only computed results, never raw log rows. */
    fun summaryText(topN: Int = DEFAULT_TOP_N): String {
        if (!hasData) return "No combat events have been processed yet."
        return buildString {
            appendLine("Events processed: ${overall.eventCount} (${overall.firstTimestamp} to ${overall.lastTimestamp})")
            appendLine("Scope: party, raid and own-character units only.")
            overall.appendTo(this, topN, durationMs = null, indent = "")
            val all = encounters + listOfNotNull(current)
            if (all.isNotEmpty()) {
                appendLine("Encounters (${all.size}, oldest first):")
                all.forEachIndexed { index, encounter ->
                    appendLine("${index + 1}. ${encounter.header()}")
                    encounter.stats.appendTo(this, ENCOUNTER_TOP_N, encounter.durationMs, indent = "   ")
                }
            }
        }.trimEnd()
    }

    private fun archive(encounter: EncounterStats) {
        encounters.addLast(encounter)
        while (encounters.size > maxEncounters) encounters.removeFirst()
    }

    private fun learnMetadata(event: Any?) {
        when (event) {
            is CombatantInfoEvent -> {
                val guid = event.playerGuid ?: return
                val specInfo = event.specId?.let { SPEC_MAP[it] }
                val current = playerMap[guid]
                val name = current?.name ?: petRegistry.guidToName[guid] ?: guid
                val className = specInfo?.className ?: current?.className ?: resolveClassFromProfile(name)
                val specName = specInfo?.specName ?: current?.specName
                playerMap[guid] = CombatPlayer(guid = guid, name = name, className = className, specName = specName)
            }

            is SpellSummonEvent -> {
                learnHeaderNames(event.header)
                if (isGroup(event.header.sourceFlags)) {
                    registerOwner(event.header.destGuid, event.header.sourceGuid)
                }
            }

            is SpellAbsorbedEvent -> {
                learnName(event.attackerGuid, event.attackerName)
                learnName(event.defenderGuid, event.defenderName)
                learnName(event.absorberGuid, event.absorberName)
            }

            else -> {
                if (event is HasCommonHeader) {
                    learnHeaderNames(event.header)
                    val ownerGuid = extractOwnerGuid(event)
                    val sourceGuid = event.header.sourceGuid
                    val sourceFlags = event.header.sourceFlags
                    if (ownerGuid != null && sourceGuid != null && isGroup(sourceFlags)) {
                        registerOwner(sourceGuid, ownerGuid)
                    }

                    val srcName = event.header.sourceName
                    if (sourceGuid != null && isPlayer(sourceFlags) && isGroup(sourceFlags)) {
                        trackPlayer(
                            guid = sourceGuid,
                            name = srcName,
                            spellName = (event as? HasSpellPrefix)?.spell?.spellName
                        )
                    }

                    val destGuid = event.header.destGuid
                    val destName = event.header.destName
                    val destFlags = event.header.destFlags
                    if (destGuid != null && isPlayer(destFlags) && isGroup(destFlags)) {
                        trackPlayer(
                            guid = destGuid,
                            name = destName,
                            spellName = null
                        )
                    }
                }
            }
        }
    }

    private fun trackPlayer(guid: String, name: String?, spellName: String?) {
        val current = playerMap[guid]
        val resolvedName = name ?: current?.name ?: petRegistry.guidToName[guid] ?: guid
        var className = current?.className
        val specName = current?.specName

        if (className == null && spellName != null) {
            className = SPELL_TO_CLASS[spellName]
        }
        if (className == null) {
            className = resolveClassFromProfile(resolvedName)
        }

        playerMap[guid] = CombatPlayer(
            guid = guid,
            name = resolvedName,
            className = className,
            specName = specName,
        )
    }

    private fun resolveClassFromProfile(name: String): String? {
        val profile = localProfile ?: return null
        return if (profile.characterName.isNotBlank() && profile.characterName.equals(name, ignoreCase = true)) {
            profile.characterClass.takeIf { it.isNotBlank() }
        } else null
    }

    private fun registerOwner(petGuid: String?, ownerGuid: String?) {
        if (petGuid == null || ownerGuid == null) return
        if (ownerGuid == petGuid || ownerGuid.isEmpty() || ownerGuid == "0000000000000000" || ownerGuid == "0x0") return
        val existing = petRegistry.petToOwner[petGuid]
        if (existing != ownerGuid) {
            petRegistry.registerOwner(petGuid, ownerGuid)
            overall.reassignPet(petGuid, ownerGuid)
            current?.stats?.reassignPet(petGuid, ownerGuid)
        }
    }

    private fun learnName(guid: String?, name: String?) {
        if (guid == null || name == null) return
        val old = petRegistry.guidToName[guid]
        if (old != name) {
            petRegistry.learnName(guid, name)
            overall.onNameLearned(guid, name)
            current?.stats?.onNameLearned(guid, name)
            val p = playerMap[guid]
            if (p != null && (p.name == guid || p.name != name)) {
                val cls = p.className ?: resolveClassFromProfile(name)
                playerMap[guid] = p.copy(name = name, className = cls)
            }
        }
    }

    private fun learnHeaderNames(header: CommonHeader) {
        learnName(header.sourceGuid, header.sourceName)
        learnName(header.destGuid, header.destName)
    }

    private fun extractOwnerGuid(event: Any?): String? = when (event) {
        is SpellDamageEvent -> event.advancedBlock?.ownerGuid
        is SpellPeriodicDamageEvent -> event.advancedBlock?.ownerGuid
        is RangeDamageEvent -> event.advancedBlock?.ownerGuid
        is DamageShieldEvent -> event.advancedBlock?.ownerGuid
        is SwingDamageEvent -> event.advancedBlock?.ownerGuid
        is SpellCastSuccessEvent -> event.advancedBlock?.ownerGuid
        is SpellHealEvent -> event.advancedBlock?.ownerGuid
        is SpellPeriodicHealEvent -> event.advancedBlock?.ownerGuid
        else -> null
    }

    private class PetRegistry {
        val guidToName = HashMap<String, String>()
        val petToOwner = HashMap<String, String>()

        fun clear() {
            guidToName.clear()
            petToOwner.clear()
        }

        fun learnName(guid: String, name: String) {
            guidToName[guid] = name
        }

        fun registerOwner(petGuid: String, ownerGuid: String) {
            petToOwner[petGuid] = ownerGuid
        }
    }

    private class EncounterStats(
        val name: String?,
        val id: String?,
        val startTimestamp: String,
        val groupSize: Long?,
        petRegistry: PetRegistry,
    ) {
        val stats = Stats(petRegistry)
        var endTimestamp: String? = null
        var success: Boolean? = null
        var durationMs: Long? = null

        fun header(): String = buildString {
            append(name ?: "Unknown encounter")
            id?.let { append(" (id $it)") }
            append(" from $startTimestamp")
            append(endTimestamp?.let { " to $it" } ?: " (still running or incomplete)")
            append(
                when (success) {
                    true -> ", kill"
                    false -> ", wipe"
                    null -> if (endTimestamp != null) ", result unknown" else ""
                }
            )
            durationMs?.let { append(", duration ${formatDuration(it)}") }
            groupSize?.let { append(", group size $it") }
        }
    }

    private class Stats(private val petRegistry: PetRegistry) {
        var eventCount = 0L
        var firstTimestamp: String? = null
        var lastTimestamp: String? = null
        val damageDone = HashMap<String, Long>()
        val damageTaken = HashMap<String, Long>()
        val healingDone = HashMap<String, Long>()
        val spellDamage = HashMap<String, Long>()
        val deaths = ArrayList<String>()
        var deathCount = 0L
        val interrupts = ArrayList<String>()
        var interruptCount = 0L

        private val unownedPetDamage = HashMap<String, Long>()
        private val unownedPetSpells = HashMap<String, HashMap<String, Long>>()
        private val unownedPetHealing = HashMap<String, Long>()
        private val lastFatalHit = HashMap<String, String>()

        fun accept(combatEvent: CombatEvent) {
            eventCount++
            if (firstTimestamp == null) firstTimestamp = combatEvent.timestamp
            lastTimestamp = combatEvent.timestamp
            when (val e = combatEvent.event) {
                is SpellDamageEvent -> {
                    val eff = calculateEffectiveDamage(e.baseAmount, e.blocked, e.resisted, e.overkill)
                    damage(e.header, eff, e.spell.spellName, e.overkill)
                }

                is SpellPeriodicDamageEvent -> {
                    val eff = calculateEffectiveDamage(e.baseAmount, e.blocked, e.resisted, e.overkill)
                    damage(e.header, eff, e.spell.spellName, e.overkill)
                }

                is RangeDamageEvent -> {
                    val eff = calculateEffectiveDamage(e.baseAmount, e.blocked, e.resisted, e.overkill)
                    damage(e.header, eff, e.spell.spellName, e.overkill)
                }

                is DamageShieldEvent -> {
                    val eff = calculateEffectiveDamage(e.baseAmount, e.blocked, e.resisted, e.overkill)
                    damage(e.header, eff, e.spell.spellName, e.overkill)
                }

                is SwingDamageEvent -> {
                    val eff = calculateEffectiveDamage(e.baseAmount, e.blocked, e.resisted, e.overkill)
                    damage(e.header, eff, "Melee", e.overkill)
                }

                is EnvironmentalDamageEvent -> {
                    val eff = calculateEffectiveDamage(e.baseAmount, e.blocked, e.resisted, e.overkill)
                    environmentalDamage(e.header, eff, e.environmentalType ?: "Environmental", e.overkill)
                }

                is SpellHealEvent -> {
                    val eff = maxOf(0L, (e.amount ?: 0L) - (e.overheal ?: 0L))
                    heal(e.header.sourceGuid, e.header.sourceName, e.header.sourceFlags, eff)
                }

                is SpellPeriodicHealEvent -> {
                    val eff = maxOf(0L, (e.amount ?: 0L) - (e.overheal ?: 0L))
                    heal(e.header.sourceGuid, e.header.sourceName, e.header.sourceFlags, eff)
                }

                is SpellAbsorbedEvent -> absorb(e)
                is SpellInterruptEvent -> interrupt(combatEvent.timestamp, e)
                is SpellInstakillEvent -> instakill(e)
                is UnitDiedEvent -> death(combatEvent.timestamp, e.header)
                else -> Unit
            }
        }

        private fun damage(header: CommonHeader, effectiveDamage: Long, spellName: String?, overkill: Long?) {
            val spell = spellName ?: "Unknown spell"
            val destGuid = header.destGuid

            if (destGuid != null && isPlayer(header.destFlags) && isGroup(header.destFlags)) {
                if (overkill != null && overkill >= 0L) {
                    val killer = header.sourceName ?: (header.sourceGuid?.let { petRegistry.guidToName[it] })
                    ?: header.sourceGuid ?: UNKNOWN
                    lastFatalHit[destGuid] = "$killer - $spell"
                }
            }

            if (effectiveDamage <= 0L) return

            if (isGroup(header.sourceFlags)) {
                val sourceGuid = header.sourceGuid
                val sourceName = header.sourceName
                val sourceFlags = header.sourceFlags

                if (isPlayer(sourceFlags)) {
                    val source = sourceName ?: (sourceGuid?.let { petRegistry.guidToName[it] }) ?: sourceGuid ?: UNKNOWN
                    damageDone.merge(source, effectiveDamage, Long::plus)
                    spellDamage.merge("$source - $spell", effectiveDamage, Long::plus)
                } else {
                    val ownerGuid = sourceGuid?.let { petRegistry.petToOwner[it] }
                    if (ownerGuid != null) {
                        val ownerName = petRegistry.guidToName[ownerGuid] ?: ownerGuid
                        damageDone.merge(ownerName, effectiveDamage, Long::plus)
                        spellDamage.merge("$ownerName - $spell", effectiveDamage, Long::plus)
                    } else {
                        val petName = sourceName ?: petRegistry.guidToName[sourceGuid] ?: sourceGuid ?: UNKNOWN
                        damageDone.merge(petName, effectiveDamage, Long::plus)
                        spellDamage.merge("$petName - $spell", effectiveDamage, Long::plus)
                        if (sourceGuid != null) {
                            unownedPetDamage.merge(sourceGuid, effectiveDamage, Long::plus)
                            unownedPetSpells.getOrPut(sourceGuid) { HashMap() }
                                .merge(spell, effectiveDamage, Long::plus)
                        }
                    }
                }
            }

            if (isGroup(header.destFlags)) {
                val target = header.destName ?: (header.destGuid?.let { petRegistry.guidToName[it] }) ?: header.destGuid
                ?: UNKNOWN
                damageTaken.merge(target, effectiveDamage, Long::plus)
            }
        }

        private fun environmentalDamage(
            header: CommonHeader,
            effectiveDamage: Long,
            hazard: String,
            overkill: Long?
        ) {
            val destGuid = header.destGuid
            if (destGuid != null && isPlayer(header.destFlags) && isGroup(header.destFlags)) {
                if (overkill != null && overkill >= 0L) {
                    lastFatalHit[destGuid] = hazard
                }
            }

            if (effectiveDamage <= 0L) return

            if (isGroup(header.destFlags)) {
                val target = header.destName ?: (header.destGuid?.let { petRegistry.guidToName[it] }) ?: header.destGuid
                ?: UNKNOWN
                damageTaken.merge(target, effectiveDamage, Long::plus)
            }
        }

        private fun heal(
            sourceGuid: String?,
            sourceName: String?,
            sourceFlags: Long?,
            effectiveHealing: Long
        ) {
            if (!isGroup(sourceFlags) || effectiveHealing <= 0L) return

            if (isPlayer(sourceFlags)) {
                val source = sourceName ?: (sourceGuid?.let { petRegistry.guidToName[it] }) ?: sourceGuid ?: UNKNOWN
                healingDone.merge(source, effectiveHealing, Long::plus)
            } else {
                val ownerGuid = sourceGuid?.let { petRegistry.petToOwner[it] }
                if (ownerGuid != null) {
                    val ownerName = petRegistry.guidToName[ownerGuid] ?: ownerGuid
                    healingDone.merge(ownerName, effectiveHealing, Long::plus)
                } else {
                    val petName =
                        sourceName ?: (sourceGuid?.let { petRegistry.guidToName[it] }) ?: sourceGuid ?: UNKNOWN
                    healingDone.merge(petName, effectiveHealing, Long::plus)
                    if (sourceGuid != null) {
                        unownedPetHealing.merge(sourceGuid, effectiveHealing, Long::plus)
                    }
                }
            }
        }

        private fun absorb(event: SpellAbsorbedEvent) {
            val shieldSpellId = event.shieldSpellId
            if (shieldSpellId != null && shieldSpellId in HEALING_EXCLUSION_SPELL_IDS) return
            val amount = event.amount?.takeIf { it > 0L } ?: return
            heal(event.absorberGuid, event.absorberName, event.absorberFlags, amount)
        }

        private fun interrupt(timestamp: String, event: SpellInterruptEvent) {
            if (!isGroup(event.header.sourceFlags)) return
            interruptCount++
            if (interrupts.size < MAX_LISTED) {
                val sourceGuid = event.header.sourceGuid
                val sourceName = event.header.sourceName
                val sourceFlags = event.header.sourceFlags

                val source = if (isPlayer(sourceFlags)) {
                    sourceName ?: (sourceGuid?.let { petRegistry.guidToName[it] }) ?: sourceGuid ?: UNKNOWN
                } else {
                    val ownerGuid = sourceGuid?.let { petRegistry.petToOwner[it] }
                    if (ownerGuid != null) {
                        val ownerName = petRegistry.guidToName[ownerGuid] ?: ownerGuid
                        val petName = sourceName ?: petRegistry.guidToName[sourceGuid]
                        if (petName != null && petName != ownerName) {
                            "$ownerName ($petName)"
                        } else {
                            ownerName
                        }
                    } else {
                        sourceName ?: (sourceGuid?.let { petRegistry.guidToName[it] }) ?: sourceGuid ?: UNKNOWN
                    }
                }

                val target =
                    event.header.destName ?: (event.header.destGuid?.let { petRegistry.guidToName[it] }) ?: UNKNOWN
                val interruptedSpell = event.interruptedSpellName ?: "unknown spell"
                val withSpell = event.spell.spellName ?: "unknown spell"
                interrupts += "$timestamp $source interrupted $target's $interruptedSpell with $withSpell"
            }
        }

        private fun instakill(event: SpellInstakillEvent) {
            val targetGuid = event.header.destGuid
            if (targetGuid != null && isPlayer(event.header.destFlags) && isGroup(event.header.destFlags)) {
                val killer = event.header.sourceName ?: (event.header.sourceGuid?.let { petRegistry.guidToName[it] })
                ?: event.header.sourceGuid ?: UNKNOWN
                val spell = event.spell.spellName ?: "Instakill"
                lastFatalHit[targetGuid] = "$killer - $spell"
            }
        }

        private fun death(timestamp: String, header: CommonHeader) {
            val flags = header.destFlags ?: return
            if (!isGroup(flags) || flags and TYPE_PLAYER == 0L) return
            deathCount++
            if (deaths.size < MAX_LISTED) {
                val targetName =
                    header.destName ?: (header.destGuid?.let { petRegistry.guidToName[it] }) ?: header.destGuid
                    ?: UNKNOWN
                val fatalCause = header.destGuid?.let { lastFatalHit.remove(it) }
                val entry = if (fatalCause != null) {
                    "$timestamp $targetName (killed by $fatalCause)"
                } else {
                    "$timestamp $targetName"
                }
                deaths += entry
            }
        }

        fun reassignPet(petGuid: String, ownerGuid: String) {
            val pendingDmg = unownedPetDamage.remove(petGuid)
            val petName = petRegistry.guidToName[petGuid] ?: petGuid
            val ownerName = petRegistry.guidToName[ownerGuid] ?: ownerGuid

            if (pendingDmg != null && pendingDmg > 0L) {
                val curDmg = damageDone[petName] ?: 0L
                if (curDmg <= pendingDmg) {
                    damageDone.remove(petName)
                } else {
                    damageDone[petName] = curDmg - pendingDmg
                }
                damageDone.merge(ownerName, pendingDmg, Long::plus)
            }

            val pendingSpells = unownedPetSpells.remove(petGuid)
            if (pendingSpells != null) {
                for ((spell, amt) in pendingSpells) {
                    val petKey = "$petName - $spell"
                    val cur = spellDamage[petKey] ?: 0L
                    if (cur <= amt) {
                        spellDamage.remove(petKey)
                    } else {
                        spellDamage[petKey] = cur - amt
                    }
                    spellDamage.merge("$ownerName - $spell", amt, Long::plus)
                }
            }

            val pendingHeal = unownedPetHealing.remove(petGuid)
            if (pendingHeal != null && pendingHeal > 0L) {
                val curHeal = healingDone[petName] ?: 0L
                if (curHeal <= pendingHeal) {
                    healingDone.remove(petName)
                } else {
                    healingDone[petName] = curHeal - pendingHeal
                }
                healingDone.merge(ownerName, pendingHeal, Long::plus)
            }
        }

        fun onNameLearned(guid: String, name: String) {
            if (guid == name) return
            migrateKey(damageDone, guid, name)
            migrateKey(damageTaken, guid, name)
            migrateKey(healingDone, guid, name)

            val keysToMigrate = spellDamage.keys.filter { it.startsWith("$guid - ") }
            for (oldKey in keysToMigrate) {
                val amt = spellDamage.remove(oldKey) ?: continue
                val newKey = name + oldKey.substring(guid.length)
                spellDamage.merge(newKey, amt, Long::plus)
            }
        }

        private fun migrateKey(map: HashMap<String, Long>, from: String, to: String) {
            val value = map.remove(from) ?: return
            map.merge(to, value, Long::plus)
        }

        fun appendTo(out: StringBuilder, topN: Int, durationMs: Long?, indent: String) {
            out.appendTop("${indent}Damage done", damageDone, topN, durationMs)
            if (durationMs == null) out.appendTop("${indent}Top spells by damage", spellDamage, topN, null)
            out.appendTop("${indent}Effective healing", healingDone, topN, durationMs)
            out.appendTop("${indent}Damage taken", damageTaken, topN, null)
            out.appendList("${indent}Player deaths", deaths, deathCount, topN * 2, indent)
            out.appendList("${indent}Interrupts", interrupts, interruptCount, topN * 2, indent)
        }
    }

    companion object {
        const val DEFAULT_TOP_N = 8
        private const val ENCOUNTER_TOP_N = 5
        private const val MAX_ENCOUNTERS = 50
        private const val MAX_LISTED = 500
        private const val UNKNOWN = "Unknown"
        private const val AFFILIATION_GROUP_MASK = 0x7L
        private const val TYPE_PLAYER = 0x400L

        private val HEALING_EXCLUSION_SPELL_IDS = setOf("114556", "31850", "31230", "115069")

        private fun isGroup(flags: Long?): Boolean = flags != null && flags and AFFILIATION_GROUP_MASK != 0L

        private fun isPlayer(flags: Long?): Boolean = flags != null && flags and TYPE_PLAYER != 0L

        private fun calculateEffectiveDamage(
            baseAmount: Long?,
            blocked: Long?,
            resisted: Long?,
            overkill: Long?
        ): Long {
            val base = baseAmount ?: 0L
            val blk = blocked ?: 0L
            val res = resisted ?: 0L
            val over = if (overkill != null && overkill > 0L) overkill else 0L
            return maxOf(0L, base + blk + res - over)
        }

        private fun StringBuilder.appendTop(label: String, totals: Map<String, Long>, topN: Int, durationMs: Long?) {
            if (totals.isEmpty()) return
            val top = totals.entries.sortedByDescending { it.value }.take(topN)
            append(label).append(" (top ${top.size} of ${totals.size}): ")
            appendLine(top.joinToString("; ") { (name, total) ->
                val perSecond = durationMs?.takeIf { it > 0 }?.let { " (${total * 1000 / it}/s)" } ?: ""
                "$name $total$perSecond"
            })
        }

        private fun StringBuilder.appendList(
            label: String,
            items: List<String>,
            total: Long,
            limit: Int,
            indent: String
        ) {
            if (total == 0L) return
            append(label).append(" ($total")
            if (items.size > limit) append(", first $limit shown")
            appendLine("):")
            items.take(limit).forEach { appendLine("$indent  - $it") }
        }

        private fun formatDuration(ms: Long): String {
            val seconds = ms / 1000
            return "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s"
        }

        private data class SpecInfo(val className: String, val specName: String)

        private val SPEC_MAP = mapOf(
            "250" to SpecInfo("Death Knight", "Blood"),
            "251" to SpecInfo("Death Knight", "Frost"),
            "252" to SpecInfo("Death Knight", "Unholy"),
            "577" to SpecInfo("Demon Hunter", "Havoc"),
            "581" to SpecInfo("Demon Hunter", "Vengeance"),
            "1480" to SpecInfo("Demon Hunter", "Devourer"),
            "102" to SpecInfo("Druid", "Balance"),
            "103" to SpecInfo("Druid", "Feral"),
            "104" to SpecInfo("Druid", "Guardian"),
            "105" to SpecInfo("Druid", "Restoration"),
            "1467" to SpecInfo("Evoker", "Devastation"),
            "1468" to SpecInfo("Evoker", "Preservation"),
            "1473" to SpecInfo("Evoker", "Augmentation"),
            "253" to SpecInfo("Hunter", "Beast Mastery"),
            "254" to SpecInfo("Hunter", "Marksmanship"),
            "255" to SpecInfo("Hunter", "Survival"),
            "62" to SpecInfo("Mage", "Arcane"),
            "63" to SpecInfo("Mage", "Fire"),
            "64" to SpecInfo("Mage", "Frost"),
            "268" to SpecInfo("Monk", "Brewmaster"),
            "269" to SpecInfo("Monk", "Windwalker"),
            "270" to SpecInfo("Monk", "Mistweaver"),
            "65" to SpecInfo("Paladin", "Holy"),
            "66" to SpecInfo("Paladin", "Protection"),
            "70" to SpecInfo("Paladin", "Retribution"),
            "256" to SpecInfo("Priest", "Discipline"),
            "257" to SpecInfo("Priest", "Holy"),
            "258" to SpecInfo("Priest", "Shadow"),
            "259" to SpecInfo("Rogue", "Assassination"),
            "260" to SpecInfo("Rogue", "Outlaw"),
            "261" to SpecInfo("Rogue", "Subtlety"),
            "262" to SpecInfo("Shaman", "Elemental"),
            "263" to SpecInfo("Shaman", "Enhancement"),
            "264" to SpecInfo("Shaman", "Restoration"),
            "265" to SpecInfo("Warlock", "Affliction"),
            "266" to SpecInfo("Warlock", "Demonology"),
            "267" to SpecInfo("Warlock", "Destruction"),
            "71" to SpecInfo("Warrior", "Arms"),
            "72" to SpecInfo("Warrior", "Fury"),
            "73" to SpecInfo("Warrior", "Protection"),
        )

        private val SPELL_TO_CLASS = mapOf(
            "Death Strike" to "Death Knight",
            "Obliterate" to "Death Knight",
            "Howling Blast" to "Death Knight",
            "Frost Strike" to "Death Knight",
            "Scourge Strike" to "Death Knight",
            "Festering Strike" to "Death Knight",
            "Heart Strike" to "Death Knight",
            "Marrowrend" to "Death Knight",
            "Blood Boil" to "Death Knight",
            "Death Coil" to "Death Knight",
            "Death and Decay" to "Death Knight",
            "Army of the Dead" to "Death Knight",
            "Anti-Magic Shell" to "Death Knight",
            "Vampiric Blood" to "Death Knight",
            "Dancing Rune Weapon" to "Death Knight",
            "Chaos Strike" to "Demon Hunter",
            "Annihilation" to "Demon Hunter",
            "Blade Dance" to "Demon Hunter",
            "Death Sweep" to "Demon Hunter",
            "Eye Beam" to "Demon Hunter",
            "Fel Rush" to "Demon Hunter",
            "Immolation Aura" to "Demon Hunter",
            "Demon's Bite" to "Demon Hunter",
            "Metamorphosis" to "Demon Hunter",
            "The Hunt" to "Demon Hunter",
            "Soul Carver" to "Demon Hunter",
            "Spirit Bomb" to "Demon Hunter",
            "Fracture" to "Demon Hunter",
            "Shear" to "Demon Hunter",
            "Moonfire" to "Druid",
            "Sunfire" to "Druid",
            "Starfall" to "Druid",
            "Starsurge" to "Druid",
            "Wrath" to "Druid",
            "Starfire" to "Druid",
            "Shred" to "Druid",
            "Rip" to "Druid",
            "Rake" to "Druid",
            "Ferocious Bite" to "Druid",
            "Mangle" to "Druid",
            "Ironfur" to "Druid",
            "Maul" to "Druid",
            "Frenzied Regeneration" to "Druid",
            "Rejuvenation" to "Druid",
            "Regrowth" to "Druid",
            "Wild Growth" to "Druid",
            "Swiftmend" to "Druid",
            "Lifebloom" to "Druid",
            "Disintegrate" to "Evoker",
            "Pyre" to "Evoker",
            "Living Flame" to "Evoker",
            "Azure Strike" to "Evoker",
            "Fire Breath" to "Evoker",
            "Eternity Surge" to "Evoker",
            "Upheaval" to "Evoker",
            "Deep Breath" to "Evoker",
            "Verdant Embrace" to "Evoker",
            "Spiritbloom" to "Evoker",
            "Dream Breath" to "Evoker",
            "Echo" to "Evoker",
            "Ebon Might" to "Evoker",
            "Prescience" to "Evoker",
            "Barbed Shot" to "Hunter",
            "Kill Command" to "Hunter",
            "Aimed Shot" to "Hunter",
            "Rapid Fire" to "Hunter",
            "Arcane Shot" to "Hunter",
            "Multi-Shot" to "Hunter",
            "Cobra Shot" to "Hunter",
            "Kill Shot" to "Hunter",
            "Wildfire Bomb" to "Hunter",
            "Raptor Strike" to "Hunter",
            "Mongoose Bite" to "Hunter",
            "Frostbolt" to "Mage",
            "Ice Lance" to "Mage",
            "Flurry" to "Mage",
            "Blizzard" to "Mage",
            "Frozen Orb" to "Mage",
            "Fireball" to "Mage",
            "Pyroblast" to "Mage",
            "Fire Blast" to "Mage",
            "Scorch" to "Mage",
            "Flamestrike" to "Mage",
            "Phoenix Flames" to "Mage",
            "Arcane Blast" to "Mage",
            "Arcane Missiles" to "Mage",
            "Arcane Barrage" to "Mage",
            "Arcane Surge" to "Mage",
            "Tiger Palm" to "Monk",
            "Blackout Kick" to "Monk",
            "Rising Sun Kick" to "Monk",
            "Fists of Fury" to "Monk",
            "Spinning Crane Kick" to "Monk",
            "Whirling Dragon Punch" to "Monk",
            "Keg Smash" to "Monk",
            "Purifying Brew" to "Monk",
            "Celestial Brew" to "Monk",
            "Breath of Fire" to "Monk",
            "Vivify" to "Monk",
            "Enveloping Mist" to "Monk",
            "Soothing Mist" to "Monk",
            "Essence Font" to "Monk",
            "Revival" to "Monk",
            "Judgment" to "Paladin",
            "Templar's Verdict" to "Paladin",
            "Final Verdict" to "Paladin",
            "Blade of Justice" to "Paladin",
            "Crusader Strike" to "Paladin",
            "Wake of Ashes" to "Paladin",
            "Shield of the Righteous" to "Paladin",
            "Avenger's Shield" to "Paladin",
            "Blessed Hammer" to "Paladin",
            "Hammer of the Righteous" to "Paladin",
            "Holy Shock" to "Paladin",
            "Word of Glory" to "Paladin",
            "Flash of Light" to "Paladin",
            "Holy Light" to "Paladin",
            "Light of Dawn" to "Paladin",
            "Hammer of Wrath" to "Paladin",
            "Smite" to "Priest",
            "Holy Fire" to "Priest",
            "Penance" to "Priest",
            "Power Word: Shield" to "Priest",
            "Power Word: Radiance" to "Priest",
            "Flash Heal" to "Priest",
            "Heal" to "Priest",
            "Prayer of Healing" to "Priest",
            "Circle of Healing" to "Priest",
            "Holy Nova" to "Priest",
            "Shadow Word: Pain" to "Priest",
            "Purge the Wicked" to "Priest",
            "Shadow Word: Death" to "Priest",
            "Mind Blast" to "Priest",
            "Mind Flay" to "Priest",
            "Mind Spike" to "Priest",
            "Void Torrent" to "Priest",
            "Devouring Plague" to "Priest",
            "Sinister Strike" to "Rogue",
            "Dispatch" to "Rogue",
            "Pistol Shot" to "Rogue",
            "Blade Flurry" to "Rogue",
            "Between the Eyes" to "Rogue",
            "Mutilate" to "Rogue",
            "Envenom" to "Rogue",
            "Garrote" to "Rogue",
            "Rupture" to "Rogue",
            "Backstab" to "Rogue",
            "Gloomblade" to "Rogue",
            "Shadowstrike" to "Rogue",
            "Eviscerate" to "Rogue",
            "Secret Technique" to "Rogue",
            "Lightning Bolt" to "Shaman",
            "Chain Lightning" to "Shaman",
            "Lava Burst" to "Shaman",
            "Flame Shock" to "Shaman",
            "Earth Shock" to "Shaman",
            "Elemental Blast" to "Shaman",
            "Earthquake" to "Shaman",
            "Stormstrike" to "Shaman",
            "Windstrike" to "Shaman",
            "Lava Lash" to "Shaman",
            "Ice Strike" to "Shaman",
            "Crash Lightning" to "Shaman",
            "Riptide" to "Shaman",
            "Healing Wave" to "Shaman",
            "Healing Surge" to "Shaman",
            "Chain Heal" to "Shaman",
            "Healing Rain" to "Shaman",
            "Shadow Bolt" to "Warlock",
            "Chaos Bolt" to "Warlock",
            "Incinerate" to "Warlock",
            "Conflagrate" to "Warlock",
            "Immolate" to "Warlock",
            "Rain of Fire" to "Warlock",
            "Drain Soul" to "Warlock",
            "Corruption" to "Warlock",
            "Agony" to "Warlock",
            "Unstable Affliction" to "Warlock",
            "Malefic Rapture" to "Warlock",
            "Vile Taint" to "Warlock",
            "Hand of Gul'dan" to "Warlock",
            "Demonbolt" to "Warlock",
            "Call Dreadstalkers" to "Warlock",
            "Mortal Strike" to "Warrior",
            "Overpower" to "Warrior",
            "Colossus Smash" to "Warrior",
            "Warbreaker" to "Warrior",
            "Bloodthirst" to "Warrior",
            "Bloodbath" to "Warrior",
            "Raging Blow" to "Warrior",
            "Crushing Blow" to "Warrior",
            "Rampage" to "Warrior",
            "Execute" to "Warrior",
            "Shield Slam" to "Warrior",
            "Revenge" to "Warrior",
            "Devastate" to "Warrior",
            "Thunder Clap" to "Warrior",
            "Shockwave" to "Warrior",
            "Shield Block" to "Warrior",
            "Whirlwind" to "Warrior",
            "Slam" to "Warrior",
        )
    }
}
