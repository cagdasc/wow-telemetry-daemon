package com.cacaosd.wowtelemetry.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cacaosd.wowtelemetry.agent.CombatEventNarrator
import com.cacaosd.wowtelemetry.combatlog.CombatEventBatch
import com.cacaosd.wowtelemetry.combatlog.CombatLogAnalyzer
import com.cacaosd.wowtelemetry.combatlog.CombatPlayer
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.CharacterProfileRepository
import com.cacaosd.wowtelemetry.domain.CombatEvent
import com.cacaosd.wowtelemetry.domain.CombatEventRepository
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState
import com.cacaosd.wowtelemetry.domain.EncounterEndEvent
import com.cacaosd.wowtelemetry.domain.EncounterStartEvent
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
import kotlin.math.abs
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
    val selectedSession: CombatLogSession? = null,
    val pendingFrames: Long = 0,
    val sessions: List<CombatLogSession> = emptyList(),
    val isProcessingSession: Boolean = false,
    val sessionMessage: String? = null,
    val sessionMessageIsError: Boolean = false,
    val analyzedEvents: Long = 0,
    val players: List<CombatPlayer> = emptyList(),
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

    @Volatile
    private var stopRequested = false

    // While replaying stored frames, batches only bound memory; one summary is narrated at the end.
    @Volatile
    private var replaying = false
    private var lastReplayTimeMs: Long? = null
    private var summaryJob: Job? = null
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
                    it.copy(
                        recording = recording,
                        pendingFrames = pendingFrames(recording, it.selectedSession),
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = characterProfileRepository.getProfile()
                analyzer.setCharacterProfile(profile)
                _uiState.update { it.copy(profile = profile, profileError = null, players = analyzer.players) }
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
                if (replaying && startsNewReplayBatch(event)) flushBatch(narrate = false)
                collectBatchEvent(event)
                if (replaying && event.event is EncounterEndEvent) flushBatch(narrate = false)
            }

            CombatStreamMessage.CaughtUp -> flushBatch(narrate = !replaying)

            CombatStreamMessage.LogCleared -> flushBatch(narrate = !replaying)
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
            _uiState.update { it.copy(selectedSession = null) }
            refreshSessions()
        }
    }

    fun stopProcessing() {
        stopRequested = true
    }

    fun selectSessionFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = sessionStore.open(path)
                batchMutex.withLock { analyzer.reset() }
                publishAnalyzedEvents()
                combatEventRepository.setIngestMode(IngestMode.COLLECT)
                val total = session.frameCount ?: 0L
                val skip = processedFrames[path] ?: 0L
                val pending = (total - skip).coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        selectedSession = session,
                        ingestMode = IngestMode.COLLECT,
                        pendingFrames = pending,
                        sessionMessage = null,
                        sessionMessageIsError = false,
                    )
                }
            } catch (error: Exception) {
                reportSessionError(error)
            }
        }
    }

    fun unselectSessionFile() {
        viewModelScope.launch(Dispatchers.IO) {
            combatEventRepository.setIngestMode(IngestMode.LIVE)
            _uiState.update {
                it.copy(
                    selectedSession = null,
                    ingestMode = IngestMode.LIVE,
                    pendingFrames = 0,
                    sessionMessage = null,
                    sessionMessageIsError = false,
                )
            }
            clearHistory()
        }
    }

    fun processCollected() {
        val selected = _uiState.value.selectedSession
        if (selected != null) {
            processSessionFile(selected.path)
        } else {
            viewModelScope.launch(Dispatchers.IO) { drainActiveRecording() }
        }
    }

    fun processSessionFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val active = _uiState.value.recording.session
                val total = if (active?.path == path) {
                    _uiState.value.recording.frameCount
                } else {
                    val opened = sessionStore.open(path)
                    _uiState.update { state -> state.copy(selectedSession = opened) }
                    // A loaded file is its own dataset, so statistics from other sources must not mix in.
                    batchMutex.withLock { analyzer.reset() }
                    publishAnalyzedEvents()
                    opened.frameCount ?: 0L
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
        stopRequested = false
        replaying = true
        lastReplayTimeMs = null
        _uiState.update { it.copy(isProcessingSession = true, sessionMessage = null) }
        try {
            var done = 0L
            // Chunks run to completion so a stop never leaves a half-counted chunk behind.
            while (done < count && !stopRequested) {
                val chunk = minOf(REPLAY_CHUNK_FRAMES, count - done)
                sessionReplayer.replay(path, skip + done, chunk).collect(::handleMessage)
                done += chunk
                processedFrames[path] = skip + done
                _uiState.update { it.copy(pendingFrames = pendingFrames(it.recording, it.selectedSession)) }
            }
            val stopped = done < count
            val encounterOpen = batchMutex.withLock { analyzer.hasOpenEncounter }
            // An unfinished encounter stays in the open batch so the next Process continues it.
            if (stopped || !encounterOpen) flushBatch(narrate = false)
            _uiState.update {
                it.copy(
                    pendingFrames = pendingFrames(it.recording, it.selectedSession),
                    sessionMessage = when {
                        stopped -> "Stopped after $done of $count frames."
                        encounterOpen -> "Processed $count frames. An encounter is still in progress; process again after it ends."
                        else -> "Processed $count frames."
                    },
                    sessionMessageIsError = false,
                )
            }
            if (!stopped && !encounterOpen) summarizeProcessed()
        } finally {
            replaying = false
            _uiState.update { it.copy(isProcessingSession = false) }
        }
    }

    private suspend fun startsNewReplayBatch(event: CombatEvent): Boolean {
        val timeMs = event.timestamp.toLogTimeMillis()
        val previous = lastReplayTimeMs
        lastReplayTimeMs = timeMs ?: previous
        if (event.event is EncounterStartEvent) return true
        if (timeMs == null || previous == null) return false
        val silenceMs = _uiState.value.narrationSettings.batchSilenceTimeoutSeconds * 1000L
        return abs(timeMs - previous) > silenceMs
    }

    // One LLM call for the whole processed dataset; follow-up questions go through ask().
    private fun summarizeProcessed() {
        summaryJob?.cancel()
        val generation = chatGeneration
        val sessionId = chatSessionId
        summaryJob = viewModelScope.launch(Dispatchers.IO) {
            val (statistics, count, first, last) = batchMutex.withLock {
                SummaryInput(
                    analyzer.summaryText(),
                    analyzer.eventCount,
                    analyzer.firstTimestamp,
                    analyzer.lastTimestamp
                )
            }
            if (count == 0L) return@launch
            _uiState.update { it.copy(isPhrasingEvent = true, phrasingError = null) }
            try {
                val answer = combatEventNarrator.summarize(sessionId, _uiState.value.profile, statistics)
                check(answer.isNotBlank()) { "Ollama returned an empty response." }
                if (generation == chatGeneration) {
                    _uiState.update {
                        it.copy(
                            chatHistory = it.chatHistory + CombatChatTurn(
                                role = "Assistant",
                                content = answer,
                                batchStartTimestamp = first,
                                batchEndTimestamp = last,
                                eventCount = count.toInt(),
                            )
                        )
                    }
                }
            } catch (error: CancellationException) {
                if (!viewModelScope.isActive) throw error
            } catch (error: Exception) {
                if (generation == chatGeneration) {
                    _uiState.update {
                        it.copy(phrasingError = error.message ?: "Could not reach the local Ollama service.")
                    }
                }
            } finally {
                if (generation == chatGeneration) _uiState.update { it.copy(isPhrasingEvent = false) }
            }
        }
    }

    private data class SummaryInput(val statistics: String, val count: Long, val first: String?, val last: String?)

    private fun pendingFrames(
        recording: RecordingState,
        selectedSession: CombatLogSession? = _uiState.value.selectedSession,
    ): Long {
        if (selectedSession != null) {
            val total = selectedSession.frameCount ?: 0L
            return (total - (processedFrames[selectedSession.path] ?: 0L)).coerceAtLeast(0)
        }
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
        summaryJob?.cancel()
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
        _uiState.update { it.copy(analyzedEvents = count, players = analyzer.players) }
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
                analyzer.setCharacterProfile(normalizedProfile)
                _uiState.update {
                    it.copy(
                        profile = normalizedProfile,
                        profileError = null,
                        players = analyzer.players
                    )
                }
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
                        players = analyzer.players,
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
        if (replaying) return
        val settings = _uiState.value.narrationSettings
        if (settings.batchFlushStrategy != BatchFlushStrategy.SILENCE_WATCHDOG) return
        batchWatchdog = viewModelScope.launch {
            delay(settings.batchSilenceTimeoutSeconds.seconds)
            flushBatch(cancelWatchdog = false)
        }
    }

    private suspend fun flushBatch(cancelWatchdog: Boolean = true, narrate: Boolean = true) {
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
                    players = analyzer.players,
                )
            }
            QueuedChatBatch(generation, result)
        }
        if (narrate && queuedBatch.generation == chatGeneration) {
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
        const val REPLAY_CHUNK_FRAMES = 2_000L

        // Month and day only: the log timestamp has no year, and only gaps between nearby events matter.
        fun String.toLogTimeMillis(): Long? {
            val match = LOG_TIME.matchEntire(this) ?: return null
            val (month, day, hour, minute, second, millis) = match.destructured
            val seconds = ((month.toLong() * 31 + day.toLong()) * 24 + hour.toLong()) * 3600 +
                    minute.toLong() * 60 + second.toLong()
            return seconds * 1000 + millis.toLong()
        }

        val LOG_TIME = Regex("""(\d{1,2})/(\d{1,2})\s+(\d{1,2}):(\d{2}):(\d{2})\.(\d{3})""")

        fun sessionId(generation: Long) = "combat-chat-$generation"

        fun askSessionId(generation: Long) = "combat-ask-$generation"
    }
}
