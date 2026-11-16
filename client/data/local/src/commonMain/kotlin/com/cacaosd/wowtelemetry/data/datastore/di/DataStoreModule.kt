package com.cacaosd.wowtelemetry.data.datastore.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.cacaosd.platform.core.config.PlatformConfiguration
import com.cacaosd.wowtelemetry.data.datastore.DataStoreManager
import com.cacaosd.wowtelemetry.data.datastore.impl.DesktopNarrationSettingsRepository
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettingsRepository
import org.koin.dsl.module

internal val dataStoreModule = module {
    single { provideDataStore(get()) }
    single { DataStoreManager(get()) }

    single<NarrationSettingsRepository> { DesktopNarrationSettingsRepository(get()) }
}

expect fun provideDataStore(platformConfiguration: PlatformConfiguration): DataStore<Preferences>
