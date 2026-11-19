package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleError
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSuccess
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurface
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextSecondary
import com.cacaosd.wowtelemetry.domain.session.CombatLogSession
import com.cacaosd.wowtelemetry.domain.session.IngestMode
import com.cacaosd.wowtelemetry.domain.session.RecordingState

/** One-line session status. Ingest mode and Process/Stop live in the top bar. */
@Composable
fun SessionPanel(
    ingestMode: IngestMode,
    recording: RecordingState,
    pendingFrames: Long,
    message: String?,
    messageIsError: Boolean,
    modifier: Modifier = Modifier,
    selectedSession: CombatLogSession? = null,
) {
    val status = when {
        message != null -> message
        selectedSession != null -> {
            val total = selectedSession.frameCount ?: 0L
            "Session: ${selectedSession.name} · $total frames · $pendingFrames unprocessed"
        }
        ingestMode == IngestMode.COLLECT || recording.session != null ->
            "Session: ${recording.frameCount} frames saved · $pendingFrames unprocessed"

        else -> "Session: none (Live)"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ChronicleSurface,
        border = BorderStroke(1.dp, ChronicleBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    message == null -> ChronicleTextSecondary
                    messageIsError -> ChronicleError
                    else -> ChronicleSuccess
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
