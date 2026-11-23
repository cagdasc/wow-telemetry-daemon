package com.cacaosd.wowtelemetry.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cacaosd.wowtelemetry.client.ui.components.BottomStatusBar
import com.cacaosd.wowtelemetry.client.ui.components.SessionPanel
import com.cacaosd.wowtelemetry.client.ui.components.TopBar
import com.cacaosd.wowtelemetry.client.ui.dialogs.ClearDaemonDialog
import com.cacaosd.wowtelemetry.client.ui.dialogs.ConnectionDialog
import com.cacaosd.wowtelemetry.client.ui.dialogs.SessionsDialog
import com.cacaosd.wowtelemetry.client.ui.screens.AttachmentsScreen
import com.cacaosd.wowtelemetry.client.ui.screens.ChatScreen
import com.cacaosd.wowtelemetry.client.ui.screens.SettingsScreen
import com.cacaosd.wowtelemetry.client.ui.theme.DarkChronicleColorScheme
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState
import kotlinx.coroutines.launch

enum class MainAppTab(val title: String) {
    CHAT("Chronicle"),
    ATTACHMENTS("Attachments"),
    SETTINGS("Settings"),
}

@Composable
fun App(
    initialWebsocketUrl: String,
    viewModel: TelemetryViewModel,
    modifier: Modifier = Modifier,
    pickSessionFile: (suspend () -> String?)? = null,
    pickLogFile: (suspend () -> String?)? = null,
) {
    val uiState = viewModel.uiState.collectAsState().value
    val chatHistory = uiState.chatHistory
    val characterProfile = uiState.profile ?: CharacterProfile()
    val profileError = uiState.profileError

    var selectedTab by remember { mutableStateOf(MainAppTab.CHAT) }
    var isClearDaemonDialogVisible by remember { mutableStateOf(false) }
    var daemonAddress by remember(initialWebsocketUrl) {
        mutableStateOf(initialDaemonAddress(initialWebsocketUrl))
    }
    var endpointError by remember { mutableStateOf<String?>(null) }
    var isConnectionDialogVisible by remember { mutableStateOf(false) }
    var isSessionsDialogVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val assistantTurns = chatHistory.filter { it.role == "Assistant" }

    val connection = uiState.daemonConnection
    val isConnected = connection.status == DaemonConnectionState.Status.CONNECTED
    val canDisconnect = connection.status != DaemonConnectionState.Status.DISCONNECTED

    MaterialTheme(colorScheme = DarkChronicleColorScheme) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    item(
                        selected = selectedTab == MainAppTab.CHAT,
                        onClick = { selectedTab = MainAppTab.CHAT },
                        icon = { Text("📜", fontSize = 18.sp) },
                        label = { Text("Chronicle") },
                    )
                    item(
                        selected = selectedTab == MainAppTab.ATTACHMENTS,
                        onClick = { selectedTab = MainAppTab.ATTACHMENTS },
                        icon = {
                            Text("📎", fontSize = 18.sp)
                        },
                        badge = {
                            if (uiState.activeAttachmentCount > 0) {
                                Badge { Text("${uiState.activeAttachmentCount}") }
                            }
                        },
                        label = { Text("Attachments") },
                    )
                    item(
                        selected = selectedTab == MainAppTab.SETTINGS,
                        onClick = {
                            selectedTab = MainAppTab.SETTINGS
                            viewModel.discoverOllamaModels()
                        },
                        icon = { Text("⚙️", fontSize = 18.sp) },
                        label = { Text("Settings") },
                    )
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Sticky TopBar
                    TopBar(
                        daemonAddress = daemonAddress,
                        connection = connection,
                        characterProfile = characterProfile,
                        onOpenConnectionSettings = { isConnectionDialogVisible = true },
                        onNavigateToSettings = {
                            selectedTab = MainAppTab.SETTINGS
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

                    // Session recording panel
                    SessionPanel(
                        ingestMode = uiState.ingestMode,
                        recording = uiState.recording,
                        selectedSession = uiState.selectedSession,
                        pendingFrames = uiState.pendingFrames,
                        message = uiState.sessionMessage,
                        messageIsError = uiState.sessionMessageIsError,
                        isConnected = isConnected,
                        isClearingDaemonBacklog = uiState.isClearingDaemonBacklog,
                        onClearView = viewModel::clearView,
                        onOpenClearDaemonDialog = { isClearDaemonDialogVisible = true },
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

                    // Tab Content Area (preserves LazyListState and active streams)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (selectedTab) {
                            MainAppTab.CHAT -> {
                                ChatScreen(
                                    uiState = uiState,
                                    isConnected = isConnected,
                                    characterProfile = characterProfile,
                                    assistantTurns = assistantTurns,
                                    onOpenCharacterSettings = {
                                        selectedTab = MainAppTab.SETTINGS
                                        viewModel.discoverOllamaModels()
                                    },
                                    onNavigateAttachments = {
                                        selectedTab = MainAppTab.ATTACHMENTS
                                    },
                                    setSupporterFeedingMode = viewModel::setSupporterFeedingMode,
                                    ask = viewModel::ask,
                                )
                            }

                            MainAppTab.ATTACHMENTS -> {
                                AttachmentsScreen(
                                    attachments = uiState.attachments,
                                    onToggleEnabled = viewModel::toggleAttachment,
                                    onDescriptionChange = viewModel::updateAttachmentDescription,
                                    onRemoveAttachment = viewModel::removeAttachment,
                                    onAddAttachment = viewModel::addAttachment,
                                    pickLogFile = pickLogFile,
                                )
                            }

                            MainAppTab.SETTINGS -> {
                                SettingsScreen(
                                    characterProfile = characterProfile,
                                    narrationSettings = uiState.narrationSettings,
                                    ollamaModels = uiState.ollamaModels,
                                    isDiscoveringModels = uiState.isDiscoveringOllamaModels,
                                    modelError = uiState.ollamaModelError,
                                    onSaveCharacterProfile = viewModel::saveCharacterProfile,
                                    onSaveNarrationSettings = viewModel::saveNarrationSettings,
                                    onRefreshModels = viewModel::discoverOllamaModels,
                                )
                            }
                        }
                    }

                    // Pinned Footer Status Bar
                    BottomStatusBar(
                        model = uiState.narrationSettings.model,
                        temperature = uiState.narrationSettings.temperature,
                        strategy = uiState.narrationSettings.batchFlushStrategy,
                        effortMode = uiState.narrationSettings.effortMode,
                        turnCount = assistantTurns.size,
                        contextUsage = uiState.contextUsage,
                    )
                }
            }
        }

        // Modals
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
