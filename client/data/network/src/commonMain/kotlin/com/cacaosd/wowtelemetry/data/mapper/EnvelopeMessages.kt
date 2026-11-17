package com.cacaosd.wowtelemetry.data.mapper

import com.cacaosd.wowtelemetry.data.proto.Envelope
import com.cacaosd.wowtelemetry.domain.CombatEvent
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage

internal fun Envelope.toStreamMessage(): CombatStreamMessage? = when {
    event != null -> CombatStreamMessage.Event(CombatEvent(event.timestamp, event.toCombatLogEvent()))
    control?.log_caught_up != null -> CombatStreamMessage.CaughtUp
    control?.log_cleared != null -> CombatStreamMessage.LogCleared
    else -> null
}

/** Frames worth keeping in a session file; handshake and replay bookkeeping are not. */
internal val Envelope.isRecordable: Boolean
    get() = event != null || control?.log_caught_up != null || control?.log_cleared != null
