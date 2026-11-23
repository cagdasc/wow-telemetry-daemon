package com.cacaosd.wowtelemetry.domain

import kotlinx.serialization.Serializable

@Serializable
data class LogAttachment(
    val id: String,
    val displayName: String,
    val uriOrPath: String,
    val mimeType: String = "text/plain",
    val sizeBytes: Long = 0L,
    val lineCount: Int = 0,
    val description: String = "",
    val isEnabled: Boolean = true,
)
