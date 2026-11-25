package com.cacaosd.wowtelemetry.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cacaosd.wowtelemetry.agent.CombatEventNarrator
import com.cacaosd.wowtelemetry.agent.strategy.NarrationResult
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
import com.cacaosd.wowtelemetry.domain.LogAttachment
import com.cacaosd.wowtelemetry.domain.LogAttachmentRepository
import com.cacaosd.wowtelemetry.domain.ProcessCombatEventUseCase
import com.cacaosd.wowtelemetry.domain.ollama.LocalModel
import com.cacaosd.wowtelemetry.domain.ollama.OllamaModelRepository
import com.cacaosd.wowtelemetry.domain.preferences.BatchFlushStrategy
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettings
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettingsRepository
import com.cacaosd.wowtelemetry.domain.preferences.SupporterFeedingMode
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

data class ContextUsage(val model: String, val inputTokens: Int?, val contextLength: Int?)

private sealed interface ActiveDataset {
    data class Stream(val recordingPath: String? = null) : ActiveDataset
    data class Session(val path: String) : ActiveDataset
}

private sealed interface ConversationRequest {
    val generation: Long
    val statistics: String

    data class Batch(
        override val generation: Long,
        override val statistics: String,
        val start: String,
        val end: String,
        val eventCount: Int,
    ) : ConversationRequest

    data class Summary(
        override val generation: Long,
        override val statistics: String,
        val count: Long,
        val first: String?,
        val last: String?,
    ) : ConversationRequest

    data class Question(
        override val generation: Long,
        override val statistics: String,
        val question: String,
    ) : ConversationRequest
}

data class TelemetryUiState(
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
    val ollamaModels: List<LocalModel> = emptyList(),
    val runningModels: List<String> = emptyList(),
    val isDiscoveringOllamaModels: Boolean = false,
    val isPreloadingModel: Boolean = false,
    val isOffloadingModel: Boolean = false,
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
    val attachments: List<LogAttachment> = emptyList(),
    val activeAttachmentCount: Int = 0,
    val contextUsage: ContextUsage? = null,
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
    private val logAttachmentRepository: LogAttachmentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TelemetryUiState())
    val uiState: StateFlow<TelemetryUiState> = _uiState.asStateFlow()

    private val batchMutex = Mutex()
    private val batchEvents = mutableListOf<CombatEvent>()
    private var batchStartTimestamp: String? = null
    private var batchEndTimestamp: String? = null
    private var batchChatGeneration = 0L
    private var batchWatchdog: Job? = null

    @Volatile
    private var chatGeneration = 0L
    private var activeResponse: Deferred<NarrationResult>? = null
    private var started = false
    private val batchQueue = Channel<ConversationRequest>(Channel.UNLIMITED)
    private val sessionMutex = Mutex()

    @Volatile
    private var activeDataset: ActiveDataset? = null

    @Volatile
    private var stopRequested = false

    // While replaying stored frames, batches only bound memory; one summary is narrated at the end.
    @Volatile
    private var replaying = false
    private var lastReplayTimeMs: Long? = null
    private val processedFrames = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var lastActiveBatchPublish: TimeMark? = null

    private val batchWorker = viewModelScope.launch(start = CoroutineStart.LAZY) {
        for (request in batchQueue) {
            val generation = request.generation
            if (generation != chatGeneration) continue

            val responseSessionId = sessionId(generation)
            val response = viewModelScope.async(Dispatchers.IO) {
                val profile = _uiState.value.profile
                when (request) {
                    is ConversationRequest.Batch ->
                        combatEventNarrator.respond(responseSessionId, profile, request.statistics)

                    is ConversationRequest.Summary ->
                        combatEventNarrator.summarize(responseSessionId, profile, request.statistics)

                    is ConversationRequest.Question ->
                        combatEventNarrator.answer(responseSessionId, profile, request.statistics, request.question)
                }
            }
            activeResponse = response
            if (request !is ConversationRequest.Question) {
                _uiState.update { it.copy(isPhrasingEvent = true, phrasingError = null) }
            }
            try {
                val result = response.await()
                check(result.text.isNotBlank()) { "Ollama returned an empty response." }
                val usage = ContextUsage(result.model, result.inputTokens, null)
                if (generation == chatGeneration) {
                    _uiState.update { state ->
                        val turns = when (request) {
                            is ConversationRequest.Batch -> listOf(
                                CombatChatTurn(
                                    role = "User",
                                    content = "Combat batch from ${request.start} to ${request.end} with ${request.eventCount} events.",
                                    batchStartTimestamp = request.start,
                                    batchEndTimestamp = request.end,
                                    eventCount = request.eventCount,
                                ),
                                CombatChatTurn(
                                    role = "Assistant",
                                    content = result.text,
                                    batchStartTimestamp = request.start,
                                    batchEndTimestamp = request.end,
                                    eventCount = request.eventCount,
                                ),
                            )

                            is ConversationRequest.Summary -> listOf(
                                CombatChatTurn(
                                    role = "Assistant",
                                    content = result.text,
                                    batchStartTimestamp = request.first,
                                    batchEndTimestamp = request.last,
                                    eventCount = request.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                )
                            )

                            is ConversationRequest.Question -> listOf(
                                CombatChatTurn(role = "Assistant", content = result.text, question = request.question)
                            )
                        }
                        state.copy(
                            chatHistory = state.chatHistory + turns,
                            contextUsage = if (state.narrationSettings.model.name == result.model) {
                                usage
                            } else null,
                        )
                    }
                    viewModelScope.launch(Dispatchers.IO) {
                        val contextLength = try {
                            withTimeoutOrNull(5.seconds) {
                                ollamaModelRepository.runningContextLength(result.model)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            null
                        }
                        _uiState.update { state ->
                            if (generation == chatGeneration && state.contextUsage === usage) {
                                state.copy(contextUsage = usage.copy(contextLength = contextLength))
                            } else state
                        }
                    }
                }
            } catch (error: CancellationException) {
                if (!viewModelScope.isActive) throw error
            } catch (error: Exception) {
                if (generation == chatGeneration) {
                    _uiState.update {
                        val message = error.message ?: "Could not reach the local Ollama service."
                        if (request is ConversationRequest.Question) it.copy(askError = message)
                        else it.copy(phrasingError = message)
                    }
                }
            } finally {
                if (activeResponse === response) activeResponse = null
                if (generation == chatGeneration) {
                    _uiState.update {
                        if (request is ConversationRequest.Question) it.copy(isAnswering = false)
                        else it.copy(isPhrasingEvent = false)
                    }
                }
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
                    val selected = if (recording.session?.path == it.selectedSession?.path) {
                        recording.session ?: it.selectedSession
                    } else {
                        it.selectedSession
                    }
                    it.copy(
                        recording = recording,
                        selectedSession = selected,
                        pendingFrames = pendingFrames(recording, selected),
                    )
                }
            }
        }
        viewModelScope.launch {
            logAttachmentRepository.attachments.collect { attachments ->
                _uiState.update {
                    it.copy(
                        attachments = attachments,
                        activeAttachmentCount = attachments.count { att -> att.isEnabled }
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
                if (error is CancellationException) {
                    ensureActive()
                }
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
                if (error is CancellationException) {
                    ensureActive()
                }
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
                combatEventRepository.setIngestMode(IngestMode.COLLECT)
                stopForDatasetChange(path)
                sessionMutex.withLock {
                    activateSessionDataset(path)
                    val pending = pendingFrames(sessionStore.recording.value, session)
                    _uiState.update {
                        it.copy(
                            selectedSession = session,
                            ingestMode = IngestMode.COLLECT,
                            pendingFrames = pending,
                            sessionMessage = null,
                            sessionMessageIsError = false,
                        )
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
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
            withContext(Dispatchers.Main.immediate) { clearHistory() }
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
                stopForDatasetChange(path)
                sessionMutex.withLock {
                    val recording = sessionStore.recording.value
                    val opened = if (recording.session?.path == path) null else sessionStore.open(path)
                    val total = if (opened != null) opened.frameCount ?: 0L else recording.frameCount
                    // A loaded file is its own dataset, so statistics from other sources must not mix in.
                    activateSessionDataset(path)
                    if (opened != null) {
                        _uiState.update { state -> state.copy(selectedSession = opened) }
                    }
                    processSession(path, total)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
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
        val recording = sessionStore.recording.value
        val path = recording.session?.path ?: return
        try {
            stopForDatasetChange(path)
            sessionMutex.withLock {
                activateSessionDataset(path)
                processSession(path, recording.frameCount)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportSessionError(error)
        }
    }

    private fun needsDatasetReset(path: String): Boolean = when (val dataset = activeDataset) {
        null -> false
        is ActiveDataset.Stream ->
            dataset.recordingPath != path && sessionStore.recording.value.session?.path != path

        is ActiveDataset.Session -> dataset.path != path
    }

    private fun datasetForSession(path: String): ActiveDataset =
        if (activeDataset is ActiveDataset.Stream || sessionStore.recording.value.session?.path == path) {
            ActiveDataset.Stream(path)
        } else {
            ActiveDataset.Session(path)
        }

    private fun stopForDatasetChange(path: String) {
        if (needsDatasetReset(path)) stopRequested = true
    }

    private suspend fun activateSessionDataset(path: String) {
        val needsReset = batchMutex.withLock {
            if (needsDatasetReset(path)) true
            else {
                activeDataset = datasetForSession(path)
                false
            }
        }
        if (needsReset) resetForDataset(path)
    }

    private suspend fun resetForDataset(path: String) {
        val previousSessionId = withContext(Dispatchers.Main.immediate) {
            val previous = sessionId(chatGeneration)
            chatGeneration++
            activeResponse?.cancel()
            previous
        }
        batchMutex.withLock {
            batchWatchdog?.cancel()
            batchWatchdog = null
            batchEvents.clear()
            batchStartTimestamp = null
            batchEndTimestamp = null
            lastActiveBatchPublish = null
            analyzer.reset()
            processedFrames.update { it - path }
            activeDataset = if (sessionStore.recording.value.session?.path == path) {
                ActiveDataset.Stream(path)
            } else {
                ActiveDataset.Session(path)
            }
        }
        _uiState.update {
            it.copy(
                chatHistory = emptyList(),
                activeBatch = null,
                latestBatch = null,
                isPhrasingEvent = false,
                phrasingError = null,
                isAnswering = false,
                askError = null,
                contextUsage = null,
                analyzedEvents = 0,
                players = emptyList(),
            )
        }
        combatEventNarrator.clearChat(previousSessionId)
    }

    private suspend fun processSession(path: String, total: Long) {
        val generation = chatGeneration
        val skip = processedFrames.value[path] ?: 0L
        val count = total - skip
        if (count <= 0) return
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
                processedFrames.update { it + (path to (skip + done)) }
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
            if (!stopped && !stopRequested && !encounterOpen && generation == chatGeneration) summarizeProcessed()
        } finally {
            replaying = false
            _uiState.update { it.copy(isProcessingSession = false) }
        }
    }

    private fun startsNewReplayBatch(event: CombatEvent): Boolean {
        val timeMs = event.timestamp.toLogTimeMillis()
        val previous = lastReplayTimeMs
        lastReplayTimeMs = timeMs ?: previous
        if (event.event is EncounterStartEvent) return true
        if (timeMs == null || previous == null) return false
        val silenceMs = _uiState.value.narrationSettings.batchSilenceTimeoutSeconds * 1000L
        return abs(timeMs - previous) > silenceMs
    }

    // One LLM call for the whole processed dataset; follow-up questions go through ask().
    private suspend fun summarizeProcessed() {
        val generation = chatGeneration
        batchMutex.withLock {
            if (analyzer.eventCount > 0 && generation == chatGeneration) {
                batchQueue.send(
                    ConversationRequest.Summary(
                        generation,
                        analyzer.summaryText(),
                        analyzer.eventCount,
                        analyzer.firstTimestamp,
                        analyzer.lastTimestamp,
                    )
                )
            }
        }
    }

    private fun pendingFrames(
        recording: RecordingState,
        selectedSession: CombatLogSession? = _uiState.value.selectedSession,
    ): Long {
        if (selectedSession != null) {
            val total = if (recording.session?.path == selectedSession.path) {
                recording.frameCount
            } else {
                selectedSession.frameCount ?: 0L
            }
            return (total - (processedFrames.value[selectedSession.path] ?: 0L)).coerceAtLeast(0)
        }
        val path = recording.session?.path ?: return 0
        return (recording.frameCount - (processedFrames.value[path] ?: 0L)).coerceAtLeast(0)
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
                if (error is CancellationException) {
                    ensureActive()
                }
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

    fun clearView() {
        val previousSessionId = sessionId(chatGeneration)
        chatGeneration++
        activeResponse?.cancel()
        stopRequested = true
        _uiState.update {
            it.copy(
                chatHistory = emptyList(),
                phrasingError = null,
                isPhrasingEvent = false,
                isAnswering = false,
                askError = null,
                contextUsage = null,
                activeBatch = null,
                latestBatch = null,
                analyzedEvents = 0,
                players = emptyList(),
                selectedSession = null,
                ingestMode = IngestMode.LIVE,
                pendingFrames = 0,
                sessionMessage = null,
                sessionMessageIsError = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            combatEventRepository.setIngestMode(IngestMode.LIVE)
            sessionMutex.withLock {
                batchMutex.withLock {
                    batchWatchdog?.cancel()
                    batchWatchdog = null
                    batchEvents.clear()
                    batchStartTimestamp = null
                    batchEndTimestamp = null
                    lastActiveBatchPublish = null
                    processedFrames.value = emptyMap()
                    analyzer.reset()
                    activeDataset = null
                }
            }
            publishAnalyzedEvents()
            combatEventNarrator.clearChat(previousSessionId)
        }
    }

    fun clearHistory() {
        clearView()
    }

    /** Answers from deterministic statistics; the LLM never sees raw log rows. */
    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _uiState.value.analyzedEvents == 0L ||
            _uiState.value.isAnswering || _uiState.value.isProcessingSession
        ) return
        val generation = chatGeneration
        _uiState.update { it.copy(isAnswering = true, askError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            batchMutex.withLock {
                if (generation == chatGeneration) {
                    batchQueue.send(ConversationRequest.Question(generation, analyzer.summaryText(), text))
                }
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
                val reloaded = narrationSettingsRepository.load()
                _uiState.update {
                    it.copy(
                        narrationSettings = reloaded,
                        settingsError = null,
                        contextUsage = it.contextUsage?.takeIf { usage -> usage.model == reloaded.model.name },
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    ensureActive()
                }
                _uiState.update {
                    it.copy(settingsError = error.message ?: "Could not save narration settings.")
                }
            }
        }
    }

    fun setSupporterFeedingMode(mode: SupporterFeedingMode) {
        val current = _uiState.value.narrationSettings
        if (current.supporterFeedingMode == mode) return
        saveNarrationSettings(current.copy(supporterFeedingMode = mode))
    }

    fun refreshRunningModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val running = ollamaModelRepository.listRunningModels()
                _uiState.update { it.copy(runningModels = running) }
            } catch (error: Exception) {
                if (error is CancellationException) ensureActive()
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
                val settings = runCatching { narrationSettingsRepository.load() }.getOrNull()
                val models = ollamaModelRepository.discoverModels()
                val running = runCatching { ollamaModelRepository.listRunningModels() }.getOrDefault(emptyList())
                _uiState.update {
                    it.copy(
                        narrationSettings = settings ?: it.narrationSettings,
                        ollamaModels = models,
                        runningModels = running,
                        isDiscoveringOllamaModels = false,
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    ensureActive()
                }
                _uiState.update {
                    it.copy(
                        isDiscoveringOllamaModels = false,
                        ollamaModelError = error.message ?: "Could not discover Ollama models.",
                    )
                }
            }
        }
    }

    fun preloadModel(modelName: String = _uiState.value.narrationSettings.model.name) {
        if (_uiState.value.isPreloadingModel) return
        _uiState.update { it.copy(isPreloadingModel = true, ollamaModelError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ollamaModelRepository.preloadModel(modelName)
                val running = runCatching { ollamaModelRepository.listRunningModels() }.getOrDefault(emptyList())
                _uiState.update { it.copy(isPreloadingModel = false, runningModels = running) }
            } catch (error: Exception) {
                if (error is CancellationException) ensureActive()
                _uiState.update {
                    it.copy(
                        isPreloadingModel = false,
                        ollamaModelError = error.message ?: "Failed to preload model.",
                    )
                }
            }
        }
    }

    fun offloadModel(modelName: String = _uiState.value.narrationSettings.model.name) {
        if (_uiState.value.isOffloadingModel) return
        _uiState.update { it.copy(isOffloadingModel = true, ollamaModelError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ollamaModelRepository.offloadModel(modelName)
                val running = runCatching { ollamaModelRepository.listRunningModels() }.getOrDefault(emptyList())
                _uiState.update { it.copy(isOffloadingModel = false, runningModels = running) }
            } catch (error: Exception) {
                if (error is CancellationException) ensureActive()
                _uiState.update {
                    it.copy(
                        isOffloadingModel = false,
                        ollamaModelError = error.message ?: "Failed to offload model.",
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
                if (error is CancellationException) {
                    ensureActive()
                }
                _uiState.update {
                    it.copy(
                        profileError = error.message ?: "Could not save the character profile."
                    )
                }
            }
        }
    }

    fun addAttachment(pathOrUri: String, displayName: String? = null, description: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logAttachmentRepository.addAttachment(pathOrUri, displayName, description)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }

    fun removeAttachment(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            logAttachmentRepository.removeAttachment(id)
        }
    }

    fun updateAttachmentDescription(id: String, description: String) {
        viewModelScope.launch(Dispatchers.IO) {
            logAttachmentRepository.updateDescription(id, description)
        }
    }

    fun toggleAttachment(id: String, isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            logAttachmentRepository.setEnabled(id, isEnabled)
        }
    }

    private suspend fun collectBatchEvent(event: CombatEvent) {
        batchMutex.withLock {
            if (activeDataset == null) activeDataset = ActiveDataset.Stream()
            armWatchdog()
            if (batchEvents.isEmpty()) {
                batchStartTimestamp = event.timestamp
                batchChatGeneration = chatGeneration
                _uiState.update {
                    it.copy(
                        activeBatch = CombatEventBatch(
                            startTimestamp = event.timestamp,
                            endTimestamp = event.timestamp,
                            events = emptyList(),
                        ),
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
        batchMutex.withLock {
            if (cancelWatchdog) {
                batchWatchdog?.cancel()
                batchWatchdog = null
            }
            if (batchEvents.isEmpty()) return
            val generation = batchChatGeneration
            val result = CombatEventBatch(
                startTimestamp = batchStartTimestamp!!,
                endTimestamp = batchEndTimestamp!!,
                events = batchEvents.toList(),
            )
            batchEvents.clear()
            lastActiveBatchPublish = null
            batchStartTimestamp = null
            batchEndTimestamp = null
            _uiState.update {
                it.copy(
                    activeBatch = null,
                    latestBatch = result,
                    phrasingError = null,
                    analyzedEvents = analyzer.eventCount,
                    players = analyzer.players,
                )
            }
            if (narrate && generation == chatGeneration) {
                batchQueue.send(
                    ConversationRequest.Batch(
                        generation, analyzer.summaryText(), result.startTimestamp,
                        result.endTimestamp, result.events.size,
                    )
                )
            }
        }
    }

    @Suppress("EmptySuperCall")
    override fun onCleared() {
        batchWatchdog?.cancel()
        activeResponse?.cancel()
        batchQueue.close()
        sessionStore.finishRecording()
        super.onCleared()
    }

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

    }
}
