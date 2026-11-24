package com.cacaosd.wowtelemetry.data.repository

import com.cacaosd.wowtelemetry.domain.ollama.LocalModel
import com.cacaosd.wowtelemetry.domain.ollama.OllamaModelRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class DesktopOllamaModelRepository(
    private val httpClient: HttpClient,
) : OllamaModelRepository {
    override suspend fun discoverModels(): List<LocalModel> {
        val response = httpClient.get("$OLLAMA_URL/api/tags")
        check(response.status.isSuccess()) {
            "Ollama model discovery failed (HTTP ${response.status.value})."
        }
        val models = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["models"]
            ?.jsonArray
            ?: error("Ollama model response is missing its models list.")

        val modelNames = models
            .map { model ->
                val ctxLength =
                    model.jsonObject["details"]?.jsonObject["context_length"]?.jsonPrimitive?.longOrNull ?: 0

                val name = (model.jsonObject["name"]?.jsonPrimitive?.content
                    ?: model.jsonObject["model"]?.jsonPrimitive?.content
                    ?: error("Ollama returned a model without a name."))
                LocalModel(
                    name = name,
                    contextLength = ctxLength
                )
            }
            .distinct()
            .sortedBy { it.name }
        return modelNames
    }

    override suspend fun listRunningModels(): List<String> {
        val response = httpClient.get("$OLLAMA_URL/api/ps")
        check(response.status.isSuccess()) {
            "Ollama running-model lookup failed (HTTP ${response.status.value})."
        }
        val models = Json.parseToJsonElement(response.bodyAsText()).jsonObject["models"]?.jsonArray
            ?: return emptyList()
        return models.mapNotNull {
            val entry = it.jsonObject
            entry["name"]?.jsonPrimitive?.content ?: entry["model"]?.jsonPrimitive?.content
        }
    }

    override suspend fun runningContextLength(model: String): Int? {
        val response = httpClient.get("$OLLAMA_URL/api/ps")
        check(response.status.isSuccess()) {
            "Ollama running-model lookup failed (HTTP ${response.status.value})."
        }
        val models = Json.parseToJsonElement(response.bodyAsText()).jsonObject["models"]?.jsonArray
            ?: error("Ollama running-model response is missing its models list.")
        return models.firstOrNull {
            val entry = it.jsonObject
            entry["name"]?.jsonPrimitive?.content == model || entry["model"]?.jsonPrimitive?.content == model
        }?.jsonObject?.get("context_length")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
    }

    override suspend fun preloadModel(model: String, keepAlive: String) {
        val response = httpClient.post("$OLLAMA_URL/api/generate") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"$model","keep_alive":"$keepAlive"}""")
        }
        check(response.status.isSuccess()) {
            "Ollama model preload failed (HTTP ${response.status.value})."
        }
    }

    override suspend fun offloadModel(model: String) {
        val response = httpClient.post("$OLLAMA_URL/api/generate") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"$model","keep_alive":0}""")
        }
        check(response.status.isSuccess()) {
            "Ollama model offload failed (HTTP ${response.status.value})."
        }
    }

    private companion object {
        const val OLLAMA_URL = "http://localhost:11434"
    }
}
