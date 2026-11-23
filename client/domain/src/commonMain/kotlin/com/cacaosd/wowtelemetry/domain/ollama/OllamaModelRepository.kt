package com.cacaosd.wowtelemetry.domain.ollama

interface OllamaModelRepository {
    suspend fun discoverModels(): List<String>

    suspend fun runningContextLength(model: String): Int?
}
