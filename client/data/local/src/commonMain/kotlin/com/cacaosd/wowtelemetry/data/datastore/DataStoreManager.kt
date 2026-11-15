package com.cacaosd.wowtelemetry.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.cacaosd.wowtelemetry.data.datastore.keys.WowTelemetryKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreManager(private val dataStore: DataStore<Preferences>) {

    suspend fun <T> saveData(laterPreferencesKey: WowTelemetryKey<T>, value: T) {
        dataStore.edit { preferences ->
            preferences[laterPreferencesKey.key] = value
        }
    }

    fun <T> readData(laterPreferencesKey: WowTelemetryKey<T>): Flow<T> {
        return dataStore.data.map { preferences ->
            preferences[laterPreferencesKey.key] ?: laterPreferencesKey.defaultValue
        }
    }
}