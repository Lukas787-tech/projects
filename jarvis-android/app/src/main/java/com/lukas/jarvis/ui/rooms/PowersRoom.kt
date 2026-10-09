package com.lukas.jarvis.ui.rooms

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.llm.Abilities
import com.lukas.jarvis.llm.Ability
import com.lukas.jarvis.llm.AbilitySwitch
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.CafeSheet
import com.lukas.jarvis.ui.kit.FollowChip
import com.lukas.jarvis.ui.kit.IconBadge
import com.lukas.jarvis.ui.kit.PaperCard
import com.lukas.jarvis.ui.kit.QuietButton
import com.lukas.jarvis.ui.kit.Tone
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.ValueRow
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.kit.icon
import com.lukas.jarvis.ui.theme.Cafe

/**
 * Powers: everything Mochi can do, a sentence for each thing to try it, and
 * which of its tools always ask first. Tapping a sentence asks it for real.
 * A switched-off power's sentences still work: they offer to switch it on.
 */
@Composable
fun PowersRoom(
    settings: Settings,
    onBack: () -> Unit,
    onToggle: (AbilitySwitch, Boolean) -> Unit,
    onTry: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val on = remember(settings) { Abilities.ALL.filter { it.isOn(settings) } }
    val toolsOn = remember(on) { on.sumOf { it.toolCount } }
    var offering by remember { mutableStateOf<Pair<Ability, String>?>(null) }
    RoomScaffold(
        title = "Powers",
        subtitle = "Tap any sentence and Mochi does it",
        onBack = onBack,
        modifier = modifier,
        mochi = CharacterState(Mood.Delighted, description = "Mochi, showing off")
    ) {
        item(key = "count") {
            PaperCard(Modifier.fillMaxWidth(), tone = Tone.Honey) {
                ValueRow("Powers switched on", "${on.size} of ${Abilities.ALL.size}", emphasise = true)
                ValueRow("Tools in reach", "$toolsOn of ${Abilities.ALL.sumOf { it.toolCount }}")
                Text(
                    "Sums, dates, units and balances are worked out on the phone, not guessed — the chips under each answer say which tools did the work.",
                    style = Cafe.type.bodySmall,
                    color = Cafe.colors.espresso,
                    modifier = Modifier.padding(top = Cafe.space.s)
                )
            }
        }
        items(Abilities.ALL, key = { it.group.name }) { ability ->
            PowerCard(
                ability = ability,
                on = ability.isOn(settings),
                onToggle = { wanted -> ability.switch?.let { onToggle(it, wanted) } },
                onTry = { sentence -> if (ability.isOn(settings)) onTry(sentence) else offering = ability to sentence }
            )
        }
    }
    offering?.let { (ability, sentence) ->
        CafeSheet("${ability.title} is off", onDismiss = { offering = null }) {
            Text("Switch it on to try \"$sentence\"?", style = Cafe.type.body, color = Cafe.colors.espresso)
            Text(ability.summary, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa, modifier = Modifier.padding(top = Cafe.space.xs))
            VSpace(Cafe.space.l)
            Wrap {
                CafeButton("Switch on and try it", {
                    ability.switch?.let { onToggle(it, true) }
                    offering = null
                    onTry(sentence)
                })
                QuietButton("Not now", { offering = null })
            }
        }
    }
}

@Composable
private fun PowerCard(ability: Ability, on: Boolean, onToggle: (Boolean) -> Unit, onTry: (String) -> Unit) {
    // The tools of this family that never run without a yes on their card.
    val asks = remember(ability) {
        ToolCatalog.ALL.filter { it.group == ability.group && it.risk.asksFirst }.map { ASKS[it.name] ?: it.doing }.distinct() +
            listOfNotNull("unlocking or opening anything".takeIf { ability.group == com.lukas.jarvis.llm.ToolGroup.Home })
    }
    PaperCard(Modifier.fillMaxWidth(), tone = if (on) Tone.Paper else Tone.Latte) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(ability.group.icon(), tone = if (on) Tone.Accent else Tone.Latte)
            androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = Cafe.space.m)) {
                Text(ability.title, style = Cafe.type.title, color = Cafe.colors.espresso, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${ability.toolCount} tool${if (ability.toolCount == 1) "" else "s"}" + if (ability.switch == null) " · always on" else if (on) " · on" else " · off",
                    style = Cafe.type.caption,
                    color = Cafe.colors.cocoa
                )
            }
            if (ability.switch != null) {
                Switch(
                    checked = on,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = Cafe.colors.accentFill, checkedThumbColor = Cafe.colors.onAccent),
                    modifier = Modifier.semantics { contentDescription = ability.title }
                )
            }
        }
        VSpace(Cafe.space.s)
        Text(ability.summary, style = Cafe.type.body, color = Cafe.colors.espresso)
        if (asks.isNotEmpty()) {
            Text("Always asks first: ${asks.joinToString(", ")}", style = Cafe.type.caption, color = Cafe.colors.accentText, modifier = Modifier.padding(top = Cafe.space.xs))
        }
        VSpace(Cafe.space.s)
        Wrap { ability.examples.forEach { example -> FollowChip(example, { onTry(example) }) } }
    }
}

/** What each asking-first tool does, as a person would put it. */
private val ASKS = mapOf(
    "send_message" to "texts",
    "send_chat_message" to "chat messages",
    "reply_to_message" to "replies",
    "call" to "calls",
    "place_call" to "calls",
    "send_email" to "emails",
    "share" to "sharing",
    "share_location" to "sharing where you are",
    "forget" to "forgetting a memory",
    "delete_entry" to "taking back an entry",
    "delete_task" to "deleting a reminder",
    "delete_routine" to "deleting a routine",
    "forget_place" to "forgetting a place",
    "change_calendar_event" to "changing or cancelling an event",
    "system_action" to "system switches"
)
