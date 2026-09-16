package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings

/**
 * Sends the smallest possible real request so a broken setup reports *why* it
 * is broken, instead of failing later in the middle of a conversation.
 */
class ConnectionTest(private val client: LlmClient) {

    sealed interface Result {
        data class Ok(val reply: String, val toolsWork: Boolean) : Result
        data class Failed(val message: String, val hint: String?) : Result
    }

    suspend fun run(settings: Settings): Result {
        if (settings.baseUrl.isBlank()) return Result.Failed("No base URL set.", null)
        if (settings.model.isBlank()) {
            return Result.Failed("No model selected.", "Tap Refresh to load the model list.")
        }
        if (Providers.byId(settings.providerId).needsKey && settings.apiKey.isBlank()) {
            return Result.Failed("This provider needs an API key.", null)
        }

        val probe = listOf(
            LlmMessage.system("Reply with exactly: OK"),
            LlmMessage.user("Say OK.")
        )

        // First pass asks for a tool so the result also reveals whether this
        // model can drive the memory and tracker features at all.
        val toolsWork = try {
            val reply = client.chat(settings.copy(maxTokens = 64), probe, listOf(PROBE_TOOL))
            reply.content != null || reply.toolCalls.isNotEmpty()
        } catch (_: Exception) {
            false
        }

        return try {
            val reply = client.chat(settings.copy(maxTokens = 64), probe, emptyList())
            val text = reply.content?.trim().orEmpty()
            Result.Ok(text.ifBlank { "(empty reply)" }, toolsWork)
        } catch (e: LlmException) {
            Result.Failed(e.message ?: "Unknown failure.", hintFor(e.message.orEmpty()))
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Unknown failure.", null)
        }
    }

    private fun hintFor(message: String): String? = when {
        message.contains("404") || message.contains("not found", true) ->
            "That usually means the model id is retired. Tap Refresh models and pick one from the list."
        message.contains("401") || message.contains("403") || message.contains("rejected", true) ->
            "Check the key is pasted whole, with no stray spaces."
        message.contains("429") ->
            "Free tiers throttle. Wait a minute or switch provider."
        message.contains("Could not reach", true) ->
            "Check the base URL, and that you are on the same Wi-Fi if this is your own machine."
        else -> null
    }

    private companion object {
        val PROBE_TOOL = org.json.JSONObject(
            """
            {"type":"function","function":{
              "name":"ping",
              "description":"A no-op used only to check tool calling works.",
              "parameters":{"type":"object","properties":{},"required":[]}
            }}
            """.trimIndent()
        )
    }
}
