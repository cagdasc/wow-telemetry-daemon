package gg.wowcoach.combatlog.model

enum class SpellSchool(val mask: Int) {
    PHYSICAL(0x1),
    HOLY(0x2),
    FIRE(0x4),
    NATURE(0x8),
    FROST(0x10),
    SHADOW(0x20),
    ARCANE(0x40);

    companion object {
        fun decode(hex: String): Set<SpellSchool> {
            val value = hex.removePrefix("0x").toInt(16)
            return entries.filter { (it.mask and value) != 0 }.toSet()
        }
    }
}

enum class PowerType(val id: Int) {
    MANA(0),
    RAGE(1),
    FOCUS(2),
    ENERGY(3),
    COMBO_POINTS(4),
    RUNES(5),
    RUNIC_POWER(6),
    SOUL_SHARDS(7),
    LUNAR_POWER(8),
    HOLY_POWER(9),
    MAELSTROM(11),
    CHI(12),
    INSANITY(13),
    ARCANE_CHARGES(16),
    FURY(17),
    PAIN(18),
    ESSENCE(19);

    companion object {
        private val map = entries.associateBy { it.id }
        fun fromId(id: Int): PowerType? = map[id]
    }
}

enum class MissType {
    MISS, DODGE, PARRY, BLOCK, DEFLECT, IMMUNE, ABSORB, REFLECT, EVADE, RESIST
}

enum class Difficulty(val id: Int) {
    MYTHIC_PLUS(8),
    NORMAL_RAID(14),
    HEROIC_RAID(15),
    MYTHIC_RAID(16),
    LFR(17),
    MYTHIC_RAID_FLEX(233)
}

enum class EnvironmentalType {
    FALLING, LAVA, FIRE, SLIME, DROWNING, FATIGUE
}

enum class AuraType {
    BUFF, DEBUFF
}

data class UnitFlags(val raw: Int) {
    val isMine: Boolean get() = (raw and 0x00F) == 0x1
    val isParty: Boolean get() = (raw and 0x00F) == 0x2
    val isRaid: Boolean get() = (raw and 0x00F) == 0x4
    val isFriendly: Boolean get() = (raw and 0x0F0) == 0x10
    val isHostile: Boolean get() = (raw and 0x0F0) == 0x40
    val isPlayerControlled: Boolean get() = (raw and 0x300) == 0x100
    val isPlayer: Boolean get() = (raw and 0xFC00) == 0x400
    val isNpc: Boolean get() = (raw and 0xFC00) == 0x800
    val isPet: Boolean get() = (raw and 0xFC00) == 0x1000
    val isTarget: Boolean get() = (raw and 0xFFFF0000.toInt()) == 0x10000
}

data class CommonHeader(
    val event: String,
    val sourceGuid: String,
    val sourceName: String?,
    val sourceFlags: UnitFlags,
    val sourceRaidFlags: Int,
    val destGuid: String,
    val destName: String?,
    val destFlags: UnitFlags,
    val destRaidFlags: Int
)

data class SpellPrefix(
    val spellId: Long,
    val spellName: String,
    val spellSchool: Set<SpellSchool>
)

data class AdvancedLoggingBlock(
    val infoGuid: String,
    val ownerGuid: String,
    val currentHp: Long,
    val maxHp: Long,
    val attackPower: Int,
    val spellPower: Int,
    val armor: Int,
    val absorb: Int,
    val powerType: PowerType?,
    val currentPower: Int,
    val maxPower: Int,
    val powerCost: Int, // in tenths (e.g., 350 = 35 rage)
    val positionX: Float,
    val positionY: Float,
    val uiMapId: Int,
    val facing: Float,
    val itemLevel: Int
)

sealed interface CombatLogEvent {
    val timestamp: String
}

// ---------------------------------------------------------
// Encounter & Zone Lifecycle Events
// ---------------------------------------------------------
data class EncounterStartEvent(
    override val timestamp: String,
    val encounterId: Int,
    val encounterName: String,
    val difficulty: Difficulty,
    val groupSize: Int,
    val instanceId: Int?
) : CombatLogEvent

data class EncounterEndEvent(
    override val timestamp: String,
    val encounterId: Int,
    val encounterName: String,
    val difficulty: Difficulty,
    val groupSize: Int,
    val success: Boolean,
    val durationMs: Long?
) : CombatLogEvent

data class ChallengeModeStartEvent(
    override val timestamp: String,
    val dungeonName: String,
    val mapId: Int,
    val challengeModeId: Int,
    val keystoneLevel: Int,
    val affixes: List<Int>
) : CombatLogEvent

// ---------------------------------------------------------
// Combat Damage & Healing Events
// ---------------------------------------------------------
data class SpellDamageEvent(
    override val timestamp: String,
    val header: CommonHeader,
    val spell: SpellPrefix,
    val advancedBlock: AdvancedLoggingBlock?,
    val baseAmount: Int,
    val rawAmount: Int,
    val overkill: Int,
    val school: Set<SpellSchool>,
    val resisted: Int,
    val blocked: Int,
    val absorbed: Int,
    val critical: Boolean?,
    val abilityHint: String?, // "ST" or "AOE"
    val supportPlayerGuid: String? // Present on _SUPPORT variants (Augmentation Evoker)
) : CombatLogEvent

data class SwingDamageEvent(
    override val timestamp: String,
    val header: CommonHeader,
    val advancedBlock: AdvancedLoggingBlock?,
    val baseAmount: Int,
    val rawAmount: Int,
    val overkill: Int,
    val school: Set<SpellSchool>,
    val resisted: Int,
    val blocked: Int,
    val absorbed: Int,
    val critical: Boolean?,
    val glancing: Boolean?,
    val crushing: Boolean?,
    val isOffHand: Boolean?
) : CombatLogEvent

data class SpellHealEvent(
    override val timestamp: String,
    val header: CommonHeader,
    val spell: SpellPrefix,
    val advancedBlock: AdvancedLoggingBlock?,
    val healedToHp: Int,
    val amount: Int,          // Canonical healing including overheal
    val overheal: Int,
    val absorbedToShield: Int,
    val critical: Boolean?,
    val supportPlayerGuid: String? // Present on _SUPPORT variants
) : CombatLogEvent

// ---------------------------------------------------------
// Aura & Utility Events
// ---------------------------------------------------------
data class SpellAuraEvent(
    override val timestamp: String,
    val header: CommonHeader,
    val spell: SpellPrefix,
    val auraType: AuraType,
    val amount: Int? // Absorb amount for shields, or stack count on _DOSE events
) : CombatLogEvent

data class UnitDiedEvent(
    override val timestamp: String,
    val destGuid: String,
    val destName: String?,
    val destFlags: UnitFlags
) : CombatLogEvent

// ---------------------------------------------------------
// Combatant Info (Snapshot per player on pull)
// ---------------------------------------------------------
data class CombatantInfoEvent(
    override val timestamp: String,
    val playerGuid: String,
    val faction: Int,
    val strength: Int,
    val agility: Int,
    val stamina: Int,
    val intellect: Int,
    val critRatings: Triple<Int, Int, Int>, // melee, ranged, spell
    val hasteRatings: Triple<Int, Int, Int>, // melee, ranged, spell
    val versatilityRatings: Triple<Int, Int, Int>, // damage done, healing done, damage taken
    val masteryRating: Int,
    val armor: Int,
    val specId: Int,
    val talents: List<TalentEntry>,
    val pvpTalents: List<Int>,
    val gear: Map<Int, GearSlot> // Equipment slot index to item details
) : CombatLogEvent {
    data class TalentEntry(val nodeId: Int, val entryId: Int, val rank: Int)
    data class GearSlot(val itemId: Int, val itemLevel: Int, val enchants: List<Int>, val bonusIds: List<Int>, val gems: List<Int>)
}

"""
Architectural Implementation Notes

    Dual-Stream & Support Mitigation (_SUPPORT): Augmentation Evoker _SUPPORT events log identical damage/healing instances alongside standard events. Your ingestion pipeline should handle deduplication by filtering or linking supportPlayerGuid.

    Variable Arity Parsing: SPELL_ABSORBED has either 19 fields (self-shield) or 22 fields (shield on another unit). Field counting during ingestion is mandatory prior to instantiation.

    Patch 12.x Offsets: COMBATANT_INFO includes an extra field at index 7 (spirit/placeholder), shifting ratings and spec_id index. Ensure your CSV tokenizer accounts for BUILD_VERSION major 12.
"""