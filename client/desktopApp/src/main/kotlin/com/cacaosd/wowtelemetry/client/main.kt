@file:OptIn(ExperimentalComposeUiApi::class)

package com.cacaosd.wowtelemetry.client

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.v2.Window
import androidx.lifecycle.ViewModelStore
import com.cacaosd.wowtelemetry.client.di.appModule
import com.cacaosd.wowtelemetry.data.database.AppDatabase
import com.cacaosd.wowtelemetry.data.database.CharacterProfileDatabase
import io.ktor.client.*
import org.koin.core.context.startKoin
import java.net.URI

private const val DEFAULT_WEBSOCKET_URL = "ws://127.0.0.1:8765"

fun main(args: Array<String>) {
    val websocketUrl = parseWebSocketUrl(args) ?: return
    val koinApplication = startKoin {
        allowOverride(false)
        modules(appModule())
    }
    val database = koinApplication.koin.get<AppDatabase>()
    val profileDatabase = koinApplication.koin.get<CharacterProfileDatabase>()
    val httpClient = koinApplication.koin.get<HttpClient>()
    val viewModel = koinApplication.koin.get<TelemetryViewModel>()
    val viewModelStore = ViewModelStore().apply {
        put("TelemetryViewModel", viewModel)
    }
    viewModel.start()

    application {
        Window(
            onCloseRequest = {
                viewModelStore.clear()
                httpClient.close()
                database.close()
                profileDatabase.close()
                koinApplication.close()
                exitApplication()
            },
            title = "WoW Telemetry Client",
            minSize = DpSize(width = 1200.dp, height = 900.dp),
        ) {
            App(websocketUrl, viewModel)
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
                            "Set WOW_TELEMETRY_WS_URL to prefill the connection panel.",
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
