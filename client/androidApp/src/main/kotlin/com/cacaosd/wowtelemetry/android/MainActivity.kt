package com.cacaosd.wowtelemetry.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.cacaosd.wowtelemetry.android.filepicker.PickerCategory
import com.cacaosd.wowtelemetry.android.filepicker.copyPickedFile
import com.cacaosd.wowtelemetry.client.App
import com.cacaosd.wowtelemetry.client.TelemetryViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject

private const val DEFAULT_WEBSOCKET_URL = "ws://127.0.0.1:8765"
private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private val viewModel: TelemetryViewModel by inject()

    private val pickerMutex = Mutex()
    private var pendingPick: CompletableDeferred<String?>? = null
    private var pickerCategory = PickerCategory.SESSION
    private lateinit var filePicker: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val result = pendingPick
            pendingPick = null
            if (result == null) return@registerForActivityResult
            if (uri == null) {
                result.complete(null)
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                result.complete(
                    runCatching { copyPickedFile(uri, pickerCategory) }
                        .onFailure { Log.e(TAG, "Could not import picked file", it) }
                        .getOrNull(),
                )
            }
        }

        viewModel.start()

        setContent {
            App(
                initialWebsocketUrl = DEFAULT_WEBSOCKET_URL,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                pickSessionFile = { pickFile(PickerCategory.SESSION) },
                pickLogFile = { pickFile(PickerCategory.LOG) },
            )
        }
    }

    private suspend fun pickFile(category: PickerCategory): String? = pickerMutex.withLock {
        val result = CompletableDeferred<String?>()
        pendingPick = result
        pickerCategory = category
        filePicker.launch(category.mimeTypes)
        result.await()
    }
}
