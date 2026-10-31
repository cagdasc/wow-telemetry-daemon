package com.cacaosd.wowtelemetry.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App(websocketUrl: String = "ws://127.0.0.1:8765") {
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
                "Incoming events are printed to the application console.",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "If the daemon is unavailable, the client retries every 3 seconds.",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}