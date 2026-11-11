package com.cacaosd.wowtelemetry.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CombatEventRepository {
    val connectionState: StateFlow<DaemonConnectionState>

    fun connect(url: String)

    fun disconnect()

    suspend fun clearDaemonBacklog()

    fun events(): Flow<CombatStreamMessage>
}

data class DaemonConnectionState(
    val status: Status = Status.DISCONNECTED,
    val url: String? = null,
) {
    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
    }
}
