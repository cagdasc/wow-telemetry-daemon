package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleSurfaceElevated
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextMuted
import com.cacaosd.wowtelemetry.client.ui.theme.ChronicleTextPrimary
import com.cacaosd.wowtelemetry.client.ui.theme.wowClassColor
import com.cacaosd.wowtelemetry.combatlog.CombatPlayer

@Composable
fun PlayerRosterBar(
    players: List<CombatPlayer>,
    localPlayerName: String? = null,
    modifier: Modifier = Modifier,
) {
    if (players.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "PARTY & RAID PLAYERS (${players.size})",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = ChronicleTextMuted,
                    letterSpacing = 1.sp,
                    fontSize = 10.sp,
                ),
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(players, key = { it.guid }) { player ->
                val isLocalPlayer = localPlayerName?.isNotBlank() == true &&
                        player.name.equals(localPlayerName.trim(), ignoreCase = true)

                PlayerBox(
                    player = player,
                    isLocalPlayer = isLocalPlayer,
                )
            }
        }
    }
}

@Composable
fun PlayerBox(
    player: CombatPlayer,
    isLocalPlayer: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val classColor = wowClassColor(player.className)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = ChronicleSurfaceElevated,
        border = BorderStroke(1.dp, classColor.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(classColor)
                )

                Text(
                    text = player.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = ChronicleTextPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (isLocalPlayer) {
                    Text(
                        text = "(You)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = ChronicleTextMuted,
                            fontSize = 10.sp,
                        ),
                    )
                }
            }

            val specAndClass = buildString {
                if (!player.specName.isNullOrBlank()) {
                    append(player.specName)
                    append(" ")
                }
                append(player.className ?: "Player")
            }

            Text(
                text = specAndClass,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = classColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
