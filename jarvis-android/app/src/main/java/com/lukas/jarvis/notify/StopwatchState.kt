package com.lukas.jarvis.notify

/**
 * A stopwatch as numbers: when it was last started, what it had counted
 * before that, and the laps. Running while [startedAt] is set.
 */
data class StopwatchState(
    val startedAt: Long? = null,
    val bankedMs: Long = 0,
    /** Total time at each lap, oldest first. */
    val laps: List<Long> = emptyList()
) {
    val running: Boolean get() = startedAt != null
    val idle: Boolean get() = startedAt == null && bankedMs == 0L

    fun elapsed(now: Long): Long = bankedMs + (startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0)

    /** Starts from zero, or carries on from a pause. */
    fun start(now: Long): StopwatchState = if (running) this else copy(startedAt = now)

    fun pause(now: Long): StopwatchState =
        if (!running) this else StopwatchState(null, elapsed(now), laps)

    fun lap(now: Long): StopwatchState = if (idle) this else copy(laps = laps + elapsed(now))

    fun reset(): StopwatchState = StopwatchState()

    /** How long each lap itself took. */
    fun lapLengths(): List<Long> = laps.mapIndexed { i, total -> total - (laps.getOrNull(i - 1) ?: 0) }

    companion object {
        /** "1:02:03.4", "4:05.2", "0:09.8": clock-style, to a tenth. */
        fun clock(ms: Long): String {
            val tenths = (ms.coerceAtLeast(0) / 100)
            val t = tenths % 10
            val totalSeconds = tenths / 10
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return if (h > 0) "%d:%02d:%02d.%d".format(h, m, s, t) else "%d:%02d.%d".format(m, s, t)
        }
    }
}
