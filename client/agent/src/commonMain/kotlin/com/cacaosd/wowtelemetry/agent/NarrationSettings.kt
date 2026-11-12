package com.cacaosd.wowtelemetry.agent

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

enum class BatchFlushStrategy {
    /** Flush on LOG_CAUGHT_UP, or after no events arrive for the silence timeout. */
    SILENCE_WATCHDOG,

    /** Flush on LOG_CAUGHT_UP, or when the daemon connection is lost. */
    FLUSH_ON_DISCONNECT,
}

data class NarrationSettings(
    val temperature: Double = 0.5,
    val model: String = "llama3.2:latest",
    val batchFlushStrategy: BatchFlushStrategy = BatchFlushStrategy.SILENCE_WATCHDOG,
    val batchSilenceTimeoutSeconds: Int = DEFAULT_SILENCE_TIMEOUT_SECONDS,
) {
    init {
        require(batchSilenceTimeoutSeconds in MIN_SILENCE_TIMEOUT_SECONDS..MAX_SILENCE_TIMEOUT_SECONDS) {
            "Batch silence timeout must be between $MIN_SILENCE_TIMEOUT_SECONDS and $MAX_SILENCE_TIMEOUT_SECONDS seconds."
        }
        require(temperature in 0.0..2.0) { "Temperature must be between 0 and 2." }
        require(model.isNotBlank()) { "Model name cannot be blank." }
    }

    fun toLLModel(): LLModel = LLModel(
        provider = LLMProvider.Ollama,
        id = model,
    )

    companion object {
        const val DEFAULT_SILENCE_TIMEOUT_SECONDS = 10
        const val MIN_SILENCE_TIMEOUT_SECONDS = 5
        const val MAX_SILENCE_TIMEOUT_SECONDS = 20
    }
}

interface NarrationSettingsRepository {
    suspend fun load(): NarrationSettings

    suspend fun save(settings: NarrationSettings)
}

fun interface SupportingDocumentProvider {
    suspend fun relevantContext(query: String): String?
}

interface OllamaModelRepository {
    suspend fun discoverModels(): List<String>
}
