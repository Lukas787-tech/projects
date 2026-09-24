package com.lukas.jarvis.llm

import android.content.Context
import com.lukas.jarvis.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

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

    val preset: ProviderPreset get() = Providers.byId(providerId)

    /**
     * Endpoints sharing this share a quota.
     *
     * Free tiers meter per model *and* per key. Two Groq models on one key are
     * two model quotas but one daily account quota, and two keys on the same
     * provider are two of everything — which is why the key is part of the
     * identity here rather than just the provider.
     */
    val account: String
        get() = providerId + "|" + baseUrl.trim().trimEnd('/') + "|" + apiKey.trim().hashCode()
}

/** What has happened to an endpoint lately, and whether it is resting. */
data class Health(
    val cooldownUntil: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastUsedAt: Long = 0L,
    val successes: Long = 0L,
    val failures: Long = 0L,
    val lastError: String? = null,
    val brokenKey: Boolean = false,
    /** Rolling average round trip, so the quicker of two equals wins. */
    val latencyMs: Long = 0L,
    /** Our own call accounting, for staying under the published rate. */
    val usage: Usage = Usage(),
    /** The payload shape this endpoint accepted last time, if it ever answered. */
    val capability: Capability? = null
) {
    fun restingAt(now: Long) = cooldownUntil > now

    /** 0..1, where 1 is "has never failed". Unproven endpoints count as good. */
    val reliability: Double
        get() {
            val total = successes + failures
            return if (total == 0L) 0.85 else successes.toDouble() / total
        }
}

data class PoolEntry(val endpoint: Endpoint, val health: Health) {

    /** 0 when callable right now, otherwise when it becomes callable. */
    fun heldUntil(now: Long = System.currentTimeMillis()): Long = maxOf(
        health.cooldownUntil.takeIf { it > now } ?: 0L,
        health.usage.heldUntil(endpoint.preset.rate, now)
    )

    fun status(now: Long = System.currentTimeMillis()): String = when {
        !endpoint.enabled -> "Off"
        health.brokenKey -> "Key rejected"
        else -> {
            val held = heldUntil(now)
            if (held <= 0L) "Ready" else "Resting ${humanise(held - now)}"
        }
    }

    /** What the pool knows about this endpoint's shape, for the settings list. */
    fun capabilityNote(): String? = health.capability?.let {
        if (it.tools) "tools" else "no tools"
    }

    private fun humanise(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds >= 3600 -> "${seconds / 3600}h"
            seconds >= 60 -> "${seconds / 60}m"
            else -> "${seconds}s"
        }
    }
}

/**
 * Keeps several free endpoints in rotation so one exhausted quota does not end
 * the conversation.
 *
 * Worth being precise about what this does and does not buy: free tiers limit
 * per model *and* per account. Rotating models on one key genuinely helps where
 * limits are per model, but an account-wide daily cap is only escaped by moving
 * to a different account — so when a failure looks account-wide the whole
 * account rests, rather than each of its models discovering the same cap in turn
 * and burning a request each to do it.
 *
 * The other half of the job is not hitting limits in the first place: every call
 * is counted against the provider's published free-tier rate ([RateHint]) and
 * against whatever its `x-ratelimit-*` headers report, and an endpoint that is
 * close to its ceiling simply loses its turn to one that is not.
 *
 * Persisted as JSON in preferences rather than SQLite: it is a short config
 * list, and this avoids a schema migration on an app already in someone's hands.
 */
class ModelPool(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_pool", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<PoolEntry>> = _entries.asStateFlow()

    /**
     * Accounts resting as a whole, because the limit they hit was charged to the
     * key rather than to one model. Endpoint id -> wake time keyed by account.
     */
    private val accountRests = HashMap<String, Long>(loadAccountRests())

    /** Endpoint the last successful call went through, for display. */
    private val _lastUsed = MutableStateFlow<String?>(null)
    val lastUsed: StateFlow<String?> = _lastUsed.asStateFlow()

    val isEmpty: Boolean get() = _entries.value.isEmpty()

    init {
        ensureBuiltIns()
    }

    /**
     * Puts the keyless endpoints in the pool, once.
     *
     * This is what lets Jarvis answer on the very first launch with nothing set
     * up. It happens once rather than on every start, so someone who removes
     * them has them stay removed; [restoreBuiltIns] puts them back on request.
     */
    private fun ensureBuiltIns() {
        if (prefs.getBoolean(KEY_BUILT_INS, false)) return
        restoreBuiltIns()
        prefs.edit().putBoolean(KEY_BUILT_INS, true).apply()
    }

    /** Adds whichever built-in keyless endpoints are missing. Returns how many. */
    fun restoreBuiltIns(): Int = add(
        Providers.BUILT_IN.map { (providerId, model) ->
            Endpoint(
                providerId = providerId,
                baseUrl = Providers.byId(providerId).baseUrl,
                apiKey = "",
                model = model
            )
        }
    )

    /** True when every built-in keyless endpoint is in the pool. */
    fun hasBuiltIns(): Boolean {
        val present = _entries.value.map { it.endpoint.providerId to it.endpoint.model }.toSet()
        return Providers.BUILT_IN.all { it in present }
    }

    /** True when the pool holds something besides the keyless endpoints. */
    val hasOwnEndpoints: Boolean
        get() = _entries.value.any { it.endpoint.preset.tier != Tier.Keyless }

    /** Re-reads everything from storage, for when a restored backup replaced it. */
    fun reload() {
        _entries.value = load()
        accountRests.clear()
        accountRests.putAll(loadAccountRests())
        _lastUsed.value = null
    }

    // ------------------------------------------------------------------ edits

    fun add(endpoints: List<Endpoint>): Int {
        val existing = _entries.value
        // The same model on the same key twice would just split one quota in two.
        val known = existing.map { it.endpoint.account to it.endpoint.model }.toSet()
        val fresh = endpoints
            .distinctBy { it.account to it.model }
            .filter { (it.account to it.model) !in known }
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
        accountRests.clear()
        persist()
    }

    /** Clears cooldowns so a pool can be retried immediately after fixing keys. */
    fun wakeAll() {
        accountRests.clear()
        _entries.value = _entries.value.map {
            it.copy(
                health = it.health.copy(
                    cooldownUntil = 0L,
                    consecutiveFailures = 0,
                    brokenKey = false,
                    usage = it.health.usage.copy(remaining = -1, remainingResetAt = 0L)
                )
            )
        }
        persist()
    }

    // -------------------------------------------------------------- selection

    /**
     * The order to try endpoints in for one turn, plus when to give up.
     *
     * [endpoints] holds only endpoints that are callable right now. If none are,
     * it holds the ones that free up soonest instead — a request that might be
     * refused still beats telling the user nothing — and [freeAt] says when the
     * first of them is genuinely ready, so a caller can wait out a short throttle
     * rather than spending a refusal on it.
     */
    data class Plan(
        val endpoints: List<Endpoint>,
        val freeAt: Long = 0L,
        val readyCount: Int = 0,
        val totalCount: Int = 0
    ) {
        val isEmpty: Boolean get() = endpoints.isEmpty()
    }

    fun plan(
        settings: Settings,
        limit: Int = DEFAULT_FAILOVER_LIMIT,
        now: Long = System.currentTimeMillis()
    ): Plan {
        val usable = _entries.value.filter {
            it.endpoint.enabled && !it.health.brokenKey && !accountResting(it.endpoint, now)
        }
        if (usable.isEmpty()) {
            // Nothing in the pool is callable. Fall back to whatever Settings
            // points at, so the app behaves exactly as before for anyone not
            // using a pool — and so a fully rested pool still gets one attempt.
            val fallback = settingsEndpoint(settings)
            val soonest = _entries.value
                .filter { it.endpoint.enabled && !it.health.brokenKey }
                .minOfOrNull { maxOf(it.heldUntil(now), accountRestUntil(it.endpoint)) } ?: 0L
            return Plan(
                endpoints = listOfNotNull(fallback),
                freeAt = soonest,
                readyCount = 0,
                totalCount = _entries.value.size
            )
        }

        val (ready, held) = usable.partition { it.heldUntil(now) <= 0L }

        val ordered = if (ready.isNotEmpty()) {
            ready.sortedBy { score(it, now) }.map { it.endpoint }
        } else {
            // Everything is throttled or resting: try the ones that wake first.
            held.sortedBy { it.heldUntil(now) }.map { it.endpoint }
        }

        return Plan(
            endpoints = ordered.take(limit),
            freeAt = if (ready.isNotEmpty()) 0L else held.minOf { it.heldUntil(now) },
            readyCount = ready.size,
            totalCount = usable.size
        )
    }

    private fun settingsEndpoint(settings: Settings): Endpoint? =
        if (settings.isConfigured) {
            Endpoint(
                id = SETTINGS_ID,
                providerId = settings.providerId,
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = settings.model
            )
        } else {
            null
        }

    /**
     * Lower is better. The ranking, in order of weight:
     *
     * 1. What the endpoint costs — a standing free allowance or your own
     *    hardware before signup credit, and credit before anything metered.
     * 2. How much of its allowance is left, so load lands where there is room
     *    instead of on whichever endpoint happens to sort first.
     * 3. How often it has actually answered.
     * 4. Speed, and how recently it was used, to keep the rotation moving.
     */
    private fun score(entry: PoolEntry, now: Long): Double {
        val preset = entry.endpoint.preset
        val tier = when (preset.tier) {
            Tier.Free, Tier.Local -> 0
            // Keyless answers anyone, which is exactly why a key of your own,
            // with its own quota and a bigger model behind it, goes first.
            Tier.Keyless, Tier.Trial, Tier.Custom -> 1
        }
        val headroom = entry.health.usage.headroom(preset.rate, now)
        val latency = min(entry.health.latencyMs.toDouble() / 4000.0, 1.0)
        val sinceUse = now - entry.health.lastUsedAt
        val recency = ((SPREAD_MS - sinceUse).toDouble() / SPREAD_MS).coerceIn(0.0, 1.0)

        return tier * 10.0 +
            (1.0 - headroom) * 4.0 +
            (1.0 - entry.health.reliability) * 3.0 +
            recency * 1.5 +
            latency * 1.0
    }

    // ----------------------------------------------------------------- health

    /**
     * Counts a request against the endpoint before it is sent.
     *
     * Stamping up front rather than on the way back matters: two turns fired in
     * quick succession would otherwise both see an empty window and both pick
     * the same endpoint.
     */
    fun recordAttempt(endpoint: Endpoint) {
        val now = System.currentTimeMillis()
        update(endpoint.id) { it.copy(usage = it.usage.stamped(now), lastUsedAt = now) }
    }

    fun recordSuccess(endpoint: Endpoint, reply: LlmReply) {
        _lastUsed.value = endpoint.label
        val now = System.currentTimeMillis()
        // Anything account-wide that was resting is evidently over.
        accountRests.remove(endpoint.account)
        update(endpoint.id) { health ->
            health.copy(
                cooldownUntil = 0L,
                consecutiveFailures = 0,
                lastUsedAt = now,
                successes = health.successes + 1,
                lastError = null,
                latencyMs = blend(health.latencyMs, reply.latencyMs),
                capability = reply.capability,
                usage = if (reply.rate.isEmpty) {
                    health.usage
                } else {
                    health.usage.withSignal(reply.rate, now)
                }
            )
        }
        // A provider that says "0 requests left" is telling us not to come back
        // until it refills. Believing it is cheaper than being refused.
        if (reply.rate.exhausted) {
            val until = reply.rate.resetAt(now) ?: (now + Usage.MINUTE_MS)
            rest(endpoint, until)
        }
    }

    fun recordFailure(endpoint: Endpoint, rawError: LlmException) {
        val now = System.currentTimeMillis()
        // There is no key to fix on a keyless endpoint, so a refusal there is
        // the service being unwell or busy: rest it for a while rather than
        // writing it off until someone taps Wake all.
        val error = if (endpoint.preset.tier == Tier.Keyless &&
            (rawError.kind == FailureKind.AuthFailed || rawError.kind == FailureKind.OutOfCredit)
        ) {
            LlmException(rawError.message ?: "Refused.", FailureKind.ServerError)
        } else {
            rawError
        }
        val failures = (entryFor(endpoint.id)?.health?.consecutiveFailures ?: 0) + 1
        val cooldown = cooldownFor(error, failures, now)

        update(endpoint.id) { health ->
            health.copy(
                cooldownUntil = if (cooldown > 0) maxOf(health.cooldownUntil, now + cooldown) else health.cooldownUntil,
                consecutiveFailures = failures,
                lastUsedAt = now,
                failures = health.failures + 1,
                lastError = error.message?.lineSequence()?.firstOrNull(),
                // A bad key never fixes itself, so stop spending turns on it.
                brokenKey = error.kind == FailureKind.AuthFailed ||
                    error.kind == FailureKind.OutOfCredit
            )
        }

        // An account-wide limit is not escaped by trying the next model on the
        // same key, so rest the whole account and move to a different one.
        if (error.scope == LimitScope.Account && cooldown > 0) {
            restAccount(endpoint.account, now + cooldown)
        }
        if (error.kind == FailureKind.AuthFailed || error.kind == FailureKind.OutOfCredit) {
            markAccountBroken(endpoint.account)
        }
    }

    private fun cooldownFor(error: LlmException, failures: Int, now: Long): Long = when (error.kind) {
        FailureKind.RateLimited -> {
            val stated = error.retryAfterSeconds?.times(1000)
            when {
                // Obey a stated wait exactly; providers know their own clocks.
                stated != null && stated > 0 -> stated
                // A daily cap only clears at midnight UTC. Backing off in
                // minutes just spends more requests discovering that again.
                error.daily -> Usage.nextMidnight(now) - now
                else -> jitter(BACKOFF_MS[failures.coerceAtMost(BACKOFF_MS.lastIndex)])
            }
        }

        // Somebody else's outage, or ours: back off, but come back.
        FailureKind.ServerError, FailureKind.Network ->
            jitter(TRANSIENT_BACKOFF_MS[failures.coerceAtMost(TRANSIENT_BACKOFF_MS.lastIndex)])

        // A retired model id will still be retired in an hour.
        FailureKind.ModelMissing -> 6 * 60 * 60_000L

        // Marked broken instead; no cooldown needed.
        FailureKind.AuthFailed, FailureKind.OutOfCredit -> 0L

        FailureKind.PayloadRejected -> 30 * 60_000L

        FailureKind.Unknown -> jitter(TRANSIENT_BACKOFF_MS[failures.coerceAtMost(TRANSIENT_BACKOFF_MS.lastIndex)])
    }

    /**
     * Spreads retries out a little. Without it, a pool that all failed at once
     * wakes up at once and hits the same wall together.
     */
    private fun jitter(base: Long): Long = base + (0..(base / 5).coerceAtLeast(1)).random()

    private fun blend(previous: Long, sample: Long): Long = when {
        sample <= 0L -> previous
        previous <= 0L -> sample
        // Weighted towards history so one slow answer does not demote an
        // endpoint that is usually quick.
        else -> (previous * 3 + sample) / 4
    }

    private fun rest(endpoint: Endpoint, until: Long) {
        update(endpoint.id) { it.copy(cooldownUntil = maxOf(it.cooldownUntil, until)) }
    }

    private fun restAccount(account: String, until: Long) {
        accountRests[account] = maxOf(accountRests[account] ?: 0L, until)
        persistAccountRests()
    }

    private fun markAccountBroken(account: String) {
        // One rejected key rejects every model behind it, so do not spend a
        // request per model discovering that.
        _entries.value = _entries.value.map {
            if (it.endpoint.account == account) it.copy(health = it.health.copy(brokenKey = true)) else it
        }
        persist()
    }

    private fun accountResting(endpoint: Endpoint, now: Long) = accountRestUntil(endpoint) > now

    private fun accountRestUntil(endpoint: Endpoint) = accountRests[endpoint.account] ?: 0L

    private fun entryFor(id: String) = _entries.value.firstOrNull { it.endpoint.id == id }

    private fun update(id: String, transform: (Health) -> Health) {
        if (id == SETTINGS_ID) return
        var changed = false
        val next = _entries.value.map {
            if (it.endpoint.id == id) {
                changed = true
                it.copy(health = transform(it.health))
            } else {
                it
            }
        }
        if (!changed) return
        _entries.value = next
        persist()
    }

    // --------------------------------------------------------------- summary

    /** One line for the settings screen: how much of the pool is actually usable. */
    fun summary(now: Long = System.currentTimeMillis()): String {
        val all = _entries.value
        if (all.isEmpty()) return "Pool is empty."
        val enabled = all.filter { it.endpoint.enabled }
        val broken = enabled.count { it.health.brokenKey }
        val ready = enabled.count {
            !it.health.brokenKey && !accountResting(it.endpoint, now) && it.heldUntil(now) <= 0L
        }
        val accounts = enabled.map { it.endpoint.account }.distinct().size
        return buildString {
            append("$ready of ${enabled.size} ready")
            append(" across $accounts account${if (accounts == 1) "" else "s"}")
            if (broken > 0) append(" · $broken need a new key")
        }
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
                    put("latencyMs", entry.health.latencyMs)
                    put("recent", entry.health.usage.toJson())
                    put("dayKey", entry.health.usage.dayKey)
                    put("dayCount", entry.health.usage.dayCount)
                    put("remaining", entry.health.usage.remaining)
                    put("remainingResetAt", entry.health.usage.remainingResetAt)
                    entry.health.capability?.let { capability ->
                        put("capTools", capability.tools)
                        put("capSampling", capability.sampling)
                        put("capTokenParam", capability.tokenParam)
                    }
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
                    brokenKey = obj.optBoolean("brokenKey", false),
                    latencyMs = obj.optLong("latencyMs"),
                    usage = Usage.fromJson(
                        array = obj.optJSONArray("recent"),
                        dayKey = obj.optLong("dayKey"),
                        dayCount = obj.optInt("dayCount"),
                        remaining = obj.optInt("remaining", -1),
                        resetAt = obj.optLong("remainingResetAt")
                    ),
                    capability = if (obj.has("capTools")) {
                        Capability(
                            tools = obj.optBoolean("capTools", true),
                            sampling = obj.optBoolean("capSampling", true),
                            tokenParam = obj.optString("capTokenParam")
                                .takeIf { it.isNotBlank() && it != "null" }
                        )
                    } else {
                        null
                    }
                )
            )
        }
    }

    private fun persistAccountRests() {
        val obj = JSONObject()
        val now = System.currentTimeMillis()
        accountRests.entries.removeAll { it.value <= now }
        accountRests.forEach { (account, until) -> obj.put(account, until) }
        prefs.edit().putString(KEY_ACCOUNT_RESTS, obj.toString()).apply()
    }

    private fun loadAccountRests(): Map<String, Long> {
        val raw = prefs.getString(KEY_ACCOUNT_RESTS, null) ?: return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val now = System.currentTimeMillis()
        return obj.keys().asSequence()
            .mapNotNull { key -> obj.optLong(key).takeIf { it > now }?.let { key to it } }
            .toMap()
    }

    private companion object {
        const val KEY_POOL = "pool"
        const val KEY_ACCOUNT_RESTS = "account_rests"
        const val KEY_BUILT_INS = "built_ins_v1"
        const val SETTINGS_ID = "settings"

        /**
         * How many endpoints one turn may walk. Beyond this the user is waiting
         * on a queue of doomed requests, each of which still costs quota.
         */
        const val DEFAULT_FAILOVER_LIMIT = 5

        /** Requests inside this window count as "recently used" for spreading. */
        const val SPREAD_MS = 90_000L

        /** 1m, 1m, 5m, 15m, 1h, 3h — indexed by consecutive failure count. */
        val BACKOFF_MS = longArrayOf(
            60_000L, 60_000L, 300_000L, 900_000L, 3_600_000L, 10_800_000L
        )

        /** Outages and dropped connections come back sooner than quotas do. */
        val TRANSIENT_BACKOFF_MS = longArrayOf(
            15_000L, 30_000L, 60_000L, 300_000L, 900_000L, 1_800_000L
        )
    }
}
