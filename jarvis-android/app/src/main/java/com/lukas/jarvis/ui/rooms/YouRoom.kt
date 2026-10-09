package com.lukas.jarvis.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.auto.RoutineDays
import com.lukas.jarvis.core.Profile
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.ActionRecord
import com.lukas.jarvis.llm.Persona
import com.lukas.jarvis.llm.Personas
import com.lukas.jarvis.llm.PoolEntry
import com.lukas.jarvis.llm.Providers
import com.lukas.jarvis.llm.Tier
import com.lukas.jarvis.maps.Geo
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mochi
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.CafeTextField
import com.lukas.jarvis.ui.kit.ChoiceChip
import com.lukas.jarvis.ui.kit.Eyebrow
import com.lukas.jarvis.ui.kit.IconCircle
import com.lukas.jarvis.ui.kit.ListRow
import com.lukas.jarvis.ui.kit.PaperCard
import com.lukas.jarvis.ui.kit.QuietButton
import com.lukas.jarvis.ui.kit.SectionHeader
import com.lukas.jarvis.ui.kit.SliderRow
import com.lukas.jarvis.ui.kit.SwitchRow
import com.lukas.jarvis.ui.kit.Tone
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.Palette
import com.lukas.jarvis.voice.FishVoice
import com.lukas.jarvis.voice.FishVoiceOption
import com.lukas.jarvis.voice.VoiceOption
import com.lukas.jarvis.vm.ModelsState
import com.lukas.jarvis.vm.TestState
import kotlinx.coroutines.launch

/** The You room's shelves, in the order the old settings tabs had them. */
enum class YouTab(val label: String) {
    You("You"), Voice("Voice"), Look("Look"), Brain("Brain"), Powers("Powers"), Data("Your data");

    companion object {
        fun at(index: Int): YouTab = entries.getOrElse(index) { You }
    }
}

/** What the phone itself allows Mochi, read again whenever the app comes back to the front. */
data class SystemAccess(
    val canText: Boolean = false,
    val canReply: Boolean = false,
    val canCall: Boolean = false,
    val canTap: Boolean = false,
    val screenReading: Boolean = false,
    val canDrawOver: Boolean = false
)

/** A system page that grants one of those, opened for the person to decide on. */
enum class Access { Replies, PressSend, ScreenReading, DrawOver }

/** Everything the You room shows. */
data class YouState(
    val settings: Settings,
    val version: String,
    val availableModels: List<String> = emptyList(),
    val modelsState: ModelsState = ModelsState.Idle,
    val testState: TestState = TestState.Idle,
    val pool: List<PoolEntry> = emptyList(),
    val poolBusy: Boolean = false,
    val poolMessage: String? = null,
    val poolSummary: String = "",
    val lastUsedEndpoint: String? = null,
    val hasFreeBrain: Boolean = true,
    val voiceProblem: String? = null,
    val profiles: List<Profile> = emptyList(),
    val access: SystemAccess = SystemAccess(),
    /** What the last save or restore said. */
    val backupNote: String? = null,
    /** A backup was opened that needs its passphrase. */
    val backupLocked: Boolean = false,
    val actionLog: List<ActionRecord> = emptyList()
)

class YouActions(
    val onBack: () -> Unit,
    val onTab: (YouTab) -> Unit,
    val onUpdate: ((Settings) -> Settings) -> Unit,
    val onSwitchProvider: (String) -> Unit = {},
    val onRefreshModels: () -> Unit = {},
    val onTestConnection: () -> Unit = {},
    val onAddToPool: () -> Unit = {},
    val onAddEveryProvider: () -> Unit = {},
    val onRemoveFromPool: (String) -> Unit = {},
    val onTogglePoolEntry: (String, Boolean) -> Unit = { _, _ -> },
    val onWakePool: () -> Unit = {},
    val onClearPool: () -> Unit = {},
    val onRestoreFreeBrain: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onPreviewVoice: () -> Unit = {},
    val voices: () -> List<VoiceOption> = { emptyList() },
    val fishVoices: suspend (String, String, Boolean) -> Result<List<FishVoiceOption>> = { _, _, _ -> Result.success(emptyList()) },
    val onSaveProfile: (String) -> Unit = {},
    val onApplyProfile: (Profile) -> Unit = {},
    val onDeleteProfile: (String) -> Unit = {},
    val onScheduleProfile: (String, String, String) -> Unit = { _, _, _ -> },
    val onOpenSkills: () -> Unit = {},
    val onCheckHome: suspend () -> String = { "" },
    val onAllow: (Access) -> Unit = {},
    /** Opens the file picker to save a backup, locked with the passphrase when there is one. */
    val onSaveBackup: (String?) -> Unit = {},
    /** Opens a backup — or, when one is waiting on its passphrase, tries it with this one. */
    val onRestoreBackup: (String?) -> Unit = {},
    val onClearConversation: () -> Unit = {},
    val onReplayIntro: () -> Unit = {}
)

/**
 * You: who Mochi is to you, how it sounds and looks, the brain it thinks with,
 * what it may reach on the phone, and your data. One row of shelves, the same
 * six the settings always had, so nothing that was here is missing.
 */
@Composable
fun YouRoom(state: YouState, tab: YouTab, actions: YouActions, modifier: Modifier = Modifier) {
    val settings = state.settings
    RoomScaffold(
        title = "You",
        subtitle = "Everything here is free — no paid keys, ever",
        onBack = actions.onBack,
        modifier = modifier,
        mochi = CharacterState(Mood.Idle, description = "${settings.assistantName.ifBlank { "Mochi" }}, listening"),
        header = {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)
            ) {
                YouTab.entries.forEach { t -> ChoiceChip(t.label, t == tab, { actions.onTab(t) }) }
            }
        }
    ) {
        when (tab) {
            YouTab.You -> youShelf(settings, actions)
            YouTab.Voice -> voiceShelf(state, actions)
            YouTab.Look -> lookShelf(state, actions)
            YouTab.Brain -> brainShelf(state, actions)
            YouTab.Powers -> powersShelf(state, actions)
            YouTab.Data -> dataShelf(state, actions)
        }
        item(key = "version") {
            // Printed so "which build is this?" is answerable at a glance.
            Text(
                state.version,
                style = Cafe.type.caption,
                color = Cafe.colors.cocoa,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Cafe.space.m)
            )
        }
    }
}

// ----------------------------------------------------------------------- you

private fun LazyListScope.youShelf(settings: Settings, actions: YouActions) {
    val update = actions.onUpdate
    item(key = "hero") {
        val persona = Personas.byId(settings.personality)
        PaperCard(Modifier.fillMaxWidth(), tone = Tone.Honey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mochi(CharacterState(Mood.Delighted, description = "Mochi"), size = 72.dp, showTag = false)
                Column(Modifier.weight(1f).padding(start = Cafe.space.m)) {
                    Text(settings.assistantName.ifBlank { "Mochi" }, style = Cafe.type.headline, color = Cafe.colors.espresso)
                    Text(
                        "${persona.label} · ${Personas.address(settings).ifBlank { "calls you by name" }}",
                        style = Cafe.type.labelSmall,
                        color = Cafe.colors.accentText
                    )
                    Text("“${persona.sample}”", style = Cafe.type.voiceSmall.copy(fontStyle = FontStyle.Italic), color = Cafe.colors.cocoa, maxLines = 3)
                }
            }
        }
    }
    item(key = "names") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Names")
            CafeTextField(settings.assistantName, { v -> update { it.copy(assistantName = v) } }, label = "Mochi's name", placeholder = "Mochi")
            VSpace(Cafe.space.m)
            CafeTextField(settings.userName, { v -> update { it.copy(userName = v) } }, label = "Your name")
            VSpace(Cafe.space.m)
            Eyebrow("How Mochi addresses you")
            VSpace(Cafe.space.xs)
            Wrap {
                Personas.ADDRESSES.forEach { address ->
                    ChoiceChip(
                        if (address.isBlank()) "My name" else address.replaceFirstChar { it.uppercase() },
                        settings.honorific.trim().equals(address, ignoreCase = true),
                        { update { it.copy(honorific = address) } }
                    )
                }
            }
            VSpace(Cafe.space.s)
            CafeTextField(settings.honorific, { v -> update { it.copy(honorific = v) } }, placeholder = "Or your own: Captain, Doctor…")
        }
    }
    item(key = "persona") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Personality")
            Text("The same Mochi and the same facts — only the voice changes.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.s)
            Personas.ALL.forEach { persona ->
                PersonaRow(persona, persona.id == settings.personality) { update { Personas.choose(it, persona.id) } }
            }
        }
    }
    item(key = "answers") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("How Mochi answers")
            Eyebrow("Length")
            VSpace(Cafe.space.xs)
            Wrap {
                Personas.LENGTHS.forEach { (id, label) -> ChoiceChip(label, settings.replyLength == id, { update { it.copy(replyLength = id) } }) }
            }
            VSpace(Cafe.space.m)
            Eyebrow("Wit")
            VSpace(Cafe.space.xs)
            Wrap {
                Personas.WIT.forEachIndexed { index, label -> ChoiceChip(label, settings.wit.coerceIn(0, 3) == index, { update { it.copy(wit = index) } }) }
            }
            VSpace(Cafe.space.m)
            CafeTextField(settings.replyLanguage, { v -> update { it.copy(replyLanguage = v) } }, label = "Always answer in (optional)", placeholder = "German, English, Español…")
            SwitchRow("Suggest a next step", settings.proactive, { v -> update { it.copy(proactive = v) } }, detail = "Offer the obvious follow-up in a few words")
            SwitchRow("Emoji in typed replies", settings.emoji, { v -> update { it.copy(emoji = v) } }, detail = "Never spoken either way")
        }
    }
    item(key = "quick") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Quick commands")
            Text("Your own one-tap phrases on the canvas, one per line — anything you'd say.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.s)
            CafeTextField(
                settings.quickCommands,
                { v -> update { it.copy(quickCommands = v.take(600)) } },
                placeholder = "Brief me\nTurn off all the lights\nLog a coffee, 3 euros",
                singleLine = false,
                minLines = 3,
                imeAction = ImeAction.Default
            )
        }
    }
    item(key = "yours") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Make it yours")
            Text("This goes into every conversation. Write it the way you'd brief a new friend.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.s)
            CafeTextField(
                settings.aboutMe,
                { v -> update { it.copy(aboutMe = v.take(1200)) } },
                label = "About you",
                placeholder = "I'm a student in Berlin, vegetarian, training for a half marathon, my sister is Anna…",
                singleLine = false,
                minLines = 3,
                imeAction = ImeAction.Default,
                supporting = "${settings.aboutMe.length} / 1200"
            )
            VSpace(Cafe.space.m)
            CafeTextField(
                settings.customInstructions,
                { v -> update { it.copy(customInstructions = v.take(1500)) } },
                label = "How Mochi should behave",
                placeholder = "Be blunt. Use metric. Call me out when I overspend…",
                singleLine = false,
                minLines = 3,
                imeAction = ImeAction.Default,
                supporting = "${settings.customInstructions.length} / 1500"
            )
        }
    }
}

@Composable
private fun PersonaRow(persona: Persona, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Cafe.space.xxs)
            .clip(Cafe.shape.medium)
            .background(if (selected) Cafe.colors.accentSoft else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Cafe.space.m, vertical = Cafe.space.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(persona.label, style = Cafe.type.title, color = Cafe.colors.espresso)
            Text(persona.tagline, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            if (selected && persona.id != Personas.CUSTOM) {
                Text("“${persona.sample}”", style = Cafe.type.voiceSmall.copy(fontStyle = FontStyle.Italic), color = Cafe.colors.cocoa)
            }
        }
        if (selected) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Cafe.colors.accentFill), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Cafe.colors.onAccent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// --------------------------------------------------------------------- voice

private fun LazyListScope.voiceShelf(state: YouState, actions: YouActions) {
    val settings = state.settings
    val update = actions.onUpdate
    item(key = "engine") { EngineCard(state, actions) }
    item(key = "speaking") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Speaking")
            SwitchRow("Speak replies", settings.speakReplies, { v -> update { it.copy(speakReplies = v) } }, detail = "Read answers out loud")
            SwitchRow("Hands free", settings.handsFree, { v -> update { it.copy(handsFree = v) } }, detail = "Listen again after each spoken reply")
            SwitchRow("Morning brief", settings.morningBrief, { v -> update { it.copy(morningBrief = v) } }, detail = "On the first open of a morning: the weather, what's due, the top story")
            VSpace(Cafe.space.s)
            TimeChoice("Written brief", "The same brief as a notification at a set time", settings.briefTime, listOf("06:00", "06:30", "07:00", "07:30", "08:00", "09:00")) { t -> update { it.copy(briefTime = t) } }
            VSpace(Cafe.space.m)
            TimeChoice("Evening wrap-up", "What got done and spent today, and what tomorrow holds", settings.eveningTime, listOf("18:00", "19:00", "20:00", "21:00", "22:00")) { t -> update { it.copy(eveningTime = t) } }
            VSpace(Cafe.space.s)
            SwitchRow("Listening tones", settings.earcons, { v -> update { it.copy(earcons = v) } }, detail = "A short tone when the microphone opens and when it heard you")
            SwitchRow("Haptics", settings.haptics, { v -> update { it.copy(haptics = v) } }, detail = "A tick under your finger")
            SliderRow("Speed", settings.speechRate, { v -> update { it.copy(speechRate = v) } }, range = 0.5f..2.0f, valueLabel = "%.2f×".format(settings.speechRate))
            SliderRow("Pitch", settings.speechPitch, { v -> update { it.copy(speechPitch = v) } }, range = 0.5f..2.0f, valueLabel = "%.2f".format(settings.speechPitch))
            VSpace(Cafe.space.s)
            CafeButton("Hear it", actions.onPreviewVoice, icon = Icons.AutoMirrored.Rounded.VolumeUp)
        }
    }
    item(key = "phone-voices") { PhoneVoices(settings, actions) }
    item(key = "language") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Language")
            SwitchRow(
                "Recognise the language by itself",
                settings.autoLanguage,
                { v -> update { it.copy(autoLanguage = v) } },
                detail = "French is read by a French voice, Spanish by a Spanish one. On Android 14 and later it also hears which language you speak."
            )
            VSpace(Cafe.space.s)
            Text("The main language to listen for and speak in. Phone follows the phone.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.xs)
            Wrap {
                listOf("", "en-US", "en-GB", "de-DE", "es-ES", "fr-FR", "it-IT", "tr-TR").forEach { tag ->
                    ChoiceChip(if (tag.isBlank()) "Phone" else tag, settings.speechLanguage.equals(tag, ignoreCase = true), { update { it.copy(speechLanguage = tag, voiceName = "") } })
                }
            }
            VSpace(Cafe.space.s)
            CafeTextField(settings.speechLanguage, { v -> update { it.copy(speechLanguage = v.trim(), voiceName = "") } }, label = "Language tag", placeholder = "e.g. pt-BR", keyboardType = KeyboardType.Ascii)
        }
    }
    item(key = "wake") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Wake word")
            SwitchRow(
                "Listen for its name",
                settings.wakeWordEnabled,
                { v -> update { it.copy(wakeWordEnabled = v) } },
                detail = "Works while the app is closed. Uses noticeably more battery."
            )
            if (settings.wakeWordEnabled) {
                VSpace(Cafe.space.s)
                CafeTextField(settings.wakePhrase, { v -> update { it.copy(wakePhrase = v.lowercase()) } }, label = "Wake phrase", placeholder = "hey mochi")
            }
        }
    }
}

@Composable
private fun TimeChoice(title: String, detail: String, value: String, times: List<String>, onPick: (String) -> Unit) {
    Text(title, style = Cafe.type.body, color = Cafe.colors.espresso)
    Text(detail, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
    VSpace(Cafe.space.xs)
    Wrap {
        (listOf("") + (times + listOfNotNull(value.takeIf { it.isNotBlank() })).distinct().sorted()).forEach { t ->
            ChoiceChip(if (t.isBlank()) "Off" else t, value == t, { onPick(t) })
        }
    }
}

/** The cloud voice: Fish Audio with the person's own key, a model, and a voice from its library. */
@Composable
private fun EngineCard(state: YouState, actions: YouActions) {
    val settings = state.settings
    val update = actions.onUpdate
    val fish = settings.voiceEngine == FishVoice.ENGINE
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Voice")
        Text(
            "The phone's own voices work offline. Fish Audio S2.1 sounds close to a person, in every language, with your own key from fish.audio.",
            style = Cafe.type.bodySmall,
            color = Cafe.colors.cocoa
        )
        VSpace(Cafe.space.s)
        Wrap {
            ChoiceChip("Phone", !fish, { update { it.copy(voiceEngine = FishVoice.DEVICE) } })
            ChoiceChip("Fish Audio S2.1", fish, { update { it.copy(voiceEngine = FishVoice.ENGINE) } })
        }
        if (!fish) return@PaperCard
        VSpace(Cafe.space.m)
        CafeTextField(
            settings.fishKey,
            { v -> update { it.copy(fishKey = v.trim()) } },
            label = "Fish Audio API key",
            placeholder = "sk-…",
            secret = true,
            isError = settings.fishKey.isBlank(),
            supporting = if (settings.fishKey.isBlank()) "Needed — until then the phone's voice speaks." else null
        )
        VSpace(Cafe.space.m)
        Eyebrow("Model")
        VSpace(Cafe.space.xs)
        Wrap {
            (FishVoice.MODELS + settings.fishModel).distinct().forEach { id ->
                val label = when (id) {
                    "s2.1-pro" -> "S2.1 Pro"
                    "s2.1-pro-free" -> "S2.1 free"
                    "s2-pro" -> "S2 Pro"
                    "s1" -> "S1"
                    else -> id
                }
                ChoiceChip(label, settings.fishModel == id, { update { it.copy(fishModel = id) } })
            }
        }
        Text("If the account has no credit for Pro, the free S2.1 tier is used by itself.", style = Cafe.type.caption, color = Cafe.colors.cocoa, modifier = Modifier.padding(top = Cafe.space.xs))
        VSpace(Cafe.space.m)
        Eyebrow("Voice")
        Text(
            if (settings.fishVoiceId.isBlank()) "Fish's default voice"
            else "${settings.fishVoiceName.ifBlank { "Custom voice" }} · ${settings.fishVoiceId.take(8)}…",
            style = Cafe.type.body,
            color = Cafe.colors.espresso
        )
        var query by rememberSaveable { mutableStateOf("") }
        var language by rememberSaveable { mutableStateOf("") }
        var mine by remember { mutableStateOf(false) }
        var found by remember { mutableStateOf<List<FishVoiceOption>>(emptyList()) }
        var status by remember { mutableStateOf<String?>(null) }
        var searching by remember { mutableIntStateOf(0) }
        LaunchedEffect(searching) {
            if (searching == 0) return@LaunchedEffect
            status = "Looking…"
            actions.fishVoices(query, language, mine)
                .onSuccess {
                    found = it
                    status = if (it.isEmpty()) "Nothing found." else null
                }
                .onFailure { status = it.message ?: "Fish Audio could not be reached." }
        }
        fun search() {
            val pasted = FishVoice.voiceIdOf(query)
            if (Regex("[0-9a-f]{32}").matches(pasted)) {
                update { it.copy(fishVoiceId = pasted, fishVoiceName = "") }
                actions.onPreviewVoice()
            } else {
                mine = false
                searching++
            }
        }
        VSpace(Cafe.space.s)
        CafeTextField(query, { query = it }, placeholder = "Search voices, or paste a voice id or link", imeAction = ImeAction.Search, onDone = { search() }, keyboardType = KeyboardType.Ascii)
        VSpace(Cafe.space.s)
        Wrap {
            listOf("", "de", "en", "fr", "es", "it", "ja").forEach { code ->
                ChoiceChip(if (code.isBlank()) "Any language" else code.uppercase(), language == code, {
                    language = code
                    mine = false
                    searching++
                })
            }
        }
        VSpace(Cafe.space.s)
        Wrap {
            CafeButton("Search", { search() }, kind = ButtonKind.Secondary)
            CafeButton("My voices", { mine = true; searching++ }, kind = ButtonKind.Secondary)
            QuietButton("Use the default", {
                update { it.copy(fishVoiceId = "", fishVoiceName = "") }
                actions.onPreviewVoice()
            })
        }
        status?.let { Text(it, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa, modifier = Modifier.padding(vertical = Cafe.space.xs)) }
        found.take(20).forEach { voice ->
            ChoiceRow(
                voice.title,
                listOfNotNull(
                    voice.languages.take(4).joinToString(" ").uppercase().takeIf { it.isNotBlank() },
                    voice.uses.takeIf { it > 0 }?.let { "%,d uses".format(it) }
                ).joinToString(" · "),
                voice.id == settings.fishVoiceId
            ) {
                update { it.copy(fishVoiceId = voice.id, fishVoiceName = voice.title) }
                actions.onPreviewVoice()
            }
        }
        state.voiceProblem?.let {
            VSpace(Cafe.space.s)
            Text("Last problem: $it. The phone's voice filled in.", style = Cafe.type.bodySmall, color = Cafe.colors.berryText)
        }
    }
}

@Composable
private fun PhoneVoices(settings: Settings, actions: YouActions) {
    val fish = settings.voiceEngine == FishVoice.ENGINE
    var open by rememberSaveable(fish) { mutableStateOf(!fish) }
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader(if (fish) "Phone voice" else "Phone voices", action = if (open) "Hide" else "Show", onAction = { open = !open })
        Text(
            if (fish) "Stands in whenever Fish Audio can't be reached — offline, or the key refused."
            else "The voices on this phone for the language it speaks. More can be downloaded in Android's text-to-speech settings — free.",
            style = Cafe.type.bodySmall,
            color = Cafe.colors.cocoa
        )
        if (!open) return@PaperCard
        var list by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
        LaunchedEffect(settings.speechLanguage) {
            // The engine may still be starting; ask again once or twice.
            repeat(4) {
                list = actions.voices()
                if (list.isNotEmpty()) return@LaunchedEffect
                kotlinx.coroutines.delay(700)
            }
        }
        VSpace(Cafe.space.s)
        ChoiceRow("Engine default", "Whatever the phone picks", settings.voiceName.isBlank()) {
            actions.onUpdate { it.copy(voiceName = "") }
            actions.onPreviewVoice()
        }
        if (list.isEmpty()) Text("No other voices reported yet.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa, modifier = Modifier.padding(vertical = Cafe.space.xs))
        list.take(24).forEach { voice ->
            ChoiceRow(voice.label, voice.language, voice.name == settings.voiceName) {
                actions.onUpdate { it.copy(voiceName = voice.name) }
                actions.onPreviewVoice()
            }
        }
    }
}

/** One of several, picked with a tap: a voice, a provider. */
@Composable
private fun ChoiceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Cafe.space.touch)
            .clip(Cafe.shape.medium)
            .background(if (selected) Cafe.colors.accentSoft else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Cafe.space.m, vertical = Cafe.space.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Cafe.type.body, color = Cafe.colors.espresso, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = Cafe.type.caption, color = Cafe.colors.cocoa, maxLines = 1)
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Cafe.colors.accentText, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------------- look

private fun LazyListScope.lookShelf(state: YouState, actions: YouActions) {
    val settings = state.settings
    val update = actions.onUpdate
    item(key = "theme") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("The café")
            Text("The latte café by day, the night café after dark — or one of them always.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.s)
            Wrap {
                listOf("system" to "Follow the phone", "light" to "Latte", "dark" to "Night café").forEach { (id, label) ->
                    ChoiceChip(label, settings.themeMode == id, { update { it.copy(themeMode = id) } })
                }
            }
        }
    }
    item(key = "accent") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Accent")
            Text("The colour of buttons, chips and Mochi's things. Text stays readable whatever you pick.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.m)
            Wrap {
                Palette.ACCENTS.forEach { accent ->
                    Swatch(accent.label, Color(accent.color), settings.accent == accent.id) { update { it.copy(accent = accent.id) } }
                }
            }
            VSpace(Cafe.space.m)
            Text("Or any colour", style = Cafe.type.body, color = Cafe.colors.espresso)
            HueSlider(Palette.customHue(settings.accent)) { hue -> update { it.copy(accent = "custom:${hue.toInt()}") } }
        }
    }
    item(key = "mochi") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Mochi and motion")
            SwitchRow("Idle moments", settings.characterIdle, { v -> update { it.copy(characterIdle = v) } }, detail = "Now and then, with nothing going on, a stretch or a sip")
            SwitchRow("Little celebrations", settings.characterDelights, { v -> update { it.copy(characterDelights = v) } }, detail = "A happy hop when a timer finishes or a list is done")
            SwitchRow("Calm motion", settings.reduceMotion, { v -> update { it.copy(reduceMotion = v) } }, detail = "Fewer moving parts. The phone's own \"remove animations\" is followed too.")
            SliderRow(
                "Text size",
                settings.textScale,
                { v -> update { it.copy(textScale = (v * 20).toInt() / 20f) } },
                range = 0.85f..1.4f,
                valueLabel = "${(settings.textScale * 100).toInt()}%"
            )
        }
    }
    item(key = "map") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("The map")
            Wrap {
                listOf("auto" to "Like the café", "light" to "Light", "dark" to "Dark", "streets" to "Streets", "satellite" to "Satellite").forEach { (id, label) ->
                    ChoiceChip(label, settings.mapStyle == id, { update { it.copy(mapStyle = id) } })
                }
            }
        }
    }
    item(key = "profiles") { ProfilesCard(state, actions) }
}

@Composable
private fun Swatch(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(68.dp)
            .clip(Cafe.shape.medium)
            .clickable(role = Role.RadioButton, onClickLabel = "Use $label", onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = Cafe.space.xs),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .then(if (selected) Modifier.border(3.dp, Cafe.colors.espresso, CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Cafe.colors.espresso, modifier = Modifier.size(20.dp))
        }
        Text(label, style = Cafe.type.caption, color = if (selected) Cafe.colors.espresso else Cafe.colors.cocoa, maxLines = 1, modifier = Modifier.padding(top = Cafe.space.xxs))
    }
}

/** A rainbow to slide along; moving it switches the accent to that hue at once. */
@Composable
private fun HueSlider(hue: Float?, onPick: (Float) -> Unit) {
    var value by remember(hue) { mutableFloatStateOf(hue ?: 30f) }
    val rainbow = remember { Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.45f, 0.82f) }) }
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp).clip(CircleShape).background(rainbow))
        Slider(
            value = value,
            onValueChange = {
                value = it
                onPick(it)
            },
            valueRange = 0f..359f,
            colors = SliderDefaults.colors(
                thumbColor = if (hue != null) Color.hsv(value, 0.45f, 0.82f) else Cafe.colors.cocoa,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your own accent colour" }
        )
    }
}

/** A whole look, character and voice under one name, switched to with a tap or at a time. */
@Composable
private fun ProfilesCard(state: YouState, actions: YouActions) {
    var naming by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Profile?>(null) }
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Profiles")
        Text("A whole look, character and voice under one name. Tap to switch — or say \"switch to night mode\".", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
        if (state.profiles.isNotEmpty()) {
            VSpace(Cafe.space.s)
            state.profiles.forEach { profile ->
                ListRow(
                    title = profile.name,
                    subtitle = listOfNotNull(
                        "In use".takeIf { profile.matches(state.settings) },
                        profile.autoAt.takeIf { it.isNotBlank() }?.let { at ->
                            "by itself at $at" + if (profile.autoDays.isEmpty()) "" else ", " + RoutineDays.describe(profile.autoDays).removePrefix("on ")
                        }
                    ).joinToString(" · ").ifBlank { null },
                    onClick = { actions.onApplyProfile(profile) },
                    clickLabel = "Switch to ${profile.name}",
                    trailing = { QuietButton("Change", { editing = profile }) }
                )
            }
        }
        VSpace(Cafe.space.s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            CafeTextField(naming, { naming = it.take(24) }, placeholder = "Save the current setup as…", modifier = Modifier.weight(1f), onDone = {
                if (naming.isNotBlank()) {
                    actions.onSaveProfile(naming.trim())
                    naming = ""
                }
            })
            IconCircle(Icons.Rounded.Add, "Save the profile", {
                if (naming.isNotBlank()) {
                    actions.onSaveProfile(naming.trim())
                    naming = ""
                }
            }, filled = true, enabled = naming.isNotBlank())
        }
    }
    editing?.let { profile ->
        var time by remember(profile.name) { mutableStateOf(profile.autoAt) }
        var days by remember(profile.name) { mutableStateOf(if (profile.autoDays.isEmpty()) "" else RoutineDays.describe(profile.autoDays)) }
        var sure by remember(profile.name) { mutableStateOf(false) }
        val timeOk = time.isBlank() || Profile.clockOf(time) != null
        com.lukas.jarvis.ui.kit.CafeSheet(profile.name, onDismiss = { editing = null }) {
            Text("Switch to it by itself at a time of day. Leave the time empty for never.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.m)
            CafeTextField(time, { time = it.take(5) }, label = "Time", placeholder = "22:00", isError = !timeOk, supporting = if (!timeOk) "A time like 22:00" else null)
            VSpace(Cafe.space.s)
            CafeTextField(days, { days = it.take(40) }, label = "Days", placeholder = "every day, weekdays, mon wed fri")
            VSpace(Cafe.space.l)
            Wrap {
                CafeButton("Save", {
                    actions.onScheduleProfile(profile.name, time.trim(), days.trim())
                    editing = null
                }, enabled = timeOk)
                if (!sure) QuietButton("Delete this profile", { sure = true }, icon = Icons.Rounded.DeleteOutline)
            }
            if (sure) {
                VSpace(Cafe.space.s)
                Text("The profile goes; the current look stays as it is.", style = Cafe.type.bodySmall, color = Cafe.colors.espresso)
                VSpace(Cafe.space.s)
                Wrap {
                    CafeButton("Delete ${profile.name}", {
                        actions.onDeleteProfile(profile.name)
                        editing = null
                    }, kind = ButtonKind.Danger)
                    QuietButton("Keep it", { sure = false })
                }
            }
        }
    }
}

// --------------------------------------------------------------------- brain

private fun LazyListScope.brainShelf(state: YouState, actions: YouActions) {
    item(key = "free") {
        PaperCard(Modifier.fillMaxWidth(), tone = if (state.hasFreeBrain) Tone.Sage else Tone.Berry) {
            SectionHeader("The free brain")
            Text(
                "Mochi answers with no account and no key, through free public models. Nothing here costs money.",
                style = Cafe.type.bodySmall,
                color = Cafe.colors.espresso
            )
            VSpace(Cafe.space.s)
            com.lukas.jarvis.ui.kit.StatusLine(
                if (state.hasFreeBrain) "Active — LLM7 and Pollinations, taking turns" else "Taken out of the pool",
                if (state.hasFreeBrain) Cafe.colors.sage else Cafe.colors.berry
            )
            state.lastUsedEndpoint?.let { Text("Last answer came from $it", style = Cafe.type.caption, color = Cafe.colors.cocoa) }
            if (!state.hasFreeBrain) {
                VSpace(Cafe.space.s)
                CafeButton("Put the free brain back", actions.onRestoreFreeBrain, icon = Icons.Rounded.Restore)
            }
            VSpace(Cafe.space.s)
            Text(
                "Faster and smarter? Add a free key below — Groq and Google Gemini take a minute to sign up for and cost nothing. Gemini also lets Mochi see photos.",
                style = Cafe.type.caption,
                color = Cafe.colors.cocoa
            )
        }
    }
    item(key = "key") { KeyCard(state, actions) }
    item(key = "pool") { PoolCard(state, actions) }
}

@Composable
private fun KeyCard(state: YouState, actions: YouActions) {
    val settings = state.settings
    val update = actions.onUpdate
    val preset = Providers.byId(settings.providerId)
    var open by rememberSaveable { mutableStateOf(false) }
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("A key of your own", action = if (open) "Hide" else "Set up", onAction = { open = !open })
        Text(preset.note + " Optional — it makes answers quicker and smarter.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
        if (!open) return@PaperCard
        VSpace(Cafe.space.m)
        Eyebrow("Provider")
        VSpace(Cafe.space.xs)
        Wrap {
            Providers.ALL.forEach { option ->
                ChoiceChip("${option.label.substringBefore(" (")} · ${tierLabel(option.tier)}", option.id == settings.providerId, { actions.onSwitchProvider(option.id) })
            }
        }
        preset.keyUrl?.let { url ->
            VSpace(Cafe.space.s)
            QuietButton("Get a free key", { actions.onOpenUrl(url) }, icon = Icons.AutoMirrored.Rounded.OpenInNew)
        }
        VSpace(Cafe.space.m)
        CafeTextField(settings.baseUrl, { v -> update { it.copy(baseUrl = v) } }, label = "Address", keyboardType = KeyboardType.Uri)
        if (preset.needsKey || settings.apiKey.isNotBlank()) {
            VSpace(Cafe.space.s)
            CafeTextField(settings.apiKey, { v -> update { it.copy(apiKey = v.trim()) } }, label = "API key", secret = true)
        }
        VSpace(Cafe.space.m)
        // The model list is fetched live: built-in ids go stale the moment a
        // provider retires one, and that failure looks like a generic 404.
        SectionHeader("Model", action = if (state.availableModels.isEmpty()) "Load models" else "Refresh", onAction = actions.onRefreshModels)
        val options = remember(state.availableModels, preset, settings.model) {
            ((state.availableModels.ifEmpty { preset.fallbackModels }) + settings.model).filter { it.isNotBlank() }.distinct()
        }
        Wrap {
            options.take(16).forEach { id -> ChoiceChip(id.substringAfterLast('/'), id == settings.model, { update { it.copy(model = id) } }) }
        }
        Text(
            when (val s = state.modelsState) {
                ModelsState.Idle -> "Built-in suggestions. Load models to see what your key can really call."
                ModelsState.Loading -> "Asking the provider…"
                is ModelsState.Loaded -> "${s.count} models available on this key."
                is ModelsState.Failed -> "Couldn't list models: ${s.message}"
            },
            style = Cafe.type.caption,
            color = if (state.modelsState is ModelsState.Failed) Cafe.colors.berryText else Cafe.colors.cocoa,
            modifier = Modifier.padding(top = Cafe.space.xs)
        )
        VSpace(Cafe.space.s)
        CafeTextField(settings.model, { v -> update { it.copy(model = v.trim()) } }, label = "Or type a model id", keyboardType = KeyboardType.Ascii)
        VSpace(Cafe.space.m)
        CafeButton(if (state.testState is TestState.Running) "Testing…" else "Test the connection", actions.onTestConnection, icon = Icons.Rounded.Bolt, enabled = state.testState !is TestState.Running)
        when (val t = state.testState) {
            is TestState.Passed -> TestResult(
                if (t.toolsWork) "Working" else "Replies, but no tool calling",
                if (t.toolsWork) "The model answered \"${t.reply}\". Memory and trackers will work."
                else "The model answered \"${t.reply}\" but didn't accept a tool call. Mochi falls back to a text protocol, which is less reliable — pick a model with tools if you can.",
                if (t.toolsWork) Tone.Sage else Tone.Honey,
                t.diagnostics,
                actions
            )
            is TestState.Failed -> TestResult("Not working", t.message, Tone.Berry, t.diagnostics, actions)
            else -> Unit
        }
    }
}

@Composable
private fun TestResult(title: String, body: String, tone: Tone, diagnostics: String, actions: YouActions) {
    VSpace(Cafe.space.m)
    PaperCard(Modifier.fillMaxWidth(), tone = tone, padding = Cafe.space.m) {
        Text(title, style = Cafe.type.title, color = Cafe.colors.espresso)
        Text(body, style = Cafe.type.bodySmall, color = Cafe.colors.espresso)
        if (diagnostics.isNotBlank()) {
            // The exact URL, status and reply: without them a report is only "it doesn't work".
            VSpace(Cafe.space.s)
            Text(diagnostics, style = Cafe.type.caption.copy(fontFamily = FontFamily.Monospace), color = Cafe.colors.cocoa, maxLines = 14, overflow = TextOverflow.Ellipsis)
            VSpace(Cafe.space.xs)
            QuietButton("Copy the details", { actions.onCopy(diagnostics) }, icon = Icons.Rounded.ContentCopy)
        }
    }
}

@Composable
private fun PoolCard(state: YouState, actions: YouActions) {
    val preset = Providers.byId(state.settings.providerId)
    var emptying by remember { mutableStateOf(false) }
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Model pool")
        Text(
            "Mochi takes turns through these, counts every call against each free tier, and steps aside before a limit is hit. A pool across several accounts survives a daily cap.",
            style = Cafe.type.bodySmall,
            color = Cafe.colors.cocoa
        )
        VSpace(Cafe.space.s)
        Wrap {
            CafeButton("Add ${preset.label.substringBefore(" (")}", actions.onAddToPool, icon = Icons.Rounded.Add, enabled = !state.poolBusy)
            CafeButton("Wake all", actions.onWakePool, kind = ButtonKind.Secondary, icon = Icons.Rounded.Refresh)
            QuietButton("Add every provider I have a key for", actions.onAddEveryProvider)
        }
        state.poolMessage?.let { Text(it, style = Cafe.type.bodySmall, color = Cafe.colors.espresso, modifier = Modifier.padding(top = Cafe.space.s)) }
        if (state.pool.isEmpty()) {
            Text("The pool is empty — Mochi uses the one model chosen above.", style = Cafe.type.caption, color = Cafe.colors.cocoa, modifier = Modifier.padding(top = Cafe.space.s))
            return@PaperCard
        }
        VSpace(Cafe.space.s)
        if (state.poolSummary.isNotBlank()) Text(state.poolSummary, style = Cafe.type.caption, color = Cafe.colors.cocoa)
        state.pool.forEach { entry -> PoolRow(entry, actions) }
        VSpace(Cafe.space.s)
        if (!emptying) {
            QuietButton("Empty the pool", { emptying = true }, icon = Icons.Rounded.DeleteOutline)
        } else {
            Text("Every model goes from the pool, keys and all. The free brain can be put back.", style = Cafe.type.bodySmall, color = Cafe.colors.espresso)
            VSpace(Cafe.space.xs)
            Wrap {
                CafeButton("Empty it", {
                    actions.onClearPool()
                    emptying = false
                }, kind = ButtonKind.Danger)
                QuietButton("Keep them", { emptying = false })
            }
        }
    }
}

@Composable
private fun PoolRow(entry: PoolEntry, actions: YouActions) {
    val status = entry.status()
    val dot = when {
        !entry.endpoint.enabled -> Cafe.colors.cocoa
        status == "Ready" -> Cafe.colors.sage
        status == "Key rejected" -> Cafe.colors.berry
        else -> Cafe.colors.honey
    }
    val name = Providers.byId(entry.endpoint.providerId).label.substringBefore(" (") + " · " + entry.endpoint.model.substringAfterLast('/')
    Row(Modifier.fillMaxWidth().padding(vertical = Cafe.space.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f).padding(horizontal = Cafe.space.m)) {
            Text(name, style = Cafe.type.body, color = if (entry.endpoint.enabled) Cafe.colors.espresso else Cafe.colors.cocoa, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(poolDetail(entry, status), style = Cafe.type.caption, color = Cafe.colors.cocoa, maxLines = 2)
        }
        Switch(
            checked = entry.endpoint.enabled,
            onCheckedChange = { actions.onTogglePoolEntry(entry.endpoint.id, it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Cafe.colors.accentFill, checkedThumbColor = Cafe.colors.onAccent),
            modifier = Modifier.semantics { contentDescription = "Use $name" }
        )
        IconCircle(Icons.Rounded.DeleteOutline, "Take $name out of the pool", { actions.onRemoveFromPool(entry.endpoint.id) }, size = 44.dp, tint = Cafe.colors.cocoa)
    }
}

/** What a provider costs, in a word or two. */
private fun tierLabel(tier: Tier): String = when (tier) {
    Tier.Keyless -> "no key"
    Tier.Free -> "free tier"
    Tier.Trial -> "free credit"
    Tier.Local -> "your PC"
    Tier.Custom -> "custom"
}

/** The second line of a pool row: whatever changes what Mochi does next. */
private fun poolDetail(entry: PoolEntry, status: String): String {
    val parts = mutableListOf(status)
    if (Providers.byId(entry.endpoint.providerId).tier == Tier.Keyless) parts += "no key"
    entry.capabilityNote()?.let { parts += it }
    if (entry.health.latencyMs > 0) parts += "${entry.health.latencyMs / 100 / 10.0}s"
    if (entry.health.successes > 0) parts += "${entry.health.successes} ok"
    entry.health.lastError?.takeIf { entry.health.successes == 0L }?.let { parts += it.take(40) }
    return parts.joinToString(" · ")
}

// -------------------------------------------------------------------- powers

private fun LazyListScope.powersShelf(state: YouState, actions: YouActions) {
    val settings = state.settings
    val update = actions.onUpdate
    item(key = "abilities") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("What Mochi can reach")
            Text("Each one adds its tools. Anything that leaves your phone or can't be undone always shows a card and waits for your yes.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            SwitchRow("The web", settings.webSearchEnabled, { v -> update { it.copy(webSearchEnabled = v) } }, detail = "Search and read pages. Free, no key.")
            SwitchRow("Weather", settings.weatherEnabled, { v -> update { it.copy(weatherEnabled = v) } }, detail = "Real forecasts for where you are. No key, no account.")
            SwitchRow("Phone control", settings.deviceControlEnabled, { v -> update { it.copy(deviceControlEnabled = v) } }, detail = "Alarms, timers, the torch, apps, texts, replies and calls")
            SwitchRow("Calendar", settings.calendarEnabled, { v -> update { it.copy(calendarEnabled = v) } }, detail = "Read what's on. Android asks for the permission.")
            SwitchRow("Contacts", settings.contactsEnabled, { v -> update { it.copy(contactsEnabled = v) } }, detail = "Look up a number so \"text Anna\" works. Read only.")
            VSpace(Cafe.space.s)
            QuietButton("See what each one can do", actions.onOpenSkills, icon = Icons.Rounded.AutoAwesome)
        }
    }
    item(key = "places") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Places and maps")
            SwitchRow("Places and routes", settings.mapsEnabled, { v -> update { it.copy(mapsEnabled = v) } }, detail = "OpenStreetMap — no key, no account, no tracking")
            if (settings.mapsEnabled) {
                VSpace(Cafe.space.xs)
                Eyebrow("Getting around")
                VSpace(Cafe.space.xs)
                Wrap {
                    Geo.ALL_MODES.forEach { mode -> ChoiceChip(mode.replaceFirstChar { it.uppercase() }, settings.travelMode == mode, { update { it.copy(travelMode = mode) } }) }
                }
                SliderRow(
                    "Search radius",
                    settings.searchRadiusMeters.toFloat(),
                    { v -> update { it.copy(searchRadiusMeters = v.toInt()) } },
                    range = 300f..10_000f,
                    valueLabel = Geo.formatDistance(settings.searchRadiusMeters.toDouble())
                )
            }
        }
    }
    item(key = "sending") {
        val a = state.access
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Sending things")
            Text("Mochi finishes these itself once you've said yes on its card.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.s)
            AccessLine(a.canText, "Texts go out as soon as you say yes.", "Texts are only drafted until the SMS permission is granted.")
            AccessLine(a.canReply, "Replies to WhatsApp, Signal, Telegram and the rest go out too.", "Replying to other apps needs notification access.")
            if (!a.canReply) CafeButton("Allow notification access", { actions.onAllow(Access.Replies) }, kind = ButtonKind.Secondary)
            AccessLine(a.canCall, "Calls ring once Mochi has read the number back and you said yes.", "Calls only reach the dialler until the call permission is granted.")
            AccessLine(a.canTap, "New WhatsApp, Telegram and Signal messages are sent outright.", "A new WhatsApp message is typed out and waits on one press. Accessibility lets Mochi press send.")
            if (!a.canTap) CafeButton("Let Mochi press send", { actions.onAllow(Access.PressSend) }, kind = ButtonKind.Secondary)
            VSpace(Cafe.space.s)
            Text(
                "Mochi reads notifications only to find the ones that can be answered, keeps them in memory while they're on screen, and writes none of it down.",
                style = Cafe.type.caption,
                color = Cafe.colors.cocoa
            )
        }
    }
    item(key = "screen") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Reading the screen")
            Text("\"Summarise this\", \"what does this say\", \"reply to this\" — about whatever app you're in.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.s)
            AccessLine(state.access.screenReading, "Ready. Mochi reads the screen only when you ask.", "Off. Android grants it on its accessibility page.")
            if (!state.access.screenReading) CafeButton("Allow screen reading", { actions.onAllow(Access.ScreenReading) }, kind = ButtonKind.Secondary)
            Text("It never reads Mochi itself or the keyboard, and keeps nothing.", style = Cafe.type.caption, color = Cafe.colors.cocoa, modifier = Modifier.padding(top = Cafe.space.s))
        }
    }
    item(key = "home") { HomeCard(settings, actions) }
    item(key = "dot") {
        val granted = state.access.canDrawOver
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Floating Mochi")
            SwitchRow(
                "Mochi over other apps",
                settings.floatingDot && granted,
                { wanted ->
                    if (wanted && !granted) actions.onAllow(Access.DrawOver)
                    // Left on, so granting the permission and coming back starts it without a second tap.
                    update { it.copy(floatingDot = wanted) }
                },
                detail = if (granted) "Tap to talk, hold to open the app, drag to park it at an edge." else "Needs permission to draw over other apps."
            )
            if (settings.floatingDot && !granted) CafeButton("Grant the permission", { actions.onAllow(Access.DrawOver) }, kind = ButtonKind.Secondary)
        }
    }
    item(key = "money") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Money and memory")
            CafeTextField(settings.defaultCurrency, { v -> update { it.copy(defaultCurrency = v.uppercase().take(5)) } }, label = "Default currency", keyboardType = KeyboardType.Ascii)
            SwitchRow("Keep things by itself", settings.autoCapture, { v -> update { it.copy(autoCapture = v) } }, detail = "Save facts and purchases you mention without being asked")
        }
    }
    item(key = "behaviour") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Thinking")
            SliderRow(
                "Creativity",
                settings.temperature,
                { v -> update { it.copy(temperature = v) } },
                range = 0f..1.2f,
                valueLabel = when {
                    settings.temperature < 0.35f -> "Precise"
                    settings.temperature < 0.8f -> "Balanced"
                    else -> "Inventive"
                }
            )
            // Typed freely and saved only when sensible: clamping each keystroke turned "2" of "2000" into 128.
            var tokens by remember(settings.maxTokens) { mutableStateOf(settings.maxTokens.toString()) }
            val ok = tokens.toIntOrNull()?.let { it in 128..8192 } == true
            CafeTextField(
                tokens,
                { v ->
                    tokens = v.filter { it.isDigit() }.take(5)
                    tokens.toIntOrNull()?.takeIf { it in 128..8192 }?.let { n -> update { it.copy(maxTokens = n) } }
                },
                label = "Longest reply (tokens)",
                keyboardType = KeyboardType.Number,
                isError = !ok,
                supporting = if (!ok) "Between 128 and 8192" else null
            )
        }
    }
}

@Composable
private fun AccessLine(ready: Boolean, yes: String, no: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Cafe.space.xs), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(if (ready) Cafe.colors.sage else Cafe.colors.honey))
        Text(if (ready) yes else no, style = Cafe.type.body, color = Cafe.colors.espresso, modifier = Modifier.padding(start = Cafe.space.m))
    }
}

/** The house, through the person's own Home Assistant: an address, a token, and a test. */
@Composable
private fun HomeCard(settings: Settings, actions: YouActions) {
    val update = actions.onUpdate
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(settings.homeUrl.isNotBlank()) }
    PaperCard(Modifier.fillMaxWidth()) {
        SectionHeader("Smart home", action = if (open) "Hide" else "Set up", onAction = { open = !open })
        Text("Lights, heating, blinds, locks and scenes through your own Home Assistant — local, no cloud account. Unlocking or opening anything asks first.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
        if (!open) return@PaperCard
        VSpace(Cafe.space.m)
        CafeTextField(settings.homeUrl, { v -> update { it.copy(homeUrl = v.trim()) } }, label = "Home Assistant address", placeholder = "http://homeassistant.local:8123", keyboardType = KeyboardType.Uri)
        VSpace(Cafe.space.s)
        CafeTextField(
            settings.homeToken,
            { v -> update { it.copy(homeToken = v.trim()) } },
            label = "Long-lived access token",
            secret = true,
            supporting = "Made in Home Assistant: your profile → Security → Long-lived access tokens."
        )
        SwitchRow("Let Mochi run the house", settings.homeEnabled, { v -> update { it.copy(homeEnabled = v) } })
        CafeButton(if (checking) "Checking…" else "Test the connection", {
            checking = true
            scope.launch {
                result = actions.onCheckHome()
                checking = false
            }
        }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Bolt, enabled = !checking)
        result?.let {
            VSpace(Cafe.space.s)
            com.lukas.jarvis.ui.kit.StatusLine(it, if (it.startsWith("Connected")) Cafe.colors.sage else Cafe.colors.berry)
        }
    }
}

// ---------------------------------------------------------------------- data

private fun LazyListScope.dataShelf(state: YouState, actions: YouActions) {
    item(key = "privacy") {
        val settings = state.settings
        PaperCard(Modifier.fillMaxWidth(), tone = if (settings.localOnly) Tone.Sage else Tone.Paper) {
            SectionHeader("Local only")
            SwitchRow(
                "Keep personal things off the free models",
                settings.localOnly,
                { v -> actions.onUpdate { it.copy(localOnly = v) } },
                detail = "Your memories, what you wrote about yourself, contacts, calendar and where you are never go to the free keyless models."
            )
            Text(
                if (state.pool.any { it.endpoint.preset.tier != Tier.Keyless && it.endpoint.enabled })
                    "You have a key of your own, so Mochi answers through it and skips the free models entirely."
                else
                    "With only the free models, Mochi answers without those things and says so when you ask for one. A free key of your own (in Brain) brings them back, privately.",
                style = Cafe.type.bodySmall,
                color = Cafe.colors.cocoa
            )
        }
    }
    item(key = "backup") {
        var passphrase by rememberSaveable { mutableStateOf("") }
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Backup")
            Text(
                "Everything Mochi knows — memories, tasks, money, the conversation, routines, places, settings and keys — in one file. Restore it after a reinstall or on a new phone.",
                style = Cafe.type.bodySmall,
                color = Cafe.colors.cocoa
            )
            VSpace(Cafe.space.m)
            CafeTextField(
                passphrase,
                { passphrase = it },
                label = if (state.backupLocked) "This backup's passphrase" else "Lock it with a passphrase (optional)",
                secret = true,
                supporting = if (state.backupLocked) null else "Without one the file is plain text: keep it somewhere private."
            )
            VSpace(Cafe.space.s)
            Wrap {
                if (state.backupLocked) {
                    CafeButton("Open it", { actions.onRestoreBackup(passphrase.ifBlank { null }) }, icon = Icons.Rounded.Lock, enabled = passphrase.isNotBlank())
                } else {
                    CafeButton("Save a backup", { actions.onSaveBackup(passphrase.ifBlank { null }) }, icon = Icons.Rounded.Save)
                }
                CafeButton("Restore", { actions.onRestoreBackup(null) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Restore)
            }
            state.backupNote?.let { Text(it, style = Cafe.type.body, color = Cafe.colors.espresso, modifier = Modifier.padding(top = Cafe.space.s)) }
            Text("Restoring replaces everything set now.", style = Cafe.type.caption, color = Cafe.colors.cocoa, modifier = Modifier.padding(top = Cafe.space.s))
        }
    }
    item(key = "log") {
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("What Mochi did for you")
            Text("Every text, call, share and delete Mochi asked about, and what you said.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            if (state.actionLog.isEmpty()) {
                Text("Nothing yet.", style = Cafe.type.body, color = Cafe.colors.cocoa, modifier = Modifier.padding(top = Cafe.space.s))
            }
            state.actionLog.take(25).forEach { record ->
                ListRow(
                    title = record.title,
                    subtitle = listOf(
                        when (record.confirmedBy) {
                            "cancelled" -> "Cancelled"
                            "voice" -> "You said yes"
                            else -> "You tapped yes"
                        },
                        record.outcome.take(60),
                        TimeUtil.relative(record.createdAt)
                    ).filter { it.isNotBlank() }.distinctBy { it.lowercase() }.joinToString(" · ")
                )
            }
        }
    }
    item(key = "conversation") {
        var sure by remember { mutableStateOf(false) }
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("The conversation")
            Text("Memories, money and tasks are kept — only the chat log goes. Everything stays on this phone.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
            VSpace(Cafe.space.s)
            if (!sure) {
                CafeButton("Clear the conversation", { sure = true }, kind = ButtonKind.Secondary, icon = Icons.Rounded.DeleteOutline)
            } else {
                Wrap {
                    CafeButton("Clear it for good", {
                        actions.onClearConversation()
                        sure = false
                    }, kind = ButtonKind.Danger)
                    QuietButton("Keep it", { sure = false })
                }
            }
            VSpace(Cafe.space.s)
            QuietButton("Run the introduction again", actions.onReplayIntro, icon = Icons.Rounded.AutoAwesome)
        }
    }
}
