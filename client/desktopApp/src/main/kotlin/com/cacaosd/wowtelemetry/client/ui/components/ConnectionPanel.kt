package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.*

@Composable
fun ConnectionPanel(
    daemonAddress: String,
    onDaemonAddressChange: (String) -> Unit,
    endpointError: String?,
    canDisconnect: Boolean,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    hasHistory: Boolean,
    onClearHistory: () -> Unit,
    isClearingDaemonBacklog: Boolean,
    onOpenClearDaemonDialog: () -> Unit,
    daemonBacklogMessage: String?,
    daemonBacklogError: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ChronicleSurfaceVariant,
        border = BorderStroke(1.dp, ChronicleBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = daemonAddress,
                    onValueChange = onDaemonAddressChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Daemon IP:port") },
                    placeholder = { Text("127.0.0.1:8765") },
                    singleLine = true,
                )

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

                OutlinedButton(
                    onClick = onClearHistory,
                    enabled = hasHistory,
                ) {
                    Text("Clear View")
                }

                OutlinedButton(
                    onClick = onOpenClearDaemonDialog,
                    enabled = isConnected && !isClearingDaemonBacklog,
                ) {
                    Text(if (isClearingDaemonBacklog) "Flushing…" else "Clear Daemon Log")
                }
            }

            endpointError?.let { error ->
                Text(
                    text = error,
                    color = ChronicleError,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            daemonBacklogMessage?.let { message ->
                Text(
                    text = message,
                    color = if (daemonBacklogError) ChronicleError else ChronicleSuccess,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
