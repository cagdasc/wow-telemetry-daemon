package com.cacaosd.wowtelemetry.client.di

import com.cacaosd.wowtelemetry.agent.CombatEventNarrator
import com.cacaosd.wowtelemetry.client.TelemetryViewModel
import com.cacaosd.wowtelemetry.combatlog.CombatEventEnricher
import com.cacaosd.wowtelemetry.data.database.AppDatabase
import com.cacaosd.wowtelemetry.data.database.CharacterProfileDatabase
import com.cacaosd.wowtelemetry.data.database.createAppDatabase
import com.cacaosd.wowtelemetry.data.database.createCharacterProfileDatabase
import com.cacaosd.wowtelemetry.data.di.dataModule
import com.cacaosd.wowtelemetry.domain.ProcessCombatEventUseCase
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import org.koin.dsl.module

fun appModule(websocketUrl: String) = module {
    includes(dataModule(websocketUrl))

    single<AppDatabase> { createAppDatabase("/home/cagdas/wow_db/wow-telemetry.db") }
    single<CharacterProfileDatabase> {
        createCharacterProfileDatabase("/home/cagdas/wow_db/wow-telemetry-profile.db")
    }
    single {
        HttpClient(CIO) {
            install(WebSockets)
        }
    }
    factory { ProcessCombatEventUseCase(get()) }
    single { CombatEventEnricher() }
    single { CombatEventNarrator() }
    single {
        TelemetryViewModel(
            processCombatEvent = get(),
            combatEventEnricher = get(),
            characterProfileRepository = get(),
            combatEventNarrator = get(),
        )
    }
}
