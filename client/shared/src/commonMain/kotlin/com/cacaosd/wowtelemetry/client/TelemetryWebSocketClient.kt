package com.cacaosd.wowtelemetry.client

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class TelemetryWebSocketClient(
    private val httpClient: HttpClient,
    private val url: String,
    private val logError: (String) -> Unit = ::println,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        listenAndReconnect()
    }

    fun start() {
        worker.start()
    }

    fun close() {
        scope.cancel()
        httpClient.close()
    }

    private suspend fun listenAndReconnect() {
        while (currentCoroutineContext().isActive) {
            try {
                httpClient.webSocket(urlString = url) {
                    println("Connected to telemetry daemon")
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> println(frame.data.decodeToString())
                            is Frame.Close -> {
                                val reason = frame.readReason()
                                logError(
                                    "WebSocket closed (${reason?.code}): ${reason?.message.orEmpty()}",
                                )
                                break
                            }

                            else -> Unit
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentCoroutineContext().isActive) {
                    logError("WebSocket connection to $url failed: ${error.message}")
                }
            }

            if (currentCoroutineContext().isActive) {
                logError("Retrying WebSocket connection to $url in 3 seconds")
                delay(RECONNECT_DELAY_MILLIS)
            }
        }
    }

    private companion object {
        val RECONNECT_DELAY_MILLIS = 3_000.milliseconds
    }
}
