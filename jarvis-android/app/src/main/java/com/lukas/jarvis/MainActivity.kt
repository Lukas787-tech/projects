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
import com.lukas.jarvis.ui.rooms.HistoryActions
import com.lukas.jarvis.ui.rooms.HistoryRoom
import com.lukas.jarvis.ui.kit.ProvidePictures
import com.lukas.jarvis.ui.kit.shareText
import com.lukas.jarvis.ui.rooms.MusicActions
import com.lukas.jarvis.ui.rooms.MusicRoom
import com.lukas.jarvis.ui.rooms.DeviceActions
import com.lukas.jarvis.ui.rooms.DevicesRoom
import com.lukas.jarvis.ui.rooms.MapActions
import com.lukas.jarvis.ui.rooms.MapRoom
import com.lukas.jarvis.ui.kit.rememberStored
import com.lukas.jarvis.maps.Compass
import androidx.compose.runtime.produceState
import com.lukas.jarvis.ui.rooms.PowersRoom
import com.lukas.jarvis.ui.rooms.Intro
import com.lukas.jarvis.llm.Tier
import com.lukas.jarvis.llm.Abilities
import com.lukas.jarvis.llm.AbilitySwitch
import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.vm.Stage
import com.lukas.jarvis.ui.theme.MochiTheme
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.CafeMotion
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
                Surface(modifier = Modifier.fillMaxSize(), color = Cafe.colors.foam) { ProvidePictures {
                    JarvisRoot(
                        autoStartListening = startListeningOnOpen,
                        onAutoStartHandled = { startListeningOnOpen = false },
                        routineToRun = routineOnOpen,
                        onRoutineHandled = { routineOnOpen = null },
                        launch = launchOnOpen,
                        onLaunchHandled = { launchOnOpen = null }
                    )
                } }
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
    data object Money : Launch
    data object Lists : Launch
    data class Shared(val text: String?, val image: Uri?) : Launch

    companion object {
        fun from(intent: Intent?): Launch? = when (intent?.action) {
            // The assistant gesture (long-press power or home, once Jarvis is
            // the phone's assistant) means "listen", not merely "open".
            Entry.TALK, Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> Talk
            Entry.TYPE -> Type
            Entry.SCAN -> Scan
            Entry.TODAY -> Today
            Entry.MONEY -> Money
            Entry.LISTS -> Lists
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
    val streaks by viewModel.streaks.collectAsStateWithLifecycle()
    val repeats by viewModel.repeats.collectAsStateWithLifecycle()
    val tidied by viewModel.tidied.collectAsStateWithLifecycle()
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
    val mapStyle = MapStyle.of(settings.mapStyle, Cafe.colors.isDark)
    val scope = rememberCoroutineScope()

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val reduce = Cafe.reduceMotion
    val historyActions = remember(viewModel) {
        HistoryActions(
            onBack = { viewModel.showElement(Element.Globe) },
            onNewConversation = {
                viewModel.newConversation()
                viewModel.showElement(Element.Globe)
            },
            onShare = { text -> shareText(context, text, "Share the conversation") },
            onCopy = { text -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) },
            onSpeak = viewModel::speakMessage,
            onDelete = viewModel::deleteMessage,
            onRetry = {
                viewModel.retryLast()
                viewModel.showElement(Element.Globe)
            },
            onRemember = { message ->
                viewModel.rememberMessage(message)
                android.widget.Toast.makeText(context, "Kept in memory", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    // The day is gathered once at launch, so Today is ready the moment it opens.
    LaunchedEffect(Unit) {
        if (brief == null) viewModel.refreshBrief()
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
            Launch.Money -> viewModel.showElement(Element.Money)
            Launch.Lists -> viewModel.showElement(Element.Lists)
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
            onForgetCountdown = { id -> viewModel.forgetCountdown(id) },
            onDidHabit = viewModel::didHabit
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
            cancelPlaceReminder = viewModel::cancelPlaceReminder,
            didHabit = viewModel::didHabit,
            stopRepeat = viewModel::stopRepeat,
            bringBack = viewModel::bringBack,
            bringAllBack = viewModel::bringAllBack,
            keepTidy = viewModel::keepTidy
        )
    }

    // The first launch belongs to the introduction; everything else waits.
    if (!settings.onboarded) {
        Box(Modifier.fillMaxSize().background(Cafe.colors.foam).windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            Intro(
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
                HistoryRoom(messages = everything, name = settings.assistantName.ifBlank { "Mochi" }, actions = historyActions)
            } else AnimatedContent(
                targetState = element,
                // The Library's shelves are one room; moving between them is
                // not a change of room and should not fade the page.
                contentKey = { if (it in HUB) "library" else it.name },
                transitionSpec = {
                    fadeIn(CafeMotion.fade(reduce, 220)) togetherWith fadeOut(CafeMotion.fade(reduce, 120))
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
                    actions = todayActions,
                    streaks = streaks
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
                    defaultCurrency = settings.defaultCurrency,
                    streaks = streaks,
                    repeats = repeats,
                    tidied = tidied
                )
                Element.Skills -> PowersRoom(
                    settings = settings,
                    onBack = { viewModel.showElement(Element.Globe) },
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
                Element.Map -> {
                    var hintRead by rememberStored("map.hint.read", false)
                    val heading by produceState<Float?>(null) { Compass.headings(context).collect { value = it } }
                    MapRoom(
                        state = map,
                        tiles = viewModel.tiles,
                        style = mapStyle,
                        styleId = settings.mapStyle,
                        saved = savedPlaces,
                        hereLabel = hereLabel,
                        travelMode = settings.travelMode,
                        routing = routing,
                        heading = heading,
                        message = mapMessage,
                        hintRead = hintRead,
                        actions = MapActions(
                            onBack = { viewModel.showElement(Element.Globe) },
                            onSelect = viewModel::selectPlace,
                            onRoute = viewModel::routeToPlace,
                            onNavigate = viewModel::navigateToPlace,
                            onModeChange = viewModel::setTravelMode,
                            onClear = viewModel::clearMap,
                            onStyleChange = viewModel::setMapStyle,
                            onFollow = viewModel::followLocation,
                            onSaveHere = viewModel::saveHere,
                            onRouteSaved = viewModel::routeToSaved,
                            onDropPin = viewModel::dropPin,
                            onSaveSelected = viewModel::saveSelected,
                            onRenameSaved = viewModel::renameSaved,
                            onForgetSaved = viewModel::forgetSaved,
                            onMessageShown = viewModel::mapMessageShown,
                            onHintRead = { hintRead = true }
                        )
                    )
                }
                Element.Settings -> YouRoute(
                    viewModel = viewModel,
                    tab = when {
                        stage.note == SETTINGS_POWERS -> YouTab.Powers
                        // "settings:tab:2" opens a shelf by number, for links and the screenshots.
                        stage.note.startsWith("settings:tab:") -> YouTab.at(stage.note.substringAfterLast(':').toIntOrNull() ?: 0)
                        else -> YouTab.You
                    }
                )
                Element.Music -> {
                    LaunchedEffect(Unit) { viewModel.refreshLevels() }
                    MusicRoom(
                        nowPlaying = nowPlaying,
                        canSeeMedia = canSeeMedia,
                        mediaVolume = levels?.media,
                        actions = MusicActions(
                            onBack = { viewModel.showElement(Element.Globe) },
                            onPlay = viewModel::playMusic,
                            onPause = viewModel::pauseMusic,
                            onNext = viewModel::nextTrack,
                            onPrevious = viewModel::previousTrack,
                            onVolume = viewModel::setMediaVolume,
                            onOpenDevices = { viewModel.showElement(Element.Devices) },
                            onRefresh = viewModel::refreshNowPlaying,
                            onGrantAccess = viewModel::openNotificationAccess
                        )
                    )
                }
                Element.Devices -> DevicesRoom(
                    bluetooth = bluetooth,
                    phoneStatus = deviceStatus,
                    levels = levels,
                    actions = DeviceActions(
                        onBack = { viewModel.showElement(Element.Globe) },
                        onRefresh = {
                            viewModel.refreshBluetooth()
                            viewModel.refreshDeviceStatus()
                            viewModel.refreshLevels()
                        },
                        onOpenBluetooth = viewModel::openBluetoothSettings,
                        onTorch = viewModel::setTorch,
                        onVolume = viewModel::setVolumeLevel,
                        onBrightness = viewModel::setBrightnessLevel,
                        onAutoBrightness = viewModel::setAutoBrightness,
                        onRinger = viewModel::setRingerMode,
                        onQuiet = viewModel::setQuiet
                    )
                )
            } }

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

/** The note that opens You on its Powers shelf. */
private const val SETTINGS_POWERS = "settings:powers"

/** The note that opens the Library on its Notes shelf, which shares the memory element. */
private const val LIBRARY_NOTES = "library:notes"

/** The Library's shelves, each its own element so Mochi can open any of them by name. */
private val HUB = setOf(Element.Notes, Element.Tasks, Element.Money, Element.Lists)

/**
 * Which room in the bar lights up for the element on screen. Not every element
 * has a slot — music and the phone are reached by asking — so the bar shows the
 * room an element belongs to rather than going blank.
 */
private fun barSelection(element: Element): Element = when (element) {
    Element.Tasks, Element.Money, Element.Lists -> Element.Notes
    Element.Music, Element.Devices, Element.Skills -> Element.Today
    else -> element
}
