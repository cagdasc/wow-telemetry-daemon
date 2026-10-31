package com.cacaosd.wowtelemetry.client

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.net.URI

private const val DEFAULT_WEBSOCKET_URL = "ws://127.0.0.1:8765"

fun main(args: Array<String>) {
    val websocketUrl = parseWebSocketUrl(args) ?: return
    val client = TelemetryWebSocketClient(
        httpClient = HttpClient(CIO) {
            install(WebSockets)
        },
        url = websocketUrl,
        logError = { System.err.println(it) },
    )
    client.start()

    application {
        Window(
            onCloseRequest = {
                client.close()
                exitApplication()
            },
            title = "WoW Telemetry Client",
        ) {
            App(websocketUrl)
        }
    }
}

private fun parseWebSocketUrl(args: Array<String>): String? {
    var url = System.getenv("WOW_TELEMETRY_WS_URL") ?: DEFAULT_WEBSOCKET_URL
    var index = 0

    while (index < args.size) {
        when (val argument = args[index]) {
            "--ws-url" -> {
                require(index + 1 < args.size) { "--ws-url requires a URL" }
                url = args[++index]
            }
            "--help", "-h" -> {
                println(
                    "Usage: WowTelemetryClient [--ws-url ws://HOST:8765]\n" +
                        "Set WOW_TELEMETRY_WS_URL to configure the URL through the environment.",
                )
                return null
            }
            else -> error("Unknown argument: $argument")
        }
        index++
    }

    val uri = URI(url)
    require(uri.scheme == "ws" || uri.scheme == "wss") {
        "WebSocket URL must use ws:// or wss://"
    }
    require(uri.host != null) { "WebSocket URL must include a host" }
    return uri.toString()
}
