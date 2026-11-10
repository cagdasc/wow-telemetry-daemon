package com.cacaosd.wowtelemetry.data.mapper

import com.cacaosd.wowtelemetry.data.model.CombatEventDecodeException
import com.cacaosd.wowtelemetry.data.model.CombatEventDto
import com.cacaosd.wowtelemetry.data.model.parseCombatEventRoot
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import kotlinx.serialization.json.JsonPrimitive

class CombatStreamMessageDecoder(
    private val combatEventMapper: CombatEventMapper,
) {
    fun decode(payload: String): CombatStreamMessage {
        val root = parseCombatEventRoot(payload)
        val control = root["control"]
        if (control != null) {
            val name = control as? JsonPrimitive
            if (name?.isString != true) {
                throw CombatEventDecodeException("Control message must include a string 'control'")
            }
            if (name.content == CAUGHT_UP && root.size == 1) {
                return CombatStreamMessage.CaughtUp
            }
            throw CombatEventDecodeException("Unsupported WebSocket control message '${name.content}'")
        }

        return CombatStreamMessage.Event(combatEventMapper.toDomain(CombatEventDto.decode(root)))
    }

    private companion object {
        const val CAUGHT_UP = "LOG_CAUGHT_UP"
    }
}
