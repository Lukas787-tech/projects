package com.lukas.jarvis.ui.rooms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lukas.jarvis.BuildConfig
import com.lukas.jarvis.control.ScreenReader
import com.lukas.jarvis.control.Tapper
import com.lukas.jarvis.core.Vault
import com.lukas.jarvis.llm.Tier
import com.lukas.jarvis.notify.ReplyListener
import com.lukas.jarvis.overlay.BubbleService
import com.lukas.jarvis.stage.Element
import com.lukas.jarvis.vm.AssistantViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The You room on the phone: the view model's flows gathered into a
 * [YouState], and everything that needs Android — system pages, the file
 * picker, the clipboard — done here so the room itself stays plain.
 */
@Composable
fun YouRoute(viewModel: AssistantViewModel, tab: YouTab, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val modelsState by viewModel.modelsState.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val pool by viewModel.poolEntries.collectAsStateWithLifecycle()
    val poolBusy by viewModel.poolBusy.collectAsStateWithLifecycle()
    val poolMessage by viewModel.poolMessage.collectAsStateWithLifecycle()
    val lastUsed by viewModel.lastUsedEndpoint.collectAsStateWithLifecycle()
    val voiceProblem by viewModel.voiceProblem.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    // Granted on system pages, so the truth is whatever holds on coming back.
    var access by remember { mutableStateOf(readAccess(context)) }
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = readAccess(context)
                resumed++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // The service is what can really be running or not, so it follows the switch.
    LaunchedEffect(settings.floatingDot, access.canDrawOver) {
        if (settings.floatingDot && access.canDrawOver) BubbleService.start(context) else BubbleService.stop(context)
    }

    var log by remember { mutableStateOf(emptyList<com.lukas.jarvis.data.ActionRecord>()) }
    LaunchedEffect(tab, resumed) {
        if (tab == YouTab.Data) log = runCatching { viewModel.actionLog() }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------- backup
    var note by remember { mutableStateOf<String?>(null) }
    var savePassphrase by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf(false) }
    suspend fun restore(text: String, passphrase: String?) {
        note = "Restoring…"
        val outcome = viewModel.restoreBackup(text, passphrase)
        locked = outcome.needsPassphrase
        waiting = if (locked) text else null
        note = outcome.said
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        note = "Saving…"
        scope.launch {
            note = runCatching {
                val text = viewModel.exportBackup(savePassphrase)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("that file can't be written")
                }
                if (savePassphrase != null) "Saved, locked with your passphrase. Without it the file can't be opened — keep it safe."
                else "Saved. Keep it somewhere private: it holds your memories and working keys."
            }.getOrElse { "Couldn't save: ${it.message ?: "unknown error"}" }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            }.getOrNull()
            if (text == null) note = "Couldn't read that file." else restore(text, null)
        }
    }

    val state = YouState(
        settings = settings,
        version = "Mochi ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
        availableModels = availableModels,
        modelsState = modelsState,
        testState = testState,
        pool = pool,
        poolBusy = poolBusy,
        poolMessage = poolMessage,
        poolSummary = remember(pool) { viewModel.poolSummary() },
        lastUsedEndpoint = lastUsed,
        hasFreeBrain = pool.any { it.endpoint.preset.tier == Tier.Keyless },
        voiceProblem = voiceProblem,
        profiles = profiles,
        access = access,
        backupNote = note,
        backupLocked = locked,
        actionLog = log
    )
    val actions = remember(viewModel) {
        YouActions(
            onBack = { viewModel.showElement(Element.Globe) },
            onTab = { t -> viewModel.showElement(Element.Settings, note = "settings:tab:${t.ordinal}") },
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
            onRestoreFreeBrain = viewModel::restoreFreeBrain,
            onOpenUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
            onPreviewVoice = viewModel::previewVoice,
            voices = viewModel::voices,
            fishVoices = viewModel::fishVoices,
            onSaveProfile = viewModel::saveProfile,
            onApplyProfile = viewModel::applyProfile,
            onDeleteProfile = viewModel::deleteProfile,
            onScheduleProfile = viewModel::scheduleProfile,
            onOpenSkills = { viewModel.showElement(Element.Skills) },
            onCheckHome = viewModel::checkHome,
            onAllow = { page ->
                runCatching {
                    context.startActivity(
                        when (page) {
                            Access.Replies -> ReplyListener.permissionIntent()
                            Access.PressSend -> Tapper.permissionIntent()
                            Access.ScreenReading -> ScreenReader.permissionIntent()
                            Access.DrawOver -> BubbleService.permissionIntent(context)
                        }
                    )
                }
            },
            onSaveBackup = { passphrase ->
                savePassphrase = passphrase
                // A phone without Android's file picker has nowhere to save to;
                // saying so beats closing the app.
                runCatching { save.launch(Vault.fileName()) }
                    .onFailure { note = "This phone has no file picker to save the backup with." }
            },
            onRestoreBackup = { passphrase ->
                val text = waiting
                // Some providers mislabel .json, so anything is offered and the contents decide.
                if (passphrase != null && text != null) scope.launch { restore(text, passphrase) }
                else runCatching { open.launch(arrayOf("application/json", "text/plain", "*/*")) }
                    .onFailure { note = "This phone has no file picker to open a backup with." }
            },
            onClearConversation = viewModel::clearConversation,
            onReplayIntro = { viewModel.updateSettings { it.copy(onboarded = false) } }
        )
    }
    YouRoom(state = state, tab = tab, actions = actions, modifier = modifier)
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun readAccess(context: Context) = SystemAccess(
    canText = granted(context, Manifest.permission.SEND_SMS),
    canReply = ReplyListener.isEnabled(context),
    canCall = granted(context, Manifest.permission.CALL_PHONE),
    canTap = Tapper.isEnabled(context),
    screenReading = ScreenReader.isEnabled(context),
    canDrawOver = BubbleService.canDraw(context)
)
