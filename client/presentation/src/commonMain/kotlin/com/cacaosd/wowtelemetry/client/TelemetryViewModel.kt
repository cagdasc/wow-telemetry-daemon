package com.cacaosd.wowtelemetry.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cacaosd.wowtelemetry.agent.CombatEventNarrator
import com.cacaosd.wowtelemetry.combatlog.CombatEventBatch
import com.cacaosd.wowtelemetry.combatlog.CombatLogAnalyzer
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.CharacterProfileRepository
import com.cacaosd.wowtelemetry.domain.CombatEvent
import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState
import com.cacaosd.wowtelemetry.domain.ProcessCombatEventUseCase
import com.cacaosd.wowtelemetry.domain.ollama.OllamaModelRepository
import com.cacaosd.wowtelemetry.domain.preferences.BatchFlushStrategy
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettings
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettingsRepository
import com.cacaosd.wowtelemetry.domain.session.CombatLogSession
import com.cacaosd.wowtelemetry.domain.session.CombatLogSessionStore
import com.cacaosd.wowtelemetry.domain.session.CombatSessionReplayer
import com.cacaosd.wowtelemetry.domain.session.IngestMode
import com.cacaosd.wowtelemetry.domain.session.RecordingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class CombatChatTurn(
    val role: String,
    val content: String,
    val batchStartTimestamp: String? = null,
    val batchEndTimestamp: String? = null,
    val eventCount: Int? = null,
    val question: String? = null,
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
    val ingestMode: IngestMode = IngestMode.LIVE,
    val recording: RecordingState = RecordingState(),
    val pendingFrames: Long = 0,
    val sessions: List<CombatLogSession> = emptyList(),
    val isProcessingSession: Boolean = false,
    val sessionMessage: String? = null,
    val sessionMessageIsError: Boolean = false,
    val analyzedEvents: Long = 0,
    val isAnswering: Boolean = false,
    val askError: String? = null,
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
    private val sessionStore: CombatLogSessionStore,
    private val sessionReplayer: CombatSessionReplayer,
    private val analyzer: CombatLogAnalyzer,
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
    private var askSessionId = askSessionId(chatGeneration)
    private var askJob: Job? = null
    private var activeResponse: Deferred<String>? = null
    private var started = false
    private val batchQueue = Channel<QueuedChatBatch>(Channel.UNLIMITED)
    private val sessionMutex = Mutex()
    private val processedFrames = mutableMapOf<String, Long>()
    private var lastActiveBatchPublish: TimeMark? = null

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
        viewModelScope.launch {
            combatEventRepository.ingestMode.collect { mode ->
                _uiState.update { it.copy(ingestMode = mode) }
            }
        }
        viewModelScope.launch {
            combatEventRepository.collectError.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(sessionMessage = error, sessionMessageIsError = true) }
                }
            }
        }
        viewModelScope.launch {
            sessionStore.recording.collect { recording ->
                _uiState.update {
                    it.copy(recording = recording, pendingFrames = pendingFrames(recording))
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

            processCombatEvent().collect(::handleMessage)
        }
    }

    private suspend fun handleMessage(message: CombatStreamMessage) {
        when (message) {
            is CombatStreamMessage.Event -> {
                val event = message.combatEvent
                _uiState.update { it.copy(latestEventSummary = event.toString()) }
                collectBatchEvent(event)
            }

            CombatStreamMessage.CaughtUp -> flushBatch()

            CombatStreamMessage.LogCleared -> flushBatch()
        }
    }

    fun setIngestMode(mode: IngestMode) {
        if (mode == _uiState.value.ingestMode) return
        if (mode == IngestMode.COLLECT) {
            combatEventRepository.setIngestMode(mode)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Frames recorded after the last drain are handled by the second drain below.
            drainActiveRecording()
            combatEventRepository.setIngestMode(IngestMode.LIVE)
            drainActiveRecording()
            sessionStore.finishRecording()
            refreshSessions()
        }
    }

    fun processCollected() {
        viewModelScope.launch(Dispatchers.IO) { drainActiveRecording() }
    }

    fun processSessionFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val active = _uiState.value.recording.session
                val total = if (active?.path == path) {
                    _uiState.value.recording.frameCount
                } else {
                    // A loaded file is its own dataset, so statistics from other sources must not mix in.
                    batchMutex.withLock { analyzer.reset() }
                    publishAnalyzedEvents()
                    sessionStore.open(path).frameCount ?: 0L
                }
                processSession(path, total)
            } catch (error: Exception) {
                reportSessionError(error)
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessions = sessionStore.list()
                _uiState.update { it.copy(sessions = sessions) }
            } catch (error: Exception) {
                reportSessionError(error)
            }
        }
    }

    private suspend fun drainActiveRecording() {
        val recording = _uiState.value.recording
        val path = recording.session?.path ?: return
        try {
            processSession(path, recording.frameCount)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportSessionError(error)
        }
    }

    private suspend fun processSession(path: String, total: Long) = sessionMutex.withLock {
        var skip = processedFrames[path] ?: 0L
        if (skip >= total) skip = 0L
        val count = total - skip
        if (count <= 0) return@withLock
        _uiState.update { it.copy(isProcessingSession = true, sessionMessage = null) }
        try {
            sessionReplayer.replay(path, skip, count).collect(::handleMessage)
            flushBatch()
            processedFrames[path] = total
            _uiState.update {
                it.copy(
                    pendingFrames = pendingFrames(it.recording),
                    sessionMessage = "Processed $count frames.",
                    sessionMessageIsError = false,
                )
            }
        } finally {
            _uiState.update { it.copy(isProcessingSession = false) }
        }
    }

    private fun pendingFrames(recording: RecordingState): Long {
        val path = recording.session?.path ?: return 0
        return (recording.frameCount - (processedFrames[path] ?: 0L)).coerceAtLeast(0)
    }

    private fun reportSessionError(error: Exception) {
        _uiState.update {
            it.copy(
                sessionMessage = error.message ?: "Session operation failed.",
                sessionMessageIsError = true,
            )
        }
    }

    fun connectToDaemon(url: String) {
        _uiState.update { it.copy(daemonBacklogMessage = null) }
        combatEventRepository.connect(url)
    }

    fun disconnectDaemon() {
        combatEventRepository.disconnect()
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.finishRecording()
            refreshSessions()
        }
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
        val previousAskSessionId = askSessionId
        chatGeneration++
        chatSessionId = sessionId(chatGeneration)
        askSessionId = askSessionId(chatGeneration)
        activeResponse?.cancel()
        askJob?.cancel()
        _uiState.update {
            it.copy(
                chatHistory = emptyList(),
                phrasingError = null,
                isAnswering = false,
                askError = null,
            )
        }
        viewModelScope.launch {
            batchMutex.withLock {
                if (batchEvents.isNotEmpty()) batchChatGeneration = chatGeneration
                analyzer.reset()
            }
            publishAnalyzedEvents()
            combatEventNarrator.clearChat(previousSessionId)
            combatEventNarrator.clearChat(previousAskSessionId)
        }
    }

    /** Answers from deterministic statistics; the LLM never sees raw log rows. */
    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _uiState.value.isAnswering) return
        val generation = chatGeneration
        val sessionId = askSessionId
        _uiState.update { it.copy(isAnswering = true, askError = null) }
        askJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val statistics = batchMutex.withLock { analyzer.summaryText() }
                val answer = combatEventNarrator.answer(sessionId, _uiState.value.profile, statistics, text)
                check(answer.isNotBlank()) { "Ollama returned an empty response." }
                if (generation == chatGeneration) {
                    _uiState.update {
                        it.copy(
                            chatHistory = it.chatHistory + CombatChatTurn(
                                role = "Assistant",
                                content = answer,
                                question = text,
                            )
                        )
                    }
                }
            } catch (error: CancellationException) {
                if (!viewModelScope.isActive) throw error
            } catch (error: Exception) {
                if (generation == chatGeneration) {
                    _uiState.update {
                        it.copy(askError = error.message ?: "Could not reach the local Ollama service.")
                    }
                }
            } finally {
                if (generation == chatGeneration) _uiState.update { it.copy(isAnswering = false) }
            }
        }
    }

    private fun publishAnalyzedEvents(count: Long = analyzer.eventCount) {
        _uiState.update { it.copy(analyzedEvents = count) }
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
            analyzer.accept(event)
            batchEndTimestamp = event.timestamp
            // Copying the batch per event is quadratic during fast replays, so publish at most every 200 ms.
            val lastPublish = lastActiveBatchPublish
            if (lastPublish == null || lastPublish.elapsedNow() >= ACTIVE_BATCH_PUBLISH_INTERVAL) {
                lastActiveBatchPublish = TimeSource.Monotonic.markNow()
                _uiState.update {
                    it.copy(
                        analyzedEvents = analyzer.eventCount,
                        activeBatch = CombatEventBatch(
                            startTimestamp = batchStartTimestamp!!,
                            endTimestamp = event.timestamp,
                            events = batchEvents.toList(),
                        )
                    )
                }
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
            lastActiveBatchPublish = null
            batchStartTimestamp = null
            batchEndTimestamp = null
            val generation = batchChatGeneration
            _uiState.update {
                it.copy(
                    activeBatch = null,
                    latestBatch = result,
                    phrasingError = null,
                    analyzedEvents = analyzer.eventCount,
                )
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
        sessionStore.finishRecording()
        super.onCleared()
    }

    private fun CombatEventBatch.toSummaryText(): String =
        "Combat batch from $startTimestamp to $endTimestamp with ${events.size} events."

    private companion object {

        val ACTIVE_BATCH_PUBLISH_INTERVAL = 200.milliseconds

        fun sessionId(generation: Long) = "combat-chat-$generation"

        fun askSessionId(generation: Long) = "combat-ask-$generation"
    }
}
