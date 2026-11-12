package com.cacaosd.wowtelemetry.client.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleError

@Composable
fun ClearDaemonDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear daemon backlog?") },
        text = {
            Text(
                "The daemon empties WoWCombatLog.txt. " +
                        "New entries continue streaming seamlessly.",
            )
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = ChronicleError),
                onClick = onConfirm,
            ) {
                Text("Clear Backlog", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
