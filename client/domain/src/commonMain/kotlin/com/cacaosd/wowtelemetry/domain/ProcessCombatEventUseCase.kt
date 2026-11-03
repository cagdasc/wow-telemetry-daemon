package com.cacaosd.wowtelemetry.domain

class ProcessCombatEventUseCase(
    private val repository: CombatEventRepository,
) {
    operator fun invoke() = repository.events()
}
