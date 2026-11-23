package com.cacaosd.wowtelemetry.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.cacaosd.wowtelemetry.client.App
import com.cacaosd.wowtelemetry.client.TelemetryViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity(), KoinComponent {
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
                        .getOrNull()
                )
            }
        }

        val viewModel: TelemetryViewModel = get()
        viewModel.start()

        setContent {
            App(
                initialWebsocketUrl = "127.0.0.1:8765",
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

    private suspend fun copyPickedFile(uri: Uri, category: PickerCategory): String? =
        withContext(Dispatchers.IO) {
            val displayName = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty()
            val safeName = displayName
                .substringAfterLast(File.separatorChar)
                .replace(INVALID_FILE_NAME, "_")
                .ifBlank { "picked-file" }
            val extension = if (category == PickerCategory.SESSION) {
                safeName.takeIf { it.endsWith(".wowlog", ignoreCase = true) } ?: "$safeName.wowlog"
            } else {
                safeName
            }
            val directory = File(filesDir, "picked/${category.directory}").apply { mkdirs() }
            val target = File(directory, "${UUID.randomUUID()}-$extension")

            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use(input::copyTo)
            } ?: return@withContext null
            target.absolutePath
        }

    private enum class PickerCategory(
        val directory: String,
        val mimeTypes: Array<String>,
    ) {
        SESSION("sessions", arrayOf("*/*")),
        LOG("logs", arrayOf("text/*", "application/json", "application/octet-stream")),
    }

    private companion object {
        const val TAG = "MainActivity"
        val INVALID_FILE_NAME = Regex("[^A-Za-z0-9._-]")
    }
}
