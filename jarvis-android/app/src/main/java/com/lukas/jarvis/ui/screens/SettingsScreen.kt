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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.llm.Providers
import com.lukas.jarvis.ui.components.JarvisCard
import com.lukas.jarvis.ui.components.Picker
import com.lukas.jarvis.ui.components.SectionLabel
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onSwitchProvider: (String) -> Unit,
    onPreviewVoice: () -> Unit,
    onClearConversation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preset = Providers.byId(settings.providerId)
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader(title = "Settings", subtitle = "All providers below have a free tier")

        // ------------------------------------------------------------- brain
        SectionLabel("Model")
        Picker(
            label = "Provider",
            value = settings.providerId,
            options = Providers.ALL.map { it.id },
            onSelect = onSwitchProvider,
            display = { Providers.byId(it).label }
        )
        Spacer(Modifier.height(8.dp))

        JarvisCard {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    preset.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                preset.keyUrl?.let { url ->
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }) {
                        Text("Get a free API key")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { value -> onUpdate { it.copy(baseUrl = value) } },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        if (preset.needsKey || settings.apiKey.isNotBlank()) {
            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = { value -> onUpdate { it.copy(apiKey = value) } },
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
            Spacer(Modifier.height(8.dp))
        }

        Picker(
            label = "Model",
            value = settings.model,
            options = (preset.models + settings.model).distinct(),
            onSelect = { value -> onUpdate { it.copy(model = value) } }
        )
        OutlinedTextField(
            value = settings.model,
            onValueChange = { value -> onUpdate { it.copy(model = value) } },
            label = { Text("Or type a model name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        // --------------------------------------------------------------- you
        SectionLabel("You")
        OutlinedTextField(
            value = settings.userName,
            onValueChange = { value -> onUpdate { it.copy(userName = value) } },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.assistantName,
            onValueChange = { value -> onUpdate { it.copy(assistantName = value) } },
            label = { Text("Assistant name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.defaultCurrency,
            onValueChange = { value ->
                onUpdate { it.copy(defaultCurrency = value.uppercase().take(5)) }
            },
            label = { Text("Default currency") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        // ------------------------------------------------------------- voice
        SectionLabel("Voice")
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
            subtitle = "Listen in the background. Uses noticeably more battery, " +
                "and can only open the app reliably while it is already running.",
            checked = settings.wakeWordEnabled,
            onChange = { value -> onUpdate { it.copy(wakeWordEnabled = value) } }
        )
        if (settings.wakeWordEnabled) {
            OutlinedTextField(
                value = settings.wakePhrase,
                onValueChange = { value ->
                    onUpdate { it.copy(wakePhrase = value.lowercase()) }
                },
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
        OutlinedButton(onClick = onPreviewVoice, modifier = Modifier.fillMaxWidth()) {
            Text("Preview voice")
        }

        Spacer(Modifier.height(20.dp))

        // ----------------------------------------------------------- behaviour
        SectionLabel("Behaviour")
        ToggleRow(
            title = "Internet access",
            subtitle = "Let Jarvis search the web. Free, no key needed.",
            checked = settings.webSearchEnabled,
            onChange = { value -> onUpdate { it.copy(webSearchEnabled = value) } }
        )
        ToggleRow(
            title = "Capture memories automatically",
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

        Spacer(Modifier.height(24.dp))
        Button(onClick = onClearConversation, modifier = Modifier.fillMaxWidth()) {
            Text("Clear conversation history")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Memories, trackers and tasks are kept — only the chat log is cleared. " +
                "Everything stays on this phone.",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint
        )
        Spacer(Modifier.height(40.dp))
    }
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
