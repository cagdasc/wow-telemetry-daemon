package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cacaosd.wowtelemetry.client.CombatChatTurn
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleArcane
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorderSubtle
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleError
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGoldContainer
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSuccess
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurface
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurfaceElevated
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurfaceVariant
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextPrimary
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextSecondary
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleWarning
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChronicleFeed(
    assistantTurns: List<CombatChatTurn>,
    isPhrasingEvent: Boolean,
    phrasingError: String?,
    listState: LazyListState,
    isConnected: Boolean,
    characterProfile: CharacterProfile,
    model: String,
    onOpenCharacterSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        if (assistantTurns.isEmpty() && !isPhrasingEvent) {
            EmptyChronicleState(
                isConnected = isConnected,
                characterProfile = characterProfile,
                model = model,
                onOpenCharacterSettings = onOpenCharacterSettings,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(assistantTurns) { index, turn ->
                    ChronicleCard(
                        entryNumber = index + 1,
                        turn = turn,
                    )
                }

                if (isPhrasingEvent) {
                    item {
                        PhrasingLoadingCard()
                    }
                }

                if (phrasingError != null) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ChronicleError.copy(alpha = 0.5f)),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "NARRATOR OFFLINE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ChronicleError,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    phrasingError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChronicleCard(
    entryNumber: Int,
    turn: CombatChatTurn,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ChronicleSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ChronicleBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Card Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    // Entry label
                    Surface(
                        color = ChronicleGoldContainer,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, ChronicleGold.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = if (turn.question != null) "ANSWER" else "ENTRY #$entryNumber",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ChronicleGold,
                            letterSpacing = 1.sp,
                        )
                    }

                    // Processed Event Count Pill
                    turn.eventCount?.let { count ->
                        Surface(
                            color = ChronicleSurfaceElevated,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, ChronicleBorderSubtle),
                        ) {
                            Text(
                                text = "⚡ $count events",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = ChronicleArcane,
                            )
                        }
                    }

                    // Timestamp badge
                    if (turn.batchStartTimestamp != null) {
                        Text(
                            text = buildString {
                                append(turn.batchStartTimestamp)
                                if (turn.batchEndTimestamp != null && turn.batchEndTimestamp != turn.batchStartTimestamp) {
                                    append(" – ")
                                    append(turn.batchEndTimestamp)
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = ChronicleTextMuted,
                        )
                    }
                }

                // Copy button
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(turn.content))
                        isCopied = true
                        coroutineScope.launch {
                            delay(2000)
                            isCopied = false
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (isCopied) "✓ Copied" else "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCopied) ChronicleSuccess else ChronicleTextMuted,
                    )
                }
            }

            turn.question?.let { question ->
                Text(
                    text = "You asked: $question",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = ChronicleArcane,
                )
            }

            // Narration Body
            Text(
                text = turn.content,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 24.sp,
                ),
                color = ChronicleTextPrimary,
            )
        }
    }
}

@Composable
fun PhrasingLoadingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ChronicleSurfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ChronicleArcane.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = ChronicleArcane,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "The narrator is weaving the next passage…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = ChronicleArcane,
                )
                Text(
                    text = "Distilling combat events with your character profile and local LLM",
                    style = MaterialTheme.typography.labelSmall,
                    color = ChronicleTextMuted,
                )
            }
        }
    }
}

@Composable
fun EmptyChronicleState(
    isConnected: Boolean,
    characterProfile: CharacterProfile,
    model: String,
    onOpenCharacterSettings: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = ChronicleSurfaceVariant.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, ChronicleBorder),
            modifier = Modifier.widthIn(max = 520.dp).padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = ChronicleSurfaceElevated,
                    border = BorderStroke(1.dp, ChronicleGold.copy(alpha = 0.3f)),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("📜", fontSize = 22.sp)
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "The Chronicle is Silent",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = ChronicleTextPrimary,
                    )
                    Text(
                        "Engage in combat in World of Warcraft or stream a log file. Your AI companion will narrate each battle encounter in real time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ChronicleTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }

                HorizontalDivider(color = ChronicleBorderSubtle)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusRow(
                        label = "Combat Daemon",
                        value = if (isConnected) "Connected" else "Disconnected",
                        isSuccess = isConnected,
                    )
                    StatusRow(
                        label = "Narration Model",
                        value = "Ollama · $model",
                        isSuccess = true,
                    )
                    StatusRow(
                        label = "Character",
                        value = if (characterProfile.characterName.isNotBlank()) {
                            "${characterProfile.characterName} (${characterProfile.characterClass})"
                        } else {
                            "Not configured"
                        },
                        isSuccess = characterProfile.characterName.isNotBlank(),
                        onClick = if (characterProfile.characterName.isBlank()) onOpenCharacterSettings else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    isSuccess: Boolean,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ChronicleTextMuted,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isSuccess) ChronicleSuccess else ChronicleWarning)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (onClick != null) ChronicleGold else ChronicleTextPrimary,
            )
        }
    }
}
