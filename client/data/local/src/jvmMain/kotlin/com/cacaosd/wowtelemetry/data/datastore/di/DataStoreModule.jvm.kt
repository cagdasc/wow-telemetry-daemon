package com.cacaosd.wowtelemetry.data.datastore.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.cacaosd.wowtelemetry.data.datastore.createDataStore
import com.cacaosd.wowtelemetry.data.datastore.settingsDataStoreFileName
import com.cacaosd.wowtelemetry.data.getHomeDirectory
import java.io.File

actual fun provideDataStore(): DataStore<Preferences> {
    return createDataStore(
        producePath = {
            val preferencesFile = File(getHomeDirectory(), settingsDataStoreFileName)
            preferencesFile.absolutePath
        }
    )
}