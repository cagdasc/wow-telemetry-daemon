package com.cacaosd.wowtelemetry.domain

import kotlinx.coroutines.flow.StateFlow

interface LogAttachmentRepository {
    val attachments: StateFlow<List<LogAttachment>>

    suspend fun addAttachment(pathOrUri: String, displayName: String? = null, description: String = ""): LogAttachment
    suspend fun removeAttachment(id: String)
    suspend fun updateDescription(id: String, description: String)
    suspend fun setEnabled(id: String, isEnabled: Boolean)
    suspend fun getAttachment(id: String): LogAttachment?

    suspend fun listAttachedLogs(): List<LogAttachment>
}
