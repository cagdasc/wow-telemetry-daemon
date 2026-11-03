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
            Text(
                "If the daemon is unavailable, the client retries every 3 seconds.",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
