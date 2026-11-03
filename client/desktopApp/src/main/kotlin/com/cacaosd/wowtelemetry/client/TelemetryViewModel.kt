package com.cacaosd.wowtelemetry.client

import com.cacaosd.wowtelemetry.domain.CombatEvent
import com.cacaosd.wowtelemetry.domain.ProcessCombatEventUseCase
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TelemetryViewModel(
    private val processCombatEvent: ProcessCombatEventUseCase,
    private val httpClient: HttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _latestEventSummary = MutableStateFlow<String?>(null)
    val latestEventSummary: StateFlow<String?> = _latestEventSummary

    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        processCombatEvent().collect { event ->
            println(event)
            _latestEventSummary.value = event.toSummary()
        }
    }

    fun start() {
        worker.start()
    }

    fun close() {
        scope.cancel()
        httpClient.close()
    }

    private fun CombatEvent.toSummary(): String = buildString {
        append(timestamp)
        append(' ')
        append(event)
        spellId?.let {
            append(" spellId=")
            append(it)
        }
        amount?.let {
            append(" amount=")
            append(it)
        }
    }
}
