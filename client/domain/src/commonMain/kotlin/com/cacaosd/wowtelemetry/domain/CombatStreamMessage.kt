package com.cacaosd.wowtelemetry.domain

sealed interface CombatStreamMessage {
    data class Event(val combatEvent: CombatEvent) : CombatStreamMessage

    object CaughtUp : CombatStreamMessage

    object LogCleared : CombatStreamMessage
}
