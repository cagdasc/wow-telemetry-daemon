package com.cacaosd.wowtelemetry.data.repository

import com.cacaosd.wowtelemetry.data.mapper.CombatEventMapper
import com.cacaosd.wowtelemetry.data.mapper.CombatStreamMessageDecoder
import com.cacaosd.wowtelemetry.data.model.CombatEventDecodeException
import com.cacaosd.wowtelemetry.data.model.parseCombatEventRoot
import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds

class CombatEventRepositoryImpl(
    private val httpClient: HttpClient,
    private val combatEventMapper: CombatEventMapper,
    private val logError: (String) -> Unit = ::println,
) : CombatEventRepository {
    private val streamMessageDecoder = CombatStreamMessageDecoder(combatEventMapper)
    private val selectedUrl = MutableStateFlow<String?>(null)
    private val mutableConnectionState = MutableStateFlow(DaemonConnectionState())
    private val payloadLog = Channel<String>(Channel.UNLIMITED)
    private var streamEpoch: Long? = null
    private var lastSeq = NO_SEQ

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
//            for (payload in payloadLog) println(payload)
        }
    }

    override val connectionState: StateFlow<DaemonConnectionState> =
        mutableConnectionState.asStateFlow()

    override fun connect(url: String) {
        resetStream()
        selectedUrl.value = url
        mutableConnectionState.value =
            DaemonConnectionState(DaemonConnectionState.Status.CONNECTING, url)
    }

    override fun disconnect() {
        resetStream()
        selectedUrl.value = null
        mutableConnectionState.value = DaemonConnectionState()
    }

    private fun resetStream() {
        streamEpoch = null
        lastSeq = NO_SEQ
    }

    override suspend fun clearDaemonBacklog() {
        val url = selectedUrl.value ?: error("Connect to a daemon before clearing its log.")
        httpClient.webSocket(urlString = url) {
            send(Frame.Text("""{"command":"CLEAR_LOG"}"""))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val root = Json.parseToJsonElement(frame.readText()).jsonObject
                if (root["control"]?.jsonPrimitive?.content != CLEAR_LOG_RESULT) continue
                if (root["success"]?.jsonPrimitive?.booleanOrNull == true) return@webSocket
                throw IllegalStateException(
                    root["message"]?.jsonPrimitive?.content ?: "Daemon could not clear its log."
                )
            }
            error("Daemon closed the connection before confirming the log clear.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun events(): Flow<CombatStreamMessage> =
        selectedUrl
            .flatMapLatest { url ->
                if (url == null) emptyFlow() else connectionEvents(url)
            }
            .flowOn(Dispatchers.IO)

    private fun connectionEvents(url: String) = flow {
        while (currentCoroutineContext().isActive) {
            try {
                httpClient.webSocket(urlString = url) {
                    mutableConnectionState.value =
                        DaemonConnectionState(DaemonConnectionState.Status.CONNECTED, url)
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        try {
                            val payload = frame.readText()
                            payloadLog.trySend(payload)
                            val root = parseCombatEventRoot(payload)
                            when (root["control"]?.jsonPrimitive?.contentOrNull) {
                                HELLO -> {
                                    resume(root)
                                    continue
                                }

                                REPLAY_GAP -> {
                                    logError("Daemon replay gap from $url: $root")
                                    continue
                                }
                            }
                            val seq = root["seq"]?.jsonPrimitive?.longOrNull
                            if (seq != null) {
                                if (seq <= lastSeq) continue
                                lastSeq = seq
                            }
                            emit(streamMessageDecoder.decode(root))
                        } catch (error: CombatEventDecodeException) {
                            logError("Rejected invalid WebSocket message from $url: ${error.message}")
                        }
                    }
                    val close = closeReason.await()
                    val description = close?.let { "${it.code}: ${it.message}" } ?: "no close frame"
                    logError("WebSocket connection to $url closed: $description")
                }
                if (currentCoroutineContext().isActive) {
                    mutableConnectionState.value =
                        DaemonConnectionState(DaemonConnectionState.Status.RECONNECTING, url)
                    logRetry(url)
                    delay(RECONNECT_DELAY)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableConnectionState.value =
                    DaemonConnectionState(DaemonConnectionState.Status.RECONNECTING, url)
                logError("WebSocket connection to $url failed: ${error.message}")
                logRetry(url)
                delay(RECONNECT_DELAY)
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.resume(hello: JsonObject) {
        val epoch = hello["epoch"]?.jsonPrimitive?.longOrNull ?: return
        val previousEpoch = streamEpoch
        streamEpoch = epoch
        if (previousEpoch == null) return
        if (previousEpoch != epoch) lastSeq = NO_SEQ
        if (previousEpoch == epoch && lastSeq != NO_SEQ) {
            send(Frame.Text("""{"command":"RESUME","epoch":$epoch,"from_seq":$lastSeq}"""))
        } else if (previousEpoch != epoch) {
            logError("Daemon restarted; events before the restart cannot be replayed")
        }
    }

    private fun logRetry(url: String) {
        logError("Retrying WebSocket connection to $url in 3 seconds")
    }

    private companion object {
        const val CLEAR_LOG_RESULT = "CLEAR_LOG_RESULT"
        const val HELLO = "HELLO"
        const val REPLAY_GAP = "REPLAY_GAP"
        const val NO_SEQ = -1L
        val RECONNECT_DELAY = 3.seconds
    }
}
