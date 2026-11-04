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
import com.cacaosd.wowtelemetry.domain.CharacterProfile

@Composable
@Preview
fun App(
    websocketUrl: String,
    viewModel: TelemetryViewModel,
) {
    val chatHistory = viewModel.chatHistory.collectAsState().value
    val isChatActive = viewModel.isChatActive.collectAsState().value
    val isPhrasingEvent = viewModel.isPhrasingEvent.collectAsState().value
    val phrasingError = viewModel.phrasingError.collectAsState().value
    val activeBatch = viewModel.activeBatch.collectAsState().value
    val activeBatchEventCount = viewModel.activeBatchEventCount.collectAsState().value
    val latestBatch = viewModel.latestBatch.collectAsState().value
    val characterProfile = viewModel.profile.collectAsState().value ?: CharacterProfile()
    val profileError = viewModel.profileError.collectAsState().value
    var isSettingsVisible by remember { mutableStateOf(false) }
    val assistantTurns = chatHistory.filter { it.role == "Assistant" }
    val scrollState = rememberScrollState()

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
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { isSettingsVisible = true }) {
                            Text("Character")
                        }
                        if (isChatActive) {
                            OutlinedButton(onClick = viewModel::stopChat) {
                                Text("Stop chat")
                            }
                        } else {
                            Button(onClick = viewModel::startChat) {
                                Text("New chat")
                            }
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
                                !isChatActive -> "Chat stopped"
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
                                        "${activeBatchEventCount.takeIf { activeBatch != null } ?: batch.events.size} events",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Batch sends at 100 events or 1 second after the latest event.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            if (isChatActive) {
                                "Your narrator will speak here after the next combat batch."
                            } else {
                                "Start a new chat to resume narration."
                            },
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
                Text(
                    "WebSocket: $websocketUrl",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
