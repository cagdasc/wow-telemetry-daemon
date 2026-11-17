package com.cacaosd.wowtelemetry.data.di

import com.cacaosd.wowtelemetry.data.mapper.CombatEventMapper
import com.cacaosd.wowtelemetry.data.mapper.CombatStreamMessageDecoder
import com.cacaosd.wowtelemetry.data.repository.CombatEventRepositoryImpl
import com.cacaosd.wowtelemetry.data.repository.DesktopOllamaModelRepository
import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import com.cacaosd.wowtelemetry.domain.ollama.OllamaModelRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds

fun networkDataModule() = module {
    single {
        HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 10.seconds
            }
        }
    }

    single<CombatEventRepository> {
        CombatEventRepositoryImpl(
            httpClient = get(),
            streamMessageDecoder = get(),
        )
    }

    single { CombatEventMapper() }
    single { CombatStreamMessageDecoder(combatEventMapper = get()) }

    single<OllamaModelRepository> { DesktopOllamaModelRepository(get()) }
}
