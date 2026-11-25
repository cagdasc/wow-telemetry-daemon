package com.cacaosd.wowtelemetry.client.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorderSubtle
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurface
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted
import com.cacaosd.wowtelemetry.client.ui.theme.wowClassColor
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.ollama.LocalModel
import com.cacaosd.wowtelemetry.domain.preferences.BatchFlushStrategy
import com.cacaosd.wowtelemetry.domain.preferences.EffortMode
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettings
import com.cacaosd.wowtelemetry.domain.preferences.SupporterFeedingMode

@Composable
fun SettingsScreen(
    characterProfile: CharacterProfile,
    narrationSettings: NarrationSettings,
    ollamaModels: List<LocalModel>,
    runningModels: List<String>,
    isDiscoveringModels: Boolean,
    isPreloadingModel: Boolean = false,
    isOffloadingModel: Boolean = false,
    modelError: String?,
    onSaveCharacterProfile: (CharacterProfile) -> Unit,
    onSaveNarrationSettings: (NarrationSettings) -> Unit,
    onRefreshModels: () -> Unit,
    onPreloadModel: (String) -> Unit = {},
    onOffloadModel: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        val isWide = maxWidth >= 840.dp
        val scrollState = rememberScrollState()

        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CharacterProfileCard(
                    profile = characterProfile,
                    onSave = onSaveCharacterProfile,
                    modifier = Modifier.weight(0.4f),
                )
                NarrationSettingsCard(
                    settings = narrationSettings,
                    models = ollamaModels,
                    runningModels = runningModels,
                    isDiscoveringModels = isDiscoveringModels,
                    isPreloadingModel = isPreloadingModel,
                    isOffloadingModel = isOffloadingModel,
                    modelError = modelError,
                    onSave = onSaveNarrationSettings,
                    onRefreshModels = onRefreshModels,
                    onPreloadModel = onPreloadModel,
                    onOffloadModel = onOffloadModel,
                    modifier = Modifier.weight(0.6f),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CharacterProfileCard(
                    profile = characterProfile,
                    onSave = onSaveCharacterProfile,
                    modifier = Modifier.fillMaxWidth(),
                )
                NarrationSettingsCard(
                    settings = narrationSettings,
                    models = ollamaModels,
                    runningModels = runningModels,
                    isDiscoveringModels = isDiscoveringModels,
                    isPreloadingModel = isPreloadingModel,
                    isOffloadingModel = isOffloadingModel,
                    modelError = modelError,
                    onSave = onSaveNarrationSettings,
                    onRefreshModels = onRefreshModels,
                    onPreloadModel = onPreloadModel,
                    onOffloadModel = onOffloadModel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CharacterProfileCard(
    profile: CharacterProfile,
    onSave: (CharacterProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var characterName by remember(profile) { mutableStateOf(profile.characterName) }
    var characterClass by remember(profile) { mutableStateOf(profile.characterClass) }
    var specialization by remember(profile) { mutableStateOf(profile.specialization) }
    var realm by remember(profile) { mutableStateOf(profile.realm) }
    var region by remember(profile) { mutableStateOf(profile.region) }

    val currentClassColor = wowClassColor(characterClass)

    Surface(
        modifier = modifier,
        color = ChronicleSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ChronicleBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(currentClassColor)
                )
                Text(
                    text = "CHARACTER PROFILE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ChronicleGold,
                )
            }

            Text(
                text = "Personalize the narrator by specifying who you play in battle.",
                style = MaterialTheme.typography.bodySmall,
                color = ChronicleTextMuted,
            )

            OutlinedTextField(
                value = characterName,
                onValueChange = { characterName = it },
                label = { Text("Character Name") },
                placeholder = { Text("e.g. Thrall") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = characterClass,
                    onValueChange = { characterClass = it },
                    label = { Text("Class") },
                    placeholder = { Text("Shaman") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = specialization,
                    onValueChange = { specialization = it },
                    label = { Text("Specialization") },
                    placeholder = { Text("Enhancement") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = realm,
                    onValueChange = { realm = it },
                    label = { Text("Realm") },
                    placeholder = { Text("Tarren Mill") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("Region") },
                    placeholder = { Text("EU") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

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
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChronicleGold,
                    contentColor = Color(0xFF1E1602),
                ),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Save Profile", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NarrationSettingsCard(
    settings: NarrationSettings,
    models: List<LocalModel>,
    runningModels: List<String>,
    isDiscoveringModels: Boolean,
    isPreloadingModel: Boolean,
    isOffloadingModel: Boolean,
    modelError: String?,
    onSave: (NarrationSettings) -> Unit,
    onRefreshModels: () -> Unit,
    onPreloadModel: (String) -> Unit,
    onOffloadModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var temperature by remember(settings) { mutableFloatStateOf(settings.temperature.toFloat()) }
    var selectedModel by remember(settings.model) { mutableStateOf(settings.model) }
    var isModelMenuExpanded by remember { mutableStateOf(false) }
    var selectedSummaryModel by remember(settings.supporterSummaryModel) {
        mutableStateOf(settings.supporterSummaryModel)
    }
    var isSummaryModelMenuExpanded by remember { mutableStateOf(false) }
    var supporterFeedingMode by remember(settings.supporterFeedingMode) {
        mutableStateOf(settings.supporterFeedingMode)
    }
    var flushStrategy by remember(settings) { mutableStateOf(settings.batchFlushStrategy) }
    var silenceTimeout by remember(settings) {
        mutableFloatStateOf(settings.batchSilenceTimeoutSeconds.toFloat())
    }
    var selectedEffortMode by remember(settings) { mutableStateOf(settings.effortMode) }

    Surface(
        modifier = modifier,
        color = ChronicleSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ChronicleBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("⚙️")
                Text(
                    text = "NARRATION & LLM SETTINGS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ChronicleGold,
                )
            }

            // Section 1: Ollama Model
            Text(
                "OLLAMA MODEL",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ChronicleGold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { isModelMenuExpanded = true },
                    enabled = models.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(selectedModel.name.ifBlank { "Select Ollama Model" })
                }
                DropdownMenu(
                    expanded = isModelMenuExpanded,
                    onDismissRequest = { isModelMenuExpanded = false },
                ) {
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.name) },
                            onClick = {
                                selectedModel = model
                                isModelMenuExpanded = false
                                onSave(settings.copy(model = model))
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onPreloadModel(selectedModel.name) },
                    enabled = !isPreloadingModel && selectedModel.name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isPreloadingModel) "Preloading…" else "⚡ Preload (VRAM)")
                }
                OutlinedButton(
                    onClick = { onOffloadModel(selectedModel.name) },
                    enabled = !isOffloadingModel && selectedModel.name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isOffloadingModel) "Offloading…" else "⏹️ Offload (VRAM)")
                }
            }
            val isLoaded = runningModels.any {
                it == selectedModel.name || selectedModel.name.contains(it) || it.contains(selectedModel.name)
            }
            Text(
                text = if (isLoaded) "● Loaded in VRAM" else "○ Not loaded in VRAM",
                style = MaterialTheme.typography.bodySmall,
                color = if (isLoaded) Color(0xFF4CAF50) else ChronicleTextMuted,
            )
            when {
                modelError != null -> Text(
                    modelError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )

                models.isEmpty() && !isDiscoveringModels -> Text(
                    "No Ollama models found. Ensure Ollama is running (`ollama serve`).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Context Length Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "MAXIMUM CONTEXT LENGTH",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ChronicleGold,
                )
                Text(
                    "${selectedModel.contextLength} tokens",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ChronicleGold,
                )
            }
            val maxContext = maxOf(131072L, selectedModel.contextLength).toFloat()
            Slider(
                value = selectedModel.contextLength.toFloat().coerceIn(512f, maxContext),
                onValueChange = {
                    selectedModel = selectedModel.copy(contextLength = it.toLong())
                },
                onValueChangeFinished = {
                    onSave(settings.copy(model = selectedModel))
                },
                valueRange = 512f..maxContext,
            )

            HorizontalDivider(color = ChronicleBorderSubtle)

            Text(
                "SUPPORTER FEEDING MODE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ChronicleGold,
            )
            listOf(
                Triple(
                    SupporterFeedingMode.DIRECT_ATTACHMENT,
                    "Raw attachment",
                    "Send supporter files with the main request.",
                ),
                Triple(
                    SupporterFeedingMode.SUBGRAPH_SUMMARY,
                    "AI summary",
                    "Summarize supporter files first, then send the summary.",
                ),
            ).forEach { (mode, title, description) ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            supporterFeedingMode = mode
                            onSave(settings.copy(supporterFeedingMode = mode))
                        },
                ) {
                    RadioButton(
                        selected = supporterFeedingMode == mode,
                        onClick = {
                            supporterFeedingMode = mode
                            onSave(settings.copy(supporterFeedingMode = mode))
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = ChronicleTextMuted,
                        )
                    }
                }
            }

            HorizontalDivider(color = ChronicleBorderSubtle)

            Text(
                "SUPPORTER SUMMARY MODEL",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ChronicleGold,
            )
            OutlinedButton(
                onClick = { isSummaryModelMenuExpanded = true },
                enabled = models.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selectedSummaryModel.ifBlank { "Same as narration model" })
            }
            DropdownMenu(
                expanded = isSummaryModelMenuExpanded,
                onDismissRequest = { isSummaryModelMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Same as narration model") },
                    onClick = {
                        selectedSummaryModel = ""
                        isSummaryModelMenuExpanded = false
                        onSave(settings.copy(supporterSummaryModel = ""))
                    },
                )
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.name) },
                        onClick = {
                            selectedSummaryModel = model.name
                            isSummaryModelMenuExpanded = false
                            onSave(settings.copy(supporterSummaryModel = model.name))
                        },
                    )
                }
            }
            Text(
                "Used only when supporter files are summarized through a subgraph.",
                style = MaterialTheme.typography.bodySmall,
                color = ChronicleTextMuted,
            )

            HorizontalDivider(color = ChronicleBorderSubtle)

            // Section 2: Analysis Effort / Depth
            Text(
                "ANALYSIS EFFORT / DEPTH",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ChronicleGold,
            )
            EffortMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedEffortMode = mode
                            onSave(settings.copy(effortMode = mode))
                        },
                ) {
                    RadioButton(
                        selected = selectedEffortMode == mode,
                        onClick = {
                            selectedEffortMode = mode
                            onSave(settings.copy(effortMode = mode))
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            mode.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = ChronicleTextMuted,
                        )
                    }
                }
            }

            HorizontalDivider(color = ChronicleBorderSubtle)

            // Section 3: Temperature
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "CREATIVITY (TEMPERATURE)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ChronicleGold,
                )
                Text(
                    "${(temperature * 10).toInt() / 10f}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ChronicleGold,
                )
            }
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                onValueChangeFinished = {
                    onSave(settings.copy(temperature = temperature.toDouble()))
                },
                valueRange = 0f..2f,
                steps = 19,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("0.0 (Factual)", style = MaterialTheme.typography.labelSmall, color = ChronicleTextMuted)
                Text("0.7 (Narrative)", style = MaterialTheme.typography.labelSmall, color = ChronicleTextMuted)
                Text("2.0 (Wild)", style = MaterialTheme.typography.labelSmall, color = ChronicleTextMuted)
            }

            HorizontalDivider(color = ChronicleBorderSubtle)

            // Section 4: Batch Flush
            Text(
                "BATCH DISPATCH STRATEGY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ChronicleGold,
            )
            listOf(
                BatchFlushStrategy.SILENCE_WATCHDOG to "Flush after combat silence timeout",
                BatchFlushStrategy.FLUSH_ON_DISCONNECT to "Flush when daemon connection is lost",
            ).forEach { (strategy, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            flushStrategy = strategy
                            onSave(settings.copy(batchFlushStrategy = strategy))
                        },
                ) {
                    RadioButton(
                        selected = flushStrategy == strategy,
                        onClick = {
                            flushStrategy = strategy
                            onSave(settings.copy(batchFlushStrategy = strategy))
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (flushStrategy == BatchFlushStrategy.SILENCE_WATCHDOG) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Silence timeout", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${silenceTimeout.toInt()} seconds",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = silenceTimeout,
                    onValueChange = { silenceTimeout = it },
                    onValueChangeFinished = {
                        onSave(settings.copy(batchSilenceTimeoutSeconds = silenceTimeout.toInt()))
                    },
                    valueRange = NarrationSettings.MIN_SILENCE_TIMEOUT_SECONDS.toFloat()..
                            NarrationSettings.MAX_SILENCE_TIMEOUT_SECONDS.toFloat(),
                )
            }

            HorizontalDivider(color = ChronicleBorderSubtle)

            Text(
                "Supporting lore & rules: placed in ./knowledge directory",
                style = MaterialTheme.typography.labelSmall,
                color = ChronicleTextMuted,
            )
        }
    }
}
