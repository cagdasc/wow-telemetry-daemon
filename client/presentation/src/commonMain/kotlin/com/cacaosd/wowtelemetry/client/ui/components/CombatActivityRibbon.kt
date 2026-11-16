package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleArcane
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleArcaneContainer
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorderSubtle
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGoldContainer
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurface
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextSecondary
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleWarning
import com.cacaosd.wowtelemetry.combatlog.CombatEventBatch

@Composable
fun CombatActivityRibbon(
    isPhrasingEvent: Boolean,
    activeBatch: CombatEventBatch?,
    latestBatch: CombatEventBatch?,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val batch = activeBatch ?: latestBatch

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = when {
            isPhrasingEvent -> ChronicleArcaneContainer
            activeBatch != null -> ChronicleGoldContainer
            else -> ChronicleSurface.copy(alpha = 0.6f)
        },
        border = BorderStroke(
            1.dp,
            when {
                isPhrasingEvent -> ChronicleArcane.copy(alpha = 0.3f)
                activeBatch != null -> ChronicleGold.copy(alpha = 0.3f)
                else -> ChronicleBorderSubtle
            }
        ),
    ) {
        Column {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                val isCompact = maxWidth < 600.dp

                if (isCompact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatusIcon(isPhrasingEvent, activeBatch, isConnected)
                            StatusLabel(isPhrasingEvent, activeBatch, latestBatch, isConnected)
                        }

                        if (batch != null) {
                            Text(
                                text = "${batch.startTimestamp} – ${batch.endTimestamp}  ·  ${batch.events.size} events",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = ChronicleTextMuted,
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            StatusIcon(isPhrasingEvent, activeBatch, isConnected)
                            StatusLabel(isPhrasingEvent, activeBatch, latestBatch, isConnected)
                        }

                        if (batch != null) {
                            Text(
                                text = "${batch.startTimestamp} – ${batch.endTimestamp}  ·  ${batch.events.size} events",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = ChronicleTextMuted,
                            )
                        }
                    }
                }
            }

            if (isPhrasingEvent) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = ChronicleArcane,
                    trackColor = ChronicleArcaneContainer,
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(
    isPhrasingEvent: Boolean,
    activeBatch: CombatEventBatch?,
    isConnected: Boolean,
) {
    Text(
        text = when {
            isPhrasingEvent -> "✨"
            activeBatch != null -> "⚔️"
            isConnected -> "🛡️"
            else -> "⚠️"
        },
        fontSize = 13.sp,
    )
}

@Composable
private fun StatusLabel(
    isPhrasingEvent: Boolean,
    activeBatch: CombatEventBatch?,
    latestBatch: CombatEventBatch?,
    isConnected: Boolean,
) {
    Text(
        text = when {
            isPhrasingEvent -> "Narrator is composing the chronicle passage…"
            activeBatch != null -> "Active combat recording (${activeBatch.events.size} events)"
            latestBatch != null -> "Last encounter batch captured"
            isConnected -> "Awaiting combat events (daemon listening)"
            else -> "Daemon offline. Connect to start streaming."
        },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = when {
            isPhrasingEvent -> ChronicleArcane
            activeBatch != null -> ChronicleGold
            isConnected -> ChronicleTextSecondary
            else -> ChronicleWarning
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
