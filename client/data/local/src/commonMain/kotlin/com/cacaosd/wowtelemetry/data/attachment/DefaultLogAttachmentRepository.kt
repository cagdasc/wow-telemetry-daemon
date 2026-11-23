package com.cacaosd.wowtelemetry.data.attachment

import com.cacaosd.wowtelemetry.domain.LogAttachment
import com.cacaosd.wowtelemetry.domain.LogAttachmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class DefaultLogAttachmentRepository : LogAttachmentRepository {

    private val mutex = Mutex()
    private val _attachments = MutableStateFlow<List<LogAttachment>>(emptyList())
    override val attachments: StateFlow<List<LogAttachment>> = _attachments.asStateFlow()

    override suspend fun addAttachment(
        pathOrUri: String,
        displayName: String?,
        description: String,
    ): LogAttachment = withContext(Dispatchers.IO) {
        val file = File(pathOrUri)
        val name = displayName?.takeIf { it.isNotBlank() } ?: file.name.ifBlank { pathOrUri }
        val size = if (file.exists() && file.isFile) file.length() else 0L
        val lines = if (file.exists() && file.isFile) {
            try {
                file.useLines { it.count() }
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }

        val id = "log-${System.currentTimeMillis()}-${(100..999).random()}"
        val mimeType = when {
            name.endsWith(".json", ignoreCase = true) -> "application/json"
            name.endsWith(".csv", ignoreCase = true) -> "text/csv"
            name.endsWith(".wowlog", ignoreCase = true) -> "application/octet-stream"
            else -> "text/plain"
        }

        val attachment = LogAttachment(
            id = id,
            displayName = name,
            uriOrPath = pathOrUri,
            mimeType = mimeType,
            sizeBytes = size,
            lineCount = lines,
            description = description,
            isEnabled = true,
        )

        mutex.withLock {
            _attachments.update { current -> current + attachment }
        }

        attachment
    }

    override suspend fun removeAttachment(id: String) {
        mutex.withLock {
            _attachments.update { current -> current.filterNot { it.id == id } }
        }
    }

    override suspend fun updateDescription(id: String, description: String) {
        mutex.withLock {
            _attachments.update { current ->
                current.map {
                    if (it.id == id) it.copy(description = description) else it
                }
            }
        }
    }

    override suspend fun setEnabled(id: String, isEnabled: Boolean) {
        mutex.withLock {
            _attachments.update { current ->
                current.map {
                    if (it.id == id) it.copy(isEnabled = isEnabled) else it
                }
            }
        }
    }

    override suspend fun getAttachment(id: String): LogAttachment? {
        return _attachments.value.firstOrNull { it.id == id }
    }

    override suspend fun listAttachedLogs(): List<LogAttachment> {
        return _attachments.value.filter { it.isEnabled }
    }
}
