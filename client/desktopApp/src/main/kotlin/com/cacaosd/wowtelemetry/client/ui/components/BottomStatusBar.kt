package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.agent.BatchFlushStrategy
import com.cacaosd.wowtelemetry.client.ui.theme.*

@Composable
fun BottomStatusBar(
    model: String,
    temperature: Double,
    strategy: BatchFlushStrategy,
    turnCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ChronicleSurface,
        border = BorderStroke(1.dp, ChronicleBorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
