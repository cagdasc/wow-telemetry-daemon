package com.cacaosd.wowtelemetry.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cacaosd.wowtelemetry.agent.CombatEventNarrator
import com.cacaosd.wowtelemetry.combatlog.CombatEventBatch
import com.cacaosd.wowtelemetry.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

data class CombatChatTurn(
    val role: String,
    val content: String,
    val batchStartTimestamp: String? = null,
    val batchEndTimestamp: String? = null,
)

data class TelemetryUiState(
    val latestEventSummary: String? = null,
    val chatHistory: List<CombatChatTurn> = emptyList(),
    val isChatActive: Boolean = true,
    val isPhrasingEvent: Boolean = false,
    val phrasingError: String? = null,
    val profile: CharacterProfile? = null,
    val profileError: String? = null,
    val activeBatch: CombatEventBatch? = null,
    val latestBatch: CombatEventBatch? = null,
)

private data class QueuedChatBatch(
    val generation: Long,
    val batch: CombatEventBatch,
)

class TelemetryViewModel(
    private val processCombatEvent: ProcessCombatEventUseCase,
    private val characterProfileRepository: CharacterProfileRepository,
    private val combatEventNarrator: CombatEventNarrator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TelemetryUiState())
    val uiState: StateFlow<TelemetryUiState> = _uiState.asStateFlow()

    private val batchMutex = Mutex()
    private val batchEvents = mutableListOf<CombatEvent>()
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
            if (!_uiState.value.isChatActive || generation != chatGeneration) continue

            val response = viewModelScope.async(Dispatchers.IO) {
                combatEventNarrator.respond(chatSessionId, _uiState.value.profile, batch)
            }
            activeResponse = response
            _uiState.update { it.copy(isPhrasingEvent = true) }
            try {
                val answer = response.await()
                check(answer.isNotBlank()) { "Ollama returned an empty response." }
                if (_uiState.value.isChatActive && generation == chatGeneration) {
                    _uiState.update { state ->
                        state.copy(
                            chatHistory = state.chatHistory + listOf(
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
                        )
                    }
                }
            } catch (error: CancellationException) {
                if (!viewModelScope.isActive) throw error
            } catch (error: Exception) {
                if (_uiState.value.isChatActive && generation == chatGeneration) {
                    _uiState.update {
                        it.copy(
                            phrasingError = error.message ?: "Could not reach the local Ollama service."
                        )
                    }
                }
            } finally {
                if (activeResponse === response) activeResponse = null
                _uiState.update { it.copy(isPhrasingEvent = false) }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        batchWorker.start()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = characterProfileRepository.getProfile()
                _uiState.update { it.copy(profile = profile, profileError = null) }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        profileError = error.message ?: "Could not load the saved character profile."
                    )
                }
            }

            processCombatEvent().collect { message ->
                when (message) {
                    is CombatStreamMessage.Event -> {
                        val event = message.combatEvent
                        _uiState.update { it.copy(latestEventSummary = event.toString()) } // enriched.summary
                        collectBatchEvent(event)
                    }

                    CombatStreamMessage.CaughtUp -> flushBatch()
                }
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
                _uiState.update { it.copy(profile = normalizedProfile, profileError = null) }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        profileError = error.message ?: "Could not save the character profile."
                    )
                }
            }
        }
    }

    fun stopChat() {
        if (!_uiState.value.isChatActive) return
        val previousSessionId = chatSessionId
        chatGeneration++
        _uiState.update {
            it.copy(isChatActive = false, chatHistory = emptyList(), phrasingError = null)
        }
        activeResponse?.cancel()
        viewModelScope.launch { combatEventNarrator.clearChat(previousSessionId) }
    }

    fun startChat() {
        if (_uiState.value.isChatActive) return
        chatGeneration++
        chatSessionId = sessionId(chatGeneration)
        _uiState.update {
            it.copy(isChatActive = true, chatHistory = emptyList(), phrasingError = null)
        }
    }

    private suspend fun collectBatchEvent(event: CombatEvent) {
        val isFull = batchMutex.withLock {
            if (batchEvents.isEmpty()) {
                batchStartTimestamp = event.timestamp
                batchChatGeneration = chatGeneration
                _uiState.update {
                    it.copy(
                        activeBatch = CombatEventBatch(event.timestamp, event.timestamp, emptyList()),
                        latestBatch = null,
                        phrasingError = null,
                    )
                }
            }
            batchEvents += event
            batchEndTimestamp = event.timestamp
            _uiState.update {
                it.copy(
                    activeBatch = CombatEventBatch(
                        startTimestamp = batchStartTimestamp!!,
                        endTimestamp = event.timestamp,
                        events = batchEvents.toList(),
                    )
                )
            }
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
            _uiState.update {
                it.copy(activeBatch = null, latestBatch = result, phrasingError = null)
            }
            QueuedChatBatch(generation, result)
        }
        if (_uiState.value.isChatActive && queuedBatch.generation == chatGeneration) {
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
        const val MAX_BATCH_EVENTS = 10000
        val BATCH_TIMEOUT = 1.5.seconds

        fun sessionId(generation: Long) = "combat-chat-$generation"
    }
}
