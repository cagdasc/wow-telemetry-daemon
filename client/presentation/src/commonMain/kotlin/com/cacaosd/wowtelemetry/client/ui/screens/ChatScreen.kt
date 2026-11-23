package com.cacaosd.wowtelemetry.client.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.cacaosd.wowtelemetry.client.CombatChatTurn
import com.cacaosd.wowtelemetry.client.TelemetryUiState
import com.cacaosd.wowtelemetry.client.ui.components.ActiveAttachmentsChip
import com.cacaosd.wowtelemetry.client.ui.components.AskComposer
import com.cacaosd.wowtelemetry.client.ui.components.ChronicleFeed
import com.cacaosd.wowtelemetry.client.ui.components.CombatActivityRibbon
import com.cacaosd.wowtelemetry.client.ui.components.PlayerRosterBar
import com.cacaosd.wowtelemetry.domain.CharacterProfile
import com.cacaosd.wowtelemetry.domain.preferences.SupporterFeedingMode

@Composable
fun ChatScreen(
    uiState: TelemetryUiState,
    isConnected: Boolean,
    characterProfile: CharacterProfile,
    assistantTurns: List<CombatChatTurn>,
    onOpenCharacterSettings: () -> Unit,
    onNavigateAttachments: () -> Unit,
    setSupporterFeedingMode: (SupporterFeedingMode) -> Unit,
    ask: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val isPhrasingEvent = uiState.isPhrasingEvent
    val activeBatch = uiState.activeBatch
    val latestBatch = uiState.latestBatch
    val phrasingError = uiState.phrasingError

    // Auto-scroll to latest chronicle passage when turns arrive or phrasing starts
    LaunchedEffect(assistantTurns.size, isPhrasingEvent) {
        if (assistantTurns.isNotEmpty() || isPhrasingEvent) {
            val target = assistantTurns.size + (if (isPhrasingEvent) 1 else 0)
            if (target > 0) {
                listState.animateScrollToItem(target - 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Combat Activity Ribbon
        CombatActivityRibbon(
            isPhrasingEvent = isPhrasingEvent,
            activeBatch = activeBatch,
            latestBatch = latestBatch,
            isConnected = isConnected,
        )

        if (uiState.players.isNotEmpty()) {
            PlayerRosterBar(
                players = uiState.players,
                localPlayerName = characterProfile.characterName,
            )
        }

        // Chronicle Narration Feed
        ChronicleFeed(
            assistantTurns = assistantTurns,
            isPhrasingEvent = isPhrasingEvent,
            phrasingError = phrasingError,
            listState = listState,
            isConnected = isConnected,
            characterProfile = characterProfile,
            model = uiState.narrationSettings.model,
            onOpenCharacterSettings = onOpenCharacterSettings,
            modifier = Modifier.weight(1f),
        )

        // Compact active-attachment chip above composer
        ActiveAttachmentsChip(
            activeCount = uiState.activeAttachmentCount,
            totalCount = uiState.attachments.size,
            feedingMode = uiState.narrationSettings.supporterFeedingMode,
            onFeedingModeChange = setSupporterFeedingMode,
            onNavigateToAttachments = { onNavigateAttachments() },
        )

        // Ask Composer
        AskComposer(
            analyzedEvents = uiState.analyzedEvents,
            isAnswering = uiState.isAnswering,
            error = uiState.askError,
            onAsk = ask,
        )
    }
}
