package com.cacaosd.wowtelemetry.client

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.components.*
import com.cacaosd.wowtelemetry.client.ui.dialogs.*
import com.cacaosd.wowtelemetry.client.ui.theme.DarkChronicleColorScheme
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState
import java.net.URI
import java.net.URISyntaxException

@Composable
fun App(
    initialWebsocketUrl: String,
    viewModel: TelemetryViewModel,
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
    var isConnectionPanelExpanded by remember { mutableStateOf(false) }

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
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Sticky Navigation & Status Bar
                TopBar(
                    daemonAddress = daemonAddress,
                    connection = connection,
                    characterProfile = characterProfile,
                    isConnectionPanelExpanded = isConnectionPanelExpanded,
                    onToggleConnectionPanel = {
                        isConnectionPanelExpanded = !isConnectionPanelExpanded
                    },
                    onOpenCharacterSettings = { isSettingsVisible = true },
                    onOpenNarrationSettings = {
                        isNarrationSettingsVisible = true
                        viewModel.discoverOllamaModels()
                    },
                )

                // 2. Collapsible Connection Drawer
                AnimatedVisibility(
                    visible = isConnectionPanelExpanded || !isConnected,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    ConnectionPanel(
                        daemonAddress = daemonAddress,
                        onDaemonAddressChange = {
                            daemonAddress = it
                            endpointError = null
                        },
                        endpointError = endpointError,
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
                    )
                }

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

private fun initialDaemonAddress(url: String): String =
    try {
        val uri = URI(url)
        val port = if (uri.port >= 0) uri.port else if (uri.scheme == "wss") 443 else 80
        "${uri.host ?: "127.0.0.1"}:$port"
    } catch (_: URISyntaxException) {
        "127.0.0.1:8765"
    }

private fun parseDaemonAddress(address: String): String? {
    val value = address.trim()
    val url = if (value.startsWith("ws://", ignoreCase = true) ||
        value.startsWith("wss://", ignoreCase = true)
    ) {
        value
    } else {
        "ws://$value"
    }
    return try {
        val uri = URI(url)
        if (
            (uri.scheme == "ws" || uri.scheme == "wss") &&
            uri.host != null &&
            uri.port in 1..65535 &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            uri.rawQuery == null &&
            uri.rawFragment == null
        ) {
            uri.toString()
        } else {
            null
        }
    } catch (_: URISyntaxException) {
        null
    }
}
