package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cacaosd.wowtelemetry.client.ui.theme.*
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState

@Composable
fun TopBar(
    daemonAddress: String,
    connection: DaemonConnectionState,
    characterProfile: CharacterProfile,
    isConnectionPanelExpanded: Boolean,
    onToggleConnectionPanel: () -> Unit,
    onOpenCharacterSettings: () -> Unit,
    onOpenNarrationSettings: () -> Unit,
) {
    val isConnected = connection.status == DaemonConnectionState.Status.CONNECTED
    val statusColor by animateColorAsState(
        when (connection.status) {
            DaemonConnectionState.Status.CONNECTED -> ChronicleSuccess
            DaemonConnectionState.Status.CONNECTING,
            DaemonConnectionState.Status.RECONNECTING -> ChronicleWarning
            DaemonConnectionState.Status.DISCONNECTED -> ChronicleError
        }
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ChronicleSurface,
        border = BorderStroke(1.dp, ChronicleBorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Brand title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ChronicleGoldContainer,
                    border = BorderStroke(1.dp, ChronicleGold.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "⚔️",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 15.sp,
                    )
                }
                Column {
                    Text(
                        text = "COMBAT CHRONICLE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = ChronicleGold,
                    )
                    Text(
                        text = "Live battle telemetry narration",
                        style = MaterialTheme.typography.labelSmall,
                        color = ChronicleTextMuted,
                    )
                }
            }

            // Right side items: Character pill + Connection pill + Settings button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Character Pill
                val hasCharacter = characterProfile.characterName.isNotBlank()
                val classColor = wowClassColor(characterProfile.characterClass)

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ChronicleSurfaceVariant,
                    border = BorderStroke(1.dp, ChronicleBorder),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onOpenCharacterSettings),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (hasCharacter) classColor else ChronicleTextMuted)
                        )
                        Text(
                            text = if (hasCharacter) {
                                buildString {
                                    append(characterProfile.characterName)
                                    if (characterProfile.specialization.isNotBlank()) {
                                        append(" · ")
                                        append(characterProfile.specialization)
                                    } else if (characterProfile.characterClass.isNotBlank()) {
                                        append(" · ")
                                        append(characterProfile.characterClass)
                                    }
                                }
                            } else {
                                "+ Set Character"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (hasCharacter) ChronicleTextPrimary else ChronicleTextSecondary,
                        )
                    }
                }

                // Connection Pill (toggles connection panel)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ChronicleSurfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        if (isConnectionPanelExpanded) ChronicleGold.copy(alpha = 0.5f) else ChronicleBorder
                    ),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onToggleConnectionPanel),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = if (isConnected) daemonAddress else when (connection.status) {
                                DaemonConnectionState.Status.DISCONNECTED -> "Offline"
                                DaemonConnectionState.Status.CONNECTING -> "Connecting…"
                                DaemonConnectionState.Status.RECONNECTING -> "Reconnecting…"
                                else -> "Connected"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isConnected) ChronicleTextPrimary else statusColor,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = if (isConnectionPanelExpanded) "▴" else "▾",
                            style = MaterialTheme.typography.labelSmall,
                            color = ChronicleTextMuted,
                        )
                    }
                }

                // Settings Button
                IconButton(onClick = onOpenNarrationSettings) {
                    Text(
                        text = "⚙️",
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}
