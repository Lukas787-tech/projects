package com.lukas.jarvis.llm

import com.lukas.jarvis.core.Settings

/**
 * Turns one pasted key into many endpoints.
 *
 * This is the part that makes a pool worth having: asking the provider for its
 * model list and enrolling several of them means a per-model quota only costs
 * you that model, not the whole provider.
 */
class PoolBuilder(private val catalog: ModelCatalog) {

    data class Result(val added: Int, val skipped: Int, val message: String)

    suspend fun expand(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        pool: ModelPool,
        limit: Int = 6
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

        val chosen = pick(providerId, models, limit)
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
     * OpenRouter bills anything without a ':free' suffix, so only free ids are
     * ever enrolled there. Elsewhere the whole listed set is free-tier, and
     * preferring bigger models first keeps answer quality up while quota lasts.
     */
    private fun pick(providerId: String, models: List<String>, limit: Int): List<String> {
        val usable = models.filterNot { model ->
            EXCLUDED.any { model.contains(it, ignoreCase = true) }
        }
        val eligible = if (providerId == Providers.OPENROUTER) {
            usable.filter { it.endsWith(":free") }
        } else {
            usable
        }
        return eligible.take(limit)
    }

    private companion object {
        val EXCLUDED = listOf(
            "embed", "whisper", "tts", "guard", "imagen", "veo",
            "rerank", "moderation", "image", "vision", "aqa"
        )
    }
}
