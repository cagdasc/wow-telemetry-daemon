@file:OptIn(ExperimentalComposeUiApi::class)

package com.cacaosd.wowtelemetry.client

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.v2.Window
import androidx.lifecycle.ViewModelStore
import com.cacaosd.platform.core.config.PlatformConfiguration
import com.cacaosd.platform.core.di.platformConfiguration
import com.cacaosd.platform.coroutines.di.platformCoroutines
import com.cacaosd.platform.coroutines.dispatchers.PlatformDispatchers
import com.cacaosd.wowtelemetry.client.di.appModule
import com.cacaosd.wowtelemetry.data.database.AppDatabase
import com.cacaosd.wowtelemetry.data.database.CharacterProfileDatabase
import com.cacaosd.wowtelemetry.data.getHomeDirectory
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.koin.compose.KoinApplication
import org.koin.compose.LocalKoinApplication
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

private const val DEFAULT_WEBSOCKET_URL = "ws://127.0.0.1:8765"

fun main(args: Array<String>) = application {
    val websocketUrl = parseWebSocketUrl(args) ?: return@application

    KoinApplication(
        application = {
            platformCoroutines()
            platformConfiguration(PlatformConfiguration())

            allowOverride(false)
            modules(appModule())
            @OptIn(ExperimentalCoroutinesApi::class)
            Dispatchers.setMain(koin.get<PlatformDispatchers>().main)
        },
        content = {
            val koin = LocalKoinApplication.current
            val database = koin.get<AppDatabase>()
            val profileDatabase = koin.get<CharacterProfileDatabase>()
            val httpClient = koin.get<HttpClient>()
            val viewModel = koin.get<TelemetryViewModel>()
            val viewModelStore = ViewModelStore().apply {
                put("TelemetryViewModel", viewModel)
            }
            viewModel.start()

            Window(
                onCloseRequest = {
                    viewModelStore.clear()
                    httpClient.close()
                    database.close()
                    profileDatabase.close()
                    koin.close()
                    exitApplication()
                },
                title = "WoW Telemetry Client",
                minSize = DpSize(width = 1200.dp, height = 900.dp),
            ) {
                App(
                    websocketUrl,
                    viewModel,
                    pickSessionFile = ::chooseSessionFile,
                    pickLogFile = ::chooseLogFile,
                )
            }
        }
    )
}

private suspend fun chooseLogFile(): String? = withContext(Dispatchers.IO) {
    var selected: String? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = "Attach combat log file"
            fileFilter = FileNameExtensionFilter("Combat logs (*.txt, *.log, *.wowlog)", "txt", "log", "wowlog")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            selected = chooser.selectedFile.absolutePath
        }
    }
    selected
}

private suspend fun chooseSessionFile(): String? = withContext(Dispatchers.IO) {
    var selected: String? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser(getHomeDirectory() + "/sessions").apply {
            dialogTitle = "Open combat log session"
            fileFilter = FileNameExtensionFilter("WoW telemetry session (*.wowlog)", "wowlog")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            selected = chooser.selectedFile.absolutePath
        }
    }
    selected
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
