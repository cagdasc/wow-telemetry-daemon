package com.cacaosd.wowtelemetry.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.agent.BatchFlushStrategy
import com.cacaosd.wowtelemetry.agent.NarrationSettings
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState
import java.net.URI
import java.net.URISyntaxException

@Composable
@Preview
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
    val assistantTurns = chatHistory.filter { it.role == "Assistant" }
    val scrollState = rememberScrollState()
    val connection = uiState.daemonConnection
    val isConnected = connection.status == DaemonConnectionState.Status.CONNECTED
    val canDisconnect = connection.status != DaemonConnectionState.Status.DISCONNECTED

    LaunchedEffect(assistantTurns.size, isPhrasingEvent, phrasingError) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .safeContentPadding()
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "Combat Chronicle",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Live battle narration",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Ollama · ${uiState.narrationSettings.model}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { isSettingsVisible = true }) {
                            Text("Character")
                        }
                        OutlinedButton(onClick = {
                            isNarrationSettingsVisible = true
                            viewModel.discoverOllamaModels()
                        }) {
                            Text("Settings")
                        }
                    }
                }

                profileError?.let { error ->
                    Text(
                        "Character profile unavailable: $error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "DAEMON CONNECTION",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                when (connection.status) {
                                    DaemonConnectionState.Status.DISCONNECTED -> "Disconnected"
                                    DaemonConnectionState.Status.CONNECTING -> "Connecting"
                                    DaemonConnectionState.Status.CONNECTED -> "Connected"
                                    DaemonConnectionState.Status.RECONNECTING -> "Reconnecting"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isConnected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = daemonAddress,
                                onValueChange = {
                                    daemonAddress = it
                                    endpointError = null
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("Daemon IP:port") },
                                placeholder = { Text("192.168.0.15:8765") },
                                singleLine = true,
                            )
                            if (canDisconnect) {
                                OutlinedButton(onClick = viewModel::disconnectDaemon) {
                                    Text("Disconnect")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val url = parseDaemonAddress(daemonAddress)
                                        if (url == null) {
                                            endpointError = "Enter a valid daemon IP:port."
                                        } else {
                                            endpointError = null
                                            viewModel.connectToDaemon(url)
                                        }
                                    },
                                ) {
                                    Text("Connect")
                                }
                            }
                        }
                        endpointError?.let { error ->
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::clearHistory,
                        enabled = chatHistory.isNotEmpty() || isPhrasingEvent,
                    ) {
                        Text("Clear client history")
                    }
                    OutlinedButton(
                        onClick = { isClearDaemonDialogVisible = true },
                        enabled = isConnected && !uiState.isClearingDaemonBacklog,
                    ) {
                        Text(
                            if (uiState.isClearingDaemonBacklog) "Clearing daemon backlog…"
                            else "Clear daemon backlog"
                        )
                    }
                }

                uiState.daemonBacklogMessage?.let { message ->
                    Text(
                        message,
                        color = if (uiState.daemonBacklogError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                uiState.settingsError?.let { error ->
                    Text(
                        "Narration settings unavailable: $error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            when {
                                isPhrasingEvent -> "Narrator is writing"
                                activeBatch != null -> "Collecting combat events"
                                latestBatch != null -> "Batch sent to narrator"
                                else -> "Waiting for combat"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        val batch = activeBatch ?: latestBatch
                        if (batch != null) {
                            Text(
                                "${batch.startTimestamp} – ${batch.endTimestamp}  ·  " +
                                        "${batch.events.size} events",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Text(
                    "NARRATION",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )

                if (assistantTurns.isEmpty() && !isPhrasingEvent) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(
                            "Your narrator will speak here after the next combat batch.",
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                assistantTurns.forEach { turn ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                buildString {
                                    append("NARRATOR")
                                    turn.batchStartTimestamp?.let {
                                        append("  ·  ")
                                        append(it)
                                        turn.batchEndTimestamp?.takeIf { end -> end != it }?.let { end ->
                                            append(" – ")
                                            append(end)
                                        }
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                turn.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                if (isPhrasingEvent) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "The narrator is composing the next passage…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                phrasingError?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            "Narrator unavailable: $error",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }

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
            AlertDialog(
                onDismissRequest = { isClearDaemonDialogVisible = false },
                title = { Text("Clear daemon backlog?") },
                text = {
                    Text(
                        "The daemon empties WoWCombatLog.txt. " +
                                "New entries continue streaming.",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isClearDaemonDialogVisible = false
                            viewModel.clearDaemonBacklog()
                        },
                    ) {
                        Text("Clear backlog")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { isClearDaemonDialogVisible = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun NarrationSettingsDialog(
    settings: NarrationSettings,
    models: List<String>,
    isDiscoveringModels: Boolean,
    modelError: String?,
    onDismiss: () -> Unit,
    onSave: (NarrationSettings) -> Unit,
    onRefreshModels: () -> Unit,
) {
    var temperature by remember(settings) { mutableFloatStateOf(settings.temperature.toFloat()) }
    var selectedModel by remember(settings.model) { mutableStateOf(settings.model) }
    var isModelMenuExpanded by remember { mutableStateOf(false) }
    var flushStrategy by remember(settings) { mutableStateOf(settings.batchFlushStrategy) }
    var silenceTimeout by remember(settings) {
        mutableFloatStateOf(settings.batchSilenceTimeoutSeconds.toFloat())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Narration settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ollama model", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { isModelMenuExpanded = true },
                        enabled = models.isNotEmpty(),
                    ) {
                        Text(selectedModel)
                    }
                    DropdownMenu(
                        expanded = isModelMenuExpanded,
                        onDismissRequest = { isModelMenuExpanded = false },
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    selectedModel = model
                                    isModelMenuExpanded = false
                                },
                            )
                        }
                    }
                    TextButton(
                        onClick = onRefreshModels,
                        enabled = !isDiscoveringModels,
                    ) {
                        Text(if (isDiscoveringModels) "Searching…" else "Refresh")
                    }
                }
                when {
                    modelError != null -> Text(
                        modelError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    models.isEmpty() && !isDiscoveringModels -> Text(
                        "No Ollama models found. Start Ollama and pull a model.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Temperature: ${(temperature * 10).toInt() / 10f}")
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 19,
                )
                Text("Batch flush (always on LOG_CAUGHT_UP)", style = MaterialTheme.typography.labelLarge)
                listOf(
                    BatchFlushStrategy.SILENCE_WATCHDOG to "Also after silence timeout",
                    BatchFlushStrategy.FLUSH_ON_DISCONNECT to "Also when connection is lost",
                ).forEach { (strategy, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = flushStrategy == strategy,
                            onClick = { flushStrategy = strategy },
                        )
                        Text(label)
                    }
                }
                if (flushStrategy == BatchFlushStrategy.SILENCE_WATCHDOG) {
                    Text("Silence timeout: ${silenceTimeout.toInt()} s")
                    Slider(
                        value = silenceTimeout,
                        onValueChange = { silenceTimeout = it },
                        valueRange = NarrationSettings.MIN_SILENCE_TIMEOUT_SECONDS.toFloat()..
                                NarrationSettings.MAX_SILENCE_TIMEOUT_SECONDS.toFloat(),
                    )
                }
                Text(
                    "Supporting documents: ./knowledge (.md and .txt files)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        NarrationSettings(
                            temperature = temperature.toDouble(),
                            model = selectedModel,
                            batchFlushStrategy = flushStrategy,
                            batchSilenceTimeoutSeconds = silenceTimeout.toInt(),
                        )
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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

@Composable
private fun CharacterSettingsDialog(
    profile: CharacterProfile,
    onDismiss: () -> Unit,
    onSave: (CharacterProfile) -> Unit,
) {
    var characterName by remember(profile) { mutableStateOf(profile.characterName) }
    var characterClass by remember(profile) { mutableStateOf(profile.characterClass) }
    var specialization by remember(profile) { mutableStateOf(profile.specialization) }
    var realm by remember(profile) { mutableStateOf(profile.realm) }
    var region by remember(profile) { mutableStateOf(profile.region) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Character settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    characterName,
                    { characterName = it },
                    label = { Text("Character name") },
                    singleLine = true
                )
                OutlinedTextField(characterClass, { characterClass = it }, label = { Text("Class") }, singleLine = true)
                OutlinedTextField(
                    specialization,
                    { specialization = it },
                    label = { Text("Specialization") },
                    singleLine = true
                )
                OutlinedTextField(realm, { realm = it }, label = { Text("Realm") }, singleLine = true)
                OutlinedTextField(region, { region = it }, label = { Text("Region") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        CharacterProfile(
                            characterName = characterName,
                            characterClass = characterClass,
                            specialization = specialization,
                            realm = realm,
                            region = region,
                        )
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
