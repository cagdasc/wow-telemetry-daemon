package com.cacaosd.wowtelemetry.client

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.components.AskComposer
import com.cacaosd.wowtelemetry.client.ui.components.BottomStatusBar
import com.cacaosd.wowtelemetry.client.ui.components.ChronicleFeed
import com.cacaosd.wowtelemetry.client.ui.components.CombatActivityRibbon
import com.cacaosd.wowtelemetry.client.ui.components.PlayerRosterBar
import com.cacaosd.wowtelemetry.client.ui.components.SessionPanel
import com.cacaosd.wowtelemetry.client.ui.components.TopBar
import com.cacaosd.wowtelemetry.client.ui.dialogs.CharacterSettingsDialog
import com.cacaosd.wowtelemetry.client.ui.dialogs.ClearDaemonDialog
import com.cacaosd.wowtelemetry.client.ui.dialogs.ConnectionDialog
import com.cacaosd.wowtelemetry.client.ui.dialogs.NarrationSettingsDialog
import com.cacaosd.wowtelemetry.client.ui.dialogs.SessionsDialog
import com.cacaosd.wowtelemetry.client.ui.theme.DarkChronicleColorScheme
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState
import kotlinx.coroutines.launch

@Composable
fun App(
    initialWebsocketUrl: String,
    viewModel: TelemetryViewModel,
    modifier: Modifier = Modifier,
    pickSessionFile: (suspend () -> String?)? = null,
) {
    val uiState = viewModel.uiState.collectAsState().value
    val chatHistory = uiState.chatHistory
    val isPhrasingEvent = uiState.isPhrasingEvent
    val phrasingError = uiState.phrasingError
    val activeBatch = uiState.activeBatch
    val latestBatch = uiState.latestBatch
    val characterProfile = uiState.profile ?: CharacterProfile()
    val profileError = uiState.profileError

    var isSettingsVisible by remember { mutableStateOf(false) }
    var isNarrationSettingsVisible by remember { mutableStateOf(false) }
    var isClearDaemonDialogVisible by remember { mutableStateOf(false) }
    var daemonAddress by remember(initialWebsocketUrl) {
        mutableStateOf(initialDaemonAddress(initialWebsocketUrl))
    }
    var endpointError by remember { mutableStateOf<String?>(null) }
    var isConnectionDialogVisible by remember { mutableStateOf(false) }
    var isSessionsDialogVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val assistantTurns = chatHistory.filter { it.role == "Assistant" }
    val listState = rememberLazyListState()
    val connection = uiState.daemonConnection
    val isConnected = connection.status == DaemonConnectionState.Status.CONNECTED
    val canDisconnect = connection.status != DaemonConnectionState.Status.DISCONNECTED

    // Auto-scroll to latest chronicle passage when turns arrive or phrasing starts
    LaunchedEffect(assistantTurns.size, isPhrasingEvent) {
        if (assistantTurns.isNotEmpty() || isPhrasingEvent) {
            val target = assistantTurns.size + (if (isPhrasingEvent) 1 else 0)
            if (target > 0) {
                listState.animateScrollToItem(target - 1)
            }
        }
    }

    MaterialTheme(colorScheme = DarkChronicleColorScheme) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Sticky Navigation & Status Bar (Responsive for Mobile & Desktop)
                TopBar(
                    daemonAddress = daemonAddress,
                    connection = connection,
                    characterProfile = characterProfile,
                    onOpenConnectionSettings = { isConnectionDialogVisible = true },
                    onOpenCharacterSettings = { isSettingsVisible = true },
                    onOpenNarrationSettings = {
                        isNarrationSettingsVisible = true
                        viewModel.discoverOllamaModels()
                    },
                    onOpenSessions = {
                        viewModel.refreshSessions()
                        isSessionsDialogVisible = true
                    },
                    ingestMode = uiState.ingestMode,
                    pendingFrames = uiState.pendingFrames,
                    isProcessing = uiState.isProcessingSession,
                    onIngestModeChange = viewModel::setIngestMode,
                    onProcess = viewModel::processCollected,
                    onStop = viewModel::stopProcessing,
                )

                SessionPanel(
                    ingestMode = uiState.ingestMode,
                    recording = uiState.recording,
                    selectedSession = uiState.selectedSession,
                    pendingFrames = uiState.pendingFrames,
                    message = uiState.sessionMessage,
                    messageIsError = uiState.sessionMessageIsError,
                )

                // Profile notice
                profileError?.let { error ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "Character profile notice: $error",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // 3. Live Combat Activity Ribbon
                CombatActivityRibbon(
                    isPhrasingEvent = isPhrasingEvent,
                    activeBatch = activeBatch,
                    latestBatch = latestBatch,
                    isConnected = isConnected,
                )

                if (uiState.players.isNotEmpty()) {
                    PlayerRosterBar(
                        players = uiState.players,
                        localPlayerName = characterProfile.characterName,
                    )
                }

                // 4. Scrollable Chronicle Narration Feed
                ChronicleFeed(
                    assistantTurns = assistantTurns,
                    isPhrasingEvent = isPhrasingEvent,
                    phrasingError = phrasingError,
                    listState = listState,
                    isConnected = isConnected,
                    characterProfile = characterProfile,
                    model = uiState.narrationSettings.model,
                    onOpenCharacterSettings = { isSettingsVisible = true },
                    modifier = Modifier.weight(1f),
                )

                AskComposer(
                    analyzedEvents = uiState.analyzedEvents,
                    isAnswering = uiState.isAnswering,
                    error = uiState.askError,
                    onAsk = viewModel::ask,
                )

                // 5. Pinned Footer Status Bar
                BottomStatusBar(
                    model = uiState.narrationSettings.model,
                    temperature = uiState.narrationSettings.temperature,
                    strategy = uiState.narrationSettings.batchFlushStrategy,
                    turnCount = assistantTurns.size,
                )
            }
        }

        // Modals
        if (isSettingsVisible) {
            CharacterSettingsDialog(
                profile = characterProfile,
                onDismiss = { isSettingsVisible = false },
                onSave = {
                    viewModel.saveCharacterProfile(it)
                    isSettingsVisible = false
                },
            )
        }

        if (isNarrationSettingsVisible) {
            NarrationSettingsDialog(
                settings = uiState.narrationSettings,
                models = uiState.ollamaModels,
                isDiscoveringModels = uiState.isDiscoveringOllamaModels,
                modelError = uiState.ollamaModelError,
                onDismiss = { isNarrationSettingsVisible = false },
                onSave = {
                    viewModel.saveNarrationSettings(it)
                    isNarrationSettingsVisible = false
                },
                onRefreshModels = viewModel::discoverOllamaModels,
            )
        }

        if (isSessionsDialogVisible) {
            SessionsDialog(
                sessions = uiState.sessions,
                selectedPath = uiState.selectedSession?.path ?: uiState.recording.session?.path,
                isProcessing = uiState.isProcessingSession,
                onSelectSession = { session ->
                    isSessionsDialogVisible = false
                    viewModel.selectSessionFile(session.path)
                },
                onUnselectSession = {
                    isSessionsDialogVisible = false
                    viewModel.unselectSessionFile()
                },
                onBrowse = pickSessionFile?.let { pick ->
                    {
                        scope.launch {
                            pick()?.let { path ->
                                isSessionsDialogVisible = false
                                viewModel.selectSessionFile(path)
                            }
                        }
                    }
                },
                onDismiss = { isSessionsDialogVisible = false },
            )
        }

        if (isConnectionDialogVisible) {
            ConnectionDialog(
                daemonAddress = daemonAddress,
                onDaemonAddressChange = {
                    daemonAddress = it
                    endpointError = null
                },
                endpointError = endpointError,
                connection = connection,
                canDisconnect = canDisconnect,
                isConnected = isConnected,
                onConnect = {
                    val url = parseDaemonAddress(daemonAddress)
                    if (url == null) {
                        endpointError = "Enter a valid daemon IP:port (e.g. 127.0.0.1:8765)."
                    } else {
                        endpointError = null
                        viewModel.connectToDaemon(url)
                    }
                },
                onDisconnect = viewModel::disconnectDaemon,
                hasHistory = chatHistory.isNotEmpty() || isPhrasingEvent,
                onClearHistory = viewModel::clearHistory,
                isClearingDaemonBacklog = uiState.isClearingDaemonBacklog,
                onOpenClearDaemonDialog = { isClearDaemonDialogVisible = true },
                daemonBacklogMessage = uiState.daemonBacklogMessage,
                daemonBacklogError = uiState.daemonBacklogError,
                onDismiss = { isConnectionDialogVisible = false },
            )
        }

        if (isClearDaemonDialogVisible) {
            ClearDaemonDialog(
                onDismiss = { isClearDaemonDialogVisible = false },
                onConfirm = {
                    isClearDaemonDialogVisible = false
                    viewModel.clearDaemonBacklog()
                },
            )
        }
    }
}

fun initialDaemonAddress(url: String): String {
    val clean = url.trim().removePrefix("ws://").removePrefix("wss://").substringBefore('/')
    return clean.ifBlank { "127.0.0.1:8765" }
}

fun parseDaemonAddress(address: String): String? {
    val value = address.trim()
    val raw = when {
        value.startsWith("ws://", ignoreCase = true) -> value
        value.startsWith("wss://", ignoreCase = true) -> value
        else -> "ws://$value"
    }
    val scheme = if (raw.startsWith("wss://", ignoreCase = true)) "wss" else "ws"
    val withoutScheme = raw.substringAfter("://")
    val hostPort = withoutScheme.substringBefore('/')
    val parts = hostPort.split(':')
    if (parts.size != 2) return null
    val host = parts[0].trim()
    val port = parts[1].trim().toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1..65535) return null
    return "$scheme://$host:$port"
}
