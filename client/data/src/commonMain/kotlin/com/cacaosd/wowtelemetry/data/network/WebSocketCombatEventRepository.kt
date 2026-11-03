package com.cacaosd.wowtelemetry.data.network

import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.seconds

class WebSocketCombatEventRepository(
    private val httpClient: HttpClient,
    private val url: String,
    private val logError: (String) -> Unit = ::println,
) : CombatEventRepository {
    override fun events(): Flow<com.cacaosd.wowtelemetry.domain.CombatEvent> = flow {
        while (currentCoroutineContext().isActive) {
            connectionEvents()
                .retryWhen { error, _ ->
                    if (error is CancellationException) throw error
                    logError("WebSocket connection to $url failed: ${error.message}")
                    logRetry()
                    delay(RECONNECT_DELAY)
                    true
                }
                .collect { emit(it) }

            if (currentCoroutineContext().isActive) {
                logRetry()
                delay(RECONNECT_DELAY)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun connectionEvents() = flow {
        httpClient.webSocket(urlString = url) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val payload = frame.readText()
                try {
                    emit(CombatEventDto.decode(payload).toDomain())
                } catch (error: CombatEventDecodeException) {
                    logError("Rejected invalid combat event from $url: ${error.message}")
                }
            }
        }
    }

    private fun logRetry() {
        logError("Retrying WebSocket connection to $url in 3 seconds")
    }

    private companion object {
        val RECONNECT_DELAY = 3.seconds
    }
}
