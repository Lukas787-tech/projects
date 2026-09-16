package com.lukas.jarvis

import android.app.Application
import android.content.Context
import com.lukas.jarvis.core.SettingsStore
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.llm.Agent
import com.lukas.jarvis.llm.ConnectionTest
import com.lukas.jarvis.llm.LlmClient
import com.lukas.jarvis.llm.ModelCatalog
import com.lukas.jarvis.llm.Tools
import com.lukas.jarvis.notify.Reminders
import com.lukas.jarvis.voice.SpeechInput
import com.lukas.jarvis.voice.Speaker
import com.lukas.jarvis.web.WebTools

/**
 * One container, constructed once. The app is small enough that a DI framework
 * would cost more build time and indirection than it saves.
 */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val brain = Brain(context)
    val reminders = Reminders(context)

    // Application-scoped on purpose: the view model outlives an activity
    // recreation (a rotation), and an activity-scoped recognizer would leave it
    // holding a destroyed one.
    val speech = SpeechInput(context)
    val speaker = Speaker(context)

    private val web = WebTools()
    private val client = LlmClient()
    private val tools = Tools(brain, web, reminders)

    val agent = Agent(client, tools, brain)
    val models = ModelCatalog()
    val connectionTest = ConnectionTest(client)
}

class JarvisApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Alarms are lost on reinstall; rebuild them from what the brain holds.
        runCatching { container.reminders.rescheduleAll(container.brain.pendingReminders()) }
    }
}
