package com.cacaosd.wowtelemetry.data.mapper

import com.cacaosd.wowtelemetry.data.model.CombatEventDto
import com.cacaosd.wowtelemetry.domain.CombatEvent

class CombatEventMapper {
    fun toDomain(combatEventDto: CombatEventDto) = with(combatEventDto) {
        CombatEvent(
            timestamp = timestamp,
            event = event,
//            fields = fields,
        )
    }
}
