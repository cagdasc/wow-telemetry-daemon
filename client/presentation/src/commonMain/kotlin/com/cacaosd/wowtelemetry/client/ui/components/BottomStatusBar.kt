package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorderSubtle
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurface
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted
import com.cacaosd.wowtelemetry.domain.preferences.BatchFlushStrategy

@Composable
fun BottomStatusBar(
    model: String,
    temperature: Double,
    strategy: BatchFlushStrategy,
    turnCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ChronicleSurface,
        border = BorderStroke(1.dp, ChronicleBorderSubtle),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 7.dp)
        ) {
            val isCompact = maxWidth < 600.dp

            if (isCompact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Ollama: $model · ${(temperature * 10).toInt() / 10f}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ChronicleTextMuted,
                    )
                    Text(
                        text = "$turnCount entries",
                        style = MaterialTheme.typography.labelSmall,
                        color = ChronicleTextMuted,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Ollama: $model",
                            style = MaterialTheme.typography.labelSmall,
                            color = ChronicleTextMuted,
                        )
                        Text("·", style = MaterialTheme.typography.labelSmall, color = ChronicleBorder)
                        Text(
                            text = "Temp: ${(temperature * 10).toInt() / 10f}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ChronicleTextMuted,
                        )
                        Text("·", style = MaterialTheme.typography.labelSmall, color = ChronicleBorder)
                        Text(
                            text = when (strategy) {
                                BatchFlushStrategy.SILENCE_WATCHDOG -> "Batch: Silence Watchdog"
                                BatchFlushStrategy.FLUSH_ON_DISCONNECT -> "Batch: Disconnect Flush"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = ChronicleTextMuted,
                        )
                    }

                    Text(
                        text = "$turnCount chronicle entries",
                        style = MaterialTheme.typography.labelSmall,
                        color = ChronicleTextMuted,
                    )
                }
            }
        }
    }
}
