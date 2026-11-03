package com.cacaosd.wowtelemetry.domain

data class CombatEvent(
    val timestamp: String,
    val event: String,
    val fields: List<String?>,
    val sourceGuid: String?,
    val sourceName: String?,
    val destGuid: String?,
    val destName: String?,
    val spellId: String?,
    val spellName: String?,
    val amount: String?,
)

data class SpellCast(
    val timestamp: String,
    val event: String,
    val sourceGuid: String?,
    val sourceName: String?,
    val spellId: String?,
    val spellName: String?,
)

data class DamageEvent(
    val timestamp: String,
    val event: String,
    val sourceGuid: String?,
    val destGuid: String?,
    val spellId: String?,
    val amount: String?,
)

data class EncounterState(
    val encounterId: String?,
    val name: String?,
    val startedAt: String?,
) {
    val isActive: Boolean
        get() = encounterId != null
}

fun CombatEvent.asSpellCast(): SpellCast? {
    if (event !in SPELL_CAST_EVENTS) return null
    return SpellCast(timestamp, event, sourceGuid, sourceName, spellId, spellName)
}

fun CombatEvent.asDamageEvent(): DamageEvent? {
    if (event !in DAMAGE_EVENTS) return null
    return DamageEvent(timestamp, event, sourceGuid, destGuid, spellId, amount)
}

private val SPELL_CAST_EVENTS = setOf(
    "SPELL_CAST_START",
    "SPELL_CAST_SUCCESS",
    "SPELL_CAST_FAILED",
    "SPELL_EMPOWER_START",
    "SPELL_EMPOWER_END",
    "SPELL_EMPOWER_INTERRUPT",
)

private val DAMAGE_EVENTS = setOf(
    "SPELL_DAMAGE",
    "SPELL_PERIODIC_DAMAGE",
    "RANGE_DAMAGE",
    "SPELL_DAMAGE_SUPPORT",
    "SPELL_PERIODIC_DAMAGE_SUPPORT",
    "RANGE_DAMAGE_SUPPORT",
    "DAMAGE_SHIELD",
    "DAMAGE_SPLIT",
    "SWING_DAMAGE_LANDED_SUPPORT",
    "SWING_DAMAGE",
    "SWING_DAMAGE_LANDED",
    "ENVIRONMENTAL_DAMAGE",
)
