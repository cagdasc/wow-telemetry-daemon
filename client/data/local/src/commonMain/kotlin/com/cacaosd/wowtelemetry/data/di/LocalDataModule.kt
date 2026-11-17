package com.cacaosd.wowtelemetry.data.di

import com.cacaosd.wowtelemetry.data.database.di.databaseModule
import com.cacaosd.wowtelemetry.data.datastore.di.dataStoreModule
import com.cacaosd.wowtelemetry.data.session.FileCombatLogSessionStore
import com.cacaosd.wowtelemetry.data.session.provideSessionDirectory
import com.cacaosd.wowtelemetry.domain.session.CombatLogSessionStore
import org.koin.dsl.module

fun localDataModule() = module {
    includes(dataStoreModule, databaseModule)

    single<CombatLogSessionStore> {
        val directory = provideSessionDirectory(get())
        FileCombatLogSessionStore(sessionDirectory = { directory })
    }
}
