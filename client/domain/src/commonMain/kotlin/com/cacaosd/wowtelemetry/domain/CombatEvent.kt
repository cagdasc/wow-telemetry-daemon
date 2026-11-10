package com.cacaosd.wowtelemetry.domain

data class CombatEvent(
    val timestamp: String,
    val event: CombatLogEvent,
//    val fields: List<String?>,
)
