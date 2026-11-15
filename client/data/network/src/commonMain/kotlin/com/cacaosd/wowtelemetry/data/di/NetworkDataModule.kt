package com.cacaosd.wowtelemetry.data.di

import com.cacaosd.wowtelemetry.data.mapper.CombatEventMapper
import com.cacaosd.wowtelemetry.data.repository.CombatEventRepositoryImpl
import com.cacaosd.wowtelemetry.data.repository.DesktopOllamaModelRepository
import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import com.cacaosd.wowtelemetry.domain.ollama.OllamaModelRepository
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
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
            combatEventMapper = get(),
        )
    }

    single { CombatEventMapper() }

    single<OllamaModelRepository> { DesktopOllamaModelRepository(get()) }
}
