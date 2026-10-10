package com.lukas.jarvis

import com.lukas.jarvis.auto.Trigger
import com.lukas.jarvis.auto.UpkeepWork
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.llm.ToolCall
import com.lukas.jarvis.llm.ToolEffects
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * The daily-life tools end to end: real tool calls, through the same Tools
 * the agent uses, against the app's real SQLite database and stores. What
 * the pure tests check rule by rule, this checks as the phone would run it:
 * rows written, totals read back, schedules caught up and stopped, things
 * put away and brought back.
 *
 * Only what needs no network is here: the weather, routes and the models are
 * left to the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyLifeTest {

    private lateinit var app: JarvisApp
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun phone() {
        app = RuntimeEnvironment.getApplication() as JarvisApp
    }

    private fun run(name: String, args: String, effects: ToolEffects = ToolEffects(), id: String = "call-$name"): String =
        runBlocking { app.container.tools.execute(ToolCall(id, name, args), app.container.settings.current, effects) }

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    @Test
    fun rentLogsItselfOnItsDaysOnceAndStopsWhenAsked() {
        val brain = app.container.brain
        val start = LocalDate.now(zone).minusMonths(3).withDayOfMonth(1)
        val said = run("repeat_entry", """{"tracker":"rent","amount":800,"every":"monthly","starting":"$start","note":"Rent"}""")
        assertTrue(said, said.contains("every month on Rent"))

        // A bare date is due at nine that day; every first of the month since then is logged, on its day.
        val now = LocalDateTime.now(zone)
        val expected = generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.atTime(9, 0).isAfter(now) }.count()
        val rent = brain.findTracker("rent")!!
        val entries = brain.recentEntries(rent.id, 20)
        assertEquals(said, expected, entries.size)
        assertTrue(entries.all { day(it.occurredAt).dayOfMonth == 1 })

        // Looking again logs nothing twice.
        assertTrue(brain.catchUpRecurring().isEmpty())
        brain.allTrackerStatus()
        assertEquals(expected, brain.recentEntries(rent.id, 20).size)

        assertTrue(run("repeat_entry", """{"action":"list"}""").contains("every month"))

        // Stopping it asks first; done, nothing more is logged, and what was logged stays.
        assertTrue(app.container.agent.gate.asksFirst("stop_repeat", """{"tracker":"rent"}"""))
        assertFalse(app.container.agent.gate.asksFirst("repeat_entry", "{}"))
        assertTrue(run("stop_repeat", """{"tracker":"rent"}""").startsWith("Stopped"))
        assertTrue(brain.recurring().isEmpty())
        assertEquals(expected, brain.recentEntries(rent.id, 20).size)
    }

    @Test
    fun aBudgetSpeaksUpAsItCrossesAndTheCardGetsItsNumbers() {
        run("configure_tracker", """{"tracker":"groceries","budget":100,"period":"monthly","unit":"EUR"}""")
        run("log_entry", """{"tracker":"groceries","amount":70}""")

        val effects = ToolEffects()
        val nearly = run("log_entry", """{"tracker":"groceries","amount":15}""", effects, id = "nearly")
        assertTrue(nearly, nearly.contains("Heads up: that's 85% of this month's budget"))
        val chart = effects.charts["nearly"]
        assertNotNull(chart)
        assertEquals(100.0, chart!!.bars.single().limit!!, 0.0)

        val again = run("log_entry", """{"tracker":"groceries","amount":5}""")
        assertFalse(again, again.contains("Heads up") || again.contains("over this"))

        val over = run("log_entry", """{"tracker":"groceries","amount":20}""")
        assertTrue(over, over.contains("That's 10.00 EUR over this month's budget."))

        val status = ToolEffects()
        run("tracker_status", "{}", status, id = "status")
        assertTrue(status.charts["status"]!!.bars.single().over)
    }

    @Test
    fun thisMonthIsSetAgainstLastMonth() {
        val lastMonth = LocalDate.now(zone).minusMonths(1).withDayOfMonth(2)
        run("log_entry", """{"tracker":"fun","amount":30,"unit":"EUR","occurred_at":"${lastMonth}T12:00"}""")
        run("log_entry", """{"tracker":"fun","amount":12,"unit":"EUR"}""")
        val effects = ToolEffects()
        val report = run("spending_report", """{"this_month":true}""", effects, id = "month")
        assertTrue(report, report.startsWith("This month so far"))
        assertTrue(report, report.contains("Fun: 12.00 EUR out"))
        assertTrue(report, report.contains("all of last month: 30.00 EUR"))
        assertEquals(12.0, effects.charts["month"]!!.bars.single().value, 0.001)
    }

    @Test
    fun aTripIsMadeReadyInOneGo() {
        val start = LocalDate.now(zone).plusDays(30)
        val said = run("plan_trip", """{"destination":"Lisbon","start":"$start","nights":5}""")
        assertTrue(said, said.contains("Trip to Lisbon is ready"))
        // A month off: no forecast yet, so a little for rain and no network asked.
        assertTrue(said, said.contains("the forecast comes closer to the date"))
        assertNotNull(app.container.countdowns.find("Lisbon trip"))
        val packing = app.container.lists.current.find("packing for lisbon")
        assertNotNull(packing)
        assertTrue(packing!!.items.any { it.text == "Passport or ID" })
        assertTrue(packing.items.any { it.text == "Something for rain, just in case" })
        assertTrue(app.container.brain.recentMemories(20).any { it.content.startsWith("Trip to Lisbon") && it.importance == 4 })
        // The trip that matters is not one upkeep would ever put away.
        assertTrue(UpkeepWork.tidy(app, app.container.brain).none { it.reason == com.lukas.jarvis.data.Upkeep.Reason.Past })
    }

    @Test
    fun upkeepPutsARepeatAwayAndBringsItBack() {
        val brain = app.container.brain
        brain.addMemory(Memory(content = "My locker code is 3917"))
        brain.addMemory(Memory(content = "Locker code: 3917"))
        brain.addMemory(Memory(content = "Anna likes tea"))

        val put = UpkeepWork.tidy(app, brain)
        assertEquals(1, put.size)
        assertEquals(1, brain.recentMemories(50).count { it.content.contains("3917") })
        assertEquals(1, brain.searchMemories("locker code").size)
        assertEquals(put, UpkeepWork.log(app))

        UpkeepWork.bringBack(app, brain, put.single().memoryId)
        assertEquals(2, brain.recentMemories(50).count { it.content.contains("3917") })
        assertTrue(UpkeepWork.log(app).isEmpty())
    }

    @Test
    fun aRoutineCanWaitOnTheCar() {
        val said = run("create_routine", """{"name":"drive","steps":["How's the traffic?"],"when":"connected","device":"my car"}""")
        assertTrue(said, said.contains("when the car connects"))
        val routine = app.container.routines.find("drive")
        assertEquals(Trigger(Trigger.Kind.Connected, "car"), routine?.trigger)
        assertEquals(listOf("drive"), app.container.routines.triggeredBy(Trigger.Kind.Connected, "VW Car Audio").map { it.name })
        assertTrue(app.container.routines.triggeredBy(Trigger.Kind.Connected, "Lukas's AirPods").isEmpty())
        assertTrue(app.container.routines.triggeredBy(Trigger.Kind.Charging).isEmpty())
    }

    @Test
    fun theShoppingListIsReadRoundTheShop() {
        run("list", """{"action":"add","list":"shopping","items":["milk","apples","bread","bananas"]}""")
        val shown = run("list", """{"action":"show","list":"shopping"}""")
        assertTrue(shown, shown.contains("in the order of the shop"))
        assertTrue(shown, shown.indexOf("apples") < shown.indexOf("bread") && shown.indexOf("bread") < shown.indexOf("milk"))
        // Any other list stays as it was made.
        run("list", """{"action":"add","list":"packing","items":["tent","apples","torch"]}""")
        assertFalse(run("list", """{"action":"show","list":"packing"}""").contains("order of the shop"))
    }

    @Test
    fun schedulesGoWithTheirTrackerAndWithAnOldBackup() {
        val brain = app.container.brain
        val later = LocalDate.now(zone).plusDays(10)
        run("repeat_entry", """{"tracker":"netflix","amount":12.99,"starting":"$later"}""")
        assertEquals(1, brain.recurring().size)

        // A backup from before schedules existed brings its trackers back without them.
        val tables = brain.exportTables().apply { remove("recurring") }
        brain.importTables(tables)
        assertTrue(brain.recurring().isEmpty())

        run("repeat_entry", """{"tracker":"netflix","amount":12.99,"starting":"$later"}""")
        assertEquals(1, brain.recurring().size)
        brain.deleteTracker(brain.findTracker("netflix")!!.id)
        assertTrue(brain.recurring().isEmpty())
    }
}
