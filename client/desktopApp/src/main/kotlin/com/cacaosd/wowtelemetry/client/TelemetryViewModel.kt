package com.cacaosd.wowtelemetry.client

import com.cacaosd.wowtelemetry.domain.*
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
        append(event.type)
        when (val typedEvent = event) {
            is UnrecognizedEventEvent -> {
                append(" eventName=")
                append(typedEvent.eventName)
            }

            is MalformedEventEvent -> {
                append(" reason=")
                append(typedEvent.reason)
                append(" rawBytes=")
                append(typedEvent.rawBytes.size)
            }

            else -> Unit
        }
        (event as? HasSpellPrefix)?.spell?.spellId?.let {
            append(" spellId=")
            append(it)
        }
        (event as? HasBaseAmount)?.baseAmount?.let {
            append(" amount=")
            append(it)
        }
    }
}
