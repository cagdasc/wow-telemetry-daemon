package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleError
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSuccess
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurface
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextSecondary
import com.cacaosd.wowtelemetry.domain.session.IngestMode
import com.cacaosd.wowtelemetry.domain.session.RecordingState

@Composable
fun SessionPanel(
    ingestMode: IngestMode,
    recording: RecordingState,
    pendingFrames: Long,
    isProcessing: Boolean,
    message: String?,
    messageIsError: Boolean,
    onIngestModeChange: (IngestMode) -> Unit,
    onProcessNow: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ChronicleSurface,
        border = BorderStroke(1.dp, ChronicleBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Ingest", style = MaterialTheme.typography.labelMedium, color = ChronicleTextSecondary)
                ModeButton("Live", ingestMode == IngestMode.LIVE) { onIngestModeChange(IngestMode.LIVE) }
                ModeButton("Collect", ingestMode == IngestMode.COLLECT) { onIngestModeChange(IngestMode.COLLECT) }
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onOpenSessions) { Text("Sessions…") }
            }

            if (ingestMode == IngestMode.COLLECT || recording.session != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${recording.frameCount} frames saved · $pendingFrames unprocessed",
                        style = MaterialTheme.typography.bodySmall,
                        color = ChronicleTextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = onProcessNow,
                        enabled = pendingFrames > 0 && !isProcessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ChronicleGold,
                            contentColor = Color(0xFF1E1602),
                        ),
                    ) {
                        Text(if (isProcessing) "Processing…" else "Process now")
                    }
                }
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (messageIsError) ChronicleError else ChronicleSuccess,
                )
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = ChronicleGold,
                contentColor = Color(0xFF1E1602),
            ),
        ) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
