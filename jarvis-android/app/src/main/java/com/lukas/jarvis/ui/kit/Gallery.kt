package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.llm.EditableField
import com.lukas.jarvis.llm.Risk
import com.lukas.jarvis.ui.character.CharacterState
import com.lukas.jarvis.ui.character.Mochi
import com.lukas.jarvis.ui.character.Mood
import com.lukas.jarvis.ui.character.Prop
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.MochiTheme

/**
 * Every piece of the café's kit on one page, in whichever theme it is drawn
 * in: the previews and the screenshot test both render this.
 */
@Composable
fun KitGallery(modifier: Modifier = Modifier, scroll: Boolean = true) {
    val c = Cafe.colors
    Column(
        modifier
            .background(c.foam)
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(Cafe.space.gutter),
        verticalArrangement = Arrangement.spacedBy(Cafe.space.l)
    ) {
        Text("The latte café", style = Cafe.type.display, color = c.espresso)
        Row(horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)) {
            listOf(c.foam, c.paper, c.latte, c.espresso, c.cocoa, c.accent, c.sage, c.berry, c.honey).forEach { swatch ->
                Box(Modifier.size(28.dp).clip(Cafe.shape.small).background(swatch))
            }
        }
        Column {
            Text("Headline in Newsreader", style = Cafe.type.headline, color = c.espresso)
            Text("Mochi's voice: “The kettle's on.”", style = Cafe.type.voice, color = c.espresso)
            Text("Body in DM Sans, for everything you read.", style = Cafe.type.body, color = c.espresso)
            Text("Secondary, still at reading contrast.", style = Cafe.type.bodySmall, color = c.cocoa)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)) {
                PixelTag("12:04")
                Text("pixel tags", style = Cafe.type.pixel, color = c.cocoa)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Cafe.space.m)) {
            Mochi(CharacterState(Mood.Idle), size = 24.dp)
            Mochi(CharacterState(Mood.Working, Prop.Magnifier), size = 72.dp)
            Mochi(CharacterState(Mood.Delighted), size = 120.dp)
        }
        Wrap {
            CafeButton("Send", {})
            CafeButton("Edit", {}, kind = ButtonKind.Secondary)
            QuietButton("Cancel", {})
            CafeButton("Delete", {}, kind = ButtonKind.Danger)
        }
        Wrap {
            FollowChip("Route there", {})
            FollowChip("Remind me", {})
            ChoiceChip("Caramel", selected = true, onClick = {})
            ChoiceChip("Sage", selected = false, onClick = {})
        }
        ComposerBar(text = "", onTextChange = {}, onSend = {}, onMic = {}, onCamera = {}, modifier = Modifier.fillMaxWidth())
        PaperCard(Modifier.fillMaxWidth()) {
            Eyebrow("Reminder")
            Text("Call mum", style = Cafe.type.title, color = c.espresso)
            Text("Tomorrow at 18:00", style = Cafe.type.bodySmall, color = c.cocoa)
            VSpace(Cafe.space.s)
            ToolTrail(listOf("recall", "add_task"), working = false)
        }
        PaperCard(Modifier.fillMaxWidth()) {
            SectionHeader("Shopping", action = "Add", onAction = {})
            CheckRow("Oat milk", checked = false, onCheckedChange = {})
            CheckRow("Eggs", checked = true, onCheckedChange = {})
            SoftDivider()
            ListRow("Café Mitte", subtitle = "200 m · open until 18:00", onClick = {})
            ValueRow("Left this week", "€23.00", emphasise = true)
        }
        ConfirmCard(
            title = "Text Anna",
            detail = "Running ten minutes late",
            verb = "Send",
            risk = Risk.Outward,
            fields = listOf(EditableField("text", "Message", "Running ten minutes late", multiline = true)),
            onConfirm = {}, onEdit = { _, _ -> }, onCancel = {}
        )
        ProblemCard(
            title = "That didn't work",
            body = "Every free model is busy right now.",
            onRetry = {},
            alternative = "Use the free models",
            onAlternative = {}
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Cafe.space.s)) {
            ControlTile(Icons.Rounded.FlashlightOn, "Torch", "On", on = true, onClick = {})
            ControlTile(Icons.Rounded.NotificationsActive, "Ringer", "Vibrate", on = false, onClick = {})
            ControlTile(Icons.Rounded.DoNotDisturbOn, "Quiet", "Until 15:00", on = true, onClick = {})
        }
        TimerTile("Pasta", "4:05", progress = 0.6f, ringing = false, onAddMinute = {}, onStop = {})
        PaperCard(Modifier.fillMaxWidth()) {
            SwitchRow("Speak replies", checked = true, onCheckedChange = {}, detail = "Mochi reads its answers aloud")
            SliderRow("Speed", value = 0.5f, onValueChange = {}, valueLabel = "1.0×")
            CafeTextField(value = "", onValueChange = {}, label = "Your name", placeholder = "What should Mochi call you?")
        }
        EmptyState(
            title = "No lists yet",
            body = "Shopping, packing, anything without a time.",
            action = "Start a shopping list",
            onAction = {},
            art = { Mochi(CharacterState(Mood.Idle), size = 72.dp) }
        )
        Skeleton(lines = 3)
        StatusLine("Offline — timers still work", Color(0xFFE3B65C))
    }
}

@Preview(name = "Kit, latte", widthDp = 411, heightDp = 2200)
@Composable
private fun KitLight() = MochiTheme(themeMode = "light", reduceMotion = true) { KitGallery(scroll = false) }

@Preview(name = "Kit, night café", widthDp = 411, heightDp = 2200)
@Composable
private fun KitDark() = MochiTheme(themeMode = "dark", reduceMotion = true) { KitGallery(scroll = false) }

@Preview(name = "Kit, large text", widthDp = 411, heightDp = 2600)
@Composable
private fun KitLarge() = MochiTheme(themeMode = "light", textScale = 1.3f, reduceMotion = true) { KitGallery(scroll = false) }
