package com.lukas.jarvis

/*
 * The requests Jarvis answers with no model at all — timers, alarms, the torch,
 * the time, quick reminders, sums — in English and German, and the sentences
 * it must leave to the model.
 */
import com.lukas.jarvis.llm.Reflexes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReflexTest {

    private fun parsed(text: String): Pair<String, JSONObject>? =
        Reflexes.parse(text)?.let { it.name to JSONObject(it.argumentsJson) }

    @Test fun timers() {
        assertEquals("set_timer", parsed("Set a timer for 10 minutes")?.first)
        assertEquals(10.0, parsed("Set a timer for 10 minutes")!!.second.getDouble("minutes"), 0.0001)
        assertEquals(0.5, parsed("timer 30 seconds")!!.second.getDouble("minutes"), 0.0001)
        assertEquals(90.0, parsed("Stell einen Timer auf 1,5 Stunden")!!.second.getDouble("minutes"), 0.0001)
    }

    @Test fun alarms() {
        assertEquals("07:30", parsed("Wake me at 7:30")!!.second.getString("time"))
        assertEquals("19:00", parsed("set an alarm for 7 pm")!!.second.getString("time"))
        assertEquals("06:00", parsed("Weck mich um 6 Uhr")!!.second.getString("time"))
        assertEquals("+480m", parsed("wake me in 8 hours")!!.second.getString("time"))
    }

    @Test fun torchTimeAndReminders() {
        assertEquals(true, parsed("turn on the torch")!!.second.getBoolean("on"))
        assertEquals(false, parsed("Taschenlampe aus")!!.second.getBoolean("on"))
        assertEquals("now", parsed("What time is it?")?.first)
        assertEquals("now", parsed("Wie spät ist es")?.first)
        val remind = parsed("remind me in 20 minutes to take the pizza out")!!
        assertEquals("add_task", remind.first)
        assertEquals("+20m", remind.second.getString("due"))
        assertEquals("Take the pizza out", remind.second.getString("title"))
    }

    @Test fun sums() {
        assertEquals("12 * 7", parsed("what is 12 times 7")!!.second.getString("expression"))
        assertEquals("(3 + 4) * 2", parsed("(3 + 4) * 2")!!.second.getString("expression"))
        assertEquals("100 / 4", parsed("was ist 100 geteilt durch 4")!!.second.getString("expression"))
    }

    @Test fun volumeAndQuiet() {
        assertEquals("up", parsed("turn it up")!!.second.getString("action"))
        assertEquals("down", parsed("Leiser bitte")!!.second.getString("action"))
        val set = parsed("set the volume to 30%")!!
        assertEquals("set", set.second.getString("action"))
        assertEquals(30, set.second.getInt("level"))
        assertEquals("mute", parsed("mute")!!.second.getString("action"))
        val quiet = parsed("do not disturb for 45 minutes")!!
        assertEquals("do_not_disturb", quiet.first)
        assertEquals("priority", quiet.second.getString("mode"))
        assertEquals(45, quiet.second.getInt("minutes"))
        assertEquals("off", parsed("turn off do not disturb")!!.second.getString("mode"))
        assertEquals(120, parsed("Nicht stören für 2 Stunden")!!.second.getInt("minutes"))
    }

    @Test fun musicChanceBatteryAndApps() {
        assertEquals("pause", parsed("pause the music")!!.second.getString("action"))
        assertEquals("next", parsed("skip")!!.second.getString("action"))
        assertEquals("resume", parsed("Weiter")!!.second.getString("action"))
        assertEquals("coin", parsed("flip a coin")!!.second.getString("kind"))
        assertEquals("dice", parsed("Würfeln")!!.second.getString("kind"))
        assertEquals("battery", parsed("how much battery do I have?")!!.second.getString("what"))
        assertEquals("spotify", parsed("open Spotify")!!.second.getString("name"))
        assertEquals("whatsapp", parsed("Öffne WhatsApp")!!.second.getString("name"))
        assertNull(Reflexes.parse("open the garage door"))
        assertEquals("set_timer", parsed("start a timer for 5 minutes")?.first)
    }

    @Test fun timerQuestions() {
        assertEquals("list", parsed("how long is left on the pasta?")!!.second.getString("action"))
        assertEquals("cancel", parsed("stop the timer")!!.second.getString("action"))
        assertEquals("cancel", parsed("cancel the pasta timer")!!.second.getString("action"))
        assertEquals("set_timer", parsed("set a timer for 10 minutes")?.first)
    }

    @Test fun ordinarySentencesAreLeftToTheModel() {
        assertNull(Reflexes.parse("tell me about the Brandenburg Gate"))
        assertNull(Reflexes.parse("what is 42"))
        assertNull(Reflexes.parse("I spent 12 euros on lunch"))
        assertNull(Reflexes.parse("how does my day look"))
        assertNull(Reflexes.parse("mute the group chat with my cousins"))
    }
}
