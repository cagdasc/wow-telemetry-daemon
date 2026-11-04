package com.cacaosd.wowtelemetry.client.di

import com.cacaosd.wowtelemetry.client.TelemetryViewModel
import com.cacaosd.wowtelemetry.data.database.AppDatabase
import com.cacaosd.wowtelemetry.data.database.RoomNpcMetadataRepository
import com.cacaosd.wowtelemetry.data.database.RoomSpellMetadataRepository
import com.cacaosd.wowtelemetry.data.database.createAppDatabase
import com.cacaosd.wowtelemetry.data.di.dataModule
import com.cacaosd.wowtelemetry.domain.NpcMetadataRepository
import com.cacaosd.wowtelemetry.domain.ProcessCombatEventUseCase
import com.cacaosd.wowtelemetry.domain.SpellMetadataRepository
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import org.koin.dsl.module

fun appModule(websocketUrl: String) = module {
    includes(dataModule(websocketUrl))

    single<AppDatabase> { createAppDatabase("/home/cagdas/wow_db/wow-telemetry.db") }
    single<SpellMetadataRepository> {
        RoomSpellMetadataRepository(dao = get<AppDatabase>().spellNameDao(), spellDao = get<AppDatabase>().spellDao())
    }
    single<NpcMetadataRepository> {
        RoomNpcMetadataRepository(get<AppDatabase>().creatureDao())
    }
    single {
        HttpClient(CIO) {
            install(WebSockets)
        }
    }
    factory { ProcessCombatEventUseCase(get()) }
    single { TelemetryViewModel(processCombatEvent = get(), httpClient = get()) }
}
