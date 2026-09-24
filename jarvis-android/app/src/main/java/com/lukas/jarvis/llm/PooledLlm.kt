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

    /**
     * Shows one picture to whichever endpoint can see.
     *
     * Most of the pool is text-only, and a text model handed an image answers
     * with a 400 that reads exactly like a dead model id. So this walk is kept
     * away from the pool's bookkeeping entirely: nothing is rested or marked
     * broken because it could not look at a photo. Endpoints already known to
     * see go first, then each account is tried once more with the model that
     * provider serves pictures on, which is usually free on the same key.
     */
    suspend fun look(settings: Settings, prompt: String, imageDataUrl: String): String {
        val message = LlmMessage(LlmMessage.USER, prompt, images = listOf(imageDataUrl))
        val known = (pool.entries.value.map { it.endpoint }.filter { it.enabled } +
            pool.plan(settings, limit = 40).endpoints +
            listOfNotNull(
                settings.takeIf { it.isConfigured }?.let {
                    Endpoint(
                        providerId = it.providerId,
                        baseUrl = it.baseUrl,
                        apiKey = it.apiKey,
                        model = it.model
                    )
                }
            )).distinctBy { it.providerId + it.model }

        val candidates = buildList {
            addAll(known.filter { seesPictures(it.model) })
            known.distinctBy { it.providerId + it.apiKey }.forEach { account ->
                VISION_MODELS[account.providerId]?.forEach { model ->
                    add(account.copy(model = model))
                }
            }
        }.distinctBy { it.providerId + it.model }.take(MAX_LOOKS)

        if (candidates.isEmpty()) throw LlmException(NO_EYES, FailureKind.ModelMissing)

        var last: LlmException? = null
        for (endpoint in candidates) {
            val attempt = settings.copy(
                providerId = endpoint.providerId,
                baseUrl = endpoint.baseUrl,
                apiKey = endpoint.apiKey,
                model = endpoint.model,
                // A description is read, not spoken, so it may run longer.
                maxTokens = maxOf(settings.maxTokens, 900)
            )
            try {
                val reply = client.chat(attempt, listOf(message), emptyList(), Capability(tools = false))
                reply.content?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            } catch (e: LlmException) {
                last = e
            }
        }
        val lastLine = last?.message?.lineSequence()?.firstOrNull()
        throw LlmException(
            NO_EYES + (lastLine?.let { "\n\nLast answer: $it" } ?: ""),
            last?.kind ?: FailureKind.Unknown
        )
    }

    private fun seesPictures(model: String): Boolean {
        val id = model.lowercase()
        return VISION_HINTS.any { id.contains(it) }
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

        /** A photo that five endpoints could not see is not going to be seen by a sixth. */
        const val MAX_LOOKS = 6

        const val NO_EYES = "None of your models can look at pictures. Add a Google Gemini " +
            "key in Settings (free at aistudio.google.com) — its models can."

        /** Model names that mean "takes images", across the providers in the picker. */
        val VISION_HINTS = listOf(
            "gemini", "gemma-3", "llama-4", "scout", "maverick", "vision", "-vl", "vl-",
            "pixtral", "gpt-4o", "gpt-4.1", "gpt-5", "llava", "qwen2.5-vl", "glm-4v",
            "mistral-small-3", "mistral-medium", "grok-2-vision", "grok-4", "kimi-vl",
            "phi-4-multimodal", "claude"
        )

        /** The model each provider serves pictures on, tried on any key already in the pool. */
        val VISION_MODELS: Map<String, List<String>> = mapOf(
            Providers.GEMINI to listOf("gemini-2.5-flash", "gemini-2.0-flash"),
            Providers.GROQ to listOf("meta-llama/llama-4-scout-17b-16e-instruct"),
            Providers.OPENROUTER to listOf(
                "meta-llama/llama-4-scout:free",
                "google/gemma-3-27b-it:free"
            ),
            Providers.MISTRAL to listOf("pixtral-12b-2409", "mistral-small-latest"),
            Providers.GITHUB to listOf("gpt-4o-mini"),
            Providers.TOGETHER to listOf("meta-llama/Llama-4-Scout-17B-16E-Instruct")
        )
    }
}
