package com.cacaosd.wowtelemetry.domain.preferences

interface NarrationSettingsRepository {
    suspend fun load(): NarrationSettings

    suspend fun save(settings: NarrationSettings)
}
