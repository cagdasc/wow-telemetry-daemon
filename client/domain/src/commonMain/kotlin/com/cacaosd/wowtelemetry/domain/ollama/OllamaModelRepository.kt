package com.cacaosd.wowtelemetry.domain.ollama

interface OllamaModelRepository {
    suspend fun discoverModels(): List<LocalModel>

    suspend fun listRunningModels(): List<String>

    suspend fun runningContextLength(model: String): Int?

    suspend fun preloadModel(model: String, keepAlive: String = "10m")

    suspend fun offloadModel(model: String)
}
