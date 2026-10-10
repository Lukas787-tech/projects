package com.lukas.jarvis.moment

import com.lukas.jarvis.llm.ConfirmationGate
import com.lukas.jarvis.llm.ToolCall
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.llm.ToolGroup
import com.lukas.jarvis.llm.ToolOutput

/**
 * Believable inputs for every kind of moment, for previews, screenshots and
 * the dead-end test — which must see each moment exactly as a person would.
 */
object Samples {

    private const val AT = 1_700_000_000_000L

    fun inputsFor(moment: Moment): MomentInputs = when (moment) {
        Moment.Resting -> MomentInputs()
        Moment.Listening -> MomentInputs(listening = true)
        Moment.Thinking -> MomentInputs(thinking = true)
        is Moment.Working -> MomentInputs(thinking = true, tool = ToolCatalog.ALL.first { it.group == moment.group }.name)
        is Moment.Showing -> MomentInputs(
            fresh = listOf(
                if (moment.kind == ShowKind.Image) Cards.photo(null, "A receipt from Café Mitte: 2 cappuccino, 7.40 EUR.", AT)
                else card(showTool(moment.kind))
            )
        )
        is Moment.Creating -> MomentInputs(
            fresh = listOf(
                if (moment.kind == CreateKind.Plan) Cards.plan("Saturday", listOf("10:00 Market", "12:30 Lunch with Anna", "15:00 Bike to the lake"), AT)
                else card(createTool(moment.kind))
            )
        )
        is Moment.Asking -> if (moment.kind == AskKind.Confirmation) {
            val gate = ConfirmationGate { AT }
            MomentInputs(pending = listOf(gate.propose(ToolCall("s", "send_message", """{"number":"+4912345","who":"Anna","text":"Running ten minutes late"}"""))))
        } else {
            MomentInputs(clarifying = true)
        }
        is Moment.Alerting -> MomentInputs(
            alerts = listOf(
                Alert(
                    id = moment.kind.name,
                    kind = moment.kind,
                    title = when (moment.kind) {
                        AlertKind.Timer -> "Pasta"
                        AlertKind.Reminder -> "Call mum"
                        AlertKind.Message -> "Anna"
                        AlertKind.Arrival -> "You're at the supermarket"
                    },
                    detail = if (moment.kind == AlertKind.Message) "Are we still on for tonight?" else "",
                    timerId = if (moment.kind == AlertKind.Timer) 7 else null
                )
            )
        )
        Moment.Navigating -> MomentInputs(navigating = true)
        is Moment.Recovering -> MomentInputs(
            problem = Cards.problem("Every free model is busy right now.", AT, offline = moment.offline),
            online = !moment.offline
        )
    }

    fun card(tool: String, result: String = resultFor(tool), args: String = argsFor(tool)): CanvasCard =
        Cards.fromOutput(ToolOutput(tool, args, result, at = AT, chart = chartFor(tool)))

    /** The numbers a money tool hands its card, so the chart is walked and drawn like the rest. */
    fun chartFor(tool: String): Chart? = when (tool) {
        "tracker_status" -> Chart(
            bars = listOf(
                Bar("Groceries", 182.40, "182.40 EUR", limit = 300.0, note = "of 300.00 EUR this month"),
                Bar("Eating out", 74.00, "74.00 EUR", limit = 80.0, note = "of 80.00 EUR this week"),
                Bar("Fun", 96.50, "96.50 EUR", limit = 90.0, note = "of 90.00 EUR this month")
            )
        )
        "spending_report" -> Chart(
            bars = listOf(Bar("Groceries", 182.40, "182.40 EUR", note = "last month by now 160.10 EUR", compare = 160.10), Bar("Fun", 40.0, "40.00 EUR", compare = 55.0)),
            caption = "This month so far, against the same 9 days of last month"
        )
        else -> null
    }

    private fun showTool(kind: ShowKind) = when (kind) {
        ShowKind.Map -> "find_places"
        ShowKind.Globe -> "show_on_map"
        ShowKind.List -> "calendar"
        ShowKind.Chart -> "tracker_status"
        ShowKind.Image -> "generate_image"
        ShowKind.Text -> "weather"
        ShowKind.Camera -> "take_photo"
        ShowKind.Screen -> "read_screen"
        ShowKind.Controls -> "set_timer"
    }

    private fun createTool(kind: CreateKind) = when (kind) {
        CreateKind.Note -> "remember"
        CreateKind.Task -> "add_task"
        CreateKind.List -> "list"
        CreateKind.Routine -> "create_routine"
        CreateKind.Entry -> "log_entry"
        CreateKind.Message -> "send_message"
        CreateKind.Event -> "add_calendar_event"
        CreateKind.Image -> "generate_image"
        CreateKind.Plan -> "briefing"
        CreateKind.Place -> "save_place"
    }

    fun argsFor(tool: String): String = when (tool) {
        "find_places" -> """{"query":"cafe"}"""
        "route_to", "start_navigation" -> """{"destination":"Café Mitte"}"""
        "list" -> """{"action":"add","list":"shopping","items":["oat milk"]}"""
        "add_task" -> """{"title":"Call mum","due":"2026-10-10T18:00"}"""
        "log_entry" -> """{"tracker":"money","amount":12,"note":"lunch"}"""
        "remember" -> """{"content":"Locker code is 3917"}"""
        "create_routine" -> """{"name":"morning","steps":["weather","my day"]}"""
        "save_place" -> """{"name":"Car"}"""
        "send_message" -> """{"number":"+4912345","text":"On my way"}"""
        "translate" -> """{"text":"thank you","to":"Japanese"}"""
        "find_contact" -> """{"name":"Anna"}"""
        else -> "{}"
    }

    fun resultFor(tool: String): String = when (tool) {
        "find_places" -> "- Café Mitte, 200 m\n- Bäckerei Hansen, 350 m\n- Kaffeebar, 600 m"
        "add_task" -> "Reminder set: Call mum, tomorrow 18:00 (id 12)"
        "log_entry" -> "Logged 12.00 EUR for lunch. Left this week: 23.00 EUR (id 41)"
        "remember" -> "Saved (id 7)"
        "list" -> "Added oat milk to shopping.\n- oat milk\n- eggs\n- bread"
        "weather" -> "Berlin: 14°, light rain from 17:00, sunset 18:52."
        "tracker_status" -> "Money: 23.00 EUR left this week of 80.00."
        "set_timer" -> "Pasta timer: 10 minutes."
        else -> "Done."
    }

    /** Every group has a tool to stand for it. */
    fun toolOf(group: ToolGroup): String = ToolCatalog.ALL.first { it.group == group }.name
}
