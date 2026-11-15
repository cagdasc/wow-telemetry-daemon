package com.cacaosd.wowtelemetry.data.repository

import com.cacaosd.wowtelemetry.domain.ollama.OllamaModelRepository
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopOllamaModelRepository(
    private val httpClient: HttpClient,
) : OllamaModelRepository {
    override suspend fun discoverModels(): List<String> {
        val response = httpClient.get("$OLLAMA_URL/api/tags")
        check(response.status.isSuccess()) {
            "Ollama model discovery failed (HTTP ${response.status.value})."
        }
        val models = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["models"]
            ?.jsonArray
            ?: error("Ollama model response is missing its models list.")

        return models
            .map { model ->
                model.jsonObject["name"]?.jsonPrimitive?.content
                    ?: model.jsonObject["model"]?.jsonPrimitive?.content
                    ?: error("Ollama returned a model without a name.")
            }
            .distinct()
            .sorted()
    }

    private companion object {
        const val OLLAMA_URL = "http://localhost:11434"
    }
}
