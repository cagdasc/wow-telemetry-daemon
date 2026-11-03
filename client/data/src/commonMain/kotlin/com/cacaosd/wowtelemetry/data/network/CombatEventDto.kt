package com.cacaosd.wowtelemetry.data.network

import com.cacaosd.wowtelemetry.domain.CombatEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class CombatEventDto(
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
) {
    fun toDomain() = CombatEvent(
        timestamp = timestamp,
        event = event,
        fields = fields,
        sourceGuid = sourceGuid,
        sourceName = sourceName,
        destGuid = destGuid,
        destName = destName,
        spellId = spellId,
        spellName = spellName,
        amount = amount,
    )

    companion object {
        private val json = Json

        fun decode(payload: String): CombatEventDto {
            val root = try {
                json.parseToJsonElement(payload) as? JsonObject
                    ?: throw CombatEventDecodeException("Event payload must be a JSON object")
            } catch (error: SerializationException) {
                throw CombatEventDecodeException("Malformed event JSON", error)
            }

            val fields = root["fields"] as? JsonArray
                ?: throw CombatEventDecodeException("Event payload must include a fields array")

            return CombatEventDto(
                timestamp = root.requiredString("timestamp"),
                event = root.requiredString("event"),
                fields = fields.mapIndexed { index, value -> value.stringOrNull("fields[$index]") },
                sourceGuid = root.optionalString("source_guid"),
                sourceName = root.optionalString("source_name"),
                destGuid = root.optionalString("dest_guid"),
                destName = root.optionalString("dest_name"),
                spellId = root.optionalString("spell_id"),
                spellName = root.optionalString("spell_name"),
                amount = root.optionalString("amount"),
            )
        }
    }
}

class CombatEventDecodeException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private fun JsonObject.requiredString(name: String): String =
    this[name]?.stringOrNull(name)
        ?: throw CombatEventDecodeException("Event payload must include string '$name'")

private fun JsonObject.optionalString(name: String): String? =
    this[name]?.stringOrNull(name)

private fun JsonElement.stringOrNull(field: String): String? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> if (isString) content else {
        throw CombatEventDecodeException("'$field' must be a string or null")
    }
    else -> throw CombatEventDecodeException("'$field' must be a string or null")
}
