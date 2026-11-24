package com.cacaosd.wowtelemetry.domain.ollama

import kotlinx.serialization.Serializable

@Serializable
data class LocalModel(
    val name: String,
    val contextLength: Long
)

