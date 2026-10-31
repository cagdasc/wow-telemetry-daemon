package com.cacaosd.wowtelemetry.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class TelemetryWebSocketClient(private val uri: URI) {
    private val stopping = AtomicBoolean(false)
    private val activeSocket = AtomicReference<WebSocket?>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val worker = Thread(::listenAndReconnect, "telemetry-websocket-client").apply {
        isDaemon = true
    }

    fun start() {
        worker.start()
    }

    fun close() {
        stopping.set(true)
        activeSocket.get()?.sendClose(WebSocket.NORMAL_CLOSURE, "Client shutting down")
        worker.interrupt()
    }

    private fun listenAndReconnect() {
        while (!stopping.get()) {
            val disconnected = CountDownLatch(1)
            val listener = ConsoleListener(disconnected, activeSocket, stopping)
            try {
                val socket = httpClient.newWebSocketBuilder()
                    .buildAsync(uri, listener)
                    .join()
                activeSocket.set(socket)
                disconnected.await()
            } catch (error: CompletionException) {
                if (!stopping.get()) {
                    System.err.println(
                        "WebSocket connection to $uri failed: " +
                            (error.cause?.message ?: error.message),
                    )
                }
            } catch (error: InterruptedException) {
                if (!stopping.get()) {
                    Thread.currentThread().interrupt()
                }
                break
            } finally {
                activeSocket.set(null)
            }

            if (!stopping.get()) {
                System.err.println("Retrying WebSocket connection to $uri in 3 seconds")
                try {
                    Thread.sleep(RECONNECT_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    if (!stopping.get()) {
                        Thread.currentThread().interrupt()
                    }
                    break
                }
            }
        }
    }

    private class ConsoleListener(
        private val disconnected: CountDownLatch,
        private val activeSocket: AtomicReference<WebSocket?>,
        private val stopping: AtomicBoolean,
    ) : WebSocket.Listener {
        private val message = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            activeSocket.set(webSocket)
            if (stopping.get()) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client shutting down")
                disconnected.countDown()
                return
            }
            println("Connected to telemetry daemon")
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletableFuture<*>? {
            message.append(data)
            if (last) {
                println(message)
                message.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletableFuture<*>? {
            System.err.println("WebSocket closed ($statusCode): $reason")
            activeSocket.compareAndSet(webSocket, null)
            disconnected.countDown()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            System.err.println("WebSocket error: ${error.message}")
            activeSocket.compareAndSet(webSocket, null)
            disconnected.countDown()
        }
    }

    private companion object {
        const val RECONNECT_DELAY_MILLIS = 3_000L
    }
}
