package com.cacaosd.wowtelemetry.data.di

import org.koin.dsl.module

fun dataModule(websocketUrl: String) = module {
    includes(networkDataModule(websocketUrl), localDataModule())
}
