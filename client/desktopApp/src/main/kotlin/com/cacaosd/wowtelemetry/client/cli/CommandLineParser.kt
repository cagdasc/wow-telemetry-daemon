package com.cacaosd.wowtelemetry.client.cli

import java.net.URI

private const val DEFAULT_WEBSOCKET_URL = "ws://127.0.0.1:8765"
private const val ENV_WEBSOCKET_URL = "WOW_TELEMETRY_WS_URL"

data class AppConfig(
    val websocketUrl: String,
)

object CommandLineParser {
    fun parse(args: Array<String>): AppConfig? {
        var url = System.getenv(ENV_WEBSOCKET_URL) ?: DEFAULT_WEBSOCKET_URL
        var index = 0

        while (index < args.size) {
            when (val argument = args[index]) {
                "--ws-url" -> {
                    if ((index + 1) >= args.size) {
                        System.err.println("Error: --ws-url requires a URL argument.")
                        printUsage()
                        return null
                    }
                    url = args[++index]
                }

                "--help", "-h" -> {
                    printUsage()
                    return null
                }

                else -> {
                    System.err.println("Error: Unknown argument '$argument'")
                    printUsage()
                    return null
                }
            }
            index++
        }

        val validatedUrl = validateWebSocketUrl(url) ?: return null
        return AppConfig(websocketUrl = validatedUrl)
    }

    private fun validateWebSocketUrl(url: String): String? {
        return try {
            val uri = URI(url)
            require((uri.scheme == "ws") || (uri.scheme == "wss")) { "WebSocket URL must use ws:// or wss://" }
            require(uri.host != null) { "WebSocket URL must include a host" }
            uri.toString()
        } catch (e: Exception) {
            System.err.println("Error: Invalid WebSocket URL '$url': ${e.message}")
            printUsage()
            null
        }
    }

    private fun printUsage() {
        println(
            """
            Usage: WowTelemetryClient [--ws-url ws://HOST:8765]
            Set $ENV_WEBSOCKET_URL to prefill the connection panel.
            """.trimIndent(),
        )
    }
}
