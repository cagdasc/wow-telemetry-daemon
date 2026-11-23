package com.cacaosd.wowtelemetry.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cacaosd.wowtelemetry.domain.preferences.SupporterFeedingMode

@Composable
fun ActiveAttachmentsChip(
    activeCount: Int,
    totalCount: Int,
    feedingMode: SupporterFeedingMode,
    onFeedingModeChange: (SupporterFeedingMode) -> Unit,
    onNavigateToAttachments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalCount == 0 && activeCount == 0) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Clickable indicator
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onNavigateToAttachments)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "📎", fontSize = 14.sp)
                val statusText = when {
                    activeCount == 0 -> "All $totalCount supporters disabled"
                    activeCount == 1 -> "1 supporter active"
                    else -> "$activeCount supporters active"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activeCount > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Manage →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Mode Selector: Direct vs Subgraph Summary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = feedingMode == SupporterFeedingMode.DIRECT_ATTACHMENT,
                    onClick = { onFeedingModeChange(SupporterFeedingMode.DIRECT_ATTACHMENT) },
                    label = { Text("Raw", fontSize = 11.sp) },
                    shape = RoundedCornerShape(6.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )

                FilterChip(
                    selected = feedingMode == SupporterFeedingMode.SUBGRAPH_SUMMARY,
                    onClick = { onFeedingModeChange(SupporterFeedingMode.SUBGRAPH_SUMMARY) },
                    label = { Text("AI Summary", fontSize = 11.sp) },
                    shape = RoundedCornerShape(6.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }
}
