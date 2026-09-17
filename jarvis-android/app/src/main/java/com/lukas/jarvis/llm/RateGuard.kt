package com.lukas.jarvis.llm

import org.json.JSONArray

/**
 * Client-side accounting of how hard an endpoint has been hit lately.
 *
 * The point is to stop *before* the provider does. A 429 is not free: it costs
 * a round trip, it usually counts against the same quota that just ran out, and
 * on several providers repeated offences lengthen the ban. Tracking our own
 * calls means Jarvis can rotate one request early instead of one request late.
 *
 * Only request counts are tracked, not tokens. Token ceilings vary too much per
 * model to guess, and the `x-ratelimit-*` headers report the real ones anyway
 * (see [RateSignal]), which is what [ModelPool] leans on when they show up.
 */
data class Usage(
    /** Start times of recent requests, oldest first, trimmed to the last minute. */
    val recent: List<Long> = emptyList(),
    /** UTC day these counts belong to; a different day means they are stale. */
    val dayKey: Long = 0L,
    val dayCount: Int = 0,
    /**
     * Requests the provider itself says are left, from response headers. -1 when
     * the provider does not publish them, which is the common case.
     */
    val remaining: Int = -1,
    /** When [remaining] refills, if the provider said. */
    val remainingResetAt: Long = 0L
) {

    /** Records that a request is being sent right now. */
    fun stamped(now: Long): Usage {
        val day = dayOf(now)
        return copy(
            recent = (recent + now).filter { now - it < MINUTE_MS }.takeLast(MAX_STAMPS),
            dayKey = day,
            dayCount = if (day == dayKey) dayCount + 1 else 1,
            remaining = if (remaining > 0) remaining - 1 else remaining
        )
    }

    fun withSignal(signal: RateSignal, now: Long): Usage = copy(
        remaining = signal.remainingRequests ?: remaining,
        remainingResetAt = signal.resetAt(now) ?: remainingResetAt
    )

    fun inLastMinute(now: Long): Int = recent.count { now - it < MINUTE_MS }

    fun today(now: Long): Int = if (dayOf(now) == dayKey) dayCount else 0

    /**
     * 0 when the endpoint can be called now, otherwise when it next can be.
     *
     * A minute ceiling clears as the oldest call ages out of the window; a daily
     * one only clears at UTC midnight, which is when every provider that counts
     * per day rolls over.
     */
    fun heldUntil(hint: RateHint, now: Long): Long {
        if (remaining == 0 && remainingResetAt > now) return remainingResetAt

        if (hint.requestsPerMinute > 0) {
            val window = recent.filter { now - it < MINUTE_MS }
            if (window.size >= hint.requestsPerMinute) {
                return window.first() + MINUTE_MS
            }
        }
        if (hint.requestsPerDay > 0 && today(now) >= hint.requestsPerDay) {
            return nextMidnight(now)
        }
        return 0L
    }

    /**
     * How much of the allowance is still unspent, 0..1. Used to spread load onto
     * whichever endpoint has the most room rather than hammering the first one
     * that happens to be ready.
     */
    fun headroom(hint: RateHint, now: Long): Double {
        val perMinute = if (hint.requestsPerMinute > 0) {
            1.0 - inLastMinute(now).toDouble() / hint.requestsPerMinute
        } else {
            1.0
        }
        val perDay = if (hint.requestsPerDay > 0) {
            1.0 - today(now).toDouble() / hint.requestsPerDay
        } else {
            1.0
        }
        val published = if (remaining >= 0 && remainingResetAt > now) {
            (remaining.toDouble() / 10.0).coerceAtMost(1.0)
        } else {
            1.0
        }
        return minOf(perMinute, perDay, published).coerceIn(0.0, 1.0)
    }

    fun toJson(): JSONArray = JSONArray().also { arr -> recent.forEach { arr.put(it) } }

    companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L

        /** A minute of requests at any sane rate fits well inside this. */
        private const val MAX_STAMPS = 120

        fun dayOf(now: Long): Long = now / DAY_MS

        fun nextMidnight(now: Long): Long = (dayOf(now) + 1) * DAY_MS

        fun fromJson(array: JSONArray?, dayKey: Long, dayCount: Int, remaining: Int, resetAt: Long): Usage {
            val stamps = if (array == null) {
                emptyList()
            } else {
                (0 until array.length()).map { array.optLong(it) }.filter { it > 0 }
            }
            return Usage(stamps, dayKey, dayCount, remaining, resetAt)
        }
    }
}

/**
 * The `x-ratelimit-*` family, as reported by whichever provider just answered.
 *
 * Nobody agrees on the format: Groq and OpenAI send durations like `7.66s` or
 * `2m59.56s`, OpenRouter sends a millisecond epoch, and others send plain
 * seconds. All three shapes are accepted, and anything unrecognised is dropped
 * rather than guessed at — a wrong reset time is worse than none, because it
 * makes the pool rest an endpoint that was actually fine.
 */
data class RateSignal(
    val remainingRequests: Int? = null,
    val remainingTokens: Int? = null,
    val resetSeconds: Double? = null,
    val retryAfterSeconds: Long? = null
) {
    val isEmpty: Boolean
        get() = remainingRequests == null && remainingTokens == null &&
            resetSeconds == null && retryAfterSeconds == null

    fun resetAt(now: Long): Long? {
        resetSeconds?.let { return now + (it * 1000).toLong() }
        retryAfterSeconds?.let { return now + it * 1000 }
        return null
    }

    /** True when the provider says this key has nothing left right now. */
    val exhausted: Boolean get() = remainingRequests == 0 || remainingTokens == 0

    companion object {

        fun from(header: (String) -> String?): RateSignal {
            val reset = duration(header("x-ratelimit-reset-requests"))
                ?: duration(header("x-ratelimit-reset-tokens"))
                ?: duration(header("x-ratelimit-reset"))
                ?: duration(header("x-ratelimit-reset-after"))
            return RateSignal(
                remainingRequests = header("x-ratelimit-remaining-requests")?.trim()?.toIntOrNull()
                    ?: header("x-ratelimit-remaining")?.trim()?.toIntOrNull(),
                remainingTokens = header("x-ratelimit-remaining-tokens")?.trim()?.toIntOrNull(),
                resetSeconds = reset,
                retryAfterSeconds = retryAfter(header("retry-after"))
            )
        }

        /** `30`, `7.66s`, `2m59.56s`, `1h2m`, or a millisecond epoch — to seconds. */
        fun duration(raw: String?): Double? {
            val text = raw?.trim()?.lowercase() ?: return null
            if (text.isEmpty()) return null

            // A bare number is either seconds or, if implausibly large, an epoch.
            text.toDoubleOrNull()?.let { value ->
                val nowSeconds = System.currentTimeMillis() / 1000.0
                return when {
                    // Millisecond epoch (OpenRouter).
                    value > nowSeconds * 500 -> (value / 1000.0 - nowSeconds).coerceAtLeast(0.0)
                    // Second epoch.
                    value > nowSeconds / 2 -> (value - nowSeconds).coerceAtLeast(0.0)
                    else -> value.coerceAtLeast(0.0)
                }
            }

            val match = UNITS.findAll(text).toList()
            if (match.isEmpty()) return null
            var total = 0.0
            for (part in match) {
                val amount = part.groupValues[1].toDoubleOrNull() ?: return null
                total += when (part.groupValues[2]) {
                    "h" -> amount * 3600
                    "m" -> amount * 60
                    "s" -> amount
                    "ms" -> amount / 1000
                    else -> return null
                }
            }
            return total
        }

        /** Retry-After is either seconds or an HTTP date. */
        fun retryAfter(raw: String?): Long? {
            val text = raw?.trim() ?: return null
            text.toLongOrNull()?.let { return it.coerceAtLeast(0) }
            val millis = runCatching {
                java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
                    .parse(text)?.time
            }.getOrNull() ?: return null
            return ((millis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }

        private val UNITS = Regex("([0-9]*\\.?[0-9]+)(ms|h|m|s)")
    }
}
