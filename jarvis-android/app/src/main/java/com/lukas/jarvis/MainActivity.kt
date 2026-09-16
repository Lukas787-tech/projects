package com.lukas.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lukas.jarvis.ui.screens.BrainScreen
import com.lukas.jarvis.ui.screens.HistoryScreen
import com.lukas.jarvis.ui.screens.SettingsScreen
import com.lukas.jarvis.ui.screens.TasksScreen
import com.lukas.jarvis.ui.screens.TrackersScreen
import com.lukas.jarvis.ui.screens.VoiceScreen
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Ink
import com.lukas.jarvis.ui.theme.InkRaised
import com.lukas.jarvis.ui.theme.JarvisTheme
import com.lukas.jarvis.ui.theme.TextFaint
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
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
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

private enum class Tab(val label: String, val icon: ImageVector) {
    Voice("Voice", Icons.Default.GraphicEq),
    Brain("Memory", Icons.Default.Psychology),
    Trackers("Trackers", Icons.Default.AccountBalanceWallet),
    Tasks("Tasks", Icons.Default.CheckCircle),
    Chat("Chat", Icons.Default.Chat),
    Settings("Settings", Icons.Default.Settings)
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

    var tab by remember { mutableStateOf(Tab.Voice) }

    LaunchedEffect(autoStartListening) {
        if (autoStartListening) {
            tab = Tab.Voice
            viewModel.startListening()
            onAutoStartHandled()
        }
    }

    // The service follows the setting rather than being toggled imperatively,
    // so it stays consistent after a process restart.
    LaunchedEffect(settings.wakeWordEnabled) {
        if (settings.wakeWordEnabled) {
            WakeWordService.start(context)
        } else {
            WakeWordService.stop(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (tab) {
                Tab.Voice -> VoiceScreen(
                    state = ui,
                    assistantName = settings.assistantName,
                    configured = settings.isConfigured,
                    onOrbTap = viewModel::toggleListening,
                    onSend = viewModel::sendTyped,
                    onDismissError = viewModel::dismissError,
                    onOpenHistory = { tab = Tab.Chat },
                    onOpenSettings = { tab = Tab.Settings }
                )

                Tab.Brain -> BrainScreen(
                    memories = memories,
                    onAdd = viewModel::addMemory,
                    onDelete = viewModel::deleteMemory,
                    onTogglePin = viewModel::togglePin
                )

                Tab.Trackers -> TrackersScreen(
                    trackers = trackers,
                    entries = entries,
                    defaultCurrency = settings.defaultCurrency,
                    onSaveTracker = viewModel::saveTracker,
                    onDeleteTracker = viewModel::deleteTracker,
                    onAddEntry = viewModel::addEntry,
                    onDeleteEntry = viewModel::deleteEntry
                )

                Tab.Tasks -> TasksScreen(
                    tasks = tasks,
                    onAdd = viewModel::addTask,
                    onToggle = viewModel::toggleTask,
                    onDelete = viewModel::deleteTask
                )

                Tab.Chat -> HistoryScreen(messages = ui.messages)

                Tab.Settings -> SettingsScreen(
                    settings = settings,
                    availableModels = availableModels,
                    modelsState = modelsState,
                    testState = testState,
                    onUpdate = viewModel::updateSettings,
                    onSwitchProvider = viewModel::switchProvider,
                    onRefreshModels = viewModel::refreshModels,
                    onTestConnection = viewModel::testConnection,
                    onPreviewVoice = viewModel::previewVoice,
                    onClearConversation = viewModel::clearConversation
                )
            }
        }

        JarvisNavBar(current = tab, onSelect = { tab = it })
    }
}

@Composable
private fun JarvisNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = InkRaised, tonalElevation = 0.dp) {
        Tab.entries.forEach { entry ->
            NavigationBarItem(
                selected = current == entry,
                onClick = { onSelect(entry) },
                icon = { Icon(entry.icon, contentDescription = entry.label) },
                label = { Text(entry.label) },
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = Accent,
                    unselectedIconColor = TextFaint,
                    unselectedTextColor = TextFaint,
                    indicatorColor = Accent.copy(alpha = 0.12f)
                )
            )
        }
    }
}
