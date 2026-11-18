package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleError
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurfaceVariant
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextPrimary
import com.cacaosd.wowtelemetry.domain.session.IngestMode

/** Mode dropdown plus Process and Stop actions. Process is only available in Collect mode. */
@Composable
fun IngestControls(
    ingestMode: IngestMode,
    pendingFrames: Long,
    isProcessing: Boolean,
    onIngestModeChange: (IngestMode) -> Unit,
    onProcess: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeDropdown(ingestMode, onIngestModeChange)
        ActionPill(
            label = "▶ Process",
            enabled = ingestMode == IngestMode.COLLECT && pendingFrames > 0 && !isProcessing,
            accent = ChronicleGold,
            onClick = onProcess,
        )
        ActionPill(
            label = "■ Stop",
            enabled = isProcessing,
            accent = ChronicleError,
            onClick = onStop,
        )
    }
}

@Composable
private fun ModeDropdown(mode: IngestMode, onChange: (IngestMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.clip(CircleShape)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = ChronicleSurfaceVariant,
            border = BorderStroke(1.dp, ChronicleBorder),
            modifier = Modifier.clickable { expanded = true },
        ) {
            Text(
                text = "Ingest: ${mode.label()} ▾",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = ChronicleTextPrimary,
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            IngestMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    enabled: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ChronicleSurfaceVariant,
        border = BorderStroke(1.dp, if (enabled) accent.copy(alpha = 0.6f) else ChronicleBorder),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).clip(CircleShape),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (enabled) accent else ChronicleTextMuted,
            maxLines = 1,
        )
    }
}

private fun IngestMode.label() = when (this) {
    IngestMode.LIVE -> "Live"
    IngestMode.COLLECT -> "Collect"
}
