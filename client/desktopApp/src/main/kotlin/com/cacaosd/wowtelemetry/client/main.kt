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
import com.cacaosd.wowtelemetry.client.cli.CommandLineParser
import com.cacaosd.wowtelemetry.client.di.appModule
import com.cacaosd.wowtelemetry.client.filechooser.chooseLogFile
import com.cacaosd.wowtelemetry.client.filechooser.chooseSessionFile
import com.cacaosd.wowtelemetry.data.database.AppDatabase
import com.cacaosd.wowtelemetry.data.database.CharacterProfileDatabase
import io.ktor.client.HttpClient
import org.koin.compose.KoinApplication
import org.koin.compose.LocalKoinApplication
import org.koin.core.Koin

private const val WINDOW_TITLE = "WoW Telemetry Client"
private val WINDOW_MIN_SIZE = DpSize(width = 1200.dp, height = 900.dp)

fun main(args: Array<String>) = application {
    val config = CommandLineParser.parse(args) ?: return@application

    KoinApplication(
        application = {
            platformCoroutines()
            platformConfiguration(PlatformConfiguration())

            allowOverride(false)
            modules(appModule())
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
                    shutdown(viewModelStore, httpClient, database, profileDatabase, koin)
                    exitApplication()
                },
                title = WINDOW_TITLE,
                minSize = WINDOW_MIN_SIZE,
            ) {
                App(
                    initialWebsocketUrl = config.websocketUrl,
                    viewModel = viewModel,
                    pickSessionFile = ::chooseSessionFile,
                    pickLogFile = ::chooseLogFile,
                )
            }
        }
    )
}

private fun shutdown(
    viewModelStore: ViewModelStore,
    httpClient: HttpClient,
    database: AppDatabase,
    profileDatabase: CharacterProfileDatabase,
    koin: Koin,
) {
    runCatching { viewModelStore.clear() }
    runCatching { httpClient.close() }
    runCatching { database.close() }
    runCatching { profileDatabase.close() }
    runCatching { koin.close() }
}
