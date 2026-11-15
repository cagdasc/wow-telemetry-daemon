package com.cacaosd.wowtelemetry.data.di

import com.cacaosd.wowtelemetry.data.database.di.databaseModule
import com.cacaosd.wowtelemetry.data.datastore.di.dataStoreModule
import org.koin.dsl.module

fun localDataModule() = module {
    includes(dataStoreModule, databaseModule)
}
