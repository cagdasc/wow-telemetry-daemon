package com.cacaosd.wowtelemetry.client.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleError
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSuccess
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextPrimary
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleWarning
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState

@Composable
fun ConnectionDialog(
    daemonAddress: String,
    onDaemonAddressChange: (String) -> Unit,
    endpointError: String?,
    connection: DaemonConnectionState,
    canDisconnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val statusColor = when (connection.status) {
        DaemonConnectionState.Status.CONNECTED -> ChronicleSuccess
        DaemonConnectionState.Status.CONNECTING,
        DaemonConnectionState.Status.RECONNECTING -> ChronicleWarning

        DaemonConnectionState.Status.DISCONNECTED -> ChronicleError
    }

    val statusText = when (connection.status) {
        DaemonConnectionState.Status.CONNECTED -> "Connected"
        DaemonConnectionState.Status.CONNECTING -> "Connecting…"
        DaemonConnectionState.Status.RECONNECTING -> "Reconnecting…"
        DaemonConnectionState.Status.DISCONNECTED -> "Disconnected"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text("Daemon Connection", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Connect to the local or remote daemon streaming WoW combat events.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ChronicleTextMuted,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Current Status: ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ChronicleTextMuted,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                        ),
                    )
                }

                OutlinedTextField(
                    value = daemonAddress,
                    onValueChange = onDaemonAddressChange,
                    label = { Text("Daemon IP:port") },
                    placeholder = { Text("127.0.0.1:8765") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                endpointError?.let { error ->
                    Text(
                        text = error,
                        color = ChronicleError,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (canDisconnect) {
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Disconnect")
                        }
                    } else {
                        Button(
                            onClick = onConnect,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ChronicleGold,
                                contentColor = Color(0xFF1E1602)
                            ),
                        ) {
                            Text("Connect", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = ChronicleTextPrimary)
            }
        },
    )
}
