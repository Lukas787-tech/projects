package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings

/**
 * Sends the smallest possible real request so a broken setup reports *why* it
 * is broken, with the provider's own words and the exact URL attached. Guessing
 * from "it doesn't work" is what made the first two attempts at this miss.
 */
class ConnectionTest(private val client: LlmClient) {

    sealed interface Result {
        data class Ok(val reply: String, val toolsWork: Boolean, val diagnostics: String) : Result
        data class Failed(val message: String, val diagnostics: String) : Result
    }

    suspend fun run(settings: Settings): Result {
        if (settings.baseUrl.isBlank()) {
            return Result.Failed("No base URL set.", "")
        }
        if (settings.model.isBlank()) {
            return Result.Failed("No model selected. Tap Load models first.", "")
        }
        if (Providers.byId(settings.providerId).needsKey && settings.apiKey.isBlank()) {
            return Result.Failed("${Providers.byId(settings.providerId).label} needs an API key.", "")
        }

        val probe = listOf(
            LlmMessage.system("Reply with exactly: OK"),
            LlmMessage.user("Say OK.")
        )
        val small = settings.copy(maxTokens = 64)

        // Plain chat first: if this fails, nothing else matters.
        val reply = try {
            client.chat(small, probe, emptyList())
        } catch (e: Exception) {
            return Result.Failed(
                e.message ?: "Unknown failure.",
                client.lastDiagnostics?.asText().orEmpty()
            )
        }

        // Then check tool calling separately, because a model can answer fine
        // and still be unable to drive memory or trackers. The answer is whether
        // the request that succeeded still had `tools` on it — a reply can come
        // back perfectly well from a rung of the ladder that dropped them.
        val toolsWork = try {
            client.chat(small, probe, listOf(PROBE_TOOL)).capability.tools
        } catch (_: Exception) {
            false
        }

        return Result.Ok(
            reply = reply.content?.trim()?.ifBlank { "(empty reply)" } ?: "(empty reply)",
            toolsWork = toolsWork,
            diagnostics = client.lastDiagnostics?.asText().orEmpty()
        )
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
