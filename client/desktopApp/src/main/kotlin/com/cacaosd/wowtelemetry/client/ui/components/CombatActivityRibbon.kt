package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cacaosd.wowtelemetry.client.ui.theme.*
import com.cacaosd.wowtelemetry.combatlog.CombatEventBatch

@Composable
fun CombatActivityRibbon(
    isPhrasingEvent: Boolean,
    activeBatch: CombatEventBatch?,
    latestBatch: CombatEventBatch?,
    isConnected: Boolean,
) {
    val batch = activeBatch ?: latestBatch

    Surface(
        modifier = Modifier.fillMaxWidth(),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    Text(
                        text = when {
                            isPhrasingEvent -> "Narrator is composing the chronicle passage…"
                            activeBatch != null -> "Active combat recording (${activeBatch.events.size} events)"
                            latestBatch != null -> "Last encounter batch captured"
                            isConnected -> "Awaiting combat events (daemon listening)"
                            else -> "Daemon offline. Connect to start streaming combat telemetry."
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            isPhrasingEvent -> ChronicleArcane
                            activeBatch != null -> ChronicleGold
                            isConnected -> ChronicleTextSecondary
                            else -> ChronicleWarning
                        },
                    )
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
