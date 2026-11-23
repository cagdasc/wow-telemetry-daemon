package com.cacaosd.wowtelemetry.data.repository

import com.cacaosd.wowtelemetry.data.mapper.CombatStreamMessageDecoder
import com.cacaosd.wowtelemetry.data.mapper.isRecordable
import com.cacaosd.wowtelemetry.data.mapper.toStreamMessage
import com.cacaosd.wowtelemetry.data.model.parseCombatEventRoot
import com.cacaosd.wowtelemetry.data.proto.Envelope
import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState
import com.cacaosd.wowtelemetry.domain.session.CombatLogSessionException
import com.cacaosd.wowtelemetry.domain.session.CombatLogSessionStore
import com.cacaosd.wowtelemetry.domain.session.FrameKind
import com.cacaosd.wowtelemetry.domain.session.IngestMode
import com.cacaosd.wowtelemetry.domain.session.RawFrame
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.IOException
import kotlin.time.Duration.Companion.seconds

class CombatEventRepositoryImpl(
    private val httpClient: HttpClient,
    private val streamMessageDecoder: CombatStreamMessageDecoder,
    private val sessionStore: CombatLogSessionStore,
    private val logError: (String) -> Unit = ::println,
) : CombatEventRepository {
    private val selectedUrl = MutableStateFlow<String?>(null)
    private val mutableConnectionState = MutableStateFlow(DaemonConnectionState())
    private val mutableIngestMode = MutableStateFlow(IngestMode.LIVE)
    private val mutableCollectError = MutableStateFlow<String?>(null)
    private var streamEpoch: Long? = null
    private var lastSeq = NO_SEQ

    override val connectionState: StateFlow<DaemonConnectionState> =
        mutableConnectionState.asStateFlow()
    override val ingestMode: StateFlow<IngestMode> = mutableIngestMode.asStateFlow()
    override val collectError: StateFlow<String?> = mutableCollectError.asStateFlow()

    override fun setIngestMode(mode: IngestMode) {
        mutableCollectError.value = null
        mutableIngestMode.value = mode
    }

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
                val result = when (frame) {
                    is Frame.Binary -> Envelope.ADAPTER.decode(frame.readBytes())
                        .control?.clear_log_result
                        ?.let { ClearResult(it.success, it.message) }

                    is Frame.Text -> {
                        val root = Json.parseToJsonElement(frame.readText()).jsonObject
                        if (root["control"]?.jsonPrimitive?.content != CLEAR_LOG_RESULT) null
                        else ClearResult(
                            root["success"]?.jsonPrimitive?.booleanOrNull == true,
                            root["message"]?.jsonPrimitive?.content,
                        )
                    }

                    else -> null
                } ?: continue
                if (result.success) return@webSocket
                throw IllegalStateException(result.message ?: "Daemon could not clear its log.")
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
                        val message = when (frame) {
                            is Frame.Binary -> processBinaryFrame(frame, url)
                            is Frame.Text -> processTextFrame(frame, url)
                            else -> null
                        }
                        if (message != null) emit(message)
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

    private suspend fun DefaultClientWebSocketSession.processBinaryFrame(
        frame: Frame.Binary,
        url: String,
    ): CombatStreamMessage? = try {
        val bytes = frame.readBytes()
        val envelope = Envelope.ADAPTER.decode(bytes)
        val hello = envelope.control?.hello
        when {
            hello != null -> {
                checkProtocolVersion(hello.protocol_version.toLong(), url)
                resume(hello.epoch)
                null
            }

            !isNewEnvelope(envelope, url) -> null
            ingestMode.value == IngestMode.COLLECT &&
                    (!envelope.isRecordable || recordFrame(RawFrame(FrameKind.BINARY, bytes))) -> null

            else -> envelope.toStreamMessage()
        }
    } catch (error: IOException) {
        logInvalidFrame("protobuf", url, error)
    } catch (error: IllegalArgumentException) {
        logInvalidFrame("protobuf", url, error)
    }

    private suspend fun DefaultClientWebSocketSession.processTextFrame(
        frame: Frame.Text,
        url: String,
    ): CombatStreamMessage? = try {
        val payload = frame.readText()
        val root = parseCombatEventRoot(payload)
        when (val control = root["control"]?.jsonPrimitive?.contentOrNull) {
            HELLO -> {
                checkProtocolVersion(
                    root["protocol_version"]?.jsonPrimitive?.longOrNull,
                    url,
                )
                resume(root["epoch"]?.jsonPrimitive?.longOrNull)
                null
            }

            REPLAY_GAP -> {
                logError("Daemon replay gap from $url: $root")
                null
            }

            else -> {
                val seq = root["seq"]?.jsonPrimitive?.longOrNull
                if (!isNewSequence(seq)) {
                    null
                } else if (
                    ingestMode.value == IngestMode.COLLECT &&
                    (!isRecordable(control) || recordFrame(RawFrame(FrameKind.TEXT, payload.encodeToByteArray())))
                ) {
                    null
                } else {
                    streamMessageDecoder.decode(root)
                }
            }
        }
    } catch (error: IllegalArgumentException) {
        logInvalidFrame("WebSocket", url, error)
    }

    private fun logInvalidFrame(
        format: String,
        url: String,
        error: Exception,
    ): CombatStreamMessage? {
        logError("Rejected invalid $format message from $url: ${error.message}")
        return null
    }

    /** Logs replay gaps and drops frames at or below the last seen `seq`. */
    private fun isNewEnvelope(envelope: Envelope, url: String): Boolean {
        val replayGap = envelope.control?.replay_gap
        if (replayGap != null) {
            logError("Daemon replay gap from $url: $replayGap")
            return false
        }
        return isNewSequence(envelope.seq)
    }

    private fun isNewSequence(seq: Long?): Boolean {
        if (seq == null || seq == UNSEQUENCED_SEQ) return true
        if (seq <= lastSeq) return false
        lastSeq = seq
        return true
    }

    private fun isRecordable(control: String?): Boolean =
        control == null || control == CAUGHT_UP || control == LOG_CLEARED

    /** Returns false after falling back to live mode, so the caller still processes the frame. */
    private fun recordFrame(frame: RawFrame): Boolean =
        try {
            sessionStore.append(frame)
            true
        } catch (error: IOException) {
            failCollecting(error)
        } catch (error: CombatLogSessionException) {
            failCollecting(error)
        }

    private fun failCollecting(error: Exception): Boolean {
        val message = "Could not save frames to disk, switched to live mode: ${error.message}"
        logError(message)
        mutableCollectError.value = message
        mutableIngestMode.value = IngestMode.LIVE
        return false
    }

    private fun checkProtocolVersion(version: Long?, url: String) {
        if (version != null && version != PROTOCOL_VERSION) {
            logError("Daemon protocol version mismatch from $url: expected $PROTOCOL_VERSION, got $version")
        }
    }

    private suspend fun DefaultClientWebSocketSession.resume(epoch: Long?) {
        epoch ?: return
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

    private class ClearResult(val success: Boolean, val message: String?)

    private companion object {
        const val CLEAR_LOG_RESULT = "CLEAR_LOG_RESULT"
        const val HELLO = "HELLO"
        const val CAUGHT_UP = "LOG_CAUGHT_UP"
        const val LOG_CLEARED = "LOG_CLEARED"
        const val REPLAY_GAP = "REPLAY_GAP"
        const val PROTOCOL_VERSION = 1L
        const val NO_SEQ = -1L
        const val UNSEQUENCED_SEQ = 0L
        val RECONNECT_DELAY = 3.seconds
    }
}
