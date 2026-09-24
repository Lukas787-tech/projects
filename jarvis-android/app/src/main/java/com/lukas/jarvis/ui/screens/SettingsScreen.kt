package com.lukas.jarvis.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lukas.jarvis.ui.components.GlassField
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.BuildConfig
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.control.Tapper
import com.lukas.jarvis.core.Vault
import com.lukas.jarvis.notify.ReplyListener
import com.lukas.jarvis.overlay.BubbleService
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.llm.PoolEntry
import com.lukas.jarvis.llm.Providers
import com.lukas.jarvis.llm.Tier
import com.lukas.jarvis.ui.components.Banner
import com.lukas.jarvis.ui.components.BannerTone
import com.lukas.jarvis.ui.components.ChipButton
import com.lukas.jarvis.ui.components.Panel
import com.lukas.jarvis.ui.components.Picker
import com.lukas.jarvis.ui.components.StatusDot
import com.lukas.jarvis.ui.components.ToggleRow
import com.lukas.jarvis.ui.theme.Positive
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.vm.ModelsState
import com.lukas.jarvis.vm.TestState

@Composable
fun SettingsScreen(
    settings: Settings,
    availableModels: List<String>,
    modelsState: ModelsState,
    testState: TestState,
    poolEntries: List<PoolEntry>,
    poolBusy: Boolean,
    poolMessage: String?,
    poolSummary: String,
    lastUsedEndpoint: String?,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onSwitchProvider: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onTestConnection: () -> Unit,
    onAddToPool: () -> Unit,
    onAddEveryProvider: () -> Unit,
    onRemoveFromPool: (String) -> Unit,
    onTogglePoolEntry: (String, Boolean) -> Unit,
    onWakePool: () -> Unit,
    onClearPool: () -> Unit,
    onPreviewVoice: () -> Unit,
    onClearConversation: () -> Unit,
    onExportBackup: () -> String,
    onRestoreBackup: (String) -> String,
    onOpenSkills: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val preset = Providers.byId(settings.providerId)
    var showKey by remember { mutableStateOf(false) }

    // Whatever the provider just told us, falling back to the baked-in guess
    // only until the first successful refresh.
    val modelOptions = remember(availableModels, preset, settings.model) {
        if (availableModels.isNotEmpty()) {
            (availableModels + settings.model).distinct()
        } else {
            (preset.fallbackModels + settings.model).distinct()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader(title = "Settings", subtitle = "Every provider here has a free tier")

        Panel(title = "Connection", subtitle = preset.note) {
            Picker(
                label = "Provider",
                value = settings.providerId,
                options = Providers.ALL.map { it.id },
                onSelect = onSwitchProvider,
                // With this many providers the label alone is not enough to
                // choose by; what an account costs is the thing you are picking.
                display = { id ->
                    val option = Providers.byId(id)
                    "${option.label} · ${tierLabel(option.tier)}"
                }
            )

            preset.keyUrl?.let { url ->
                Spacer(Modifier.height(12.dp))
                ChipButton(
                    label = "Get a free API key",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                )
            }

            Spacer(Modifier.height(14.dp))
            GlassField(
                value = settings.baseUrl,
                onValueChange = { value -> onUpdate { it.copy(baseUrl = value) } },
                label = "Base URL",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (preset.needsKey || settings.apiKey.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                GlassField(
                    value = settings.apiKey,
                    onValueChange = { value -> onUpdate { it.copy(apiKey = value.trim()) } },
                    label = "API key",
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        Text(
                            text = if (showKey) "HIDE" else "SHOW",
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clickable { showKey = !showKey }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))

            // The model list is fetched live. Baked-in ids go stale the moment a
            // provider retires one, and the failure looks like a generic 404.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Model", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                ChipButton(
                    label = if (availableModels.isEmpty()) "Load models" else "Refresh",
                    icon = Icons.Default.Refresh,
                    busy = modelsState is ModelsState.Loading,
                    onClick = onRefreshModels
                )
            }

            Spacer(Modifier.height(8.dp))
            Picker(
                label = "",
                value = settings.model,
                options = modelOptions,
                onSelect = { value -> onUpdate { it.copy(model = value) } }
            )

            Spacer(Modifier.height(6.dp))
            when (val state = modelsState) {
                is ModelsState.Idle -> Text(
                    "Showing built-in suggestions. Tap Load models to see what your " +
                        "key can actually call — providers retire model names often.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint
                )
                is ModelsState.Loading -> Text(
                    "Asking the provider…",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                is ModelsState.Loaded -> Text(
                    "${state.count} models available on this key.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint
                )
                is ModelsState.Failed -> Banner(
                    tone = BannerTone.Bad,
                    title = "Could not list models",
                    body = state.message
                )
            }

            Spacer(Modifier.height(10.dp))
            GlassField(
                value = settings.model,
                onValueChange = { value -> onUpdate { it.copy(model = value.trim()) } },
                label = "Or type a model id",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            ChipButton(
                label = "Test connection",
                icon = Icons.Default.Bolt,
                prominent = true,
                busy = testState is TestState.Running,
                onClick = onTestConnection,
                modifier = Modifier.fillMaxWidth()
            )

            when (val state = testState) {
                is TestState.Idle, is TestState.Running -> Unit
                is TestState.Passed -> {
                    Spacer(Modifier.height(12.dp))
                    Banner(
                        tone = if (state.toolsWork) BannerTone.Good else BannerTone.Neutral,
                        title = if (state.toolsWork) "Working" else "Replies, but no tool calling",
                        body = if (state.toolsWork) {
                            "Model answered: \"${state.reply}\". Memory and trackers will work."
                        } else {
                            "Model answered: \"${state.reply}\", but it did not accept a tool " +
                                "call. Jarvis falls back to a text protocol, which is less " +
                                "reliable — pick a model that supports tools if you can."
                        }
                    )
                    DiagnosticsBlock(state.diagnostics, clipboard)
                }
                is TestState.Failed -> {
                    Spacer(Modifier.height(12.dp))
                    Banner(
                        tone = BannerTone.Bad,
                        title = "Not working",
                        body = state.message
                    )
                    DiagnosticsBlock(state.diagnostics, clipboard)
                }
            }
        }

        Panel(
            title = "Model pool",
            subtitle = "Jarvis rotates through these, counts every call against the " +
                "provider's free-tier rate, and steps aside before a limit is hit " +
                "rather than after. A pool spanning several accounts survives a " +
                "daily cap; one spanning models on a single key does not."
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChipButton(
                    label = "Add ${preset.label}",
                    icon = Icons.Default.Add,
                    prominent = true,
                    busy = poolBusy,
                    onClick = onAddToPool,
                    modifier = Modifier.weight(1f)
                )
                ChipButton(
                    label = "Wake all",
                    icon = Icons.Default.Refresh,
                    onClick = onWakePool
                )
            }

            Spacer(Modifier.height(8.dp))
            ChipButton(
                label = "Add every provider I have a key for",
                icon = Icons.Default.Bolt,
                busy = poolBusy,
                onClick = onAddEveryProvider,
                modifier = Modifier.fillMaxWidth()
            )

            poolMessage?.let {
                Spacer(Modifier.height(10.dp))
                Banner(tone = BannerTone.Neutral, title = it)
            }

            if (poolEntries.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Pool is empty — Jarvis uses the single model selected above. " +
                        "Paste a key for each provider, tap Add, and switch provider " +
                        "to stack up more.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    poolSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                lastUsedEndpoint?.let {
                    Text(
                        "Last answer came from $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextFaint
                    )
                }
                Spacer(Modifier.height(6.dp))
                poolEntries.forEach { entry ->
                    PoolRow(
                        entry = entry,
                        onToggle = { onTogglePoolEntry(entry.endpoint.id, it) },
                        onRemove = { onRemoveFromPool(entry.endpoint.id) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                ChipButton(
                    label = "Empty the pool",
                    onClick = onClearPool,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Panel(title = "You", collapsible = true, initiallyExpanded = false) {
            GlassField(
                value = settings.userName,
                onValueChange = { value -> onUpdate { it.copy(userName = value) } },
                label = "Your name",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            GlassField(
                value = settings.assistantName,
                onValueChange = { value -> onUpdate { it.copy(assistantName = value) } },
                label = "Assistant name",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            GlassField(
                value = settings.defaultCurrency,
                onValueChange = { value ->
                    onUpdate { it.copy(defaultCurrency = value.uppercase().take(5)) }
                },
                label = "Default currency",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Panel(title = "Voice", collapsible = true, initiallyExpanded = false) {
            ToggleRow(
                title = "Speak replies",
                subtitle = "Read answers out loud",
                checked = settings.speakReplies,
                onChange = { value -> onUpdate { it.copy(speakReplies = value) } }
            )
            ToggleRow(
                title = "Hands free",
                subtitle = "Start listening again after each reply",
                checked = settings.handsFree,
                onChange = { value -> onUpdate { it.copy(handsFree = value) } }
            )
            ToggleRow(
                title = "Wake word",
                subtitle = "Listens in the background. Uses noticeably more battery, " +
                    "and can only open the app reliably while it is already running.",
                checked = settings.wakeWordEnabled,
                onChange = { value -> onUpdate { it.copy(wakeWordEnabled = value) } }
            )
            if (settings.wakeWordEnabled) {
                Spacer(Modifier.height(8.dp))
                GlassField(
                    value = settings.wakePhrase,
                    onValueChange = { value -> onUpdate { it.copy(wakePhrase = value.lowercase()) } },
                    label = "Wake phrase",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SliderRow(
                label = "Speech rate",
                value = settings.speechRate,
                range = 0.5f..2.0f,
                onChange = { value -> onUpdate { it.copy(speechRate = value) } }
            )
            SliderRow(
                label = "Pitch",
                value = settings.speechPitch,
                range = 0.5f..2.0f,
                onChange = { value -> onUpdate { it.copy(speechPitch = value) } }
            )
            Spacer(Modifier.height(8.dp))
            ChipButton(
                label = "Preview voice",
                onClick = onPreviewVoice,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Panel(title = "Behaviour", collapsible = true, initiallyExpanded = false) {
            ToggleRow(
                title = "Internet access",
                subtitle = "Let Jarvis search the web. Free, no key needed.",
                checked = settings.webSearchEnabled,
                onChange = { value -> onUpdate { it.copy(webSearchEnabled = value) } }
            )
            ToggleRow(
                title = "Capture automatically",
                subtitle = "Save facts and purchases without being asked",
                checked = settings.autoCapture,
                onChange = { value -> onUpdate { it.copy(autoCapture = value) } }
            )
            SliderRow(
                label = "Creativity",
                value = settings.temperature,
                range = 0f..1.2f,
                onChange = { value -> onUpdate { it.copy(temperature = value) } }
            )
            Spacer(Modifier.height(8.dp))
            GlassField(
                value = settings.maxTokens.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { parsed ->
                        onUpdate { it.copy(maxTokens = parsed.coerceIn(128, 8192)) }
                    }
                },
                label = "Max reply length (tokens)",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Panel(
            title = "Places and maps",
            subtitle = "OpenStreetMap data — no key, no account, no tracking"
        ) {
            ToggleRow(
                title = "Places and routes",
                subtitle = "Let Jarvis look up what is near you and draw the way there",
                checked = settings.mapsEnabled,
                onChange = { value -> onUpdate { it.copy(mapsEnabled = value) } }
            )
            if (settings.mapsEnabled) {
                Picker(
                    label = "Default travel mode",
                    value = settings.travelMode,
                    options = Geo.ALL_MODES,
                    onSelect = { mode -> onUpdate { it.copy(travelMode = mode) } },
                    display = { it.replaceFirstChar { first -> first.uppercase() } }
                )
                SliderRow(
                    label = "Search radius: ${Geo.formatDistance(settings.searchRadiusMeters.toDouble())}",
                    value = settings.searchRadiusMeters.toFloat(),
                    range = 300f..10_000f,
                    onChange = { value ->
                        onUpdate { it.copy(searchRadiusMeters = value.toInt()) }
                    }
                )
            }
        }

        Panel(
            title = "Abilities",
            subtitle = "What Jarvis is allowed to reach beyond the conversation. " +
                "Each one adds its tools to the assistant; switching a group off " +
                "keeps the model's choices short and its answers quicker."
        ) {
            ToggleRow(
                title = "Weather",
                subtitle = "Real forecasts for where you are. No key, no account.",
                checked = settings.weatherEnabled,
                onChange = { value -> onUpdate { it.copy(weatherEnabled = value) } }
            )
            ToggleRow(
                title = "Phone control",
                subtitle = "Alarms, timers, the torch, apps, texts, WhatsApp replies and " +
                    "calls. A call is always read back and confirmed before it rings.",
                checked = settings.deviceControlEnabled,
                onChange = { value -> onUpdate { it.copy(deviceControlEnabled = value) } }
            )
            ToggleRow(
                title = "Calendar",
                subtitle = "Read what is on today. Android will ask for the permission.",
                checked = settings.calendarEnabled,
                onChange = { value -> onUpdate { it.copy(calendarEnabled = value) } }
            )
            ToggleRow(
                title = "Contacts",
                subtitle = "Look up a number so \"text Anna\" works. Read only.",
                checked = settings.contactsEnabled,
                onChange = { value -> onUpdate { it.copy(contactsEnabled = value) } }
            )
            Spacer(Modifier.height(10.dp))
            // The switches say what can be turned off; the Skills screen says
            // what each one is for, with sentences to try it.
            ChipButton(
                label = "See what each one can do",
                icon = Icons.Default.AutoAwesome,
                onClick = onOpenSkills,
                modifier = Modifier.fillMaxWidth()
            )
        }

        AutomaticPanel()

        FloatingDotPanel(
            enabled = settings.floatingDot,
            onChange = { wanted -> onUpdate { it.copy(floatingDot = wanted) } }
        )

        BackupPanel(onExport = onExportBackup, onRestore = onRestoreBackup)

        Panel(title = "Data", collapsible = true, initiallyExpanded = false) {
            ChipButton(
                label = "Clear conversation history",
                onClick = onClearConversation,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Memories, trackers and tasks are kept — only the chat log is cleared. " +
                    "Everything stays on this phone.",
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint
            )
        }

        Spacer(Modifier.height(20.dp))
        // Printed so "which build is this?" is answerable at a glance rather
        // than by guesswork.
        Text(
            "Jarvis ${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * What Jarvis is allowed to finish on its own.
 *
 * A text it can send outright; a WhatsApp or Signal message it can answer
 * through the notification that message arrived on, which is the only route
 * Android offers and the same one a smartwatch uses. Both need permissions the
 * app cannot grant itself, so this shows whether each is really in place rather
 * than whether it was asked for.
 */
private fun granted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun AutomaticPanel() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canReply by remember { mutableStateOf(ReplyListener.isEnabled(context)) }
    var canText by remember { mutableStateOf(granted(context, Manifest.permission.SEND_SMS)) }
    var canCall by remember { mutableStateOf(granted(context, Manifest.permission.CALL_PHONE)) }
    var canTap by remember { mutableStateOf(Tapper.isEnabled(context)) }

    // Both are granted on a system page, so the truth is whatever is true on
    // returning from it, not whatever was true when this was first drawn.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canReply = ReplyListener.isEnabled(context)
                canText = granted(context, Manifest.permission.SEND_SMS)
                canCall = granted(context, Manifest.permission.CALL_PHONE)
                canTap = Tapper.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Panel(
        title = "Sending things",
        subtitle = "Jarvis finishes these itself. Nothing waits on a screen for you " +
            "to press send."
    ) {
        Text(
            if (canText) {
                "Texts go out as soon as you ask. Ready."
            } else {
                "Texts are only drafted until the SMS permission is granted."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (canText) Positive else TextSecondary
        )
        Spacer(Modifier.height(14.dp))
        Text(
            if (canReply) {
                "Replies to WhatsApp, Signal, Telegram and the rest go out too. Ready."
            } else {
                "Replying to other apps needs notification access. Without it, a " +
                    "message that arrives cannot be answered."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (canReply) Positive else TextSecondary
        )
        if (!canReply) {
            Spacer(Modifier.height(12.dp))
            ChipButton(
                label = "Allow notification access",
                onClick = { runCatching { context.startActivity(ReplyListener.permissionIntent()) } },
                prominent = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (canCall) {
                "Calls are placed after Jarvis reads the number back and you say yes."
            } else {
                "Calls only reach the dialler until the call permission is granted."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (canCall) Positive else TextSecondary
        )

        Spacer(Modifier.height(14.dp))
        Text(
            if (canTap) {
                "New WhatsApp, Telegram and Signal messages are sent outright. Ready."
            } else {
                "A new WhatsApp message is typed out for you and waits on one press. " +
                    "Switching Jarvis on under accessibility lets it press send itself."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (canTap) Positive else TextSecondary
        )
        if (!canTap) {
            Spacer(Modifier.height(12.dp))
            ChipButton(
                label = "Let Jarvis press send",
                onClick = { runCatching { context.startActivity(Tapper.permissionIntent()) } },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Jarvis reads notifications only to find the ones that can be answered, " +
                "keeps them in memory while they are on screen, and writes none of it " +
                "down. Pressing send is armed for a few seconds after it writes a " +
                "message and is inert the rest of the time.",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint
        )
    }
}

/**
 * The dot that floats over other apps.
 *
 * Android will not let an app grant itself the right to draw over everything
 * else — for good reason, since that is how an overlay would fake a login
 * screen — so the switch cannot simply be flipped. It opens the system page
 * instead, and reads the permission back every time the screen is resumed, so
 * returning from that page shows the truth rather than whatever was true when
 * the screen was first drawn.
 */
@Composable
private fun FloatingDotPanel(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(BubbleService.canDraw(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = BubbleService.canDraw(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The service is the thing that can really be running or not, so the switch
    // follows it rather than the other way round.
    LaunchedEffect(enabled, granted) {
        if (enabled && granted) BubbleService.start(context) else BubbleService.stop(context)
    }

    Panel(
        title = "Floating dot",
        subtitle = "Jarvis on top of whatever you are doing. Tap it to talk, " +
            "hold it to open the app, drag it to park it against an edge."
    ) {
        ToggleRow(
            title = "Show the dot everywhere",
            subtitle = if (granted) {
                "Runs as a notification you can tap to put it away."
            } else {
                "Needs permission to draw over other apps."
            },
            checked = enabled && granted,
            onChange = { wanted ->
                if (wanted && !granted) {
                    runCatching {
                        context.startActivity(BubbleService.permissionIntent(context))
                    }
                    // Left on, so granting the permission and coming back
                    // starts it without a second tap.
                    onChange(true)
                } else {
                    onChange(wanted)
                }
            }
        )
        if (enabled && !granted) {
            Spacer(Modifier.height(10.dp))
            ChipButton(
                label = "Grant permission",
                onClick = {
                    runCatching {
                        context.startActivity(BubbleService.permissionIntent(context))
                    }
                },
                prominent = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "The dot answers where you are — it speaks without bringing the app " +
                "to the front. It listens only while you are holding a turn with it.",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint
        )
    }
}

/**
 * Keeping the keys, which is the one thing here that cannot be re-created.
 *
 * Android's own cloud backup normally brings these back on reinstall, but only
 * with a Google account, only with backup switched on, and only during the
 * restore window at first setup — none of which this app can see, let alone
 * promise. So there is a file as well, written through the system file picker
 * to wherever the user wants it, which depends on none of that.
 */
@Composable
private fun BackupPanel(onExport: () -> String, onRestore: (String) -> String) {
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        note = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(onExport().toByteArray())
            } ?: error("could not open that file for writing")
            "Saved. Keep it somewhere private — it holds your working API keys."
        }.getOrElse { "Could not save: ${it.message ?: "unknown error"}" }
    }

    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().decodeToString()
            }
        }.getOrNull()
        note = if (text == null) "Could not read that file." else onRestore(text)
    }

    Panel(
        title = "Backup",
        subtitle = "Your API keys, endpoints, model pool and settings in one file. " +
            "Restore it after a reinstall and everything is back as it was."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipButton(
                label = "Save backup",
                icon = Icons.Default.Save,
                onClick = { save.launch(Vault.fileName()) },
                modifier = Modifier.weight(1f)
            )
            ChipButton(
                label = "Restore",
                icon = Icons.Default.Restore,
                // Some providers mislabel .json, so anything is offered and the
                // contents decide whether it is a backup.
                onClick = { open.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f)
            )
        }
        note?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Restoring replaces everything currently set, and the file is plain " +
                "text so it can always be opened.",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint
        )
    }
}

@Composable
private fun PoolRow(
    entry: PoolEntry,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val status = entry.status()
    val tone = when {
        !entry.endpoint.enabled -> BannerTone.Neutral
        status == "Ready" -> BannerTone.Good
        status == "Key rejected" -> BannerTone.Bad
        else -> BannerTone.Neutral
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusDot(tone)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.endpoint.model,
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.endpoint.enabled) TextPrimary else TextFaint
            )
            Text(
                detail(entry, status),
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint
            )
        }
        Switch(
            checked = entry.endpoint.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove",
                tint = TextFaint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** What a provider costs, in one word, for the picker. */
private fun tierLabel(tier: Tier): String = when (tier) {
    Tier.Keyless -> "no key"
    Tier.Free -> "free tier"
    Tier.Trial -> "free credit"
    Tier.Local -> "your PC"
    Tier.Custom -> "custom"
}

/**
 * The second line of a pool row. Everything here is something that changes what
 * Jarvis does next: whether the endpoint can call tools decides if it can reach
 * your trackers, and how fast it answers decides whether it gets picked first.
 */
private fun detail(entry: PoolEntry, status: String): String {
    val parts = mutableListOf(
        Providers.byId(entry.endpoint.providerId).label,
        status
    )
    entry.capabilityNote()?.let { parts += it }
    if (entry.health.latencyMs > 0) parts += "${entry.health.latencyMs / 100 / 10.0}s"
    if (entry.health.successes > 0) parts += "${entry.health.successes} ok"
    entry.health.lastError?.takeIf { entry.health.successes == 0L }?.let { parts += it.take(40) }
    return parts.joinToString(" · ")
}

/**
 * The exact URL, status and provider response. Without this, a failure report is
 * just "it doesn't work", which is not enough to fix anything.
 */
@Composable
private fun DiagnosticsBlock(diagnostics: String, clipboard: ClipboardManager) {
    if (diagnostics.isBlank()) return
    Spacer(Modifier.height(10.dp))
    Text(
        diagnostics,
        style = MaterialTheme.typography.labelSmall,
        color = TextFaint,
        fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(8.dp))
    ChipButton(
        label = "Copy details",
        icon = Icons.Default.ContentCopy,
        onClick = { clipboard.setText(AnnotatedString(diagnostics)) }
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                String.format(java.util.Locale.US, "%.2f", value),
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
