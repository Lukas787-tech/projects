package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings

/**
 * Turns one pasted key into many endpoints.
 *
 * This is the part that makes a pool worth having: asking the provider for its
 * model list and enrolling several of them means a per-model quota only costs
 * you that model, not the whole provider.
 *
 * Which models get enrolled matters as much as how many. A pool of six models
 * that cannot call tools is six ways for Jarvis to forget your trackers, so the
 * ranking below puts ordinary instruction-following chat models first and leaves
 * the specialist ones out entirely.
 */
class PoolBuilder(private val catalog: ModelCatalog) {

    data class Result(val added: Int, val skipped: Int, val message: String)

    suspend fun expand(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        pool: ModelPool,
        limit: Int? = null
    ): Result {
        val preset = Providers.byId(providerId)
        val probe = Settings(
            providerId = providerId,
            baseUrl = baseUrl.ifBlank { preset.baseUrl },
            apiKey = apiKey,
            model = preset.defaultModel
        )

        val models = try {
            catalog.fetch(probe)
        } catch (e: Exception) {
            return Result(0, 0, e.message ?: "Could not list models for ${preset.label}.")
        }

        val chosen = pick(preset, models, limit ?: preset.poolLimit)
        if (chosen.isEmpty()) {
            return Result(0, 0, "${preset.label} returned no usable chat models.")
        }

        val added = pool.add(
            chosen.map { model ->
                Endpoint(
                    providerId = providerId,
                    baseUrl = probe.baseUrl,
                    apiKey = apiKey,
                    model = model
                )
            }
        )
        return Result(
            added = added,
            skipped = chosen.size - added,
            message = if (added == 0) {
                "${preset.label}: already in the pool."
            } else {
                "${preset.label}: added $added model${if (added == 1) "" else "s"}."
            }
        )
    }

    /**
     * Enrols every provider that already has a key saved, in one go.
     *
     * Spanning accounts is the only thing that survives a daily cap, so making
     * that one tap rather than a dozen trips through the provider picker is the
     * difference between a pool that is actually wide and one that is not.
     */
    suspend fun expandAll(
        saved: List<Settings>,
        pool: ModelPool,
        onProgress: (String) -> Unit = {}
    ): Result {
        val usable = saved.filter { settings ->
            val preset = Providers.byId(settings.providerId)
            settings.apiKey.isNotBlank() || !preset.needsKey
        }
        if (usable.isEmpty()) {
            return Result(0, 0, "No saved keys yet. Paste one above and add it first.")
        }

        var added = 0
        var skipped = 0
        val failed = mutableListOf<String>()

        for (settings in usable) {
            val preset = Providers.byId(settings.providerId)
            onProgress("Asking ${preset.label}…")
            val result = expand(
                providerId = settings.providerId,
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                pool = pool
            )
            added += result.added
            skipped += result.skipped
            if (result.added == 0 && result.skipped == 0) failed += preset.label
        }

        return Result(
            added = added,
            skipped = skipped,
            message = buildString {
                append(
                    if (added == 0) {
                        "Nothing new to add."
                    } else {
                        "Added $added endpoint${if (added == 1) "" else "s"} " +
                            "across ${usable.size - failed.size} providers."
                    }
                )
                if (failed.isNotEmpty()) {
                    append(" Could not reach: ${failed.joinToString(", ")}.")
                }
            }
        )
    }

    /**
     * Picks which of a provider's models to enrol.
     *
     * Where a provider mixes free and paid ids in one catalogue, only the free
     * ones are ever enrolled — OpenRouter bills anything without `:free`, and
     * quietly spending money is not a failure mode this app should have. Beyond
     * that it is a preference order: general chat models ahead of reasoning
     * models (which burn tokens thinking and answer slowly for a voice
     * assistant), and anything that is not a chat model at all is dropped.
     */
    private fun pick(preset: ProviderPreset, models: List<String>, limit: Int): List<String> {
        val usable = models.filterNot { model ->
            EXCLUDED.any { model.contains(it, ignoreCase = true) }
        }
        val eligible = preset.freeSuffix?.let { suffix ->
            usable.filter { it.endsWith(suffix) }
        } ?: usable

        // Deliberately no de-duplication by model family here: a provider meters
        // gpt-oss-120b and gpt-oss-20b separately, so enrolling both is a second
        // quota rather than a redundant entry. Shorter ids first only because
        // they tend to be the stable alias rather than a dated snapshot.
        return eligible
            .sortedWith(compareBy({ rank(it) }, { it.length }))
            .take(limit)
    }

    /** Lower sorts earlier. A voice assistant wants quick and instruction-shaped. */
    private fun rank(model: String): Int {
        val id = model.lowercase()
        return when {
            PREFERRED.any { id.contains(it) } -> 0
            SLOW.any { id.contains(it) } -> 2
            else -> 1
        }
    }

    private companion object {
        /** Reliable, quick, and generally good at tool calling. */
        val PREFERRED = listOf(
            "gpt-oss", "llama-3.3", "llama-3.1", "llama3.3", "llama3.1",
            "gemini-flash", "gemini-2", "mistral-small", "ministral", "nemo",
            "qwen", "command-r", "glm-4", "kimi", "gpt-4o-mini", "gpt-4.1-mini",
            "deepseek-chat", "gpt-5-mini", "haiku"
        )

        /** Works, but thinks out loud for a while first. Kept as a backstop. */
        val SLOW = listOf(
            "reasoner", "thinking", "-r1", "o1", "o3", "o4", "qwq", "deep-research"
        )

        val EXCLUDED = listOf(
            "embed", "whisper", "tts", "guard", "imagen", "veo", "rerank",
            "moderation", "image", "vision", "aqa", "audio", "flux", "dall-e",
            "stable-diffusion", "bge-", "sora", "transcribe", "realtime",
            "distill-whisper", "safety", "prompt-guard"
        )
    }
}
