package com.cacaosd.wowtelemetry.client.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGoldContainer
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurfaceVariant
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextPrimary
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextSecondary
import com.cacaosd.wowtelemetry.domain.session.CombatLogSession

@Composable
fun SessionsDialog(
    sessions: List<CombatLogSession>,
    selectedPath: String?,
    isProcessing: Boolean,
    onSelectSession: (CombatLogSession) -> Unit,
    onUnselectSession: () -> Unit,
    onBrowse: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved sessions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBrowse != null) {
                        OutlinedButton(onClick = onBrowse, enabled = !isProcessing) {
                            Text("Open file…")
                        }
                    }
                    if (selectedPath != null) {
                        TextButton(onClick = onUnselectSession, enabled = !isProcessing) {
                            Text("Unselect file", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (sessions.isEmpty()) {
                    Text(
                        "No sessions yet. Switch to Collect mode and connect to record one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ChronicleTextSecondary,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(sessions, key = { it.path }) { session ->
                            val isSelected = session.path == selectedPath
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) ChronicleGoldContainer.copy(alpha = 0.3f) else ChronicleSurfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) ChronicleGold else ChronicleBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isProcessing) {
                                        if (isSelected) {
                                            onUnselectSession()
                                        } else {
                                            onSelectSession(session)
                                        }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            session.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = ChronicleTextPrimary,
                                        )
                                        Text(
                                            text = formatSize(session.sizeBytes) +
                                                    if (session.frameCount != null) " · ${session.frameCount} frames" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ChronicleTextSecondary,
                                        )
                                    }
                                    if (isSelected) {
                                        Text(
                                            "✓ Selected",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = ChronicleGold,
                                        )
                                    } else {
                                        OutlinedButton(
                                            onClick = { onSelectSession(session) },
                                            enabled = !isProcessing,
                                        ) {
                                            Text("Select")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${bytes * 10 / (1L shl 30) / 10.0} GB"
    bytes >= 1L shl 20 -> "${bytes * 10 / (1L shl 20) / 10.0} MB"
    bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} KB"
    else -> "$bytes B"
}
