package com.cacaosd.wowtelemetry.combatlog

import com.cacaosd.wowtelemetry.domain.CombatEvent
import com.cacaosd.wowtelemetry.domain.CommonHeader
import com.cacaosd.wowtelemetry.domain.EncounterEndEvent
import com.cacaosd.wowtelemetry.domain.EncounterStartEvent
import com.cacaosd.wowtelemetry.domain.RangeDamageEvent
import com.cacaosd.wowtelemetry.domain.SpellDamageEvent
import com.cacaosd.wowtelemetry.domain.SpellHealEvent
import com.cacaosd.wowtelemetry.domain.SpellInterruptEvent
import com.cacaosd.wowtelemetry.domain.SpellPeriodicDamageEvent
import com.cacaosd.wowtelemetry.domain.SpellPeriodicHealEvent
import com.cacaosd.wowtelemetry.domain.SwingDamageEvent
import com.cacaosd.wowtelemetry.domain.UnitDiedEvent

/**
 * Deterministic running statistics over processed combat events. Counts only party, raid and
 * own-character sources/targets. Not thread-safe; callers serialize access.
 *
 * Damage uses the spec's `base_amount`. `SWING_DAMAGE_LANDED` and `*_SUPPORT` events are ignored
 * because they repeat the damage already carried by their base events. Healing is
 * `amount - overheal`.
 */
class CombatLogAnalyzer(
    private val maxEncounters: Int = MAX_ENCOUNTERS,
) {
    private var overall = Stats()
    private val encounters = ArrayDeque<EncounterStats>()
    private var current: EncounterStats? = null

    fun reset() {
        overall = Stats()
        encounters.clear()
        current = null
    }

    fun accept(combatEvent: CombatEvent) {
        val event = combatEvent.event
        if (event is EncounterStartEvent) {
            current?.let(::archive)
            current = EncounterStats(event.encounterName, event.encounterId, combatEvent.timestamp, event.groupSize)
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

    private class EncounterStats(
        val name: String?,
        val id: String?,
        val startTimestamp: String,
        val groupSize: Long?,
    ) {
        val stats = Stats()
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

    private class Stats {
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

        fun accept(combatEvent: CombatEvent) {
            eventCount++
            if (firstTimestamp == null) firstTimestamp = combatEvent.timestamp
            lastTimestamp = combatEvent.timestamp
            when (val e = combatEvent.event) {
                is SpellDamageEvent -> damage(e.header, e.baseAmount, e.spell.spellName)
                is SpellPeriodicDamageEvent -> damage(e.header, e.baseAmount, e.spell.spellName)
                is RangeDamageEvent -> damage(e.header, e.baseAmount, e.spell.spellName)
                is SwingDamageEvent -> damage(e.header, e.baseAmount, "Melee")
                is SpellHealEvent -> heal(e.header, e.amount, e.overheal)
                is SpellPeriodicHealEvent -> heal(e.header, e.amount, e.overheal)
                is SpellInterruptEvent -> interrupt(combatEvent.timestamp, e)
                is UnitDiedEvent -> death(combatEvent.timestamp, e.header)
                else -> Unit
            }
        }

        private fun damage(header: CommonHeader, amount: Long?, spellName: String?) {
            val value = amount?.takeIf { it > 0 } ?: return
            if (isGroup(header.sourceFlags)) {
                val source = header.sourceName ?: header.sourceGuid ?: UNKNOWN
                damageDone.merge(source, value, Long::plus)
                spellDamage.merge("$source - ${spellName ?: "Unknown spell"}", value, Long::plus)
            }
            if (isGroup(header.destFlags)) {
                damageTaken.merge(header.destName ?: header.destGuid ?: UNKNOWN, value, Long::plus)
            }
        }

        private fun heal(header: CommonHeader, amount: Long?, overheal: Long?) {
            if (!isGroup(header.sourceFlags)) return
            val effective = (amount ?: return) - (overheal ?: 0)
            if (effective <= 0) return
            healingDone.merge(header.sourceName ?: header.sourceGuid ?: UNKNOWN, effective, Long::plus)
        }

        private fun interrupt(timestamp: String, event: SpellInterruptEvent) {
            if (!isGroup(event.header.sourceFlags)) return
            interruptCount++
            if (interrupts.size < MAX_LISTED) {
                val source = event.header.sourceName ?: UNKNOWN
                val target = event.header.destName ?: UNKNOWN
                interrupts += "$timestamp $source interrupted $target's " +
                        "${event.interruptedSpellName ?: "unknown spell"} with ${event.spell.spellName ?: "unknown spell"}"
            }
        }

        private fun death(timestamp: String, header: CommonHeader) {
            val flags = header.destFlags ?: return
            if (!isGroup(flags) || flags and TYPE_PLAYER == 0L) return
            deathCount++
            if (deaths.size < MAX_LISTED) deaths += "$timestamp ${header.destName ?: UNKNOWN}"
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

        private fun isGroup(flags: Long?): Boolean = flags != null && flags and AFFILIATION_GROUP_MASK != 0L

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
    }
}
