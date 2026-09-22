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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
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
import com.lukas.jarvis.ui.screens.BrainScreen
import com.lukas.jarvis.ui.screens.HistoryScreen
import com.lukas.jarvis.ui.screens.DevicesScreen
import com.lukas.jarvis.ui.screens.HubScreen
import com.lukas.jarvis.ui.screens.MusicScreen
import com.lukas.jarvis.ui.screens.MapScreen
import com.lukas.jarvis.ui.screens.SettingsScreen
import com.lukas.jarvis.ui.screens.TasksScreen
import com.lukas.jarvis.ui.screens.TodayScreen
import com.lukas.jarvis.ui.screens.TrackersScreen
import com.lukas.jarvis.ui.screens.VoiceScreen
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.ui.components.JarvisDot
import com.lukas.jarvis.ui.components.JarvisNavBar
import com.lukas.jarvis.ui.components.NavEntry
import com.lukas.jarvis.ui.theme.Ink
import com.lukas.jarvis.ui.theme.JarvisTheme
import com.lukas.jarvis.ui.theme.PageBackground
import com.lukas.jarvis.voice.WakeWordService
import com.lukas.jarvis.vm.AssistantViewModel

class MainActivity : ComponentActivity() {

    private var startListeningOnOpen by mutableStateOf(false)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startListeningOnOpen = intent?.getBooleanExtra(EXTRA_START_LISTENING, false) == true
        askForPermissions()

        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    JarvisRoot(
                        autoStartListening = startListeningOnOpen,
                        onAutoStartHandled = { startListeningOnOpen = false }
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
    }

    private fun askForPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            // Coarse is enough to answer "what is near me", and it is the one
            // users grant without thinking twice.
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            // Without this a text can only be drafted, and a draft waiting on a
            // screen is not what "send Anna a message" asked for.
            Manifest.permission.SEND_SMS
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
    Element.Music -> Icons.Default.MusicNote
    Element.Devices -> Icons.Default.Bluetooth
    Element.Settings -> Icons.Default.Settings
}

@Composable
private fun JarvisRoot(
    autoStartListening: Boolean,
    onAutoStartHandled: () -> Unit
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
    val poolBusy by viewModel.poolBusy.collectAsStateWithLifecycle()
    val poolMessage by viewModel.poolMessage.collectAsStateWithLifecycle()
    val lastUsedEndpoint by viewModel.lastUsedEndpoint.collectAsStateWithLifecycle()
    val map by viewModel.map.collectAsStateWithLifecycle()
    val routing by viewModel.routing.collectAsStateWithLifecycle()
    val bluetooth by viewModel.bluetooth.collectAsStateWithLifecycle()
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val brief by viewModel.brief.collectAsStateWithLifecycle()
    val briefLoading by viewModel.briefLoading.collectAsStateWithLifecycle()

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
            if (settings.calendarEnabled) add(Manifest.permission.READ_CALENDAR)
            if (settings.contactsEnabled) add(Manifest.permission.READ_CONTACTS)
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestOptional.launch(missing.toTypedArray())
    }

    // The chat log is an overlay, so the system back gesture has to close it
    // rather than leave the app — it is not a destination of its own.
    BackHandler(enabled = showHistory) { showHistory = false }

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
        showHistory = false
        // Voice mode shows the result on its own stage — the globe is mid-flight
        // towards it — so jumping tabs would interrupt the thing being watched.
        // Text mode has nowhere to put a map, so it goes to the map tab.
        if (!settings.voiceMode) viewModel.showElement(Element.Map)
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
                Lifecycle.Event.ON_RESUME -> WakeWordService.stop(context)
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

    Column(
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
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (showHistory) {
                HistoryScreen(messages = ui.messages, onBack = { showHistory = false })
            } else when (element) {
                Element.Today -> TodayScreen(
                    brief = brief,
                    loading = briefLoading,
                    trackers = trackers,
                    onRefresh = viewModel::refreshBrief,
                    onOpen = viewModel::showElement,
                    onCompleteTask = viewModel::toggleTask,
                    onPlayMusic = { viewModel.showElement(Element.Music) }
                )

                Element.Globe -> VoiceScreen(
                    state = ui,
                    assistantName = settings.assistantName,
                    configured = settings.isConfigured || poolEntries.isNotEmpty(),
                    voiceMode = settings.voiceMode,
                    onModeChange = { voice ->
                        viewModel.updateSettings { it.copy(voiceMode = voice) }
                    },
                    map = map,
                    tiles = viewModel.tiles,
                    onSend = viewModel::sendTyped,
                    onDismissError = viewModel::dismissError,
                    onOpenHistory = { showHistory = true },
                    onOpenSettings = { viewModel.showElement(Element.Settings) },
                    onOpenMap = { viewModel.showElement(Element.Map) }
                )

                // Notes, tasks and money are one element with three segments,
                // so each stays one tap from the others while the assistant can
                // still name any of them directly.
                Element.Notes, Element.Tasks, Element.Money -> HubScreen(
                    selected = when (element) {
                        Element.Money -> 1
                        Element.Tasks -> 2
                        else -> 0
                    },
                    onSelect = { index ->
                        viewModel.showElement(
                            when (index) {
                                1 -> Element.Money
                                2 -> Element.Tasks
                                else -> Element.Notes
                            }
                        )
                    },
                    memoryCount = memories.size,
                    trackerCount = trackers.size,
                    openTaskCount = tasks.count { !it.done },
                    memory = {
                        BrainScreen(
                            memories = memories,
                            onAdd = viewModel::addMemory,
                            onDelete = viewModel::deleteMemory,
                            onTogglePin = viewModel::togglePin,
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
                            embedded = true
                        )
                    }
                )

                Element.Map -> MapScreen(
                    state = map,
                    tiles = viewModel.tiles,
                    travelMode = settings.travelMode,
                    routing = routing,
                    onSelect = viewModel::selectPlace,
                    onRoute = viewModel::routeToPlace,
                    onNavigate = viewModel::navigateToPlace,
                    onModeChange = viewModel::setTravelMode,
                    onClear = viewModel::clearMap
                )

                Element.Music -> MusicScreen(
                    onPlay = viewModel::playMusic,
                    onPause = viewModel::pauseMusic,
                    onNext = viewModel::nextTrack,
                    onPrevious = viewModel::previousTrack,
                    onVolume = viewModel::setMediaVolume,
                    onOpenDevices = { viewModel.showElement(Element.Devices) }
                )

                Element.Devices -> DevicesScreen(
                    status = bluetooth,
                    phoneStatus = deviceStatus,
                    onRefresh = {
                        viewModel.refreshBluetooth()
                        viewModel.refreshDeviceStatus()
                    },
                    onOpenSettings = viewModel::openBluetoothSettings,
                    onTorch = viewModel::setTorch
                )

                Element.Settings -> SettingsScreen(
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
                    onRestoreBackup = viewModel::restoreBackup
                )
            }
        }

        // The dot has a strip of its own between the elements and the bar. It
        // could float over the content instead, but then it would sit on top of
        // whatever is at the bottom of the element underneath — the text field,
        // the last row of a list — and every element would have to leave a hole
        // for it. A strip cannot overlap anything, and the dot that really does
        // float over everything is the one outside the app.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentAlignment = Alignment.Center
        ) {
            JarvisDot(
                stage = ui.stage,
                level = ui.level,
                onTap = viewModel::toggleListening
            )
        }

        JarvisNavBar(
            entries = remember {
                Element.BAR.map { NavEntry(it.name, it.title, iconFor(it)) }
            },
            selectedId = barSelection(element).name,
            onSelect = { id ->
                showHistory = false
                Element.entries.firstOrNull { it.name == id }?.let(viewModel::showElement)
            }
        )
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
private fun barSelection(element: Element): Element = when (element) {
    Element.Tasks, Element.Money -> Element.Notes
    Element.Music, Element.Devices -> Element.Today
    else -> element
}
