package com.cacaosd.wowtelemetry.data.repository

import com.cacaosd.wowtelemetry.data.mapper.CombatEventMapper
import com.cacaosd.wowtelemetry.data.mapper.CombatStreamMessageDecoder
import com.cacaosd.wowtelemetry.data.model.CombatEventDecodeException
import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlin.time.Duration.Companion.seconds

class CombatEventRepositoryImpl(
    private val httpClient: HttpClient,
    private val url: String,
    private val combatEventMapper: CombatEventMapper,
    private val logError: (String) -> Unit = ::println,
) : CombatEventRepository {
    private val streamMessageDecoder = CombatStreamMessageDecoder(combatEventMapper)

    override fun events(): Flow<CombatStreamMessage> = flow {
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
                println(payload)
                try {
                    emit(streamMessageDecoder.decode(payload))
                } catch (error: CombatEventDecodeException) {
                    logError("Rejected invalid WebSocket message from $url: ${error.message}")
                }
            }
            val close = closeReason.await()
            val description = close?.let { "${it.code}: ${it.message}" } ?: "no close frame"
            logError("WebSocket connection to $url closed: $description")
        }
    }

    private fun logRetry() {
        logError("Retrying WebSocket connection to $url in 3 seconds")
    }

    private companion object {
        val RECONNECT_DELAY = 3.seconds
    }
}
