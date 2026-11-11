package com.cacaosd.wowtelemetry.data.mapper

import com.cacaosd.wowtelemetry.data.model.CombatEventDecodeException
import com.cacaosd.wowtelemetry.data.model.CombatEventDto
import com.cacaosd.wowtelemetry.data.model.parseCombatEventRoot
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CombatStreamMessageDecoder(
    private val combatEventMapper: CombatEventMapper,
) {
    fun decode(payload: String): CombatStreamMessage = decode(parseCombatEventRoot(payload))

    fun decode(root: JsonObject): CombatStreamMessage {
        val control = root["control"]
        if (control != null) {
            val name = control as? JsonPrimitive
            if (name?.isString != true) {
                throw CombatEventDecodeException("Control message must include a string 'control'")
            }
            if (name.content == CAUGHT_UP && root.payloadSize == 1) {
                return CombatStreamMessage.CaughtUp
            }
            if (name.content == LOG_CLEARED && root.payloadSize == 1) {
                return CombatStreamMessage.LogCleared
            }
            throw CombatEventDecodeException("Unsupported WebSocket control message '${name.content}'")
        }

        return CombatStreamMessage.Event(combatEventMapper.toDomain(CombatEventDto.decode(root)))
    }

    private val JsonObject.payloadSize: Int
        get() = if (containsKey("seq")) size - 1 else size

    private companion object {
        const val CAUGHT_UP = "LOG_CAUGHT_UP"
        const val LOG_CLEARED = "LOG_CLEARED"
    }
}
