package com.cacaosd.wowtelemetry.data.datastore.impl

import com.cacaosd.wowtelemetry.data.datastore.DataStoreManager
import com.cacaosd.wowtelemetry.data.datastore.keys.BatchFlushStrategyPreferences
import com.cacaosd.wowtelemetry.data.datastore.keys.BatchSilenceTimeoutSecondsPreferences
import com.cacaosd.wowtelemetry.data.datastore.keys.EffortModePreferences
import com.cacaosd.wowtelemetry.data.datastore.keys.LLModelPreferences
import com.cacaosd.wowtelemetry.data.datastore.keys.SupporterFeedingModePreferences
import com.cacaosd.wowtelemetry.data.datastore.keys.SupporterSummaryModelPreferences
import com.cacaosd.wowtelemetry.data.datastore.keys.TemperaturePreferences
import com.cacaosd.wowtelemetry.domain.ollama.LocalModel
import com.cacaosd.wowtelemetry.domain.preferences.BatchFlushStrategy
import com.cacaosd.wowtelemetry.domain.preferences.EffortMode
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettings
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettingsRepository
import com.cacaosd.wowtelemetry.domain.preferences.SupporterFeedingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class DesktopNarrationSettingsRepository(private val dataStoreManager: DataStoreManager) : NarrationSettingsRepository {

    override suspend fun load(): NarrationSettings = withContext(Dispatchers.IO) {
        val modelRaw = dataStoreManager.readData(LLModelPreferences).first()
        val model = runCatching {
            Json.decodeFromString<LocalModel>(modelRaw)
        }.getOrElse {
            LocalModel(name = modelRaw.ifBlank { NarrationSettings.DEFAULT_MODEL.name }, contextLength = 2048L)
        }

        NarrationSettings(
            temperature = dataStoreManager.readData(TemperaturePreferences).first(),
            model = model,
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
            effortMode = runCatching {
                EffortMode.valueOf(
                    dataStoreManager.readData(EffortModePreferences).first()
                )
            }.getOrDefault(EffortMode.BALANCED),
            supporterFeedingMode = runCatching {
                SupporterFeedingMode.valueOf(
                    dataStoreManager.readData(SupporterFeedingModePreferences).first()
                )
            }.getOrDefault(SupporterFeedingMode.DIRECT_ATTACHMENT),
            supporterSummaryModel = dataStoreManager.readData(SupporterSummaryModelPreferences).first(),
        )
    }

    override suspend fun save(settings: NarrationSettings) = withContext(Dispatchers.IO) {
        dataStoreManager.saveData(TemperaturePreferences, settings.temperature)
        dataStoreManager.saveData(
            LLModelPreferences,
            Json.encodeToString(settings.model)
        )
        dataStoreManager.saveData(BatchFlushStrategyPreferences, settings.batchFlushStrategy.name)
        dataStoreManager.saveData(BatchSilenceTimeoutSecondsPreferences, settings.batchSilenceTimeoutSeconds)
        dataStoreManager.saveData(EffortModePreferences, settings.effortMode.name)
        dataStoreManager.saveData(SupporterFeedingModePreferences, settings.supporterFeedingMode.name)
        dataStoreManager.saveData(SupporterSummaryModelPreferences, settings.supporterSummaryModel)
    }
}
