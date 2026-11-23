package com.cacaosd.wowtelemetry.agent.supporter

import com.cacaosd.wowtelemetry.domain.LogAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ResolvedSupporter(
    val attachment: LogAttachment,
    val content: String,
    val isTruncated: Boolean = false,
    val error: String? = null,
) {
    fun toUserMessageBlock(): String = buildString {
        appendLine("--- SUPPORTING FILE [role: SUPPORTER] ---")
        appendLine("ID: ${attachment.id}")
        appendLine("Name: ${attachment.displayName}")
        appendLine("User Description / Role: ${attachment.description.ifBlank { "Supplemental combat log context" }}")
        appendLine("MIME: ${attachment.mimeType} | Size: ${attachment.sizeBytes} bytes | Lines: ${attachment.lineCount}")
        if (error != null) {
            appendLine("Status: ERROR - $error")
        } else {
            if (isTruncated) {
                appendLine("Status: Content truncated to fit budget limits (max $MAX_LINES_PER_FILE lines / ${MAX_BYTES_PER_FILE / 1024} KB).")
            }
            appendLine("Content:")
            appendLine(content)
        }
        append("--- END SUPPORTING FILE ---")
    }

    companion object {
        const val MAX_LINES_PER_FILE = 1_000
        const val MAX_BYTES_PER_FILE = 256 * 1024L // 256 KB
    }
}

class SupportingFileReader {

    suspend fun resolveSupporter(attachment: LogAttachment): ResolvedSupporter = withContext(Dispatchers.IO) {
        val file = File(attachment.uriOrPath)
        if (!file.exists()) {
            return@withContext ResolvedSupporter(
                attachment = attachment,
                content = "",
                error = "File does not exist at '${attachment.uriOrPath}'.",
            )
        }
        if (!file.isFile) {
            return@withContext ResolvedSupporter(
                attachment = attachment,
                content = "",
                error = "Path '${attachment.uriOrPath}' is not a regular file.",
            )
        }
        if (!file.canRead()) {
            return@withContext ResolvedSupporter(
                attachment = attachment,
                content = "",
                error = "Cannot read file at '${attachment.uriOrPath}' (permission denied).",
            )
        }
        if (attachment.mimeType == "application/octet-stream") {
            return@withContext ResolvedSupporter(
                attachment = attachment,
                content = "",
                error = "Binary combat-log sessions must be analyzed before they can be used as supporter context.",
            )
        }

        try {
            val lines = mutableListOf<String>()
            var totalBytes = 0L
            var truncated = false

            file.forEachLine { line ->
                val lineBytes = line.length + 1L // approximate newline
                if (lines.size >= ResolvedSupporter.MAX_LINES_PER_FILE || totalBytes + lineBytes > ResolvedSupporter.MAX_BYTES_PER_FILE) {
                    truncated = true
                    return@forEachLine
                }
                lines.add(line)
                totalBytes += lineBytes
            }

            ResolvedSupporter(
                attachment = attachment,
                content = lines.joinToString("\n"),
                isTruncated = truncated,
            )
        } catch (e: Exception) {
            ResolvedSupporter(
                attachment = attachment,
                content = "",
                error = "Failed to read file: ${e.message}",
            )
        }
    }

    suspend fun resolveAll(attachments: List<LogAttachment>): List<ResolvedSupporter> = withContext(Dispatchers.IO) {
        attachments.filter { it.isEnabled }.map { resolveSupporter(it) }
    }
}
