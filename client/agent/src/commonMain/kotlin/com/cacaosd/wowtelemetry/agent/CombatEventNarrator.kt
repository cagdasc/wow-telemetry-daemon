package com.cacaosd.wowtelemetry.agent

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.chatMemory.feature.InMemoryChatHistoryProvider
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.time.KoogClock
import com.cacaosd.wowtelemetry.combatlog.CombatEventBatch
import com.cacaosd.wowtelemetry.combatlog.EnrichedCombatEvent
import com.cacaosd.wowtelemetry.domain.CharacterProfile

class CombatEventNarrator(
    private val clock: KoogClock,
) {
    private val chatHistoryProvider = InMemoryChatHistoryProvider()

    suspend fun respond(
        sessionId: String,
        profile: CharacterProfile?,
        batch: CombatEventBatch,
    ): String {
        addProfileAsFirstUserMessage(sessionId, profile)
        val executor = MultiLLMPromptExecutor(OllamaClient())
        val agent = AIAgent(
            agentConfig = AIAgentConfig(
                prompt = prompt(
                    id = sessionId,
                    params = LLMParams(temperature = 0.5),
                    clock = clock,
                ) {
                    system(
                        """
                            $systemPrompt
                            Never ask the user questions. 
                            Respond as a self-contained monologue.
                            Never quote or display raw combat-log rows.
                            """.trimIndent(),
                    )
                },
                model = OllamaModels.Meta.LLAMA_3_2_3B.copy(id = "llama3.2:latest"),
                maxAgentIterations = 50
            ),
            promptExecutor = executor,
            strategy = functionalStrategy { input: String ->
                requestLLM(input).parts
                    .filterIsInstance<MessagePart.Text>()
                    .joinToString("\n") { it.text }
            },
            installFeatures = {
                install(ChatMemory) {
                    chatHistoryProvider = this@CombatEventNarrator.chatHistoryProvider
                }
            }
        )

        return try {
            agent.run(batch.toPromptText(), sessionId)
        } finally {
            executor.use {
                agent.close()
            }
        }
    }

    suspend fun clearChat(sessionId: String) {
        chatHistoryProvider.store(sessionId, emptyList())
    }

    private suspend fun addProfileAsFirstUserMessage(
        sessionId: String,
        profile: CharacterProfile?,
    ) {
        if (chatHistoryProvider.load(sessionId).isNotEmpty()) return
        profile?.toChatMessage()?.let { message ->
            chatHistoryProvider.store(
                sessionId,
                listOf(Message.User(message, RequestMetaInfo.Empty)),
            )
        }
    }
}

private fun CharacterProfile.toChatMessage(): String? {
    val details = listOf(
        "Character" to characterName,
        "Class" to characterClass,
        "Specialization" to specialization,
        "Realm" to realm,
        "Region" to region,
    ).filter { (_, value) -> value.isNotBlank() }
    if (details.isEmpty()) return null

    return buildString {
        appendLine("Character profile for this combat chat:")
        details.forEach { (label, value) -> appendLine("$label: ${value.trim()}") }
        append("Use this profile as context; do not infer missing profile details.")
    }
}

private fun CombatEventBatch.toPromptText(): String = buildString {
    appendLine("Combat log batch")
    appendLine("Start: $startTimestamp")
    appendLine("End: $endTimestamp")
    appendLine("Events: ${events.size}")
    appendLine("<combat-log-data>")
    events.forEach { appendLine(it.toPromptText()) }
    append("</combat-log-data>")
}

private fun EnrichedCombatEvent.toPromptText(): String = buildString {
    appendLine("Summary: $summary")
    appendLine("Source GUID: ${sourceGuid ?: "unknown"}")
    appendLine("Target GUID: ${targetGuid ?: "unknown"}")
    spellId?.let { appendLine("Spell ID: $it") }
    spellName?.let { appendLine("Spell name: $it") }
    amount?.let { appendLine("Amount: $it") }
    append("Full typed event and raw fields: $details")
}
