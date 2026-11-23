package com.cacaosd.wowtelemetry.client.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorderSubtle
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted
import com.cacaosd.wowtelemetry.domain.preferences.BatchFlushStrategy
import com.cacaosd.wowtelemetry.domain.preferences.EffortMode
import com.cacaosd.wowtelemetry.domain.preferences.NarrationSettings
import com.cacaosd.wowtelemetry.domain.preferences.SupporterFeedingMode

@Composable
fun NarrationSettingsDialog(
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("⚙️")
                Text("Narration & LLM Settings", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
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
                        "No Ollama models found. Ensure Ollama is running (`ollama serve`).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

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
                        "Direct attachment",
                        "Send supporter files with the main request.",
                    ),
                    Triple(
                        SupporterFeedingMode.SUBGRAPH_SUMMARY,
                        "Subgraph summary",
                        "Summarize supporter files first, then send the summary.",
                    ),
                ).forEach { (mode, title, description) ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { supporterFeedingMode = mode },
                    ) {
                        RadioButton(
                            selected = supporterFeedingMode == mode,
                            onClick = { supporterFeedingMode = mode },
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
                        },
                    )
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                selectedSummaryModel = model
                                isSummaryModelMenuExpanded = false
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
                            .clickable { selectedEffortMode = mode },
                    ) {
                        RadioButton(
                            selected = selectedEffortMode == mode,
                            onClick = { selectedEffortMode = mode },
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
                            .clickable { flushStrategy = strategy },
                    ) {
                        RadioButton(
                            selected = flushStrategy == strategy,
                            onClick = { flushStrategy = strategy },
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
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChronicleGold,
                    contentColor = Color(0xFF1E1602)
                ),
                onClick = {
                    onSave(
                        NarrationSettings(
                            temperature = temperature.toDouble(),
                            model = selectedModel,
                            batchFlushStrategy = flushStrategy,
                            batchSilenceTimeoutSeconds = silenceTimeout.toInt(),
                            effortMode = selectedEffortMode,
                            supporterFeedingMode = supporterFeedingMode,
                            supporterSummaryModel = selectedSummaryModel,
                        )
                    )
                },
            ) {
                Text("Save Settings", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
