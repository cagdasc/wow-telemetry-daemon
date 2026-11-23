package com.cacaosd.wowtelemetry.data.datastore.keys

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cacaosd.wowtelemetry.domain.preferences.BatchFlushStrategy
import com.cacaosd.wowtelemetry.domain.preferences.EffortMode
import com.cacaosd.wowtelemetry.domain.preferences.SupporterFeedingMode

abstract class WowTelemetryKey<T>(val key: Preferences.Key<T>, val defaultValue: T) {
    internal companion object {
        const val DEFAULT_MODEL = "llama3.2:latest"
        const val MODEL_KEY = "model"
        const val FLUSH_STRATEGY_KEY = "batchFlushStrategy"
        const val SILENCE_TIMEOUT_KEY = "batchSilenceTimeoutSeconds"
        const val TEMPERATURE_KEY = "temperature"
        const val EFFORT_MODE_KEY = "effortMode"
        const val SUPPORTER_FEEDING_MODE_KEY = "supporterFeedingMode"
        const val SUPPORTER_SUMMARY_MODEL_KEY = "supporterSummaryModel"

        const val DEFAULT_SILENCE_TIMEOUT_SECONDS = 10
        const val DEFAULT_TEMPERATURE = .5
    }
}

data object LLModelPreferences : WowTelemetryKey<String>(key = stringPreferencesKey(MODEL_KEY), DEFAULT_MODEL)

data object BatchFlushStrategyPreferences :
    WowTelemetryKey<String>(key = stringPreferencesKey(FLUSH_STRATEGY_KEY), BatchFlushStrategy.SILENCE_WATCHDOG.name)

data object BatchSilenceTimeoutSecondsPreferences :
    WowTelemetryKey<Int>(key = intPreferencesKey(SILENCE_TIMEOUT_KEY), DEFAULT_SILENCE_TIMEOUT_SECONDS)

data object TemperaturePreferences :
    WowTelemetryKey<Double>(key = doublePreferencesKey(TEMPERATURE_KEY), DEFAULT_TEMPERATURE)

data object EffortModePreferences :
    WowTelemetryKey<String>(key = stringPreferencesKey(EFFORT_MODE_KEY), EffortMode.BALANCED.name)

data object SupporterFeedingModePreferences :
    WowTelemetryKey<String>(
        key = stringPreferencesKey(SUPPORTER_FEEDING_MODE_KEY),
        SupporterFeedingMode.DIRECT_ATTACHMENT.name
    )

data object SupporterSummaryModelPreferences :
    WowTelemetryKey<String>(key = stringPreferencesKey(SUPPORTER_SUMMARY_MODEL_KEY), "")
