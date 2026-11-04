package com.cacaosd.wowtelemetry.data.network

import com.cacaosd.wowtelemetry.domain.CombatEvent
import com.cacaosd.wowtelemetry.domain.CombatLogEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

data class CombatEventDto(
    val timestamp: String,
    val event: CombatLogEvent,
    val fields: List<String?>,
) {
    fun toDomain() = CombatEvent(
        timestamp = timestamp,
        event = event,
        fields = fields,
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
            val eventObject = root.requiredObject("event")
            val event = decodeCombatLogEvent(
                type = eventObject.requiredString("type"),
                data = eventObject.requiredObject("data"),
            )

            return CombatEventDto(
                timestamp = root.requiredString("timestamp"),
                event = event,
                fields = fields.mapIndexed { index, value -> value.stringOrNull("fields[$index]") },
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

private fun JsonObject.requiredObject(name: String): JsonObject =
    this[name] as? JsonObject
        ?: throw CombatEventDecodeException("Event payload must include object '$name'")

private fun JsonElement.stringOrNull(field: String): String? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> if (isString) content else {
        throw CombatEventDecodeException("'$field' must be a string or null")
    }
    else -> throw CombatEventDecodeException("'$field' must be a string or null")
}
