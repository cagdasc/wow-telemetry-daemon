package com.cacaosd.wowtelemetry.client.di

import com.cacaosd.wowtelemetry.agent.di.agetModule
import com.cacaosd.wowtelemetry.client.TelemetryViewModel
import com.cacaosd.wowtelemetry.combatlog.CombatLogAnalyzer
import com.cacaosd.wowtelemetry.data.di.localDataModule
import com.cacaosd.wowtelemetry.data.di.networkDataModule
import com.cacaosd.wowtelemetry.domain.ProcessCombatEventUseCase
import org.koin.dsl.module

fun appModule() = module {
    includes(networkDataModule(), localDataModule(), agetModule())

    factory { ProcessCombatEventUseCase(get()) }

    single {
        TelemetryViewModel(
            processCombatEvent = get(),
            combatEventRepository = get(),
            characterProfileRepository = get(),
            narrationSettingsRepository = get(),
            ollamaModelRepository = get(),
            combatEventNarrator = get(),
            sessionStore = get(),
            sessionReplayer = get(),
            analyzer = CombatLogAnalyzer(),
        )
    }
}
