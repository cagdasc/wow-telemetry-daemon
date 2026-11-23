package com.cacaosd.wowtelemetry.client.filechooser

import com.cacaosd.wowtelemetry.data.getHomeDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

suspend fun chooseLogFile(): String? = chooseFile(
    dialogTitle = "Attach combat log file",
    filterName = "Combat logs (*.txt, *.log, *.wowlog)",
    extensions = arrayOf("txt", "log", "wowlog"),
)

suspend fun chooseSessionFile(): String? = chooseFile(
    dialogTitle = "Open combat log session",
    filterName = "WoW telemetry session (*.wowlog)",
    extensions = arrayOf("wowlog"),
    initialDirectory = getHomeDirectory() + "/sessions",
)

private suspend fun chooseFile(
    dialogTitle: String,
    filterName: String,
    extensions: Array<String>,
    initialDirectory: String? = null,
): String? = withContext(Dispatchers.IO) {
    var selected: String? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser(initialDirectory).apply {
            this.dialogTitle = dialogTitle
            fileFilter = FileNameExtensionFilter(filterName, *extensions)
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            selected = chooser.selectedFile.absolutePath
        }
    }
    selected
}
