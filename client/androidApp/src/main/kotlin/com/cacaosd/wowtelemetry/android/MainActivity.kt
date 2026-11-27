package com.cacaosd.wowtelemetry.android

import android.content.pm.PackageManager
import android.os.Build
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

// On a real phone 127.0.0.1 is the phone itself; enter the daemon host's LAN IP instead.
// The emulator reaches the host machine through 10.0.2.2.
private const val EMULATOR_WEBSOCKET_URL = "ws://10.0.2.2:8765"
private const val DEFAULT_WEBSOCKET_URL = "ws://127.0.0.1:8765"
private const val LOCAL_NETWORK_PERMISSION_API = 37
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private val viewModel: TelemetryViewModel by inject()

    private val pickerMutex = Mutex()
    private var pendingPick: CompletableDeferred<String?>? = null
    private var pickerCategory = PickerCategory.SESSION
    private lateinit var filePicker: ActivityResultLauncher<Array<String>>

    private val localNetworkMutex = Mutex()
    private var pendingLocalNetwork: CompletableDeferred<Boolean>? = null
    private lateinit var localNetworkPermission: ActivityResultLauncher<String>

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

        localNetworkPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            pendingLocalNetwork?.complete(granted)
            pendingLocalNetwork = null
        }

        viewModel.start()

        setContent {
            App(
                initialWebsocketUrl = if (isEmulator()) EMULATOR_WEBSOCKET_URL else DEFAULT_WEBSOCKET_URL,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                pickSessionFile = { pickFile(PickerCategory.SESSION) },
                pickLogFile = { pickFile(PickerCategory.LOG) },
                ensureNetworkAccess = ::ensureLocalNetworkAccess,
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

    /**
     * Android 17+ apps targeting SDK 37 need the ACCESS_LOCAL_NETWORK runtime permission to open
     * connections to LAN addresses. Loopback and public hosts are exempt. Returns null when the
     * connection may proceed, or a message to show the user.
     */
    private suspend fun ensureLocalNetworkAccess(url: String): String? {
        if (Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_API) return null
        val host = url.substringAfter("://").substringBefore('/').substringBeforeLast(':')
        if (host == "127.0.0.1" || host == "localhost") return null
        val permission = LOCAL_NETWORK_PERMISSION
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val granted = localNetworkMutex.withLock {
            val result = CompletableDeferred<Boolean>()
            pendingLocalNetwork = result
            localNetworkPermission.launch(permission)
            result.await()
        }
        return if (granted) {
            null
        } else {
            "Local network access was denied. Allow \"Nearby devices\" for this app in system settings to reach the daemon."
        }
    }

    private fun isEmulator(): Boolean =
        Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
                Build.FINGERPRINT.startsWith("generic")
}
