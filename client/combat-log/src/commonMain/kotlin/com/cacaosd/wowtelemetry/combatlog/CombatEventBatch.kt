package com.cacaosd.wowtelemetry.combatlog

import com.cacaosd.wowtelemetry.domain.CombatEvent

data class CombatEventBatch(
    val startTimestamp: String,
    val endTimestamp: String,
    val events: List<CombatEvent>,
    val generation: Long,
)
