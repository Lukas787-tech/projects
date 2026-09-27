package com.lukas.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.ui.globe.GlobeMapFlight
import com.lukas.jarvis.ui.theme.Motion
import com.lukas.jarvis.vision.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lukas.jarvis.ui.screens.BrainScreen
import com.lukas.jarvis.ui.screens.HistoryScreen
import com.lukas.jarvis.ui.screens.DevicesScreen
import com.lukas.jarvis.ui.screens.HubScreen
import com.lukas.jarvis.ui.screens.MusicScreen
import com.lukas.jarvis.ui.screens.MapScreen
import com.lukas.jarvis.ui.screens.SettingsScreen
import com.lukas.jarvis.ui.screens.SkillsScreen
import com.lukas.jarvis.ui.screens.ListsScreen
import com.lukas.jarvis.ui.screens.TasksScreen
import com.lukas.jarvis.ui.screens.TodayScreen
import com.lukas.jarvis.ui.screens.TrackersScreen
import com.lukas.jarvis.ui.screens.VoiceScreen
import com.lukas.jarvis.ui.screens.Onboarding
import com.lukas.jarvis.llm.Tier
import com.lukas.jarvis.llm.Abilities
import com.lukas.jarvis.llm.AbilitySwitch
import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.ui.components.CoreStyle
import com.lukas.jarvis.ui.components.CoreButton
import com.lukas.jarvis.ui.holo.materialize
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import com.lukas.jarvis.ui.components.MessageActions
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.vm.Stage
import com.lukas.jarvis.ui.theme.Ink
import com.lukas.jarvis.ui.theme.JarvisTheme
import com.lukas.jarvis.ui.theme.PageBackground
import com.lukas.jarvis.ui.theme.ThemeState
import androidx.compose.runtime.SideEffect
import com.lukas.jarvis.voice.WakeWordService
import com.lukas.jarvis.vm.AssistantViewModel
import com.lukas.jarvis.surface.Entry

class MainActivity : ComponentActivity() {

    private var startListeningOnOpen by mutableStateOf(false)
    private var routineOnOpen by mutableStateOf<String?>(null)

    /** A shortcut, widget, tile or share that opened the app, waiting to be acted on. */
    private var launchOnOpen by mutableStateOf<Launch?>(null)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark, whatever the system theme: left on automatic, a phone in
        // light mode drew dark status icons on Jarvis's dark background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // A recreation after rotation carries the same intent, already handled:
        // reading it again ran a notification's routine a second time.
        if (savedInstanceState == null) {
            startListeningOnOpen = intent?.getBooleanExtra(EXTRA_START_LISTENING, false) == true
            routineOnOpen = intent?.getStringExtra(EXTRA_RUN_ROUTINE)
            launchOnOpen = Launch.from(intent)
        }
        askForPermissions()

        // The saved look, before the first frame, so the app does not flash
        // the default colours on its way to the user's own.
        (application as JarvisApp).container.settings.current.let {
            ThemeState.apply(it.accent, it.backdrop, it.textScale, it.reduceMotion)
        }

        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    JarvisRoot(
                        autoStartListening = startListeningOnOpen,
                        onAutoStartHandled = { startListeningOnOpen = false },
                        routineToRun = routineOnOpen,
                        onRoutineHandled = { routineOnOpen = null },
                        launch = launchOnOpen,
                        onLaunchHandled = { launchOnOpen = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Reaching here from the wake word or the assistant gesture should open
        // straight into listening.
        startListeningOnOpen = intent.getBooleanExtra(EXTRA_START_LISTENING, false)
        // A routine's notification, tapped: run it the moment the app is up.
        intent.getStringExtra(EXTRA_RUN_ROUTINE)?.let { routineOnOpen = it }
        Launch.from(intent)?.let { launchOnOpen = it }
    }

    private fun askForPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            // Coarse is enough to answer "what is near me", and it is the one
            // users grant without thinking twice.
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
            // Texting and calling are not asked for here: the first "send Anna
            // a message" or "call mum" asks for exactly the one it needs, when
            // the reason is obvious, instead of a wall of dialogs at first start.
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    companion object {
        const val EXTRA_START_LISTENING = "start_listening"
        const val EXTRA_RUN_ROUTINE = "run_routine"
    }
}

/** Why the app was opened, when it was for something in particular. */
sealed interface Launch {
    data object Talk : Launch
    data object Type : Launch
    data object Scan : Launch
    data object Today : Launch
    data class Shared(val text: String?, val image: Uri?) : Launch

    companion object {
        fun from(intent: Intent?): Launch? = when (intent?.action) {
            // The assistant gesture (long-press power or home, once Jarvis is
            // the phone's assistant) means "listen", not merely "open".
            Entry.TALK, Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> Talk
            Entry.TYPE -> Type
            Entry.SCAN -> Scan
            Entry.TODAY -> Today
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                @Suppress("DEPRECATION")
                val image = if (intent.type?.startsWith("image/") == true) {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                } else {
                    null
                }
                if (text.isNullOrBlank() && image == null) null else Shared(text, image)
            }
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?.toString()?.takeIf { it.isNotBlank() }?.let { Shared(it, null) }
            else -> null
        }
    }
}

/**
 * What to ask about something shared in. A link is read; a stretch of text is
 * explained and summed up; one short line is taken as if it had been typed; either way the question is Jarvis's to answer, in
 * the chat, where the whole reply can be read.
 */
private fun sharedPrompt(text: String): String {
    val trimmed = text.trim()
    val link = Regex("https?://\\S+").find(trimmed)?.value
    return if (link != null && trimmed.length < link.length + 80) {
        "Open this link, read it, and give me the gist in a few sentences: $link"
    } else if (trimmed.length <= 140 && '\n' !in trimmed) {
        // One short line — "set a timer for 5 minutes", "call mum at six" —
        // is a request or a note, not a text to sum up: it goes as said.
        trimmed
    } else {
        "I'm sharing this with you. Summarise it, explain anything unclear, and tell me " +
            "if there's something I should do about it:\n\n${trimmed.take(6000)}"
    }
}

@Composable
private fun JarvisRoot(
    autoStartListening: Boolean,
    onAutoStartHandled: () -> Unit,
    routineToRun: String?,
    onRoutineHandled: () -> Unit,
    launch: Launch?,
    onLaunchHandled: () -> Unit
) {
    val viewModel: AssistantViewModel = viewModel(factory = AssistantViewModel.Factory)
    val context = LocalContext.current

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val trackers by viewModel.trackers.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val modelsState by viewModel.modelsState.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val poolEntries by viewModel.poolEntries.collectAsStateWithLifecycle()
    val voiceProblem by viewModel.voiceProblem.collectAsStateWithLifecycle()
    val poolBusy by viewModel.poolBusy.collectAsStateWithLifecycle()
    val poolMessage by viewModel.poolMessage.collectAsStateWithLifecycle()
    val lastUsedEndpoint by viewModel.lastUsedEndpoint.collectAsStateWithLifecycle()
    val map by viewModel.map.collectAsStateWithLifecycle()
    val routing by viewModel.routing.collectAsStateWithLifecycle()
    val bluetooth by viewModel.bluetooth.collectAsStateWithLifecycle()
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val brief by viewModel.brief.collectAsStateWithLifecycle()
    val briefLoading by viewModel.briefLoading.collectAsStateWithLifecycle()
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val placeReminders by viewModel.placeReminders.collectAsStateWithLifecycle()
    val interpreter by viewModel.interpreter.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val hereLabel by viewModel.hereLabel.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val levels by viewModel.levels.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val timers by viewModel.timers.collectAsStateWithLifecycle()
    val stopwatch by viewModel.stopwatch.collectAsStateWithLifecycle()
    val countdowns by viewModel.countdowns.collectAsStateWithLifecycle()
    val mapMessage by viewModel.mapMessage.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val ringingTimers by viewModel.ringingTimers.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val canSeeMedia by viewModel.canSeeMedia.collectAsStateWithLifecycle()
    val cameraRequest by viewModel.cameraRequests.collectAsStateWithLifecycle()
    val scene by viewModel.scene.collectAsStateWithLifecycle()
    val mapStyle = MapStyle.of(settings.mapStyle)
    val scope = rememberCoroutineScope()

    // The look follows the settings live: pick a colour and the whole app
    // changes under your finger.
    SideEffect {
        ThemeState.apply(settings.accent, settings.backdrop, settings.textScale, settings.reduceMotion)
    }

    val coreStyle = CoreStyle.of(settings.coreStyle)
    var showHistory by remember { mutableStateOf(false) }

    // The core is the only control that is always there: a tap talks (and
    // sends away whatever screen was opened over the HUD), a long press types.
    fun talk() {
        showHistory = false
        if (viewModel.ui.value.stage != Stage.Idle) {
            viewModel.toggleListening()
        } else {
            if (viewModel.element.value.element != Element.Globe) viewModel.showElement(Element.Globe)
            viewModel.startListening()
        }
    }
    fun type() {
        showHistory = false
        viewModel.showElement(Element.Globe)
        viewModel.updateSettings { it.copy(voiceMode = false) }
    }
    val holoActions = remember(viewModel) {
        com.lukas.jarvis.ui.holo.HoloActions(
            onCoreTap = ::talk,
            onCoreLongPress = ::type,
            onDismiss = viewModel::dismissHolo,
            onTouch = viewModel::touchHolo,
            onExpand = { holo ->
                viewModel.recedeHolo()
                viewModel.showElement(holo.element)
            },
            onPromote = viewModel::promoteHolo,
            onExpire = viewModel::expireHolos,
            onRefreshBrief = viewModel::refreshBrief,
            onSelectPlace = viewModel::selectPlace,
            onRoute = viewModel::routeToPlace,
            onNavigate = viewModel::navigateToPlace,
            onFollow = viewModel::followLocation,
            onToggleTask = viewModel::toggleTask,
            onCheckItem = viewModel::checkListItem,
            onPlay = viewModel::playMusic,
            onPause = viewModel::pauseMusic,
            onNext = viewModel::nextTrack,
            onPrevious = viewModel::previousTrack,
            onRefreshMusic = viewModel::refreshNowPlaying,
            onGrantMedia = viewModel::openNotificationAccess
        )
    }
    val messageActions = remember(viewModel) {
        MessageActions(
            onSpeak = viewModel::speakMessage,
            onDelete = viewModel::deleteMessage,
            onRetry = { viewModel.retryLast() },
            onRemember = { message ->
                viewModel.rememberMessage(message)
                android.widget.Toast.makeText(context, "Kept in memory", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    // The readouts along the top of the assistant need the day gathered once.
    LaunchedEffect(settings.showHud) {
        if (settings.showHud && brief == null) viewModel.refreshBrief()
    }

    val stage by viewModel.element.collectAsStateWithLifecycle()
    val element = stage.element

    // Calendar and contacts are asked for the moment they are switched on, not
    // at first launch: a permission prompt for a feature the user has not chosen
    // yet is the prompt most likely to be refused out of hand.
    val requestOptional = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(settings.calendarEnabled, settings.contactsEnabled) {
        val missing = buildList {
            if (settings.calendarEnabled) {
                add(Manifest.permission.READ_CALENDAR)
                add(Manifest.permission.WRITE_CALENDAR)
            }
            if (settings.contactsEnabled) add(Manifest.permission.READ_CONTACTS)
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestOptional.launch(missing.toTypedArray())
    }

    // The chat log is an overlay, so the system back gesture has to close it
    // rather than leave the app — it is not a destination of its own.
    BackHandler(enabled = showHistory) { showHistory = false }
    BackHandler(enabled = interpreter != null && !showHistory) { viewModel.endInterpreter() }
    BackHandler(enabled = !showHistory && interpreter == null && element != Element.Globe) {
        viewModel.showElement(Element.Globe)
    }
    BackHandler(enabled = !showHistory && interpreter == null && element == Element.Globe && scene.focus != null) {
        viewModel.dismissHolo()
    }
    // "Show me our old conversations" arrives as a note on the stage, and
    // any other change of screen — the dock, the assistant — closes it, so the
    // history never sits on top of a screen that was asked for.
    LaunchedEffect(stage) {
        showHistory = stage.note == com.lukas.jarvis.stage.StageStore.HISTORY
    }

    // ------------------------------------------------------------- the camera
    //
    // The shot is written to a file of our own rather than returned as a
    // thumbnail: at thumbnail size, the writing on a receipt is a grey smear.
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    var handledCamera by rememberSaveable { mutableLongStateOf(0L) }

    fun deliver(uri: Uri?) {
        if (uri == null) {
            viewModel.cancelPhoto()
            return
        }
        scope.launch {
            val photo = withContext(Dispatchers.IO) { Photos.load(context, uri) }
            if (photo != null) {
                viewModel.showElement(Element.Globe)
                viewModel.sendPhoto(photo)
            } else {
                viewModel.cancelPhoto()
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        deliver(if (ok) captureUri?.let(Uri::parse) else null)
    }
    val pickPicture = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> deliver(uri) }

    // Tools and buttons both ask through the same request, so there is one
    // place that opens the camera.
    LaunchedEffect(cameraRequest?.id) {
        val request = cameraRequest ?: return@LaunchedEffect
        if (request.id == handledCamera) return@LaunchedEffect
        handledCamera = request.id
        runCatching {
            if (request.fromGallery) {
                pickPicture.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else {
                val uri = Photos.newCaptureUri(context)
                captureUri = uri.toString()
                takePicture.launch(uri)
            }
        }.onFailure { viewModel.cancelPhoto() }
    }

    // Shortcuts, the widget, the tile and shares from other apps.
    LaunchedEffect(launch, settings.onboarded) {
        val request = launch ?: return@LaunchedEffect
        // The introduction comes first; a request made before it waits.
        if (!settings.onboarded) return@LaunchedEffect
        showHistory = false
        when (request) {
            Launch.Talk -> {
                viewModel.showElement(Element.Globe)
                viewModel.startListening()
            }
            Launch.Type -> {
                viewModel.showElement(Element.Globe)
                viewModel.updateSettings { it.copy(voiceMode = false) }
            }
            Launch.Scan -> {
                viewModel.showElement(Element.Globe)
                viewModel.askCamera("What is this? Read any text in it.")
            }
            Launch.Today -> viewModel.showElement(Element.Today)
            is Launch.Shared -> {
                viewModel.showElement(Element.Globe)
                viewModel.updateSettings { it.copy(voiceMode = false) }
                val image = request.image
                if (image != null) {
                    val photo = withContext(Dispatchers.IO) { Photos.load(context, image) }
                    if (photo != null) {
                        viewModel.sendPhoto(photo, request.text?.takeIf { it.isNotBlank() } ?: "What is in this picture?")
                    }
                } else {
                    request.text?.let { viewModel.sendTyped(sharedPrompt(it)) }
                }
            }
        }
        onLaunchHandled()
    }

    LaunchedEffect(routineToRun) {
        val name = routineToRun ?: return@LaunchedEffect
        viewModel.showElement(Element.Globe)
        showHistory = false
        viewModel.runRoutine(name)
        onRoutineHandled()
    }

    // ------------------------------------------------ globe <-> map flight
    //
    // Only this pair gets the flight; everything else crossfades. The flight
    // is laid over the destination, which settles underneath it unseen, and
    // lifts off in its last frames.
    var previousElement by remember { mutableStateOf(element) }
    var flying by remember { mutableStateOf(false) }
    val flight = remember { Animatable(0f) }
    LaunchedEffect(element) {
        val from = previousElement
        previousElement = element
        // The flight belongs to the globe; the reactor opens its centre instead.
        val globe = coreStyle == CoreStyle.Globe
        val inbound = globe && from == Element.Globe && element == Element.Map
        val outbound = globe && from == Element.Map && element == Element.Globe
        if (!inbound && !outbound) return@LaunchedEffect
        flying = true
        try {
            if (inbound) {
                flight.snapTo(0f)
                flight.animateTo(1f, tween(FLIGHT_IN_MS, easing = LinearEasing))
            } else {
                flight.snapTo(1f)
                flight.animateTo(0f, tween(FLIGHT_OUT_MS, easing = LinearEasing))
            }
        } finally {
            flying = false
        }
    }

    LaunchedEffect(autoStartListening) {
        if (autoStartListening) {
            viewModel.showElement(Element.Globe)
            showHistory = false
            viewModel.startListening()
            onAutoStartHandled()
        }
    }

    // An answer about places is half map, so a turn that moved the map brings
    // the map forward rather than leaving it a tab away. The starting revision
    // is taken as already seen, so an activity recreation does not do it.
    var seenMapRevision by remember { mutableStateOf(map.revision) }
    LaunchedEffect(map.revision) {
        if (map.revision == seenMapRevision) return@LaunchedEffect
        seenMapRevision = map.revision
        // A pin dropped by hand on the full map is already where the eye is.
        if (map.quiet) return@LaunchedEffect
        showHistory = false
        // Voice mode projects the map over the core, where the answer is being
        // spoken; text mode has nowhere to put a map, so it goes to the map tab.
        if (settings.voiceMode) {
            if (element == Element.Globe) viewModel.raiseHolo(com.lukas.jarvis.stage.Holo.Map)
        } else {
            viewModel.showElement(Element.Map)
        }
    }

    // Only one thing may hold the microphone. The wake-word service listens
    // continuously, so leaving it running while the app is open makes every tap
    // on the orb fail with ERROR_RECOGNIZER_BUSY. It yields whenever the app is
    // in front, and takes over again once the app is backgrounded — which is the
    // only time a wake word is useful anyway.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.wakeWordEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    WakeWordService.stop(context)
                    // A reminder ticked off from its notification, or a
                    // message logged from the floating dot, changed things
                    // while the app was away.
                    viewModel.refreshAll()
                    // Back from granting a permission on one of Android's own
                    // pages: the control centre shows the switch that now works.
                    viewModel.refreshLevels()
                    viewModel.greetIfFirstThisMorning()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (settings.wakeWordEnabled) WakeWordService.start(context)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(settings.wakeWordEnabled) {
        // Turning it off should take effect at once, not at the next pause.
        if (!settings.wakeWordEnabled) WakeWordService.stop(context)
    }

    // The first launch belongs to the introduction; everything else waits.
    if (!settings.onboarded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PageBackground)
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
        ) {
            Onboarding(
                settings = settings,
                onUpdate = viewModel::updateSettings,
                onPreviewVoice = viewModel::previewVoice,
                onFinish = {
                    viewModel.stopSpeaking()
                    viewModel.updateSettings { it.copy(onboarded = true) }
                    viewModel.showElement(Element.Globe)
                },
                initialStep = stage.note.takeIf { it.startsWith("onboarding:step:") }
                    ?.substringAfterLast(':')?.toIntOrNull() ?: 0
            )
        }
        return
    }

    val onHud = element == Element.Globe && !showHistory
    Box(
        modifier = Modifier
            .fillMaxSize()
            // One background for the whole app, defined with the rest of the
            // palette rather than inline here.
            .background(PageBackground)
            .windowInsetsPadding(WindowInsets.systemBars)
            // Without this the soft keyboard sits on top of the text field it
            // was opened for, which makes typing to Jarvis a guessing game.
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Room under a screen for the floating core, so it covers nothing.
                .padding(bottom = if (onHud) 0.dp else CORE_ROOM)
        ) {
            if (showHistory) {
                val everything by viewModel.history.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.loadHistory() }
                HistoryScreen(messages = everything, onBack = { showHistory = false }, actions = messageActions)
            } else AnimatedContent(
                targetState = element,
                // The three list screens are one element with tabs; switching
                // tabs is not a change of screen and should not fade the page.
                contentKey = { if (it in HUB) "hub" else it.name },
                transitionSpec = {
                    val flightPair = coreStyle == CoreStyle.Globe &&
                        setOf(initialState, targetState) == setOf(Element.Globe, Element.Map)
                    if (flightPair) {
                        fadeIn(tween(1)) togetherWith fadeOut(tween(Motion.standard))
                    } else {
                        // Held just short of the ends so the scan below has time to run.
                        fadeIn(tween(SCREEN_IN_MS), initialAlpha = 0.99f) togetherWith
                            fadeOut(tween(SCREEN_OUT_MS), targetAlpha = 0.99f)
                    }
                },
                label = "element"
            ) { shown ->
                val calm = ThemeState.reduceMotion
                val scan = transition.animateFloat(
                    transitionSpec = {
                        if (targetState == EnterExitState.Visible) {
                            tween(if (calm) 220 else SCREEN_IN_MS, easing = LinearEasing)
                        } else {
                            tween(if (calm) 160 else SCREEN_OUT_MS, easing = LinearEasing)
                        }
                    },
                    label = "scan"
                ) { if (it == EnterExitState.Visible) 1f else 0f }
                // A screen the assistant opens is projected like a panel; the HUD
                // itself, and the map the globe flies into, simply appear.
                val projected = shown != Element.Globe && !(coreStyle == CoreStyle.Globe && shown == Element.Map)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (projected) Modifier.materialize { scan.value }
                            else Modifier.graphicsLayer { alpha = scan.value }
                        )
                ) { when (shown) {
                Element.Today -> TodayScreen(
                    address = Personas.address(settings),
                    brief = brief,
                    loading = briefLoading,
                    trackers = trackers,
                    onRefresh = viewModel::refreshBrief,
                    onOpen = viewModel::showElement,
                    onCompleteTask = viewModel::toggleTask,
                    onPlayMusic = { viewModel.showElement(Element.Music) },
                    onCamera = { viewModel.askCamera("What is this?") },
                    onScanReceipt = {
                        viewModel.askCamera(
                            "This is a receipt. Log the total as spending on the right tracker " +
                                "and tell me the new balance."
                        )
                    },
                    onPark = { viewModel.saveHere("car") },
                    onGo = { name ->
                        viewModel.showElement(Element.Map)
                        viewModel.routeToSaved(name)
                    },
                    savedPlaces = savedPlaces,
                    routines = routines,
                    onRunRoutine = viewModel::runRoutine,
                    onSaveRoutine = viewModel::saveRoutine,
                    onDeleteRoutine = viewModel::deleteRoutine,
                    onAsk = { sentence ->
                        viewModel.showElement(Element.Globe)
                        viewModel.sendTyped(sentence)
                    },
                    countdowns = countdowns,
                    onForgetCountdown = viewModel::forgetCountdown
                )

                Element.Globe -> VoiceScreen(
                    state = ui,
                    assistantName = settings.assistantName.ifBlank { "Jarvis" },
                    configured = settings.isConfigured || poolEntries.isNotEmpty(),
                    voiceMode = settings.voiceMode,
                    onModeChange = { voice ->
                        viewModel.updateSettings { it.copy(voiceMode = voice) }
                    },
                    map = map,
                    tiles = viewModel.tiles,
                    // Only sentences whose ability is on, so a first tap never
                    // earns "that is switched off".
                    starters = remember(settings) { Abilities.starters(settings) },
                    onSend = viewModel::sendTyped,
                    onDismissError = viewModel::dismissError,
                    onOpenHistory = { showHistory = true },
                    onOpenSettings = { viewModel.showElement(Element.Settings) },
                    onOpenSkills = { viewModel.showElement(Element.Skills) },
                    onOpenMap = { viewModel.showElement(Element.Map) },
                    onCamera = { viewModel.askCamera("What is this?") },
                    onGallery = { viewModel.askCamera("What is in this picture?", fromGallery = true) },
                    mapStyle = mapStyle,
                    onClearMap = viewModel::clearMap,
                    coreStyle = coreStyle,
                    showHud = settings.showHud,
                    brief = brief,
                    address = Personas.address(settings),
                    actions = messageActions,
                    onStop = viewModel::cancelTurn,
                    brainLabel = lastUsedEndpoint,
                    onNewChat = viewModel::newConversation,
                    timers = timers,
                    onCancelTimer = viewModel::cancelTimer,
                    online = online,
                    ringing = ringingTimers,
                    onStopRinging = viewModel::stopTimerAlarm,
                    stopwatch = stopwatch,
                    onStopwatchToggle = viewModel::toggleStopwatch,
                    onStopwatchReset = viewModel::resetStopwatch,
                    interpreter = interpreter,
                    onInterpretListen = viewModel::interpretListen,
                    onInterpretType = viewModel::interpretTyped,
                    onEndInterpreter = viewModel::endInterpreter,
                    scene = scene,
                    holoData = com.lukas.jarvis.ui.holo.HoloData(
                        map = map,
                        tiles = viewModel.tiles,
                        mapStyle = mapStyle,
                        saved = savedPlaces,
                        hereLabel = hereLabel,
                        routing = routing,
                        brief = brief,
                        tasks = tasks,
                        trackers = trackers,
                        lists = lists,
                        nowPlaying = nowPlaying,
                        canSeeMedia = canSeeMedia
                    ),
                    holoActions = holoActions,
                    onTalk = ::talk
                )

                // Notes, tasks and money are one element with three segments,
                // so each stays one tap from the others while the assistant can
                // still name any of them directly.
                Element.Notes, Element.Tasks, Element.Money, Element.Lists -> HubScreen(
                    selected = when (shown) {
                        Element.Money -> 1
                        Element.Tasks -> 2
                        Element.Lists -> 3
                        else -> 0
                    },
                    onSelect = { index ->
                        viewModel.showElement(
                            when (index) {
                                1 -> Element.Money
                                2 -> Element.Tasks
                                3 -> Element.Lists
                                else -> Element.Notes
                            }
                        )
                    },
                    memoryCount = memories.size,
                    trackerCount = trackers.size,
                    openTaskCount = tasks.count { !it.done },
                    openListCount = lists.lists.sumOf { it.open.size },
                    lists = {
                        ListsScreen(
                            book = lists,
                            onAdd = viewModel::addToList,
                            onCheck = viewModel::checkListItem,
                            onRemove = viewModel::removeListItem,
                            onClearDone = viewModel::clearDoneItems,
                            onDeleteList = viewModel::deleteList
                        )
                    },
                    memory = {
                        BrainScreen(
                            memories = memories,
                            onAdd = viewModel::addMemory,
                            onDelete = viewModel::deleteMemory,
                            onTogglePin = viewModel::togglePin,
                            onEdit = viewModel::updateMemory,
                            embedded = true
                        )
                    },
                    trackers = {
                        TrackersScreen(
                            trackers = trackers,
                            entries = entries,
                            defaultCurrency = settings.defaultCurrency,
                            onSaveTracker = viewModel::saveTracker,
                            onDeleteTracker = viewModel::deleteTracker,
                            onAddEntry = viewModel::addEntry,
                            onDeleteEntry = viewModel::deleteEntry,
                            embedded = true
                        )
                    },
                    tasks = {
                        TasksScreen(
                            tasks = tasks,
                            onAdd = viewModel::addTask,
                            onToggle = viewModel::toggleTask,
                            onDelete = viewModel::deleteTask,
                            placeReminders = placeReminders,
                            onCancelPlace = viewModel::cancelPlaceReminder,
                            embedded = true
                        )
                    }
                )

                Element.Map -> MapScreen(
                    state = map,
                    tiles = viewModel.tiles,
                    style = mapStyle,
                    saved = savedPlaces,
                    hereLabel = hereLabel,
                    travelMode = settings.travelMode,
                    routing = routing,
                    onSelect = viewModel::selectPlace,
                    onRoute = viewModel::routeToPlace,
                    onNavigate = viewModel::navigateToPlace,
                    onModeChange = viewModel::setTravelMode,
                    onClear = viewModel::clearMap,
                    onStyleChange = { viewModel.setMapStyle(it.id) },
                    onFollow = viewModel::followLocation,
                    onSaveHere = viewModel::saveHere,
                    onRouteSaved = viewModel::routeToSaved,
                    onDropPin = viewModel::dropPin,
                    onSaveSelected = viewModel::saveSelected,
                    onRenameSaved = viewModel::renameSaved,
                    onForgetSaved = viewModel::forgetSaved,
                    message = mapMessage,
                    onMessageShown = viewModel::mapMessageShown
                )

                Element.Music -> {
                    LaunchedEffect(Unit) { viewModel.refreshLevels() }
                    MusicScreen(
                        onPlay = viewModel::playMusic,
                        onPause = viewModel::pauseMusic,
                        onNext = viewModel::nextTrack,
                        onPrevious = viewModel::previousTrack,
                        onVolume = viewModel::setMediaVolume,
                        onOpenDevices = { viewModel.showElement(Element.Devices) },
                        mediaVolume = levels?.media,
                        nowPlaying = nowPlaying,
                        canSeeMedia = canSeeMedia,
                        onRefresh = viewModel::refreshNowPlaying,
                        onGrantAccess = viewModel::openNotificationAccess
                    )
                }

                Element.Devices -> DevicesScreen(
                    status = bluetooth,
                    phoneStatus = deviceStatus,
                    onRefresh = {
                        viewModel.refreshBluetooth()
                        viewModel.refreshDeviceStatus()
                        viewModel.refreshLevels()
                    },
                    onOpenSettings = viewModel::openBluetoothSettings,
                    onTorch = viewModel::setTorch,
                    levels = levels,
                    onVolume = viewModel::setVolumeLevel,
                    onBrightness = viewModel::setBrightnessLevel,
                    onAutoBrightness = viewModel::setAutoBrightness,
                    onRinger = viewModel::setRingerMode,
                    onQuiet = viewModel::setQuiet
                )

                Element.Skills -> SkillsScreen(
                    settings = settings,
                    onToggle = { ability, on ->
                        if (ability == AbilitySwitch.Home && on &&
                            (settings.homeUrl.isBlank() || settings.homeToken.isBlank())
                        ) {
                            // Nothing to switch on yet: go to where it is set up.
                            viewModel.updateSettings { it.copy(homeEnabled = true) }
                            viewModel.showElement(Element.Settings, note = SETTINGS_POWERS)
                        } else {
                            viewModel.updateSettings { ability.applyTo(it, on) }
                        }
                    },
                    onTry = viewModel::trySkill
                )

                Element.Settings -> SettingsScreen(
                    profiles = profiles,
                    onSaveProfile = viewModel::saveProfile,
                    onApplyProfile = viewModel::applyProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onScheduleProfile = viewModel::scheduleProfile,
                    initialTab = when {
                        stage.note == SETTINGS_POWERS -> 4
                        // "settings:tab:2" opens a tab by number, for links and the screenshots.
                        stage.note?.startsWith("settings:tab:") == true ->
                            stage.note?.substringAfterLast(':')?.toIntOrNull() ?: 0
                        else -> 0
                    },
                    settings = settings,
                    availableModels = availableModels,
                    modelsState = modelsState,
                    testState = testState,
                    poolEntries = poolEntries,
                    poolBusy = poolBusy,
                    poolMessage = poolMessage,
                    // Recomputed whenever the pool changes, which is exactly
                    // what poolEntries already tracks.
                    poolSummary = remember(poolEntries) { viewModel.poolSummary() },
                    lastUsedEndpoint = lastUsedEndpoint,
                    onUpdate = viewModel::updateSettings,
                    onSwitchProvider = viewModel::switchProvider,
                    onRefreshModels = viewModel::refreshModels,
                    onTestConnection = viewModel::testConnection,
                    onAddToPool = viewModel::addCurrentProviderToPool,
                    onAddEveryProvider = viewModel::addEverySavedProviderToPool,
                    onRemoveFromPool = viewModel::removeFromPool,
                    onTogglePoolEntry = viewModel::setPoolEntryEnabled,
                    onWakePool = viewModel::wakePool,
                    onClearPool = viewModel::clearPool,
                    onPreviewVoice = viewModel::previewVoice,
                    onClearConversation = viewModel::clearConversation,
                    onExportBackup = viewModel::exportBackup,
                    onRestoreBackup = viewModel::restoreBackup,
                    onOpenSkills = { viewModel.showElement(Element.Skills) },
                    voices = viewModel::voices,
                    fishVoices = viewModel::fishVoices,
                    voiceProblem = voiceProblem,
                    hasFreeBrain = poolEntries.any { it.endpoint.preset.tier == Tier.Keyless },
                    onRestoreFreeBrain = viewModel::restoreFreeBrain,
                    onReplayIntro = { viewModel.updateSettings { it.copy(onboarded = false) } },
                    onCheckHome = viewModel::checkHome
                )
            } } }

            // Decided in the same frame as the switch, so the destination never
            // shows for a frame before the flight covers it.
            val starting = coreStyle == CoreStyle.Globe && element != previousElement &&
                setOf(previousElement, element) == setOf(Element.Globe, Element.Map)
            if ((flying || starting) && !showHistory) {
                val progress = when {
                    flying -> flight.value
                    element == Element.Map -> 0f
                    else -> 1f
                }
                // Lifts off over the last stretch, handing the frame to the
                // screen that has been settling underneath it.
                val lift = if (element == Element.Map) {
                    1f - ((progress - 0.9f) / 0.1f).coerceIn(0f, 1f)
                } else {
                    (progress / 0.12f).coerceIn(0f, 1f)
                }
                GlobeMapFlight(
                    progress = progress,
                    map = map,
                    tiles = viewModel.tiles,
                    style = mapStyle,
                    saved = savedPlaces,
                    modifier = Modifier.alpha(lift)
                )
            }
        }

        // No dock: the HUD's own core is the control there. Over a screen the
        // assistant opened, the core floats small at the bottom and is the way back.
        if (!onHud) {
            CoreButton(
                stage = ui.stage,
                level = ui.level,
                coreStyle = coreStyle,
                onTap = ::talk,
                onLongPress = ::type,
                haptics = settings.haptics,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            )
        }
    }
}

/** The note that opens Settings on its Powers tab. */
private const val SETTINGS_POWERS = "settings:powers"

/** The three list screens, which share one element with tabs. */
private val HUB = setOf(Element.Notes, Element.Tasks, Element.Money, Element.Lists)

/** Globe to map: long enough to read as a journey, short enough not to be waited on. */
private const val FLIGHT_IN_MS = 1_750
private const val FLIGHT_OUT_MS = 1_250

/** A screen scanning in over the HUD, and switching off again. */
private const val SCREEN_IN_MS = 720
private const val SCREEN_OUT_MS = 320

/** Kept clear under an opened screen for the floating core. */
private val CORE_ROOM = 76.dp
