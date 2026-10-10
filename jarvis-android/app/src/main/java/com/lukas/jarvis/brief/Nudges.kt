package com.lukas.jarvis.brief

import com.lukas.jarvis.control.Appointment
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Money
import com.lukas.jarvis.data.Streak
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** One small, useful thing worth saying unasked, and what a tap on it asks. */
data class Nudge(
    val kind: String,
    /** What makes it the same nudge again, so nothing is said twice. */
    val key: String,
    val title: String,
    val text: String,
    /** The sentence a tap puts to Mochi, or null to just open the app. */
    val ask: String? = null
)

/**
 * Proactive nudges: a few times a day, at most one useful thing, from what the
 * phone already knows, never at night, never the same thing twice, and no more
 * than [MOST_A_DAY] in a day. Off unless switched on, and each kind has its own
 * switch.
 *
 * Every rule is here, as plain values in and at most one nudge out, so what
 * Mochi would say and when it would keep quiet can be tested without a phone.
 */
object Nudges {
    const val EVENT = "event"
    const val RAIN = "rain"
    const val BUDGET = "budget"
    const val HABIT = "habit"
    const val OPEN = "open"

    /** Most useful first: the one that is about to matter wins. */
    val ALL = listOf(EVENT, RAIN, BUDGET, HABIT, OPEN)

    const val MOST_A_DAY = 3
    private const val QUIET_FROM = 22
    private const val QUIET_UNTIL = 8

    fun label(kind: String): String = when (kind) {
        EVENT -> "Before an appointment somewhere"
        RAIN -> "Rain on the way"
        BUDGET -> "A budget nearly gone"
        HABIT -> "A habit's run about to end"
        OPEN -> "What's still open in the evening"
        else -> kind
    }

    fun detail(kind: String): String = when (kind) {
        EVENT -> "Within the hour and a half before, with the way there one tap away"
        RAIN -> "When rain starts in the next few hours and it is dry now"
        BUDGET -> "Once a period, at nine tenths, with days still to go"
        HABIT -> "After seven in the evening, for a run of three days or more"
        OPEN -> "After six in the evening, what was due today and is not done"
        else -> ""
    }

    /** "rain,event" -> the kinds switched on, in their order; blank is all of them. */
    fun kinds(raw: String): Set<String> =
        raw.split(',').map { it.trim() }.filter { it in ALL }.toSet().ifEmpty { ALL.toSet() }

    data class Inputs(
        val now: Long,
        val zone: ZoneId = ZoneId.systemDefault(),
        /** "15:00" when rain becomes likely in the next twelve hours. */
        val rainFrom: String? = null,
        val rainingNow: Boolean = false,
        val appointments: List<Appointment> = emptyList(),
        val trackers: List<TrackerStatus> = emptyList(),
        /** Each habit's label and how it is going. */
        val habits: List<Pair<Tracker, Streak>> = emptyList(),
        /** Open tasks due today. */
        val dueToday: List<Task> = emptyList(),
        /** Every nudge key already said, with when. */
        val said: Map<String, Long> = emptyMap()
    )

    /** At most one nudge, the most useful one, or null when nothing is worth a word. */
    fun pick(inputs: Inputs, kinds: Set<String>): Nudge? {
        val time = Instant.ofEpochMilli(inputs.now).atZone(inputs.zone)
        val hour = time.hour
        if (hour >= QUIET_FROM || hour < QUIET_UNTIL) return null
        val startOfDay = time.toLocalDate().atStartOfDay(inputs.zone).toInstant().toEpochMilli()
        if (inputs.said.values.count { it >= startOfDay } >= MOST_A_DAY) return null
        val day = time.toLocalDate().toString()

        val candidates = sequence {
            if (EVENT in kinds) {
                inputs.appointments
                    .filter { !it.allDay && it.location != null && it.startsAt - inputs.now in 30 * 60_000L..90 * 60_000L }
                    .forEach { event ->
                        yield(
                            Nudge(
                                EVENT,
                                "event:${event.title}:${event.startsAt}",
                                "${event.title} at ${TimeUtil.formatTime(event.startsAt)}",
                                "At ${event.location}. Want the way there?",
                                ask = "How do I get to ${event.location}?"
                            )
                        )
                    }
            }
            if (RAIN in kinds && !inputs.rainingNow && hour < 20) {
                inputs.rainFrom?.let { at -> runCatching { LocalTime.parse(at) }.getOrNull() }
                    ?.takeIf { it.isAfter(time.toLocalTime()) && it.hour - hour <= 3 }
                    ?.let { at ->
                        yield(Nudge(RAIN, "rain:$day", "Rain from about $at", "Worth an umbrella if you're heading out.", ask = "Will it rain today?"))
                    }
            }
            if (BUDGET in kinds) {
                inputs.trackers.forEach { status ->
                    val t = status.tracker
                    val budget = t.budget?.takeIf { it > 0 } ?: return@forEach
                    val window = Money.window(t.period, inputs.now, inputs.zone) ?: return@forEach
                    val share = status.periodSpent / budget
                    val left = Money.daysLeft(window, inputs.now)
                    if (share >= 0.9 && share <= 1.0 && left >= 2) {
                        yield(
                            Nudge(
                                BUDGET,
                                "budget:${t.id}:${window.start}",
                                "${t.label}: ${(share * 100).roundToInt()}% of the budget",
                                "With $left days of ${Tracker.thisPeriod(t.period)} to go.",
                                ask = "Where did my money go this month?"
                            )
                        )
                    }
                }
            }
            if (HABIT in kinds && hour >= 19) {
                inputs.habits.filter { (_, streak) -> streak.current >= 3 && !streak.today }.forEach { (t, streak) ->
                    yield(
                        Nudge(
                            HABIT,
                            "habit:${t.id}:$day",
                            "Your ${streak.current}-day ${t.label} run",
                            "It ends tonight unless today counts too.",
                            ask = null
                        )
                    )
                }
            }
            if (OPEN in kinds && hour >= 18 && inputs.dueToday.isNotEmpty()) {
                val names = inputs.dueToday.take(3).joinToString(", ") { it.title }
                val count = inputs.dueToday.size
                yield(
                    Nudge(
                        OPEN,
                        "open:$day",
                        if (count == 1) "One thing still open today" else "$count things still open today",
                        names + if (count > 3) ", and more" else "",
                        ask = "What's still open?"
                    )
                )
            }
        }
        return candidates.firstOrNull { it.key !in inputs.said }
    }

    /** The said-log kept to five weeks, which covers a monthly budget's "once a period". */
    fun prune(said: Map<String, Long>, now: Long): Map<String, Long> =
        said.filterValues { now - it < 35L * 86_400_000L }
}
