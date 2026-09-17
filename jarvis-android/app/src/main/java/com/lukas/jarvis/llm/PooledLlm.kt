package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Runs a request against the pool, moving to the next endpoint when one is out
 * of quota, and resting the ones that fail.
 *
 * Presents the same shape as [LlmClient.chat] so the agent does not care whether
 * a pool is configured.
 *
 * Three things keep this from turning into a scattergun that burns every quota
 * in the pool on one bad turn:
 *
 * - the pool hands back only endpoints with room to spare, best first;
 * - the walk stops after a handful of attempts, because the tenth failure in a
 *   row is telling you the same thing the second one did, at the same price;
 * - failures that will repeat identically on the next endpoint (a rejected
 *   payload is not one of those — a dead key is) end the walk immediately.
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
        val plan = pool.plan(settings)
        if (plan.isEmpty) {
            throw noEndpointError(plan)
        }

        // Everything is throttled but something frees up in a moment: waiting is
        // strictly cheaper than spending a request to be told to wait.
        val wait = plan.freeAt - System.currentTimeMillis()
        if (plan.readyCount == 0 && wait in 1..MAX_WAIT_MS) {
            onEndpointChange("waiting ${(wait / 1000) + 1}s for quota")
            delay(wait + 100)
        }

        var last: LlmException? = null
        val tried = StringBuilder()

        for ((index, endpoint) in plan.endpoints.withIndex()) {
            if (index > 0) onEndpointChange(endpoint.label)

            // Settings already carries exactly the fields an endpoint overrides,
            // so the client needs no knowledge of pooling.
            val attempt = settings.copy(
                providerId = endpoint.providerId,
                baseUrl = endpoint.baseUrl,
                apiKey = endpoint.apiKey,
                model = endpoint.model
            )
            val known = pool.entries.value
                .firstOrNull { it.endpoint.id == endpoint.id }
                ?.health?.capability

            pool.recordAttempt(endpoint)
            try {
                val reply = client.chat(attempt, messages, tools, known)
                pool.recordSuccess(endpoint, reply)
                return reply
            } catch (e: LlmException) {
                pool.recordFailure(endpoint, e)
                last = e
                tried.appendLine("• ${endpoint.label}: ${headline(e)}")

                // A dead key on the only endpoint there is, is the whole story.
                if (e.kind == FailureKind.AuthFailed && plan.endpoints.size == 1) throw e
            }
        }

        throw LlmException(
            buildString {
                append("Every endpoint Jarvis tried came back empty.\n\n")
                append(tried.toString().trim())
                append("\n\n")
                append(advice(last))
            },
            last?.kind ?: FailureKind.Unknown,
            scope = last?.scope ?: LimitScope.Model
        )
    }

    private fun noEndpointError(plan: ModelPool.Plan): LlmException {
        if (plan.totalCount == 0) {
            return LlmException(
                "No model set up yet. Open Settings, add a key and tap Load models.",
                FailureKind.Unknown
            )
        }
        val wait = plan.freeAt - System.currentTimeMillis()
        val human = when {
            wait <= 0 -> "shortly"
            wait >= 3_600_000 -> "in about ${wait / 3_600_000}h"
            wait >= 60_000 -> "in about ${wait / 60_000}m"
            else -> "in ${wait / 1000}s"
        }
        return LlmException(
            "Every endpoint in the pool is resting. The first one is back $human.\n\n" +
                "Adding a provider from a different account is what actually widens " +
                "this — a daily cap is charged to the key, not the model.",
            FailureKind.RateLimited
        )
    }

    /** The first line of a provider's complaint, which is the part worth reading. */
    private fun headline(e: LlmException): String =
        e.message?.lineSequence()?.firstOrNull()?.take(90) ?: "failed"

    private fun advice(last: LlmException?): String = when (last?.kind) {
        FailureKind.RateLimited ->
            "That is every quota spent for now. Add another provider in Settings — " +
                "a second account is the only thing a daily cap cannot follow you to."

        FailureKind.AuthFailed ->
            "The keys were rejected. Check them in Settings, then tap Wake all."

        FailureKind.OutOfCredit ->
            "Those accounts are out of credit. Add a free-tier provider in Settings."

        FailureKind.Network ->
            "Jarvis could not reach anything. Check the phone's connection."

        FailureKind.ModelMissing ->
            "Those model ids are gone. Tap Load models in Settings to refresh them."

        else -> "Open Settings and run Test connection to see the raw response."
    }

    private companion object {
        /**
         * The longest Jarvis will sit on its hands waiting for a throttle to
         * clear. Past this it is faster to say so than to keep the user staring
         * at a silent orb.
         */
        const val MAX_WAIT_MS = 4_000L
    }
}
