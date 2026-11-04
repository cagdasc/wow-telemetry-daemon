package com.cacaosd.wowtelemetry.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App(
    websocketUrl: String,
    viewModel: TelemetryViewModel,
) {
    val latestEvent = viewModel.latestEventSummary.collectAsState().value
    val phrasedEvent = viewModel.phrasedEventSummary.collectAsState().value
    val isPhrasingEvent = viewModel.isPhrasingEvent.collectAsState().value
    val phrasingError = viewModel.phrasingError.collectAsState().value
    val activeBatch = viewModel.activeBatch.collectAsState().value
    val activeBatchEventCount = viewModel.activeBatchEventCount.collectAsState().value
    val latestBatch = viewModel.latestBatch.collectAsState().value
    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("WoW Telemetry Client")
            Text("WebSocket endpoint: $websocketUrl")
            Text(
                latestEvent?.let { "Latest event: $it" } ?: "Waiting for combat events.",
                modifier = Modifier.fillMaxWidth(),
            )
            activeBatch?.let {
                Text(
                    "Buffering events: ${it.startTimestamp} to ${it.endTimestamp} · $activeBatchEventCount/100",
                    modifier = Modifier.fillMaxWidth(),
                )
            } ?: latestBatch?.let {
                Text(
                    "Last batch: ${it.startTimestamp} to ${it.endTimestamp} · ${it.events.size} events",
                    modifier = Modifier.fillMaxWidth(),
                )
            } ?: Text(
                "Waiting for events to start a batch.",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when {
                    isPhrasingEvent -> "AI is rephrasing a batch..."
                    activeBatch != null -> "Batch processes at 100 events or after 1 second without new events."
                    else -> "Batches are rephrased automatically."
                },
                modifier = Modifier.fillMaxWidth(),
            )
            phrasedEvent?.let {
                Text("AI phrased batch: $it", modifier = Modifier.fillMaxWidth())
            }
            phrasingError?.let {
                Text("AI unavailable: $it", modifier = Modifier.fillMaxWidth())
            }
            Text(
                "If the daemon is unavailable, the client retries every 3 seconds.",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
