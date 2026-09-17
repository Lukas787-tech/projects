package com.lukas.jarvis.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
                    icon = Icons.Default.OpenInNew,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                )
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { value -> onUpdate { it.copy(baseUrl = value) } },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (preset.needsKey || settings.apiKey.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { value -> onUpdate { it.copy(apiKey = value.trim()) } },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
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
            OutlinedTextField(
                value = settings.model,
                onValueChange = { value -> onUpdate { it.copy(model = value.trim()) } },
                label = { Text("Or type a model id") },
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
            OutlinedTextField(
                value = settings.userName,
                onValueChange = { value -> onUpdate { it.copy(userName = value) } },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.assistantName,
                onValueChange = { value -> onUpdate { it.copy(assistantName = value) } },
                label = { Text("Assistant name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.defaultCurrency,
                onValueChange = { value ->
                    onUpdate { it.copy(defaultCurrency = value.uppercase().take(5)) }
                },
                label = { Text("Default currency") },
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
                OutlinedTextField(
                    value = settings.wakePhrase,
                    onValueChange = { value -> onUpdate { it.copy(wakePhrase = value.lowercase()) } },
                    label = { Text("Wake phrase") },
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
            OutlinedTextField(
                value = settings.maxTokens.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { parsed ->
                        onUpdate { it.copy(maxTokens = parsed.coerceIn(128, 8192)) }
                    }
                },
                label = { Text("Max reply length (tokens)") },
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
    Tier.Free -> "free tier"
    Tier.Trial -> "free credit"
    Tier.Local -> "your PC"
    Tier.Paid -> "paid"
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
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextFaint)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent)
        )
    }
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
