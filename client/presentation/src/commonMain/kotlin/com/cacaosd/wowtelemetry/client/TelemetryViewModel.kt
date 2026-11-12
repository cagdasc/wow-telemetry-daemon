package com.cacaosd.wowtelemetry.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cacaosd.wowtelemetry.agent.*
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
    val eventCount: Int? = null,
)

data class TelemetryUiState(
    val latestEventSummary: String? = null,
    val chatHistory: List<CombatChatTurn> = emptyList(),
    val isPhrasingEvent: Boolean = false,
    val phrasingError: String? = null,
    val profile: CharacterProfile? = null,
    val profileError: String? = null,
    val activeBatch: CombatEventBatch? = null,
    val latestBatch: CombatEventBatch? = null,
    val daemonConnection: DaemonConnectionState = DaemonConnectionState(),
    val narrationSettings: NarrationSettings = NarrationSettings(),
    val settingsError: String? = null,
    val daemonBacklogMessage: String? = null,
    val daemonBacklogError: Boolean = false,
    val isClearingDaemonBacklog: Boolean = false,
    val ollamaModels: List<String> = emptyList(),
    val isDiscoveringOllamaModels: Boolean = false,
    val ollamaModelError: String? = null,
)

private data class QueuedChatBatch(
    val generation: Long,
    val batch: CombatEventBatch,
)

class TelemetryViewModel(
    private val processCombatEvent: ProcessCombatEventUseCase,
    private val combatEventRepository: CombatEventRepository,
    private val characterProfileRepository: CharacterProfileRepository,
    private val narrationSettingsRepository: NarrationSettingsRepository,
    private val ollamaModelRepository: OllamaModelRepository,
    private val combatEventNarrator: CombatEventNarrator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TelemetryUiState())
    val uiState: StateFlow<TelemetryUiState> = _uiState.asStateFlow()

    private val batchMutex = Mutex()
    private val batchEvents = mutableListOf<CombatEvent>()
    private var batchStartTimestamp: String? = null
    private var batchEndTimestamp: String? = null
    private var batchChatGeneration = 0L
    private var batchWatchdog: Job? = null
    private var chatGeneration = 0L
    private var chatSessionId = sessionId(chatGeneration)
    private var activeResponse: Deferred<String>? = null
    private var started = false
    private val batchQueue = Channel<QueuedChatBatch>(Channel.UNLIMITED)

    private val batchWorker = viewModelScope.launch(start = CoroutineStart.LAZY) {
        for ((generation, batch) in batchQueue) {
            if (generation != chatGeneration) continue

            val responseSessionId = chatSessionId
            val response = viewModelScope.async(Dispatchers.IO) {
                combatEventNarrator.respond(responseSessionId, _uiState.value.profile, batch)
            }
            activeResponse = response
            _uiState.update { it.copy(isPhrasingEvent = true) }
            try {
                val answer = response.await()
                check(answer.isNotBlank()) { "Ollama returned an empty response." }
                if (generation == chatGeneration) {
                    _uiState.update { state ->
                        state.copy(
                            chatHistory = state.chatHistory + listOf(
                                CombatChatTurn(
                                    role = "User",
                                    content = batch.toSummaryText(),
                                    batchStartTimestamp = batch.startTimestamp,
                                    batchEndTimestamp = batch.endTimestamp,
                                    eventCount = batch.events.size,
                                ),
                                CombatChatTurn(
                                    role = "Assistant",
                                    content = answer,
                                    batchStartTimestamp = batch.startTimestamp,
                                    batchEndTimestamp = batch.endTimestamp,
                                    eventCount = batch.events.size,
                                ),
                            )
                        )
                    }
                }
            } catch (error: CancellationException) {
                if (!viewModelScope.isActive) throw error
            } catch (error: Exception) {
                if (generation == chatGeneration) {
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
        viewModelScope.launch {
            combatEventRepository.connectionState.collect { connection ->
                _uiState.update { it.copy(daemonConnection = connection) }
                if (
                    connection.status != DaemonConnectionState.Status.CONNECTED &&
                    _uiState.value.narrationSettings.batchFlushStrategy ==
                    BatchFlushStrategy.FLUSH_ON_DISCONNECT
                ) {
                    flushBatch()
                }
            }
        }
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
            try {
                val settings = narrationSettingsRepository.load()
                _uiState.update { it.copy(narrationSettings = settings, settingsError = null) }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        settingsError = error.message ?: "Could not load narration settings."
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

                    CombatStreamMessage.LogCleared -> flushBatch()
                }
            }
        }
    }

    fun connectToDaemon(url: String) {
        _uiState.update { it.copy(daemonBacklogMessage = null) }
        combatEventRepository.connect(url)
    }

    fun disconnectDaemon() {
        combatEventRepository.disconnect()
    }

    fun clearDaemonBacklog() {
        if (_uiState.value.isClearingDaemonBacklog) return
        _uiState.update { it.copy(isClearingDaemonBacklog = true, daemonBacklogMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                combatEventRepository.clearDaemonBacklog()
                _uiState.update {
                    it.copy(
                        isClearingDaemonBacklog = false,
                        daemonBacklogMessage = "Daemon log file cleared.",
                        daemonBacklogError = false,
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isClearingDaemonBacklog = false,
                        daemonBacklogMessage = error.message ?: "Could not clear daemon backlog.",
                        daemonBacklogError = true,
                    )
                }
            }
        }
    }

    fun clearHistory() {
        val previousSessionId = chatSessionId
        chatGeneration++
        chatSessionId = sessionId(chatGeneration)
        activeResponse?.cancel()
        _uiState.update {
            it.copy(
                chatHistory = emptyList(),
                phrasingError = null,
            )
        }
        viewModelScope.launch {
            batchMutex.withLock {
                if (batchEvents.isNotEmpty()) batchChatGeneration = chatGeneration
            }
            combatEventNarrator.clearChat(previousSessionId)
        }
    }

    fun saveNarrationSettings(settings: NarrationSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                narrationSettingsRepository.save(settings)
                _uiState.update { it.copy(narrationSettings = settings, settingsError = null) }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(settingsError = error.message ?: "Could not save narration settings.")
                }
            }
        }
    }

    fun discoverOllamaModels() {
        if (_uiState.value.isDiscoveringOllamaModels) return
        _uiState.update {
            it.copy(isDiscoveringOllamaModels = true, ollamaModelError = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val models = ollamaModelRepository.discoverModels()
                _uiState.update {
                    it.copy(ollamaModels = models, isDiscoveringOllamaModels = false)
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isDiscoveringOllamaModels = false,
                        ollamaModelError = error.message ?: "Could not discover Ollama models.",
                    )
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

    private suspend fun collectBatchEvent(event: CombatEvent) {
        batchMutex.withLock {
            armWatchdog()
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
        }
    }

    private fun armWatchdog() {
        batchWatchdog?.cancel()
        batchWatchdog = null
        val settings = _uiState.value.narrationSettings
        if (settings.batchFlushStrategy != BatchFlushStrategy.SILENCE_WATCHDOG) return
        batchWatchdog = viewModelScope.launch {
            delay(settings.batchSilenceTimeoutSeconds.seconds)
            flushBatch(cancelWatchdog = false)
        }
    }

    private suspend fun flushBatch(cancelWatchdog: Boolean = true) {
        val queuedBatch = batchMutex.withLock {
            if (cancelWatchdog) {
                batchWatchdog?.cancel()
                batchWatchdog = null
            }
            if (batchEvents.isEmpty()) return
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
        if (queuedBatch.generation == chatGeneration) {
            batchQueue.send(queuedBatch)
        }
    }

    override fun onCleared() {
        batchWatchdog?.cancel()
        activeResponse?.cancel()
        batchQueue.close()
        super.onCleared()
    }

    private fun CombatEventBatch.toSummaryText(): String =
        "Combat batch from $startTimestamp to $endTimestamp with ${events.size} events."

    private companion object {

        fun sessionId(generation: Long) = "combat-chat-$generation"
    }
}
