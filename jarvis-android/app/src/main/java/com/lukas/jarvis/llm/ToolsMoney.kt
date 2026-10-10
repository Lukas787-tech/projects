package com.lukas.jarvis.llm

import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.auto.Routines
import com.lukas.jarvis.brief.Briefer
import com.lukas.jarvis.control.Agenda
import com.lukas.jarvis.control.Caller
import com.lukas.jarvis.control.Chat
import com.lukas.jarvis.control.Chats
import com.lukas.jarvis.control.Device
import com.lukas.jarvis.control.Launcher
import com.lukas.jarvis.control.Messenger
import com.lukas.jarvis.control.People
import com.lukas.jarvis.control.Phone
import com.lukas.jarvis.control.ScreenReader
import com.lukas.jarvis.core.Calculator
import com.lukas.jarvis.core.DateMath
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.core.Units
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.data.Money
import com.lukas.jarvis.auto.Trigger
import com.lukas.jarvis.data.Recurring
import com.lukas.jarvis.moment.Bar
import com.lukas.jarvis.moment.Chart
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.maps.Locator
import com.lukas.jarvis.maps.Navigator
import com.lukas.jarvis.maps.PlacesClient
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.stage.StageStore
import com.lukas.jarvis.notify.ReplyListener
import com.lukas.jarvis.notify.Reminders
import com.lukas.jarvis.web.Currency
import com.lukas.jarvis.web.Home
import com.lukas.jarvis.web.Imagine
import com.lukas.jarvis.web.Knowledge
import com.lukas.jarvis.web.Weather
import com.lukas.jarvis.vision.CameraBus
import com.lukas.jarvis.web.WebTools
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Money and anything else counted: entries, budgets, reports, what repeats.

internal fun Tools.trackerTools(settings: Settings): List<JSONObject> = listOf(
    tool(
        "log_entry",
        "Record one movement on a tracker: money spent or received, calories eaten, " +
            "kilometres run, hours worked. Creates the tracker automatically if it is new. " +
            "Use this every time the user mentions buying something or doing a countable thing.",
        props(
            "tracker" to str("Short name of the thing being tracked, e.g. 'groceries', 'card', 'calories'."),
            "amount" to num("How much. Always positive."),
            "direction" to str("'out' for spending/using, 'in' for income/adding.", listOf("out", "in")),
            "note" to str("What it was, e.g. 'chips'."),
            "unit" to str("Currency code or unit, e.g. 'EUR', 'kcal', 'km'. Defaults to ${settings.defaultCurrency}."),
            "kind" to str("What sort of quantity this is.", Tracker.ALL_KINDS),
            "occurred_at" to str("ISO time if it did not happen just now, e.g. '2026-09-15T18:30'.")
        ),
        listOf("tracker", "amount")
    ),
    tool(
        "tracker_status",
        "Read current totals: money left on a card, budget remaining this period, " +
            "calories today. Call this whenever the user asks how much of anything is left.",
        props("tracker" to str("Name of one tracker, or omit for all of them.")),
        emptyList()
    ),
    tool(
        "configure_tracker",
        "Create a tracker or change its budget, starting balance, reset period or unit. " +
            "Use when the user says things like 'my card has 50 euros on it' or " +
            "'my food budget is 200 a month'.",
        props(
            "tracker" to str("Short name."),
            "label" to str("Nicer display name."),
            "kind" to str("What sort of quantity.", Tracker.ALL_KINDS),
            "unit" to str("Currency code or unit."),
            "budget" to num("Spending cap per period. Omit to leave unchanged."),
            "starting_balance" to num("Starting amount, for card or wallet style balances."),
            "period" to str("When the budget resets.", Tracker.ALL_PERIODS)
        ),
        listOf("tracker")
    ),
    tool(
        "list_entries",
        "List recent movements on a tracker, newest first, with their ids.",
        props(
            "tracker" to str("Name of the tracker, or omit for everything."),
            "limit" to int("How many. Default 10.")
        ),
        emptyList()
    ),
    tool(
        "spending_report",
        "Add up a period and break it down: totals per tracker, the biggest single items, " +
            "and the daily average. Use for 'where did my money go', 'how much did I spend " +
            "this week', 'what am I averaging'.",
        props(
            "days" to int("How far back to look. Default 30."),
            "this_month" to bool("True for 'this month so far', set against the same days of last month."),
            "tracker" to str("Restrict to one tracker, or omit for all of them.")
        ),
        emptyList()
    ),
    tool(
        "repeat_entry",
        "Something logged by itself on a schedule: rent, a subscription, a salary, pocket " +
            "money. Use for 'my rent is 800 every month on the 1st', 'Netflix 12.99 monthly', " +
            "'I get paid 2400 on the 25th'. action 'add' sets one up; 'list' says what repeats.",
        props(
            "action" to str("What to do. Default 'add'.", listOf("add", "list")),
            "tracker" to str("Which tracker it goes on, e.g. 'rent', 'subscriptions', 'salary'."),
            "amount" to num("How much each time. Always positive."),
            "every" to str("How often.", listOf(Recurring.EVERY_DAY, Recurring.EVERY_WEEK, Recurring.EVERY_MONTH, Recurring.EVERY_YEAR)),
            "day" to str("The day it falls due: a day of the month like '1' or '25', or a weekday like 'friday'."),
            "starting" to str("ISO date of the first one, when it is not simply the next such day."),
            "direction" to str("'out' for spending, 'in' for income.", listOf("out", "in")),
            "note" to str("What it is, e.g. 'Netflix'."),
            "unit" to str("Currency code. Defaults to ${settings.defaultCurrency}.")
        ),
        emptyList()
    ),
    tool(
        "stop_repeat",
        "Stop something that repeats, so it is no longer logged by itself. What it already " +
            "logged stays on the tracker.",
        props(
            "id" to int("The id repeat_entry's list shows."),
            "tracker" to str("Or the tracker it goes on."),
            "note" to str("Or what it is, e.g. 'Netflix'.")
        ),
        emptyList()
    ),
    tool(
        "delete_entry",
        "Take a logged movement back off its tracker: 'undo that', 'I did not actually " +
            "buy it', 'that was logged twice'. With no id, the most recent entry is removed.",
        props("id" to int("The entry id shown by list_entries. Omit for the latest entry.")),
        emptyList()
    )
)

internal fun Tools.logEntry(args: JSONObject, settings: Settings, effects: ToolEffects, callId: String = ""): String {
    val name = args.optString("tracker").trim()
    if (name.isBlank()) return "Need a tracker name."
    val amount = number(args, "amount") ?: Double.NaN
    if (amount.isNaN()) return "Need a numeric amount."

    val requestedUnit = args.optString("unit").trim()
    val requestedKind = args.optString("kind").trim()
    val tracker = brain.findTracker(name) ?: brain.upsertTracker(
        Tracker(
            name = name,
            label = name.replaceFirstChar { it.titlecase(Locale.getDefault()) },
            kind = requestedKind.ifBlank { inferKind(requestedUnit, settings) },
            unit = requestedUnit.ifBlank { settings.defaultCurrency },
            period = Tracker.PERIOD_MONTHLY
        )
    )

    val direction = args.optString("direction").ifBlank { Entry.DIR_OUT }
        .let { if (it == Entry.DIR_IN) Entry.DIR_IN else Entry.DIR_OUT }
    val before = brain.trackerStatus(tracker).periodSpent

    brain.addEntry(
        Entry(
            trackerId = tracker.id,
            amount = abs(amount),
            direction = direction,
            note = args.optString("note").takeIf { it.isNotBlank() },
            category = args.optString("category").takeIf { it.isNotBlank() },
            occurredAt = TimeUtil.parse(args.optString("occurred_at").takeIf { it.isNotBlank() })
                ?: System.currentTimeMillis()
        )
    )
    effects.trackersChanged = true

    val status = brain.trackerStatus(brain.findTracker(name) ?: tracker)
    val verb = if (direction == Entry.DIR_OUT) "Logged" else "Added"
    val now = System.currentTimeMillis()
    // A habit's run is the news: "That's 5 days in a row."
    val run = if (com.lukas.jarvis.data.Streaks.isHabit(tracker)) {
        val days = com.lukas.jarvis.data.Streaks.days(brain.entriesBetween(now - 400L * 86_400_000L, now, tracker.id))
        com.lukas.jarvis.data.Streaks.of(days, java.time.LocalDate.now())
    } else null
    val streak = run?.let { com.lukas.jarvis.data.Streaks.describe(it) }
        ?.takeIf { it.isNotEmpty() }?.let { " That's $it." }.orEmpty()
    // A budget crossing four fifths or all of itself is said once, as it happens.
    val news = budgetNews(status, before, now)?.let { " $it" }.orEmpty()
    val chart = when {
        run != null -> Chart(week = run.week, caption = com.lukas.jarvis.data.Streaks.describe(run).replaceFirstChar { it.titlecase(Locale.getDefault()) })
        tracker.budget != null -> Chart(bars = statusBars(listOf(status)))
        else -> null
    }
    if (chart != null && callId.isNotEmpty()) effects.charts[callId] = chart
    return "$verb ${money(abs(amount), tracker)} on ${tracker.label}.$streak$news ${summaryLine(status)}"
}

/** "Heads up: that's 84% of this month's budget, with 9 days to go", or where the pace leads. */
internal fun Tools.budgetNews(status: TrackerStatus, before: Double, now: Long): String? {
    val t = status.tracker
    val budget = t.budget ?: return null
    val window = Money.window(t.period, now)
    Money.budgetNews(budget, before, status.periodSpent, t.period, window, now) { money(it, t) }?.let { return it }
    val w = window ?: return null
    val heading = Money.pace(status.periodSpent, budget, w, now) ?: return null
    // The pace is mentioned when this entry is what tipped it, not on every one after.
    if (Money.pace(before, budget, w, now) != null) return null
    return "At this pace it'll be about ${money(heading, t)} by the end of ${Tracker.thisPeriod(t.period)}, over the ${money(budget, t)} budget."
}

/** One row per money tracker: a meter against its budget, or what went out this period. */
internal fun Tools.statusBars(statuses: List<TrackerStatus>): List<Bar> = statuses
    .filter { it.tracker.budget != null || it.tracker.kind == Tracker.KIND_MONEY }
    .map { s ->
        val t = s.tracker
        Bar(
            label = t.label,
            value = s.periodSpent,
            shown = money(s.periodSpent, t),
            limit = t.budget,
            note = t.budget?.let { "of ${money(it, t)} ${Tracker.thisPeriod(t.period)}" } ?: Tracker.thisPeriod(t.period)
        )
    }

internal fun Tools.trackerStatus(args: JSONObject, effects: ToolEffects, callId: String = ""): String {
    val name = args.optString("tracker").trim()
    val chosen = if (name.isNotBlank()) {
        val tracker = brain.findTracker(name)
            ?: return "No tracker called '$name' yet. Create one with configure_tracker " +
                "or just log something to it."
        listOf(brain.trackerStatus(tracker))
    } else {
        brain.allTrackerStatus().ifEmpty { return "No trackers set up yet." }
    }
    val bars = statusBars(chosen)
    if (bars.isNotEmpty() && callId.isNotEmpty()) effects.charts[callId] = Chart(bars = bars)
    val now = System.currentTimeMillis()
    return chosen.joinToString("\n") { status ->
        val heading = status.tracker.budget?.let { budget ->
            Money.window(status.tracker.period, now)?.let { Money.pace(status.periodSpent, budget, it, now) }
        }
        summaryLine(status) + (heading?.let { ", heading for about ${money(it, status.tracker)} at this pace" } ?: "")
    }
}

internal fun Tools.configureTracker(
    args: JSONObject,
    settings: Settings,
    effects: ToolEffects
): String {
    val name = args.optString("tracker").trim()
    if (name.isBlank()) return "Need a tracker name."
    val existing = brain.findTracker(name)
    val unit = args.optString("unit").trim()
        .ifBlank { existing?.unit ?: settings.defaultCurrency }

    val updated = brain.upsertTracker(
        Tracker(
            id = existing?.id ?: 0,
            name = name,
            label = args.optString("label").trim().ifBlank {
                existing?.label ?: name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            },
            kind = args.optString("kind").trim()
                .ifBlank { existing?.kind ?: inferKind(unit, settings) },
            unit = unit,
            budget = optDoubleOrNull(args, "budget") ?: existing?.budget,
            period = args.optString("period").trim()
                .ifBlank { existing?.period ?: Tracker.PERIOD_MONTHLY },
            startingBalance = optDoubleOrNull(args, "starting_balance")
                ?: existing?.startingBalance
        )
    )
    effects.trackersChanged = true
    return "Tracker ready. " + summaryLine(brain.trackerStatus(updated))
}

internal fun Tools.listEntries(args: JSONObject): String {
    val name = args.optString("tracker").trim()
    val limit = args.optInt("limit", 10).coerceIn(1, 50)
    val tracker = if (name.isBlank()) null else brain.findTracker(name)
    if (name.isNotBlank() && tracker == null) return "No tracker called '$name'."

    val entries = brain.recentEntries(tracker?.id, limit)
    if (entries.isEmpty()) return "No entries recorded yet."
    val byId = brain.allTrackers().associateBy { it.id }
    return entries.joinToString("\n") { e ->
        val owner = byId[e.trackerId]
        val sign = if (e.direction == Entry.DIR_OUT) "-" else "+"
        "[id ${e.id}] $sign${money(e.amount, owner)} ${owner?.label ?: "?"}" +
            (e.note?.let { " ($it)" } ?: "") +
            " — ${TimeUtil.relative(e.occurredAt)}"
    }
}

/**
 * Undo, for the tracker.
 *
 * Speech recognition mishears amounts and small models sometimes log the
 * same purchase twice, so a balance that can only ever grow wrong is one
 * nobody trusts. With no id the newest entry goes, because "undo that" is
 * nearly always about the last thing said.
 */
internal fun Tools.deleteEntry(args: JSONObject, effects: ToolEffects): String {
    val requested = args.optLong("id", -1L)
    val entry = if (requested > 0) {
        brain.recentEntries(null, 200).firstOrNull { it.id == requested }
            ?: return "No entry with id $requested."
    } else {
        brain.recentEntries(null, 1).firstOrNull() ?: return "There are no entries to undo."
    }
    val tracker = brain.allTrackers().firstOrNull { it.id == entry.trackerId }
    if (!brain.deleteEntry(entry.id)) return "That entry would not delete."
    effects.trackersChanged = true

    val what = entry.note?.let { " for $it" }.orEmpty()
    val head = "Removed ${money(entry.amount, tracker)}$what from ${tracker?.label ?: "its tracker"}."
    val status = tracker?.let { brain.trackerStatus(it) } ?: return head
    return "$head ${summaryLine(status)}"
}

/**
 * The arithmetic done here rather than in the model.
 *
 * "Where did my money go" is the question most likely to be answered with a
 * plausible invention, so every number in this reply is summed from the rows
 * and the model is left with nothing to do but read it out.
 */
internal fun Tools.spendingReport(args: JSONObject, effects: ToolEffects = ToolEffects(), callId: String = ""): String {
    val days = args.optInt("days", 30).coerceIn(1, 365)
    val name = args.optString("tracker").trim()
    val only = if (name.isBlank()) null else brain.findTracker(name)
    if (name.isNotBlank() && only == null) return "No tracker called '$name'."
    if (args.optBoolean("this_month")) return monthReport(only, effects, callId)

    val now = System.currentTimeMillis()
    val from = now - days * 86_400_000L
    val entries = brain.entriesBetween(from, now, only?.id)
    if (entries.isEmpty()) return "Nothing recorded in the last $days days."

    val byId = brain.allTrackers().associateBy { it.id }
    val grouped = entries.groupBy { it.trackerId }
    val bars = grouped.mapNotNull { (trackerId, rows) ->
        val tracker = byId[trackerId]?.takeIf { it.kind == Tracker.KIND_MONEY } ?: return@mapNotNull null
        val out = rows.filter { it.direction == Entry.DIR_OUT }.sumOf { it.amount }
        Bar(tracker.label, out, money(out, tracker), note = "${rows.size} ${if (rows.size == 1) "entry" else "entries"}")
    }.filter { it.value > 0 }.sortedByDescending { it.value }
    if (bars.isNotEmpty() && callId.isNotEmpty()) effects.charts[callId] = Chart(bars = bars, caption = "The last $days days")

    return buildString {
        appendLine("Last $days days:")
        grouped.entries
            .sortedByDescending { (_, rows) ->
                rows.filter { it.direction == Entry.DIR_OUT }.sumOf { it.amount }
            }
            .forEach { (trackerId, rows) ->
                val tracker = byId[trackerId]
                val out = rows.filter { it.direction == Entry.DIR_OUT }.sumOf { it.amount }
                val income = rows.filter { it.direction == Entry.DIR_IN }.sumOf { it.amount }
                append("- ${tracker?.label ?: "Unknown"}: ${money(out, tracker)} out")
                if (income > 0) append(", ${money(income, tracker)} in")
                append(" across ${rows.size} entries")
                append(", averaging ${money(out / days, tracker)} a day")
                appendLine(".")
            }

        val biggest = entries.filter { it.direction == Entry.DIR_OUT }
            .sortedByDescending { it.amount }
            .take(3)
        if (biggest.isNotEmpty()) {
            appendLine("Biggest single items:")
            biggest.forEach { e ->
                val tracker = byId[e.trackerId]
                appendLine(
                    "- ${money(e.amount, tracker)} ${e.note ?: tracker?.label ?: ""}" +
                        " (${TimeUtil.relative(e.occurredAt)})"
                )
            }
        }
    }.trim()
}

/** This month so far, tracker by tracker, set against the same days of last month. */
internal fun Tools.monthReport(only: Tracker?, effects: ToolEffects, callId: String): String {
    val now = System.currentTimeMillis()
    val zone = java.time.ZoneId.systemDefault()
    val from = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val lines = Money.month(brain.entriesBetween(from, now, only?.id), now, zone)
    if (lines.isEmpty()) return "Nothing recorded this month or last."
    val byId = brain.allTrackers(includeArchived = true).associateBy { it.id }
    val day = java.time.LocalDate.now(zone).dayOfMonth
    val bars = lines.mapNotNull { line ->
        val t = byId[line.trackerId]?.takeIf { it.kind == Tracker.KIND_MONEY } ?: return@mapNotNull null
        Bar(
            label = t.label,
            value = line.out,
            shown = money(line.out, t),
            limit = t.budget?.takeIf { t.period == Tracker.PERIOD_MONTHLY },
            note = "last month by now ${money(line.lastByNow, t)}",
            compare = line.lastByNow
        )
    }
    if (bars.isNotEmpty() && callId.isNotEmpty()) {
        effects.charts[callId] = Chart(bars = bars, caption = "This month so far, against the same $day days of last month")
    }
    return buildString {
        appendLine("This month so far ($day days), with the same days of last month:")
        lines.forEach { line ->
            val t = byId[line.trackerId]
            append("- ${t?.label ?: "Unknown"}: ${money(line.out, t)} out")
            if (line.income > 0) append(", ${money(line.income, t)} in")
            append(" (last month by now: ${money(line.lastByNow, t)}; all of last month: ${money(line.lastTotal, t)})")
            appendLine(".")
        }
    }.trim()
}

internal fun Tools.repeatEntry(args: JSONObject, settings: Settings, effects: ToolEffects): String {
    if (args.optString("action").trim().lowercase(Locale.ROOT) == "list") return listRepeats()
    val name = args.optString("tracker").trim()
    if (name.isBlank()) return "Need a tracker name."
    val amount = number(args, "amount") ?: return "Need a numeric amount."
    val every = Recurring.parseEvery(args.optString("every")) ?: Recurring.EVERY_MONTH
    val unit = args.optString("unit").trim()
    val tracker = brain.findTracker(name) ?: brain.upsertTracker(
        Tracker(
            name = name,
            label = name.replaceFirstChar { it.titlecase(Locale.getDefault()) },
            kind = inferKind(unit, settings),
            unit = unit.ifBlank { settings.defaultCurrency },
            period = Tracker.PERIOD_MONTHLY
        )
    )
    val now = System.currentTimeMillis()
    val starting = TimeUtil.parse(args.optString("starting").takeIf { it.isNotBlank() })
    val first = Money.firstDue(every, args.optString("day").takeIf { it.isNotBlank() }, starting, now)
    val direction = if (args.optString("direction").trim() == Entry.DIR_IN) Entry.DIR_IN else Entry.DIR_OUT
    val note = args.optString("note").trim().takeIf { it.isNotBlank() }
    val id = brain.addRecurring(
        Recurring(trackerId = tracker.id, amount = abs(amount), direction = direction, note = note, every = every, anchor = first)
    )
    val loggedNow = brain.catchUpRecurring(now).count { it.first.id == id }
    effects.trackersChanged = true
    runCatching { onRepeatsChanged() }
    val next = brain.recurring(tracker.id).firstOrNull { it.id == id }?.nextAt
    return buildString {
        append("${note ?: tracker.label}: ${money(abs(amount), tracker)} ${Recurring.word(every)} on ${tracker.label} (id $id).")
        when {
            loggedNow == 1 -> append(" The first one is logged now.")
            loggedNow > 1 -> append(" The $loggedNow already due are logged.")
        }
        next?.let { append(" Next: ${TimeUtil.formatDate(it)}.") }
        append(" It logs itself each time from now on; say so to stop it.")
    }
}

internal fun Tools.listRepeats(): String {
    val rules = brain.recurring()
    if (rules.isEmpty()) return "Nothing repeats yet."
    val byId = brain.allTrackers(includeArchived = true).associateBy { it.id }
    return rules.joinToString("\n") { rule ->
        val t = byId[rule.trackerId]
        "[id ${rule.id}] ${rule.note ?: t?.label ?: "?"}: ${if (rule.direction == Entry.DIR_IN) "+" else "-"}${money(rule.amount, t)} " +
            "${Recurring.word(rule.every)} on ${t?.label ?: "?"}, next ${TimeUtil.formatDate(rule.nextAt)}"
    }
}

internal fun Tools.stopRepeat(args: JSONObject, effects: ToolEffects): String {
    val rules = brain.recurring()
    if (rules.isEmpty()) return "Nothing repeats, so there is nothing to stop."
    val byId = brain.allTrackers(includeArchived = true).associateBy { it.id }
    val id = args.optLong("id", -1L)
    val words = listOf(args.optString("note"), args.optString("tracker")).map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }
    val matches = when {
        id > 0 -> rules.filter { it.id == id }
        words.isNotEmpty() -> rules.filter { rule ->
            val said = listOfNotNull(rule.note, byId[rule.trackerId]?.label, byId[rule.trackerId]?.name).joinToString(" ").lowercase(Locale.ROOT)
            words.any { said.contains(it) }
        }
        rules.size == 1 -> rules
        else -> emptyList()
    }
    if (matches.isEmpty()) return "Which one? ${listRepeats()}"
    if (matches.size > 1) return "More than one matches; say which: " + matches.joinToString("; ") { "[id ${it.id}] ${it.note ?: byId[it.trackerId]?.label}" }
    val rule = matches.single()
    brain.stopRecurring(rule.id)
    effects.trackersChanged = true
    runCatching { onRepeatsChanged() }
    return "Stopped ${rule.note ?: byId[rule.trackerId]?.label ?: "it"}. Nothing more will be logged by itself; what it logged stays."
}

internal fun Tools.inferKind(unit: String, settings: Settings): String {
    val candidate = unit.ifBlank { settings.defaultCurrency }.uppercase(Locale.ROOT)
    return if (candidate in CURRENCIES) Tracker.KIND_MONEY else Tracker.KIND_QUANTITY
}

/** Money gets two decimals; counts drop a pointless trailing zero. */
internal fun Tools.money(amount: Double, tracker: Tracker?): String {
    val unit = tracker?.unit ?: ""
    val isMoney = tracker?.kind == Tracker.KIND_MONEY
    val number = if (isMoney) {
        String.format(Locale.US, "%.2f", amount)
    } else if (amount == amount.toLong().toDouble()) {
        amount.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", amount)
    }
    return if (unit.isBlank()) number else "$number $unit"
}

internal fun Tools.summaryLine(status: TrackerStatus): String = buildString {
    val t = status.tracker
    append("${t.label}: ")
    val parts = mutableListOf<String>()
    status.balance?.let { parts += "${money(it, t)} left" }
    status.budgetLeft?.let {
        parts += if (it >= 0) {
            "${money(it, t)} of budget left this ${Tracker.periodWord(t.period)}"
        } else {
            "${money(-it, t)} over budget this ${Tracker.periodWord(t.period)}"
        }
    }
    parts += "${money(status.periodSpent, t)} used this ${Tracker.periodWord(t.period)}"
    if (status.periodReceived > 0) parts += "${money(status.periodReceived, t)} added"
    append(parts.joinToString(", "))
}
