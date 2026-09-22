package com.lukas.jarvis

import android.app.Application
import android.content.Context
import com.lukas.jarvis.brief.Briefer
import com.lukas.jarvis.control.Agenda
import com.lukas.jarvis.control.Caller
import com.lukas.jarvis.control.Device
import com.lukas.jarvis.control.Launcher
import com.lukas.jarvis.control.Messenger
import com.lukas.jarvis.control.People
import com.lukas.jarvis.control.Phone
import com.lukas.jarvis.core.SettingsStore
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.llm.Agent
import com.lukas.jarvis.llm.ConnectionTest
import com.lukas.jarvis.llm.LlmClient
import com.lukas.jarvis.llm.ModelCatalog
import com.lukas.jarvis.llm.ModelPool
import com.lukas.jarvis.llm.PoolBuilder
import com.lukas.jarvis.llm.PooledLlm
import com.lukas.jarvis.llm.Tools
import com.lukas.jarvis.maps.Locator
import com.lukas.jarvis.maps.MapStore
import com.lukas.jarvis.maps.Navigator
import com.lukas.jarvis.maps.PlacesClient
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.notify.Reminders
import com.lukas.jarvis.stage.StageStore
import com.lukas.jarvis.voice.SpeechInput
import com.lukas.jarvis.voice.Speaker
import com.lukas.jarvis.web.Weather
import com.lukas.jarvis.web.WebTools

/**
 * One container, constructed once. The app is small enough that a DI framework
 * would cost more build time and indirection than it saves.
 */
class AppContainer(context: Context) {
    /** Kept for the things that need a Context after construction, like backups. */
    val app: Context = context.applicationContext

    val settings = SettingsStore(context)
    val brain = Brain(context)
    val reminders = Reminders(context)

    // Application-scoped on purpose: the view model outlives an activity
    // recreation (a rotation), and an activity-scoped recognizer would leave it
    // holding a destroyed one.
    val speech = SpeechInput(context)
    val speaker = Speaker(context)

    private val web = WebTools()
    private val weather = Weather()
    private val client = LlmClient()

    val stage = StageStore()

    // The phone itself, split by what each part is allowed to touch: media and
    // Bluetooth, the device's own switches and readouts, and everything that is
    // done by handing an intent to another app.
    val phone = Phone(context)
    val device = Device(context)
    val launcher = Launcher(context)
    val messenger = Messenger(context)
    val caller = Caller(context)
    val people = People(context)
    val agenda = Agenda(context)

    val mapStore = MapStore(context)
    val tiles = TileCache(context)
    val locator = Locator(context)
    val places = PlacesClient()
    val navigator = Navigator(locator, places, mapStore)

    /** One day, gathered once, for the dashboard and the spoken brief alike. */
    val briefer = Briefer(brain, agenda, weather, locator, places, device)

    private val tools = Tools(
        brain = brain,
        web = web,
        weather = weather,
        reminders = reminders,
        navigator = navigator,
        locator = locator,
        places = places,
        stage = stage,
        phone = phone,
        device = device,
        launcher = launcher,
        people = people,
        agenda = agenda,
        briefer = briefer,
        messenger = messenger,
        caller = caller
    )

    val models = ModelCatalog()
    val pool = ModelPool(context)
    val poolBuilder = PoolBuilder(models)

    private val pooled = PooledLlm(client, pool)

    val agent = Agent(pooled, tools, brain)
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
