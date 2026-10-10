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

// Exact answers worked out on the phone: sums, units, dates, chance.

internal fun Tools.thinkingTools(): List<JSONObject> = listOf(
    tool(
        "now",
        "Get the current date, time and day of week.",
        props(),
        emptyList()
    ),
    tool(
        "calculate",
        "Work out an arithmetic expression exactly. ALWAYS use this instead of doing sums " +
            "in your head — percentages, splitting a bill, totals, unit prices. Supports " +
            "+ - * / ^ %, brackets, sqrt, ln, log, sin, cos, tan, abs, round, pi and e.",
        props("expression" to str("The sum, e.g. '(249.99 * 0.175) + 12' or '15% of 80'.")),
        listOf("expression")
    ),
    tool(
        "date_calc",
        "Calendar arithmetic, exactly. ALWAYS use this for 'how many days until', 'how long " +
            "ago was', 'what date is three weeks from now', 'what weekday is the 24th'. " +
            "Never count days in your head.",
        props(
            "operation" to str("What to work out.", listOf("between", "add", "weekday")),
            "from" to str("Start date: ISO like '2026-12-24', '24.12.', 'tomorrow'. Omit for today."),
            "to" to str("For 'between': the other date. For 'weekday': the date to look at."),
            "amount" to int("For 'add': how many units to add. Negative goes back."),
            "unit" to str("For 'add'.", listOf("days", "weeks", "months", "years"))
        ),
        listOf("operation")
    ),
    tool(
        "convert_units",
        "Convert between units exactly. Known units — ${Units.known()}.",
        props(
            "amount" to num("How much of the source unit."),
            "from" to str("The unit it is in now, e.g. 'miles'."),
            "to" to str("The unit wanted, e.g. 'km'.")
        ),
        listOf("amount", "from", "to")
    ),
    tool(
        "briefing",
        "The whole day in one call: weather, what is due, the next appointment, budgets and " +
            "the phone's own state. Use for 'how does my day look', 'good morning', " +
            "'catch me up', or any question that spans more than one of those. With " +
            "evening true it looks back instead — done and spent today, still open, and " +
            "tomorrow — for 'how did my day go', 'wrap up my day', 'what's tomorrow'.",
        props("evening" to bool("True for the look back over today and ahead to tomorrow.")),
        emptyList()
    )
)

internal fun Tools.randomTool(): JSONObject = tool(
    "random",
    "Truly random choices: flip a coin, roll dice, a random number, pick one of several " +
        "options, or make a strong password. Always use this instead of choosing yourself.",
    props(
        "kind" to str("What to do.", listOf("coin", "dice", "number", "pick", "password")),
        "min" to int("For 'number': the lowest value. Default 1."),
        "max" to int(
            "For 'number': the highest value. For 'dice': the sides. For 'password': its length. " +
                "Default 6, 100 or 20."
        ),
        "count" to int("How many coins, dice or numbers. Default 1."),
        "options" to arr("For 'pick': the things to choose between.")
    ),
    listOf("kind")
)

internal fun Tools.nowText(): String {
    val now = System.currentTimeMillis()
    return "Current local date and time: ${TimeUtil.format(now)} (ISO ${TimeUtil.iso(now)})."
}

internal fun Tools.calculate(args: JSONObject): String {
    val expression = args.optString("expression").trim()
    if (expression.isBlank()) return "Need an expression."
    return when (val result = Calculator.evaluate(expression)) {
        is Calculator.Result.Ok -> "$expression = ${Calculator.format(result.value)}"
        is Calculator.Result.Error -> "I could not work that out: ${result.reason}."
    }
}

internal fun Tools.convert(args: JSONObject): String {
    val amount = number(args, "amount") ?: Double.NaN
    if (amount.isNaN()) return "Need an amount to convert."
    return when (
        val result = Units.convert(amount, args.optString("from"), args.optString("to"))
    ) {
        is Units.Result.Ok -> result.spoken + "."
        is Units.Result.Error -> result.reason
    }
}

internal fun Tools.dateCalc(args: JSONObject): String {
    val from = args.optString("from").takeIf { it.isNotBlank() }
    val to = args.optString("to").takeIf { it.isNotBlank() }
    return when (args.optString("operation").trim().lowercase(Locale.ROOT)) {
        "add", "plus", "offset" -> {
            if (!args.has("amount")) return "For 'add' I need an amount."
            DateMath.add(from, args.optLong("amount", 0L), args.optString("unit"))
        }
        "weekday", "day", "which_day" -> DateMath.weekday(to ?: from)
        else -> {
            // "between" with only one date given means "from today to that one",
            // which is what "how long until" is always asking.
            if (to == null && from == null) return "Which date should I count to?"
            if (to == null) DateMath.between(null, from) else DateMath.between(from, to)
        }
    }
}

internal suspend fun Tools.convertCurrency(args: JSONObject): String {
    val amount = number(args, "amount") ?: Double.NaN
    if (amount.isNaN()) return "Need an amount to convert."
    val from = args.optString("from").trim()
    val to = args.optString("to").trim()
    if (from.isBlank() || to.isBlank()) return "Need both currencies."
    return currency.convert(amount, from, to)
}
