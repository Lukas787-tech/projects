package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.llm.Abilities
import com.lukas.jarvis.llm.Ability
import com.lukas.jarvis.llm.AbilitySwitch
import com.lukas.jarvis.ui.components.IconBadge
import com.lukas.jarvis.ui.components.StatTile
import com.lukas.jarvis.ui.components.SayChip
import com.lukas.jarvis.ui.components.icon
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.Hairline
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.glassCard
import com.lukas.jarvis.ui.theme.sheen

/**
 * Everything Jarvis can do, and a sentence for each thing to try it.
 *
 * An assistant's hardest problem is that nobody knows what to ask it. The only
 * list of its abilities used to be the tool definitions, which a person never
 * sees, and a row of switches in Settings that said what could be turned off
 * rather than what could be done. This is the list the other way round: each
 * family, what it is for, and the shortest things to say to reach it. Tapping
 * one asks it for real.
 *
 * The switches live here as well as in Settings, because this is where the
 * decision is made — seeing what the calendar would add is the moment to
 * switch it on.
 */
@Composable
fun SkillsScreen(
    settings: Settings,
    onToggle: (AbilitySwitch, Boolean) -> Unit,
    onTry: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val on = remember(settings) { Abilities.ALL.filter { it.isOn(settings) } }
    val toolsOn = remember(on) { on.sumOf { it.toolCount } }
    val toolsAll = remember { Abilities.ALL.sumOf { it.toolCount } }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.snug)
    ) {
        item {
            ScreenHeader(
                title = "Skills",
                subtitle = "Tap any sentence and Jarvis answers it"
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
                StatTile(
                    value = "${on.size}/${Abilities.ALL.size}",
                    label = "Abilities",
                    caption = "switched on",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = "$toolsOn",
                    label = "Tools",
                    caption = "of $toolsAll in reach",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        items(Abilities.ALL, key = { it.group.name }) { ability ->
            AbilityCard(
                ability = ability,
                on = ability.isOn(settings),
                onToggle = { wanted -> ability.switch?.let { onToggle(it, wanted) } },
                onTry = onTry
            )
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .sheen(RoundedCornerShape(Corner.card))
                    .padding(Space.gutter)
            ) {
                Text(
                    "Everything here is worked out on the phone where it can be.",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(Space.hair + 2.dp))
                Text(
                    "Sums, dates, units, balances and exchange rates come from code and " +
                        "live services, not from the model's memory — the chips under each " +
                        "reply show which ones answered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        item { Spacer(Modifier.height(Space.loose)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbilityCard(
    ability: Ability,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    onTry: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(Corner.large, raised = false)
            .padding(Space.step)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = ability.group.icon(), lit = on)
            Spacer(Modifier.width(Space.snug))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ability.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (on) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${ability.toolCount} tool${if (ability.toolCount == 1) "" else "s"}" +
                        if (ability.switch == null) " · always on" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextFaint
                )
            }
            if (ability.switch != null) {
                Switch(
                    checked = on,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = Accent,
                        checkedBorderColor = Accent,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = Color.Transparent,
                        uncheckedBorderColor = Hairline
                    )
                )
            }
        }

        Spacer(Modifier.height(Space.tight))
        Text(
            ability.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(Modifier.height(Space.snug))
        FlowRow(
            modifier = Modifier.alpha(if (on) 1f else 0.45f),
            horizontalArrangement = Arrangement.spacedBy(Space.tight),
            verticalArrangement = Arrangement.spacedBy(Space.tight)
        ) {
            ability.examples.forEach { example ->
                SayChip(
                    text = example,
                    // An example for a switched-off ability would only earn the
                    // answer that it is switched off.
                    onClick = if (on) {
                        { onTry(example) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}
