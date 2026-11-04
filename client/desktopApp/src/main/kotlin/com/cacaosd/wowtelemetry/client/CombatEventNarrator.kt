package com.cacaosd.wowtelemetry.client

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.message.MessagePart

class CombatEventNarrator {
    suspend fun rephrase(summary: String): String {
        val executor = MultiLLMPromptExecutor(OllamaClient())
        val agent = AIAgent<String, String>(
            systemPrompt = """
                Rephrase the supplied combat log batch for a player.
                Preserve its start and end timestamps and every listed event fact.
                Do not add, infer, or alter facts, causes, event counts, or combat analysis.
                Keep the batch summary concise but represent the events as a group.
                Return only the rephrased batch summary.
            """.trimIndent(),
            promptExecutor = executor,
            strategy = functionalStrategy { input: String ->
                requestLLM(input).parts
                    .filterIsInstance<MessagePart.Text>()
                    .joinToString("\n") { it.text }
            },
            llmModel = OllamaModels.Meta.LLAMA_3_2_3B.copy(id = "llama3.2:latest"),
        )

        return try {
            agent.run(summary)
        } finally {
            try {
                agent.close()
            } finally {
                executor.close()
            }
        }
    }
}
