package com.cacaosd.wowtelemetry.combatlog

import com.cacaosd.wowtelemetry.domain.*

data class EnrichedCombatEvent(
    val timestamp: String,
    val type: String,
    val sourceGuid: String?,
    val sourceName: String?,
    val targetGuid: String?,
    val targetName: String?,
    val spellId: String?,
    val spellName: String?,
    val amount: Long?,
    val summary: String,
    val details: String,
)

data class CombatEventBatch(
    val startTimestamp: String,
    val endTimestamp: String,
    val events: List<EnrichedCombatEvent>,
)

class CombatEventEnricher {
    fun enrich(event: CombatEvent): EnrichedCombatEvent {
        val header = (event.event as? HasCommonHeader)?.header
        val spell = (event.event as? HasSpellPrefix)?.spell
        val amount = (event.event as? HasBaseAmount)?.baseAmount
        val summary = buildString {
            append(event.timestamp)
            append(' ')
            append(event.event.type)
            (event.event as? UnrecognizedEventEvent)?.let {
                append(" eventName=")
                append(it.eventName)
            }
            (event.event as? MalformedEventEvent)?.let {
                append(" reason=")
                append(it.reason)
                append(" rawBytes=")
                append(it.rawBytes.size)
            }
            header?.sourceName?.let {
                append(" source=")
                append(it)
            }
            header?.destName?.let {
                append(" target=")
                append(it)
            }
            spell?.spellId?.let {
                append(" spellId=")
                append(it)
            }
            spell?.spellName?.let {
                append(" spellName=")
                append(it)
            }
            amount?.let {
                append(" amount=")
                append(it)
            }
        }

        return EnrichedCombatEvent(
            timestamp = event.timestamp,
            type = event.event.type,
            sourceGuid = header?.sourceGuid,
            sourceName = header?.sourceName,
            targetGuid = header?.destGuid,
            targetName = header?.destName,
            spellId = spell?.spellId,
            spellName = spell?.spellName,
            amount = amount,
            summary = summary,
            details = "typed=${event.event} fields=${event.fields}",
        )
    }
}
