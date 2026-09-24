package com.lukas.jarvis

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.app.Application
import android.content.Context
import com.lukas.jarvis.auto.Routines
import com.lukas.jarvis.brief.Briefer
import com.lukas.jarvis.control.Agenda
import com.lukas.jarvis.control.Caller
import com.lukas.jarvis.control.Chats
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
import com.lukas.jarvis.maps.SavedPlaces
import com.lukas.jarvis.maps.TileCache
import com.lukas.jarvis.notify.Reminders
import com.lukas.jarvis.stage.StageStore
import com.lukas.jarvis.vision.CameraBus
import com.lukas.jarvis.voice.SpeechInput
import com.lukas.jarvis.voice.Speaker
import com.lukas.jarvis.web.Currency
import com.lukas.jarvis.web.Imagine
import com.lukas.jarvis.web.Knowledge
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
    private val currency = Currency()
    private val knowledge = Knowledge()
    val imagine = Imagine(context)
    val home = com.lukas.jarvis.web.Home()
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
    val chats = Chats(context, people)
    val agenda = Agenda(context)

    val mapStore = MapStore(context)
    val tiles = TileCache(context)
    val locator = Locator(context)
    val places = PlacesClient()
    val savedPlaces = SavedPlaces(context)
    val navigator = Navigator(locator, places, mapStore, savedPlaces)

    /** The camera, asked for by a tool and opened by the activity. */
    val camera = CameraBus()

    /** Text, objects and codes read on the phone itself, with no key. */
    val eyes = com.lukas.jarvis.vision.OnDeviceVision()
    val routines = Routines(context)
    val lists = com.lukas.jarvis.data.Lists(context)

    /** One day, gathered once, for the dashboard and the spoken brief alike. */
    val briefer = Briefer(brain, agenda, weather, locator, places, device, knowledge)

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
        caller = caller,
        chats = chats,
        currency = currency,
        camera = camera,
        routines = routines,
        knowledge = knowledge,
        imagine = imagine,
        home = home,
        lists = lists
    )

    val models = ModelCatalog()
    val pool = ModelPool(context)
    val poolBuilder = PoolBuilder(models)

    private val pooled = PooledLlm(client, pool)

    val agent = Agent(pooled, tools, brain).also { agent ->
        agent.ambient = {
            listOfNotNull(
                briefer.lastPlace?.let { "Roughly where the phone is: $it" },
                lists.current.lists.takeIf { it.isNotEmpty() }?.let { all ->
                    "The user's lists: " + all.joinToString { "${it.name} (${it.open.size} open)" }
                }
            )
        }
        tools.routineRunner = { routine, settings ->
            routines.markRun(routine.name)
            "Routine '${routine.name}' finished. What each step came back with: " +
                agent.runRoutine(routine, settings)
        }
    }
    val connectionTest = ConnectionTest(client)
}

class JarvisApp : Application() {

    lateinit var container: AppContainer
        private set

    /** How many of the app's activities are started; above zero means it is on screen. */
    @Volatile
    private var started = 0

    /** True while Jarvis is the app in front of the user. */
    val inForeground: Boolean get() = started > 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { started++ }
            override fun onActivityStopped(activity: android.app.Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        // Alarms are lost on reinstall; rebuild them from what the brain holds.
        // Off the main thread: it is a database read, and startup is when a
        // stutter is most visible.
        appScope.launch {
            runCatching { container.reminders.rescheduleAll(container.brain.pendingReminders()) }
            runCatching { container.routines.rescheduleAll() }
        }
        // The written brief follows its setting wherever it changes — the
        // settings screen, a restored backup — and is set again at every
        // start, which covers reboots and updates too.
        appScope.launch {
            container.settings.state
                .map { it.briefTime }
                .distinctUntilChanged()
                .collect { time -> runCatching { com.lukas.jarvis.brief.BriefAlarm.schedule(this@JarvisApp, time) } }
        }
    }

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )
}
