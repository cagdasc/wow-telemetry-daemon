package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorder
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleBorderSubtle
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleError
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGold
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleGoldContainer
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSuccess
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurface
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurfaceVariant
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextPrimary
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextSecondary
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleWarning
import com.cacaosd.wowtelemetry.client.ui.theme.wowClassColor
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.DaemonConnectionState
import com.cacaosd.wowtelemetry.domain.session.IngestMode

@Composable
fun TopBar(
    daemonAddress: String,
    connection: DaemonConnectionState,
    characterProfile: CharacterProfile,
    isConnectionPanelExpanded: Boolean,
    onToggleConnectionPanel: () -> Unit,
    onOpenCharacterSettings: () -> Unit,
    onOpenNarrationSettings: () -> Unit,
    ingestMode: IngestMode,
    pendingFrames: Long,
    isProcessing: Boolean,
    onIngestModeChange: (IngestMode) -> Unit,
    onProcess: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth(),
        color = ChronicleSurface,
        border = BorderStroke(1.dp, ChronicleBorderSubtle),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            val isCompact = maxWidth < 600.dp

            if (isCompact) {
                // Adaptive Mobile Layout
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        BrandHeader()
                        IconButton(
                            onClick = onOpenNarrationSettings,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Text(text = "⚙️", fontSize = 16.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CharacterPill(
                            characterProfile = characterProfile,
                            onOpenCharacterSettings = onOpenCharacterSettings,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        ConnectionPill(
                            isConnected = isConnected,
                            daemonAddress = daemonAddress,
                            connection = connection,
                            statusColor = statusColor,
                            isConnectionPanelExpanded = isConnectionPanelExpanded,
                            onToggleConnectionPanel = onToggleConnectionPanel,
                        )
                    }

                    IngestControls(ingestMode, pendingFrames, isProcessing, onIngestModeChange, onProcess, onStop)
                }
            } else {
                // Desktop / Tablet Wide Layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BrandHeader()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IngestControls(ingestMode, pendingFrames, isProcessing, onIngestModeChange, onProcess, onStop)
                        CharacterPill(
                            characterProfile = characterProfile,
                            onOpenCharacterSettings = onOpenCharacterSettings,
                        )
                        ConnectionPill(
                            isConnected = isConnected,
                            daemonAddress = daemonAddress,
                            connection = connection,
                            statusColor = statusColor,
                            isConnectionPanelExpanded = isConnectionPanelExpanded,
                            onToggleConnectionPanel = onToggleConnectionPanel,
                        )
                        IconButton(onClick = onOpenNarrationSettings) {
                            Text(text = "⚙️", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = ChronicleGoldContainer,
            border = BorderStroke(1.dp, ChronicleGold.copy(alpha = 0.4f)),
        ) {
            Text(
                text = "⚔️",
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                fontSize = 14.sp,
            )
        }
        Column {
            Text(
                text = "COMBAT CHRONICLE",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
                color = ChronicleGold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Live battle telemetry narration",
                style = MaterialTheme.typography.labelSmall,
                color = ChronicleTextMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CharacterPill(
    characterProfile: CharacterProfile,
    onOpenCharacterSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasCharacter = characterProfile.characterName.isNotBlank()
    val classColor = wowClassColor(characterProfile.characterClass)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ChronicleSurfaceVariant,
        border = BorderStroke(1.dp, ChronicleBorder),
        modifier = modifier.clickable(onClick = onOpenCharacterSettings),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConnectionPill(
    isConnected: Boolean,
    daemonAddress: String,
    connection: DaemonConnectionState,
    statusColor: Color,
    isConnectionPanelExpanded: Boolean,
    onToggleConnectionPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ChronicleSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (isConnectionPanelExpanded) ChronicleGold.copy(alpha = 0.5f) else ChronicleBorder
        ),
        modifier = modifier.clickable(onClick = onToggleConnectionPanel),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                maxLines = 1,
            )
            Text(
                text = if (isConnectionPanelExpanded) "▴" else "▾",
                style = MaterialTheme.typography.labelSmall,
                color = ChronicleTextMuted,
            )
        }
    }
}
