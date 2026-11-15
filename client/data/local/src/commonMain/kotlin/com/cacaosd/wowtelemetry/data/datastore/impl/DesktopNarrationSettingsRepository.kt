package com.cacaosd.wowtelemetry.data.datastore.impl

import com.cacaosd.wowtelemetry.data.datastore.DataStoreManager
import com.cacaosd.wowtelemetry.data.datastore.keys.BatchFlushStrategyPreferences
import com.cacaosd.wowtelemetry.data.datastore.keys.BatchSilenceTimeoutSecondsPreferences
import com.cacaosd.wowtelemetry.data.datastore.keys.LLModelPreferences
import com.cacaosd.wowtelemetry.data.datastore.keys.TemperaturePreferences
import com.cacaosd.wowtelemetry.domain.preferences.BatchFlushStrategy
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettings
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class DesktopNarrationSettingsRepository(private val dataStoreManager: DataStoreManager) : NarrationSettingsRepository {

    override suspend fun load(): NarrationSettings = withContext(Dispatchers.IO) {
        NarrationSettings(
            temperature = dataStoreManager.readData(TemperaturePreferences).first(),
            model = dataStoreManager.readData(LLModelPreferences).first(),
            batchFlushStrategy = runCatching {
                BatchFlushStrategy.valueOf(
                    dataStoreManager.readData(BatchFlushStrategyPreferences).first()
                )
            }.getOrDefault(BatchFlushStrategy.SILENCE_WATCHDOG),
            batchSilenceTimeoutSeconds = dataStoreManager.readData(BatchSilenceTimeoutSecondsPreferences).first()
                .coerceIn(
                    NarrationSettings.MIN_SILENCE_TIMEOUT_SECONDS,
                    NarrationSettings.MAX_SILENCE_TIMEOUT_SECONDS,
                ),
        )
    }

    override suspend fun save(settings: NarrationSettings) = withContext(Dispatchers.IO) {
        dataStoreManager.saveData(TemperaturePreferences, settings.temperature)
        dataStoreManager.saveData(LLModelPreferences, settings.model)
        dataStoreManager.saveData(BatchFlushStrategyPreferences, settings.batchFlushStrategy.name)
        dataStoreManager.saveData(BatchSilenceTimeoutSecondsPreferences, settings.batchSilenceTimeoutSeconds)
    }
}
