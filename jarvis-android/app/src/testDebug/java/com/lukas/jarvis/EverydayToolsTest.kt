package com.lukas.jarvis

import com.lukas.jarvis.llm.ToolCall
import com.lukas.jarvis.llm.ToolEffects
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/*
 * The everyday tools that work with no connection, end to end through the
 * same Tools the agent calls: memory, reminders, exact answers, countdowns
 * and lists. They guard the split of Tools into one file per group, and
 * anything that later changes how these answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EverydayToolsTest {

    private lateinit var app: JarvisApp

    @Before
    fun phone() {
        app = RuntimeEnvironment.getApplication() as JarvisApp
    }

    private fun run(name: String, args: String, effects: ToolEffects = ToolEffects()): String =
        runBlocking { app.container.tools.execute(ToolCall("call-$name", name, args), app.container.settings.current, effects) }

    private fun idIn(text: String): Long = Regex("id (\\d+)").find(text)!!.groupValues[1].toLong()

    @Test
    fun aMemoryIsKeptFoundCorrectedAndForgotten() {
        val effects = ToolEffects()
        val said = run("remember", """{"content":"Lukas's bike lock code is 4821","kind":"fact"}""", effects)
        assertTrue(said, said.startsWith("Remembered (id "))
        assertTrue(effects.memoriesChanged)
        val id = idIn(said)

        assertTrue(run("recall", """{"query":"bike lock code"}""").contains("4821"))
        assertTrue(run("update_memory", """{"id":$id,"content":"Lukas's bike lock code is 1234"}""").startsWith("Updated"))
        val again = run("recall", """{"query":"bike lock code"}""")
        assertTrue(again, again.contains("1234") && !again.contains("4821"))

        // Forgetting asks first; once said yes to, the memory is gone.
        assertTrue(app.container.agent.gate.asksFirst("forget", """{"id":$id}"""))
        assertTrue(run("forget", """{"id":$id}""").startsWith("Forgot"))
        assertTrue(run("recall", """{"query":"bike lock code"}""").startsWith("No memories match"))
    }

    @Test
    fun aReminderIsAddedMovedDoneAndDeleted() {
        val brain = app.container.brain
        val said = run("add_task", """{"title":"Call mum","due":"+2h"}""")
        assertTrue(said, said.startsWith("Task added (id ") && said.contains("Call mum"))
        val id = idIn(said)
        val first = brain.getTask(id)!!.dueAt!!

        run("update_task", """{"id":$id,"due":"+3h"}""")
        assertTrue(brain.getTask(id)!!.dueAt!! > first)

        run("complete_task", """{"id":$id}""")
        assertTrue(brain.tasks(includeDone = false).none { it.id == id })
        assertTrue(brain.getTask(id)!!.done)

        assertTrue(app.container.agent.gate.asksFirst("delete_task", """{"id":$id}"""))
        run("delete_task", """{"id":$id}""")
        assertNull(brain.getTask(id))
    }

    @Test
    fun exactAnswersAreWorkedOutNotGuessed() {
        assertEquals("15% of 80 = 12", run("calculate", """{"expression":"15% of 80"}"""))
        val km = run("convert_units", """{"amount":10,"from":"miles","to":"km"}""")
        assertTrue(km, km.contains("16.09") || km.contains("16,09"))
        val days = run("date_calc", """{"operation":"between","from":"2026-12-24","to":"2026-12-31"}""")
        assertTrue(days, days.contains("1 week") || days.contains("7 days"))
    }

    @Test
    fun aCountdownIsSetListedAndRemoved() {
        val day = LocalDate.now().plusDays(20)
        run("countdown", """{"action":"add","name":"Holiday","date":"$day"}""")
        assertNotNull(app.container.countdowns.find("Holiday"))
        assertTrue(run("countdown", """{"action":"list"}""").contains("Holiday"))
        run("countdown", """{"action":"remove","name":"Holiday"}""")
        assertNull(app.container.countdowns.find("Holiday"))
    }

    @Test
    fun aListIsTickedOffAndCleared() {
        run("list", """{"action":"add","list":"Einkaufsliste","items":["eggs, milk and bread"]}""")
        // "Einkaufsliste" is the shopping list, and one entry with commas is three things.
        val shopping = app.container.lists.current.find("shopping")!!
        assertEquals(listOf("eggs", "milk", "bread"), shopping.items.map { it.text })

        run("list", """{"action":"check","list":"groceries","items":["eggs"]}""")
        assertTrue(app.container.lists.current.find("shopping")!!.items.first { it.text == "eggs" }.done)
        run("list", """{"action":"clear_done","list":"shopping"}""")
        assertEquals(listOf("milk", "bread"), app.container.lists.current.find("shopping")!!.items.map { it.text })
    }
}
