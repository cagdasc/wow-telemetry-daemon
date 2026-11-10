package com.cacaosd.wowtelemetry.data.di

import com.cacaosd.wowtelemetry.data.mapper.CombatEventMapper
import com.cacaosd.wowtelemetry.data.repository.CombatEventRepositoryImpl
import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import org.koin.dsl.module

fun networkDataModule(websocketUrl: String) = module {
    single<CombatEventRepository> {
        CombatEventRepositoryImpl(
            httpClient = get(),
            combatEventMapper = get(),
            url = websocketUrl,
        )
    }

    single { CombatEventMapper() }
}
