package com.cacaosd.wowtelemetry.data.di

import com.cacaosd.wowtelemetry.data.network.WebSocketCombatEventRepository
import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import org.koin.dsl.module

fun dataModule(websocketUrl: String) = module {
    single<CombatEventRepository> {
        WebSocketCombatEventRepository(
            httpClient = get(),
            url = websocketUrl,
        )
    }
}
