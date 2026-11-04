package com.cacaosd.wowtelemetry.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cacaosd.wowtelemetry.agent.CombatEventNarrator
import com.cacaosd.wowtelemetry.combatlog.CombatEventBatch
import com.cacaosd.wowtelemetry.combatlog.CombatEventEnricher
import com.cacaosd.wowtelemetry.combatlog.EnrichedCombatEvent
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.CharacterProfileRepository
import com.cacaosd.wowtelemetry.domain.ProcessCombatEventUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

data class CombatChatTurn(
    val role: String,
    val content: String,
    val batchStartTimestamp: String? = null,
    val batchEndTimestamp: String? = null,
)

private data class QueuedChatBatch(
    val generation: Long,
    val batch: CombatEventBatch,
)

class TelemetryViewModel(
    private val processCombatEvent: ProcessCombatEventUseCase,
    private val combatEventEnricher: CombatEventEnricher,
    private val characterProfileRepository: CharacterProfileRepository,
    private val combatEventNarrator: CombatEventNarrator,
) : ViewModel() {
    private val _latestEventSummary = MutableStateFlow<String?>(null)
    val latestEventSummary: StateFlow<String?> = _latestEventSummary.asStateFlow()
    private val _chatHistory = MutableStateFlow<List<CombatChatTurn>>(emptyList())
    val chatHistory: StateFlow<List<CombatChatTurn>> = _chatHistory.asStateFlow()
    private val _isChatActive = MutableStateFlow(true)
    val isChatActive: StateFlow<Boolean> = _isChatActive.asStateFlow()
    private val _isPhrasingEvent = MutableStateFlow(false)
    val isPhrasingEvent: StateFlow<Boolean> = _isPhrasingEvent.asStateFlow()
    private val _phrasingError = MutableStateFlow<String?>(null)
    val phrasingError: StateFlow<String?> = _phrasingError.asStateFlow()
    private val _profile = MutableStateFlow<CharacterProfile?>(null)
    val profile: StateFlow<CharacterProfile?> = _profile.asStateFlow()
    private val _profileError = MutableStateFlow<String?>(null)
    val profileError: StateFlow<String?> = _profileError.asStateFlow()
    private val _activeBatch = MutableStateFlow<CombatEventBatch?>(null)
    val activeBatch: StateFlow<CombatEventBatch?> = _activeBatch.asStateFlow()
    private val _activeBatchEventCount = MutableStateFlow(0)
    val activeBatchEventCount: StateFlow<Int> = _activeBatchEventCount.asStateFlow()
    private val _latestBatch = MutableStateFlow<CombatEventBatch?>(null)
    val latestBatch: StateFlow<CombatEventBatch?> = _latestBatch.asStateFlow()

    private val batchMutex = Mutex()
    private val batchEvents = mutableListOf<EnrichedCombatEvent>()
    private var batchStartTimestamp: String? = null
    private var batchEndTimestamp: String? = null
    private var batchChatGeneration = 0L
    private var batchTimeout: Job? = null
    private var chatGeneration = 0L
    private var chatSessionId = sessionId(chatGeneration)
    private var activeResponse: Deferred<String>? = null
    private var started = false
    private val batchQueue = Channel<QueuedChatBatch>(capacity = 1)

    private val batchWorker = viewModelScope.launch(start = CoroutineStart.LAZY) {
        for ((generation, batch) in batchQueue) {
            if (!_isChatActive.value || generation != chatGeneration) continue

            val response = viewModelScope.async(Dispatchers.IO) {
                combatEventNarrator.respond(chatSessionId, _profile.value, batch)
            }
            activeResponse = response
            _isPhrasingEvent.value = true
            try {
                val answer = response.await()
                check(answer.isNotBlank()) { "Ollama returned an empty response." }
                if (_isChatActive.value && generation == chatGeneration) {
                    _chatHistory.value += listOf(
                        CombatChatTurn(
                            role = "User",
                            content = batch.toSummaryText(),
                            batchStartTimestamp = batch.startTimestamp,
                            batchEndTimestamp = batch.endTimestamp,
                        ),
                        CombatChatTurn(
                            role = "Assistant",
                            content = answer,
                            batchStartTimestamp = batch.startTimestamp,
                            batchEndTimestamp = batch.endTimestamp,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                if (!viewModelScope.isActive) throw error
            } catch (error: Exception) {
                if (_isChatActive.value && generation == chatGeneration) {
                    _phrasingError.value =
                        error.message ?: "Could not reach the local Ollama service."
                }
            } finally {
                if (activeResponse === response) activeResponse = null
                _isPhrasingEvent.value = false
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        batchWorker.start()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _profile.value = characterProfileRepository.getProfile()
                _profileError.value = null
            } catch (error: Exception) {
                _profileError.value = error.message ?: "Could not load the saved character profile."
            }

            processCombatEvent().collect { event ->
                val enriched = combatEventEnricher.enrich(event)
                _latestEventSummary.value = enriched.summary
                collectBatchEvent(enriched)
            }
        }
    }

    fun saveCharacterProfile(profile: CharacterProfile) {
        viewModelScope.launch {
            val normalizedProfile = profile.copy(
                characterName = profile.characterName.trim(),
                characterClass = profile.characterClass.trim(),
                specialization = profile.specialization.trim(),
                realm = profile.realm.trim(),
                region = profile.region.trim(),
            )
            try {
                characterProfileRepository.saveProfile(normalizedProfile)
                _profile.value = normalizedProfile
                _profileError.value = null
            } catch (error: Exception) {
                _profileError.value = error.message ?: "Could not save the character profile."
            }
        }
    }

    fun stopChat() {
        if (!_isChatActive.value) return
        val previousSessionId = chatSessionId
        chatGeneration++
        _isChatActive.value = false
        activeResponse?.cancel()
        _chatHistory.value = emptyList()
        _phrasingError.value = null
        viewModelScope.launch { combatEventNarrator.clearChat(previousSessionId) }
    }

    fun startChat() {
        if (_isChatActive.value) return
        chatGeneration++
        chatSessionId = sessionId(chatGeneration)
        _isChatActive.value = true
        _chatHistory.value = emptyList()
        _phrasingError.value = null
    }

    private suspend fun collectBatchEvent(event: EnrichedCombatEvent) {
        val isFull = batchMutex.withLock {
            if (batchEvents.isEmpty()) {
                batchStartTimestamp = event.timestamp
                batchChatGeneration = chatGeneration
                _activeBatch.value = CombatEventBatch(event.timestamp, event.timestamp, emptyList())
                _latestBatch.value = null
                _phrasingError.value = null
            }
            batchEvents += event
            batchEndTimestamp = event.timestamp
            _activeBatchEventCount.value = batchEvents.size
            _activeBatch.value = CombatEventBatch(
                startTimestamp = batchStartTimestamp!!,
                endTimestamp = event.timestamp,
                events = batchEvents.toList(),
            )
            batchTimeout?.cancel()
            batchTimeout = viewModelScope.launch {
                delay(BATCH_TIMEOUT)
                flushBatch(cancelTimeout = false)
            }
            batchEvents.size >= MAX_BATCH_EVENTS
        }
        if (isFull) flushBatch()
    }

    private suspend fun flushBatch(cancelTimeout: Boolean = true) {
        val queuedBatch = batchMutex.withLock {
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
            val generation = batchChatGeneration
            _activeBatch.value = null
            _activeBatchEventCount.value = 0
            _latestBatch.value = result
            _phrasingError.value = null
            QueuedChatBatch(generation, result)
        }
        if (_isChatActive.value && queuedBatch.generation == chatGeneration) {
            batchQueue.send(queuedBatch)
        }
    }

    override fun onCleared() {
        batchTimeout?.cancel()
        activeResponse?.cancel()
        batchQueue.close()
        super.onCleared()
    }

    private fun CombatEventBatch.toSummaryText(): String =
        "Combat batch from $startTimestamp to $endTimestamp with ${events.size} events."

    private companion object {
        const val MAX_BATCH_EVENTS = 100
        val BATCH_TIMEOUT = 1.seconds

        fun sessionId(generation: Long) = "combat-chat-$generation"
    }
}
