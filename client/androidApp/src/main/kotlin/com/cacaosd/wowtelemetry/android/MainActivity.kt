package com.cacaosd.wowtelemetry.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.cacaosd.wowtelemetry.client.App
import com.cacaosd.wowtelemetry.client.TelemetryViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class MainActivity : ComponentActivity(), KoinComponent {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel: TelemetryViewModel = get()
        viewModel.start()

        setContent {
            App(
                initialWebsocketUrl = "127.0.0.1:8765",
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            )
        }

    }
}
