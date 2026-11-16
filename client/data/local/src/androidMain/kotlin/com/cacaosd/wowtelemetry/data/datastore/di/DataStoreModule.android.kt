package com.cacaosd.wowtelemetry.data.datastore.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.cacaosd.platform.core.config.PlatformConfiguration
import com.cacaosd.wowtelemetry.data.datastore.createDataStore
import com.cacaosd.wowtelemetry.data.datastore.settingsDataStoreFileName

actual fun provideDataStore(platformConfiguration: PlatformConfiguration): DataStore<Preferences> {
    return createDataStore(
        producePath = { platformConfiguration.context.filesDir.resolve(settingsDataStoreFileName).absolutePath }
    )
}