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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import com.lukas.jarvis.maps.MapStyle
import com.lukas.jarvis.ui.theme.Motion
import com.lukas.jarvis.vision.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Today
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lukas.jarvis.ui.screens.HistoryScreen
import com.lukas.jarvis.ui.screens.DevicesScreen
import com.lukas.jarvis.ui.screens.MusicScreen
import com.lukas.jarvis.ui.screens.MapScreen
import com.lukas.jarvis.ui.screens.SkillsScreen
import com.lukas.jarvis.ui.screens.Onboarding
import com.lukas.jarvis.llm.Tier
import com.lukas.jarvis.llm.Abilities
import com.lukas.jarvis.llm.AbilitySwitch
import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.ui.components.CoreStyle
import com.lukas.jarvis.ui.components.MessageActions
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.vm.Stage
import com.lukas.jarvis.ui.components.NavEntry
import com.lukas.jarvis.ui.theme.Ink
import com.lukas.jarvis.ui.theme.JarvisTheme
import com.lukas.jarvis.ui.theme.MochiTheme
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.talk.TalkRoute
import com.lukas.jarvis.ui.rooms.LibraryActions
import com.lukas.jarvis.ui.rooms.LibraryRoom
import com.lukas.jarvis.ui.rooms.Shelf
import com.lukas.jarvis.ui.rooms.TodayActions
import com.lukas.jarvis.ui.rooms.TodayRoom
import com.lukas.jarvis.ui.rooms.YouRoute
import com.lukas.jarvis.ui.rooms.YouTab
import com.lukas.jarvis.ui.kit.RoomBar
import com.lukas.jarvis.ui.kit.RoomItem
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import com.lukas.jarvis.ui.theme.PageBackground
import com.lukas.jarvis.ui.theme.hudBackdrop
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
        // Drawn edge to edge; the bars' icons are set to match the café once it is known.
        enableEdgeToEdge()
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
            val look by (application as JarvisApp).container.settings.state.collectAsStateWithLifecycle()
            MochiTheme(
                themeMode = look.themeMode,
                accentId = look.accent,
                textScale = look.textScale,
                reduceMotion = look.reduceMotion,
                idleCharacter = look.characterIdle,
                delights = look.characterDelights
            ) {
                // Status and navigation bar icons follow the café: dark on latte, light at night.
                val dark = Cafe.colors.isDark
                LaunchedEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    )
                }
                Surface(modifier = Modifier.fillMaxSize(), color = Cafe.colors.foam) {
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

/**
 * The icon for each element in the bar along the bottom.
 *
 * The bar is no longer a set of destinations the user navigates between: the
 * assistant puts elements up too, through its `show` tool, and both write the
 * same state. So this is a lookup from element to icon rather than a navigation
 * model of its own.
 */
private fun iconFor(element: Element): ImageVector = when (element) {
    Element.Today -> Icons.Default.Today
    Element.Globe -> Icons.Default.Public
    Element.Map -> Icons.Default.Map
    Element.Notes -> Icons.Default.Psychology
    Element.Tasks -> Icons.Default.CheckCircle
    Element.Money -> Icons.Default.AccountBalanceWallet
    Element.Lists -> Icons.Default.Checklist
    Element.Music -> Icons.Default.MusicNote
    Element.Devices -> Icons.Default.Bluetooth
    Element.Skills -> Icons.Default.AutoAwesome
    Element.Settings -> Icons.Default.Settings
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
    val mapStyle = MapStyle.of(settings.mapStyle)
    val scope = rememberCoroutineScope()

    // The look follows the settings live: pick a colour and the whole app
    // changes under your finger.
    SideEffect {
        ThemeState.apply(settings.accent, settings.backdrop, settings.textScale, settings.reduceMotion)
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
    var showHistory by remember { mutableStateOf(false) }

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

    LaunchedEffect(autoStartListening) {
        if (autoStartListening) {
            viewModel.showElement(Element.Globe)
            showHistory = false
            viewModel.startListening()
            onAutoStartHandled()
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

    val todayActions = remember(viewModel) {
        TodayActions(
            onBack = { viewModel.showElement(Element.Globe) },
            onRefresh = viewModel::refreshBrief,
            onAsk = { sentence ->
                viewModel.showElement(Element.Globe)
                viewModel.sendTyped(sentence)
            },
            onType = viewModel::typeOnCanvas,
            onCompleteTask = viewModel::toggleTask,
            onRunRoutine = viewModel::runRoutine,
            onGo = { name ->
                viewModel.showElement(Element.Map)
                viewModel.routeToSaved(name)
            },
            onPark = { viewModel.saveHere("car") },
            onCamera = { viewModel.askCamera("What is this?") },
            onScanReceipt = {
                viewModel.askCamera(
                    "This is a receipt. Log the total as spending on the right tracker " +
                        "and tell me the new balance."
                )
            },
            onOpenMoney = { viewModel.showElement(Element.Money) },
            onOpenTasks = { viewModel.showElement(Element.Tasks) },
            onTurnOnCalendar = { viewModel.updateSettings { it.copy(calendarEnabled = true) } },
            onSaveRoutine = viewModel::saveRoutine,
            onDeleteRoutine = viewModel::deleteRoutine,
            onForgetCountdown = { id -> viewModel.forgetCountdown(id) }
        )
    }
    val libraryActions = remember(viewModel) {
        LibraryActions(
            onBack = { viewModel.showElement(Element.Globe) },
            onShelf = { shelf ->
                when (shelf) {
                    Shelf.Money -> viewModel.showElement(Element.Money)
                    Shelf.Tasks -> viewModel.showElement(Element.Tasks)
                    Shelf.Lists -> viewModel.showElement(Element.Lists)
                    Shelf.Memory -> viewModel.showElement(Element.Notes)
                    // Notes share the memory element; the stage's note picks the shelf.
                    Shelf.Notes -> viewModel.showElement(Element.Notes, note = LIBRARY_NOTES)
                }
            },
            onType = viewModel::typeOnCanvas,
            addMemory = { content, kind -> viewModel.addMemory(content, kind, emptyList(), 3) },
            updateMemory = viewModel::updateMemory,
            deleteMemory = viewModel::deleteMemory,
            togglePin = viewModel::togglePin,
            addToList = viewModel::addToList,
            checkItem = viewModel::checkListItem,
            removeItem = viewModel::removeListItem,
            clearDone = viewModel::clearDoneItems,
            deleteList = viewModel::deleteList,
            saveTracker = viewModel::saveTracker,
            deleteTracker = viewModel::deleteTracker,
            addEntry = viewModel::addEntry,
            deleteEntry = viewModel::deleteEntry,
            addTask = viewModel::addTask,
            toggleTask = viewModel::toggleTask,
            deleteTask = viewModel::deleteTask,
            cancelPlaceReminder = viewModel::cancelPlaceReminder
        )
    }

    // The first launch belongs to the introduction; everything else waits.
    if (!settings.onboarded) {
        Legacy(Modifier.windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
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

    // Away from the canvas, back always leads back to it.
    BackHandler(enabled = element != Element.Globe && !showHistory) { viewModel.showElement(Element.Globe) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cafe.colors.foam)
            .windowInsetsPadding(WindowInsets.systemBars)
            // Without this the soft keyboard sits on top of the text field it
            // was opened for, which makes typing to Jarvis a guessing game.
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (showHistory) {
                val everything by viewModel.history.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.loadHistory() }
                Legacy { HistoryScreen(messages = everything, onBack = { showHistory = false }, actions = messageActions) }
            } else AnimatedContent(
                targetState = element,
                // The three list screens are one element with tabs; switching
                // tabs is not a change of screen and should not fade the page.
                contentKey = { if (it in HUB) "hub" else it.name },
                transitionSpec = {
                    fadeIn(tween(Motion.standard, delayMillis = 60)) togetherWith fadeOut(tween(Motion.quick))
                },
                label = "element"
            ) { shown -> when (shown) {
                Element.Globe -> TalkRoute(
                    viewModel = viewModel,
                    settings = settings,
                    onCamera = { viewModel.askCamera("What is this?") }
                )
                Element.Today -> TodayRoom(
                    name = settings.assistantName.ifBlank { "Mochi" },
                    brief = brief,
                    loading = briefLoading,
                    trackers = trackers,
                    routines = routines,
                    savedPlaces = savedPlaces,
                    countdowns = countdowns,
                    actions = todayActions
                )
                // Memory, notes, lists, money and tasks are one Library with
                // shelves, so each stays one tap from the others while Mochi can
                // still open any of them by name.
                Element.Notes, Element.Tasks, Element.Money, Element.Lists -> LibraryRoom(
                    shelf = when (shown) {
                        Element.Money -> Shelf.Money
                        Element.Tasks -> Shelf.Tasks
                        Element.Lists -> Shelf.Lists
                        else -> if (stage.note == LIBRARY_NOTES) Shelf.Notes else Shelf.Memory
                    },
                    memories = memories,
                    lists = lists,
                    trackers = trackers,
                    entries = entries,
                    tasks = tasks,
                    placeReminders = placeReminders,
                    actions = libraryActions,
                    defaultCurrency = settings.defaultCurrency
                )
                Element.Settings -> YouRoute(
                    viewModel = viewModel,
                    tab = when {
                        stage.note == SETTINGS_POWERS -> YouTab.Powers
                        // "settings:tab:2" opens a shelf by number, for links and the screenshots.
                        stage.note.startsWith("settings:tab:") -> YouTab.at(stage.note.substringAfterLast(':').toIntOrNull() ?: 0)
                        else -> YouTab.You
                    }
                )
                else -> Legacy { when (shown) {
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

                else -> Unit
            } } } }

        }

        // Away from the canvas, a soft bar of rooms; on the canvas, the composer
        // and its own top bar take its place.
        if (element != Element.Globe || showHistory) {
            RoomBar(
                items = ROOMS,
                selected = if (showHistory) null else barSelection(element).name,
                onSelect = { id ->
                    showHistory = false
                    Element.entries.firstOrNull { it.name == id }?.let(viewModel::showElement)
                }
            )
        }
    }
}

/** The rooms of the app: Talk is home, the rest are a tap away. */
private val ROOMS = listOf(
    RoomItem(Element.Globe.name, "Talk", Icons.Rounded.ChatBubbleOutline),
    RoomItem(Element.Today.name, "Today", Icons.Rounded.WbSunny),
    RoomItem(Element.Notes.name, "Library", Icons.AutoMirrored.Rounded.MenuBook),
    RoomItem(Element.Map.name, "Map", Icons.Rounded.Map),
    RoomItem(Element.Settings.name, "You", Icons.Rounded.AccountCircle)
)

/**
 * A screen still drawn in the 5.5 look, inside its own dark backdrop, while it
 * waits its turn to be rebuilt on the café.
 */
@Composable
private fun Legacy(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    JarvisTheme {
        Box(modifier.fillMaxSize().background(PageBackground).hudBackdrop()) { content() }
    }
}

/**
 * Which bar item lights up for the element on screen.
 *
 * Not every element has a slot — devices and music are reached by asking, and
 * the three list screens share one — so the bar shows the family an element
 * belongs to rather than going blank whenever the assistant raises something
 * that has no icon of its own.
 */
/** The note that opens Settings on its Powers tab. */
private const val SETTINGS_POWERS = "settings:powers"

/** The three list screens, which share one element with tabs. */
private const val LIBRARY_NOTES = "library:notes"

private val HUB = setOf(Element.Notes, Element.Tasks, Element.Money, Element.Lists)


private fun barSelection(element: Element): Element = when (element) {
    Element.Tasks, Element.Money, Element.Lists -> Element.Notes
    Element.Music, Element.Devices, Element.Skills -> Element.Today
    else -> element
}
