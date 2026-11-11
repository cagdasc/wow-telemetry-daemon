package com.cacaosd.wowtelemetry.client.di

import ai.koog.utils.time.KoogClock
import com.cacaosd.wowtelemetry.agent.CombatEventNarrator
import com.cacaosd.wowtelemetry.agent.NarrationSettingsRepository
import com.cacaosd.wowtelemetry.agent.OllamaModelRepository
import com.cacaosd.wowtelemetry.agent.SupportingDocumentProvider
import com.cacaosd.wowtelemetry.client.TelemetryViewModel
import com.cacaosd.wowtelemetry.data.database.AppDatabase
import com.cacaosd.wowtelemetry.data.database.CharacterProfileDatabase
import com.cacaosd.wowtelemetry.data.database.createAppDatabase
import com.cacaosd.wowtelemetry.data.database.createCharacterProfileDatabase
import com.cacaosd.wowtelemetry.data.di.localDataModule
import com.cacaosd.wowtelemetry.data.di.networkDataModule
import com.cacaosd.wowtelemetry.domain.ProcessCombatEventUseCase
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds

fun appModule() = module {
    includes(networkDataModule(), localDataModule())

    single<AppDatabase> { createAppDatabase("/home/cagdas/wow_db/wow-telemetry.db") }
    single<CharacterProfileDatabase> {
        createCharacterProfileDatabase("/home/cagdas/wow_db/wow-telemetry-profile.db")
    }
    single {
        HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 10.seconds
            }
        }
    }
    factory { ProcessCombatEventUseCase(get()) }
    single<KoogClock> { KoogClock.System }
    single<NarrationSettingsRepository> { DesktopNarrationSettingsRepository() }
    single<OllamaModelRepository> { DesktopOllamaModelRepository(get()) }
    single<SupportingDocumentProvider> { FileSupportingDocumentProvider() }
    single { CombatEventNarrator(get(), get(), get()) }
    single {
        TelemetryViewModel(
            processCombatEvent = get(),
            combatEventRepository = get(),
            characterProfileRepository = get(),
            narrationSettingsRepository = get(),
            ollamaModelRepository = get(),
            combatEventNarrator = get(),
        )
    }
}
