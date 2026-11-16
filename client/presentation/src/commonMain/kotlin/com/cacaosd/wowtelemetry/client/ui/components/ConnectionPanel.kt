package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleError
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSuccess
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurfaceVariant

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
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ChronicleSurfaceVariant,
        border = BorderStroke(1.dp, ChronicleBorder),
    ) {
        BoxWithConstraints(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val isCompact = maxWidth < 600.dp

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isCompact) {
                    // Mobile 2-row layout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = daemonAddress,
                            onValueChange = onDaemonAddressChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("Daemon IP:port") },
                            placeholder = { Text("127.0.0.1:8765") },
                            singleLine = true,
                        )

                        ConnectButton(
                            canDisconnect = canDisconnect,
                            onDisconnect = onDisconnect,
                            onConnect = onConnect,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onClearHistory,
                            enabled = hasHistory,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Clear View")
                        }

                        OutlinedButton(
                            onClick = onOpenClearDaemonDialog,
                            enabled = isConnected && !isClearingDaemonBacklog,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (isClearingDaemonBacklog) "Flushing…" else "Clear Daemon Log")
                        }
                    }
                } else {
                    // Desktop single-row layout
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

                        ConnectButton(
                            canDisconnect = canDisconnect,
                            onDisconnect = onDisconnect,
                            onConnect = onConnect,
                        )

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
}

@Composable
private fun ConnectButton(
    canDisconnect: Boolean,
    onDisconnect: () -> Unit,
    onConnect: () -> Unit,
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
