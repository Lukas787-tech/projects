package com.lukas.jarvis.llm

import android.content.Context
import com.lukas.jarvis.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One callable (provider, key, model) combination. */
data class Endpoint(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean = true
) {
    val label: String get() = "${Providers.byId(providerId).label} · $model"
}

/** What has happened to an endpoint lately, and whether it is resting. */
data class Health(
    val cooldownUntil: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastUsedAt: Long = 0L,
    val successes: Long = 0L,
    val failures: Long = 0L,
    val lastError: String? = null,
    val brokenKey: Boolean = false
) {
    fun restingAt(now: Long) = cooldownUntil > now
}

data class PoolEntry(val endpoint: Endpoint, val health: Health) {
    fun status(now: Long = System.currentTimeMillis()): String = when {
        !endpoint.enabled -> "Off"
        health.brokenKey -> "Key rejected"
        health.restingAt(now) -> {
            val seconds = (health.cooldownUntil - now) / 1000
            when {
                seconds >= 3600 -> "Resting ${seconds / 3600}h"
                seconds >= 60 -> "Resting ${seconds / 60}m"
                else -> "Resting ${seconds}s"
            }
        }
        else -> "Ready"
    }
}

/**
 * Keeps several free endpoints in rotation so one exhausted quota does not end
 * the conversation.
 *
 * Worth being precise about what this does and does not buy: free tiers limit
 * per model *and* per account. Rotating models on one key genuinely helps where
 * limits are per model (Groq, Gemini), but an account-wide daily cap is only
 * escaped by rotating to a different provider. So a useful pool spans providers,
 * not just models.
 *
 * Persisted as JSON in preferences rather than SQLite: it is a short config
 * list, and this avoids a schema migration on an app already in someone's hands.
 */
class ModelPool(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_pool", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<PoolEntry>> = _entries.asStateFlow()

    /** Endpoint the last successful call went through, for display. */
    private val _lastUsed = MutableStateFlow<String?>(null)
    val lastUsed: StateFlow<String?> = _lastUsed.asStateFlow()

    val isEmpty: Boolean get() = _entries.value.isEmpty()

    // ------------------------------------------------------------------ edits

    fun add(endpoints: List<Endpoint>): Int {
        val existing = _entries.value
        // A pool with the same model twice would just double the same quota.
        val known = existing.map { it.endpoint.providerId to it.endpoint.model }.toSet()
        val fresh = endpoints.filter { (it.providerId to it.model) !in known }
        if (fresh.isEmpty()) return 0
        _entries.value = existing + fresh.map { PoolEntry(it, Health()) }
        persist()
        return fresh.size
    }

    fun remove(id: String) {
        _entries.value = _entries.value.filterNot { it.endpoint.id == id }
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _entries.value = _entries.value.map {
            if (it.endpoint.id == id) it.copy(endpoint = it.endpoint.copy(enabled = enabled)) else it
        }
        persist()
    }

    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    /** Clears cooldowns so a pool can be retried immediately after fixing keys. */
    fun wakeAll() {
        _entries.value = _entries.value.map {
            it.copy(health = it.health.copy(cooldownUntil = 0L, consecutiveFailures = 0, brokenKey = false))
        }
        persist()
    }

    // -------------------------------------------------------------- selection

    /**
     * Endpoints to try, best first: ready ones ordered least-recently-used so
     * load spreads evenly, then resting ones ordered by who wakes soonest, as a
     * last resort when everything is exhausted.
     */
    fun candidates(settings: Settings, now: Long = System.currentTimeMillis()): List<Endpoint> {
        val usable = _entries.value.filter { it.endpoint.enabled && !it.health.brokenKey }
        if (usable.isEmpty()) {
            // No pool configured: fall back to whatever Settings points at, so
            // the app behaves exactly as before for anyone not using this.
            return if (settings.isConfigured) {
                listOf(
                    Endpoint(
                        id = "settings",
                        providerId = settings.providerId,
                        baseUrl = settings.baseUrl,
                        apiKey = settings.apiKey,
                        model = settings.model
                    )
                )
            } else {
                emptyList()
            }
        }
        val (ready, resting) = usable.partition { !it.health.restingAt(now) }
        return ready.sortedBy { it.health.lastUsedAt }.map { it.endpoint } +
            resting.sortedBy { it.health.cooldownUntil }.map { it.endpoint }
    }

    // ----------------------------------------------------------------- health

    fun recordSuccess(endpoint: Endpoint) {
        _lastUsed.value = endpoint.label
        update(endpoint.id) {
            it.copy(
                cooldownUntil = 0L,
                consecutiveFailures = 0,
                lastUsedAt = System.currentTimeMillis(),
                successes = it.successes + 1,
                lastError = null
            )
        }
    }

    fun recordFailure(endpoint: Endpoint, error: LlmException) {
        val now = System.currentTimeMillis()
        update(endpoint.id) { health ->
            val failures = health.consecutiveFailures + 1
            val cooldown = when (error.kind) {
                // Obey a stated wait; otherwise back off further each time this
                // endpoint keeps coming back empty.
                FailureKind.RateLimited ->
                    error.retryAfterSeconds?.times(1000)
                        ?: BACKOFF_MS[failures.coerceAtMost(BACKOFF_MS.lastIndex)]

                FailureKind.ServerError, FailureKind.Network -> 60_000L
                FailureKind.ModelMissing -> 6 * 60 * 60_000L
                FailureKind.AuthFailed -> 0L
                else -> 0L
            }
            health.copy(
                cooldownUntil = if (cooldown > 0) now + cooldown else health.cooldownUntil,
                consecutiveFailures = failures,
                lastUsedAt = now,
                failures = health.failures + 1,
                lastError = error.message?.lineSequence()?.firstOrNull(),
                // A bad key never fixes itself, so stop spending turns on it.
                brokenKey = error.kind == FailureKind.AuthFailed
            )
        }
    }

    private fun update(id: String, transform: (Health) -> Health) {
        if (id == "settings") return
        _entries.value = _entries.value.map {
            if (it.endpoint.id == id) it.copy(health = transform(it.health)) else it
        }
        persist()
    }

    // ------------------------------------------------------------ persistence

    private fun persist() {
        val array = JSONArray()
        _entries.value.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.endpoint.id)
                    put("provider", entry.endpoint.providerId)
                    put("baseUrl", entry.endpoint.baseUrl)
                    put("apiKey", entry.endpoint.apiKey)
                    put("model", entry.endpoint.model)
                    put("enabled", entry.endpoint.enabled)
                    put("cooldownUntil", entry.health.cooldownUntil)
                    put("consecutiveFailures", entry.health.consecutiveFailures)
                    put("lastUsedAt", entry.health.lastUsedAt)
                    put("successes", entry.health.successes)
                    put("failures", entry.health.failures)
                    put("lastError", entry.health.lastError)
                    put("brokenKey", entry.health.brokenKey)
                }
            )
        }
        prefs.edit().putString(KEY_POOL, array.toString()).apply()
    }

    private fun load(): List<PoolEntry> {
        val raw = prefs.getString(KEY_POOL, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val model = obj.optString("model")
            val baseUrl = obj.optString("baseUrl")
            if (model.isBlank() || baseUrl.isBlank()) return@mapNotNull null
            PoolEntry(
                endpoint = Endpoint(
                    id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                    providerId = obj.optString("provider"),
                    baseUrl = baseUrl,
                    apiKey = obj.optString("apiKey"),
                    model = model,
                    enabled = obj.optBoolean("enabled", true)
                ),
                health = Health(
                    cooldownUntil = obj.optLong("cooldownUntil"),
                    consecutiveFailures = obj.optInt("consecutiveFailures"),
                    lastUsedAt = obj.optLong("lastUsedAt"),
                    successes = obj.optLong("successes"),
                    failures = obj.optLong("failures"),
                    lastError = obj.optString("lastError").takeIf { it.isNotBlank() },
                    brokenKey = obj.optBoolean("brokenKey", false)
                )
            )
        }
    }

    private companion object {
        const val KEY_POOL = "pool"

        /** 1m, 5m, 15m, 1h, 3h — indexed by consecutive failure count. */
        val BACKOFF_MS = longArrayOf(
            60_000L, 60_000L, 300_000L, 900_000L, 3_600_000L, 10_800_000L
        )
    }
}
