package com.cacaosd.wowtelemetry.domain

import com.cacaosd.wowtelemetry.domain.session.IngestMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CombatEventRepository {
    val connectionState: StateFlow<DaemonConnectionState>

    val ingestMode: StateFlow<IngestMode>

    /** Set when spilling frames to disk fails; the repository then falls back to [IngestMode.LIVE]. */
    val collectError: StateFlow<String?>

    /** In [IngestMode.COLLECT], [events] emits nothing; frames go to the session store undecoded. */
    fun setIngestMode(mode: IngestMode)

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
