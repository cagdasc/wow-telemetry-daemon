package com.cacaosd.wowtelemetry.client

import com.cacaosd.wowtelemetry.domain.*
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

data class CombatEventBatch(
    val startTimestamp: String,
    val endTimestamp: String,
    val events: List<String>,
)

class TelemetryViewModel(
    private val processCombatEvent: ProcessCombatEventUseCase,
    private val httpClient: HttpClient,
    private val combatEventNarrator: CombatEventNarrator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _latestEventSummary = MutableStateFlow<String?>(null)
    val latestEventSummary: StateFlow<String?> = _latestEventSummary
    private val _phrasedEventSummary = MutableStateFlow<String?>(null)
    val phrasedEventSummary: StateFlow<String?> = _phrasedEventSummary
    private val _isPhrasingEvent = MutableStateFlow(false)
    val isPhrasingEvent: StateFlow<Boolean> = _isPhrasingEvent
    private val _phrasingError = MutableStateFlow<String?>(null)
    val phrasingError: StateFlow<String?> = _phrasingError
    private val _activeBatch = MutableStateFlow<CombatEventBatch?>(null)
    val activeBatch: StateFlow<CombatEventBatch?> = _activeBatch
    private val _activeBatchEventCount = MutableStateFlow(0)
    val activeBatchEventCount: StateFlow<Int> = _activeBatchEventCount
    private val _latestBatch = MutableStateFlow<CombatEventBatch?>(null)
    val latestBatch: StateFlow<CombatEventBatch?> = _latestBatch

    private val batchMutex = Mutex()
    private val batchEvents = mutableListOf<String>()
    private var batchStartTimestamp: String? = null
    private var batchEndTimestamp: String? = null
    private var batchTimeout: Job? = null
    private val batchQueue = Channel<CombatEventBatch>(capacity = 1)

    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        processCombatEvent().collect { event ->
            val summary = event.toString()
            _latestEventSummary.value = summary
            collectBatchEvent(event.timestamp, event.toBatchEntry())
        }
    }
    private val batchWorker = scope.launch(start = CoroutineStart.LAZY) {
        for (batch in batchQueue) {
            _isPhrasingEvent.value = true
            try {
                val batchInput = buildString {
                    appendLine("Combat log batch")
                    appendLine("Start: ${batch.startTimestamp}")
                    appendLine("End: ${batch.endTimestamp}")
                    appendLine("Events: ${batch.events.size}")
                    appendLine("Event summaries:")
                    batch.events.forEach { appendLine(it) }
                }
                val phrased = combatEventNarrator.rephrase(batchInput)
                check(phrased.isNotBlank()) { "Ollama returned an empty response." }
                if (_latestBatch.value === batch) {
                    _phrasedEventSummary.value = phrased
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (_latestBatch.value === batch) {
                    _phrasingError.value =
                        error.message ?: "Could not reach the local Ollama service."
                }
            } finally {
                _isPhrasingEvent.value = false
            }
        }
    }

    fun start() {
        batchWorker.start()
        worker.start()
    }

    private suspend fun collectBatchEvent(timestamp: String, summary: String) {
        val isFull = batchMutex.withLock {
            if (batchEvents.isEmpty()) {
                batchStartTimestamp = timestamp
                _activeBatch.value = CombatEventBatch(timestamp, timestamp, emptyList())
                _latestBatch.value = null
                _phrasedEventSummary.value = null
                _phrasingError.value = null
            }
            batchEvents += summary
            batchEndTimestamp = timestamp
            _activeBatchEventCount.value = batchEvents.size
            _activeBatch.value = CombatEventBatch(
                startTimestamp = batchStartTimestamp!!,
                endTimestamp = timestamp,
                events = batchEvents.toList(),
            )
            batchTimeout?.cancel()
            batchTimeout = scope.launch {
                delay(BATCH_TIMEOUT)
                flushBatch(cancelTimeout = false)
            }
            batchEvents.size >= MAX_BATCH_EVENTS
        }
        if (isFull) flushBatch()
    }

    private suspend fun flushBatch(cancelTimeout: Boolean = true) {
        val batch = batchMutex.withLock {
            if (batchEvents.isEmpty()) return
            if (cancelTimeout) batchTimeout?.cancel()
            batchTimeout = null
            val result = CombatEventBatch(
                startTimestamp = batchStartTimestamp!!,
                endTimestamp = batchEndTimestamp!!,
                events = batchEvents.toList(),
            )
            batchEvents.clear()
            batchStartTimestamp = null
            batchEndTimestamp = null
            _activeBatch.value = null
            _activeBatchEventCount.value = 0
            _latestBatch.value = result
            _phrasedEventSummary.value = null
            _phrasingError.value = null
            result
        }
        batchQueue.send(batch)
    }

    fun close() {
        batchTimeout?.cancel()
        batchQueue.close()
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
        (event as? HasCommonHeader)?.header?.let { header ->
            header.sourceName?.let {
                append(" source=")
                append(it)
            }
            header.destName?.let {
                append(" target=")
                append(it)
            }
        }
        (event as? HasSpellPrefix)?.spell?.spellId?.let {
            append(" spellId=")
            append(it)
        }
        (event as? HasSpellPrefix)?.spell?.spellName?.let {
            append(" spellName=")
            append(it)
        }
        (event as? HasBaseAmount)?.baseAmount?.let {
            append(" amount=")
            append(it)
        }
    }

    private fun CombatEvent.toBatchEntry(): String =
        "$timestamp event=$event fields=$fields"

    private companion object {
        const val MAX_BATCH_EVENTS = 100
        val BATCH_TIMEOUT = 1.seconds
    }
}
