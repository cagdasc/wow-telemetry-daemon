package com.cacaosd.wowtelemetry.client.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextSecondary
import com.cacaosd.wowtelemetry.domain.session.CombatLogSession

@Composable
fun SessionsDialog(
    sessions: List<CombatLogSession>,
    activePath: String?,
    isProcessing: Boolean,
    onProcess: (CombatLogSession) -> Unit,
    onBrowse: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved sessions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBrowse != null) {
                    OutlinedButton(onClick = onBrowse, enabled = !isProcessing) {
                        Text("Open file…")
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(session.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = formatSize(session.sizeBytes) +
                                                if (session.path == activePath) " · recording" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ChronicleTextSecondary,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { onProcess(session) },
                                    enabled = !isProcessing,
                                ) { Text("Process") }
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
