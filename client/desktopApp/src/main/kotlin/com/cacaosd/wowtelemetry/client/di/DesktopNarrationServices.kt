package com.cacaosd.wowtelemetry.client.di

import com.cacaosd.wowtelemetry.agent.BatchFlushStrategy
import com.cacaosd.wowtelemetry.agent.NarrationSettings
import com.cacaosd.wowtelemetry.agent.NarrationSettingsRepository
import com.cacaosd.wowtelemetry.agent.SupportingDocumentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.prefs.Preferences

class DesktopNarrationSettingsRepository : NarrationSettingsRepository {
    private val preferences =
        Preferences.userNodeForPackage(DesktopNarrationSettingsRepository::class.java)

    override suspend fun load(): NarrationSettings = withContext(Dispatchers.IO) {
        NarrationSettings(
            temperature = preferences.getDouble(TEMPERATURE_KEY, 0.5),
            model = preferences.get(MODEL_KEY, DEFAULT_MODEL),
            batchFlushStrategy = runCatching {
                BatchFlushStrategy.valueOf(
                    preferences.get(FLUSH_STRATEGY_KEY, BatchFlushStrategy.SILENCE_WATCHDOG.name)
                )
            }.getOrDefault(BatchFlushStrategy.SILENCE_WATCHDOG),
            batchSilenceTimeoutSeconds = preferences
                .getInt(SILENCE_TIMEOUT_KEY, NarrationSettings.DEFAULT_SILENCE_TIMEOUT_SECONDS)
                .coerceIn(
                    NarrationSettings.MIN_SILENCE_TIMEOUT_SECONDS,
                    NarrationSettings.MAX_SILENCE_TIMEOUT_SECONDS,
                ),
        )
    }

    override suspend fun save(settings: NarrationSettings) = withContext(Dispatchers.IO) {
        preferences.putDouble(TEMPERATURE_KEY, settings.temperature)
        preferences.put(MODEL_KEY, settings.model)
        preferences.put(FLUSH_STRATEGY_KEY, settings.batchFlushStrategy.name)
        preferences.putInt(SILENCE_TIMEOUT_KEY, settings.batchSilenceTimeoutSeconds)
        preferences.flush()
    }

    private companion object {
        const val DEFAULT_MODEL = "llama3.2:latest"
        const val MODEL_KEY = "model"
        const val FLUSH_STRATEGY_KEY = "batchFlushStrategy"
        const val SILENCE_TIMEOUT_KEY = "batchSilenceTimeoutSeconds"
        const val TEMPERATURE_KEY = "temperature"
    }
}

class FileSupportingDocumentProvider(
    private val directory: Path = Path.of("knowledge"),
) : SupportingDocumentProvider {
    override suspend fun relevantContext(query: String): String? = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val terms = query
            .lowercase()
            .split(WORD_BOUNDARY)
            .filter { it.length > MIN_TERM_LENGTH && it !in STOP_WORDS }
            .toSet()
            .take(MAX_QUERY_TERMS)
        if (terms.isEmpty()) return@withContext null

        val paths = Files.list(directory).use { files ->
            files
                .filter {
                    Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
                            it.fileName.toString() != "README.md" &&
                            it.extension in SUPPORTED_EXTENSIONS
                }
                .sorted()
                .limit((MAX_DOCUMENT_COUNT + 1).toLong())
                .toList()
        }
        require(paths.size <= MAX_DOCUMENT_COUNT) {
            "Supporting document directory contains more than $MAX_DOCUMENT_COUNT files."
        }
        paths.map { path ->
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_DOCUMENT_BYTES + 1) }
            require(bytes.size <= MAX_DOCUMENT_BYTES) {
                "Supporting document is larger than 64 KiB: ${path.fileName}"
            }
            val text = String(bytes, StandardCharsets.UTF_8)
            RankedDocument(
                name = path.fileName.toString(),
                text = text,
                score = terms.count { term -> text.contains(term, ignoreCase = true) },
            )
        }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(MAX_SELECTED_DOCUMENTS)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { "### ${it.name}\n${it.text}" }
            ?.take(MAX_CONTEXT_CHARS)
    }

    private data class RankedDocument(
        val name: String,
        val text: String,
        val score: Int,
    )

    private val Path.extension: String
        get() = fileName.toString().substringAfterLast('.', "").lowercase()

    private companion object {
        const val MAX_CONTEXT_CHARS = 12_000
        const val MAX_DOCUMENT_BYTES = 64 * 1024
        const val MAX_DOCUMENT_COUNT = 200
        const val MAX_QUERY_TERMS = 40
        const val MAX_SELECTED_DOCUMENTS = 3
        const val MIN_TERM_LENGTH = 3
        val SUPPORTED_EXTENSIONS = setOf("md", "txt")
        val STOP_WORDS = setOf("combat", "event", "events", "log", "batch", "timestamp")
        val WORD_BOUNDARY = Regex("[^\\p{L}\\p{N}]+")
    }
}
