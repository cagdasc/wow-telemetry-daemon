package com.cacaosd.wowtelemetry.domain

import kotlinx.coroutines.flow.Flow

interface CombatEventRepository {
    fun events(): Flow<CombatStreamMessage>
}
