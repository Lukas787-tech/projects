package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import org.json.JSONObject

/**
 * Runs a request against the pool, moving to the next endpoint when one is out
 * of quota, and resting the ones that fail.
 *
 * Presents the same shape as [LlmClient.chat] so the agent does not care whether
 * a pool is configured.
 */
class PooledLlm(
    private val client: LlmClient,
    private val pool: ModelPool
) {

    val lastDiagnostics: Diagnostics? get() = client.lastDiagnostics

    suspend fun chat(
        settings: Settings,
        messages: List<LlmMessage>,
        tools: List<JSONObject> = emptyList(),
        onEndpointChange: (String) -> Unit = {}
    ): LlmReply {
        val candidates = pool.candidates(settings)
        if (candidates.isEmpty()) {
            throw LlmException(
                "No model set up yet. Open Settings, add a key and tap Load models.",
                FailureKind.Unknown
            )
        }

        var last: LlmException? = null
        for ((index, endpoint) in candidates.withIndex()) {
            if (index > 0) onEndpointChange(endpoint.label)
            // Settings already carries exactly the fields an endpoint overrides,
            // so the client needs no knowledge of pooling.
            val attempt = settings.copy(
                providerId = endpoint.providerId,
                baseUrl = endpoint.baseUrl,
                apiKey = endpoint.apiKey,
                model = endpoint.model
            )
            try {
                val reply = client.chat(attempt, messages, tools)
                pool.recordSuccess(endpoint)
                return reply
            } catch (e: LlmException) {
                pool.recordFailure(endpoint, e)
                last = e
                // A rejected payload or a missing model is this endpoint's
                // problem; everything else may well be too, so just move on.
                if (e.kind == FailureKind.AuthFailed && candidates.size == 1) throw e
            }
        }
        throw last ?: LlmException("Every endpoint failed.", FailureKind.Unknown)
    }
}
