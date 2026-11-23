package com.cacaosd.wowtelemetry.android.filepicker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class PickerCategory(
    val directory: String,
    val mimeTypes: Array<String>,
) {
    SESSION("sessions", arrayOf("*/*")),
    LOG("logs", arrayOf("text/*", "application/json", "application/octet-stream")),
}

private val INVALID_FILE_NAME = Regex("[^A-Za-z0-9._-]")

suspend fun Context.copyPickedFile(uri: Uri, category: PickerCategory): String? =
    withContext(Dispatchers.IO) {
        val displayName = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && (index >= 0)) cursor.getString(index) else null
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
