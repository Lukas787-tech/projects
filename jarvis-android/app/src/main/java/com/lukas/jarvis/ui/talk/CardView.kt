package com.lukas.jarvis.ui.talk

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.data.ListBook
import com.lukas.jarvis.llm.ToolCatalog
import com.lukas.jarvis.moment.ActionIntent
import com.lukas.jarvis.moment.CanvasCard
import com.lukas.jarvis.moment.CardKind
import com.lukas.jarvis.moment.CardMode
import com.lukas.jarvis.moment.Cards
import com.lukas.jarvis.notify.RunningTimer
import com.lukas.jarvis.ui.kit.ButtonKind
import com.lukas.jarvis.ui.kit.CafeButton
import com.lukas.jarvis.ui.kit.CheckRow
import com.lukas.jarvis.ui.kit.ConfirmCard
import com.lukas.jarvis.ui.kit.Eyebrow
import com.lukas.jarvis.ui.kit.FollowChip
import com.lukas.jarvis.ui.kit.IconCircle
import com.lukas.jarvis.ui.kit.PaperCard
import com.lukas.jarvis.ui.kit.ProblemCard
import com.lukas.jarvis.ui.kit.QuietButton
import com.lukas.jarvis.ui.kit.TimerTile
import com.lukas.jarvis.ui.kit.Tone
import com.lukas.jarvis.ui.kit.VSpace
import com.lukas.jarvis.ui.kit.Wrap
import com.lukas.jarvis.ui.kit.icon
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.Elevation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a card can draw on besides itself: the live lists, timers and photos. */
class CardContext(
    val lists: ListBook? = null,
    val timers: List<RunningTimer> = emptyList(),
    val ringing: List<RunningTimer> = emptyList(),
    val photos: Map<Long, Bitmap> = emptyMap(),
    val stopwatch: com.lukas.jarvis.notify.StopwatchState? = null,
    val now: Long = System.currentTimeMillis()
)

/**
 * One card on the canvas, drawn by its kind. Every card has its way forward —
 * the actions it carries — and a pin and a "put away" in its corner, so it can
 * always be kept or moved aside without being lost.
 */
@Composable
fun CanvasCardView(
    card: CanvasCard,
    context: CardContext,
    onAction: (ActionIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    when (card.kind) {
        CardKind.Confirm -> {
            val pending = card.pending ?: return
            ConfirmCard(
                title = pending.title,
                detail = pending.detail,
                verb = pending.verb,
                risk = pending.risk,
                fields = pending.fields,
                onConfirm = { onAction(ActionIntent.Confirm(pending.id)) },
                onEdit = { key, value -> onAction(ActionIntent.EditPending(pending.id, key, value)) },
                onCancel = { onAction(ActionIntent.Cancel(pending.id)) },
                modifier = modifier
            )
            return
        }
        CardKind.Problem -> {
            val retry = card.actions.firstOrNull { it.intent == ActionIntent.Retry }
            val other = card.actions.firstOrNull { it.forward && it.intent != ActionIntent.Retry }
            ProblemCard(
                title = card.title,
                body = card.body,
                onRetry = { onAction(retry?.intent ?: ActionIntent.Retry) },
                alternative = other?.label ?: "Ask another way",
                onAlternative = { onAction(other?.intent ?: ActionIntent.Type()) },
                modifier = modifier
            )
            return
        }
        else -> Unit
    }

    // A ringing timer is shown live, with its own two buttons.
    val timerId = card.actions.firstNotNullOfOrNull { (it.intent as? ActionIntent.StopTimer)?.id }
    if (timerId != null && card.id.startsWith("alert:")) {
        TimerTile(
            label = card.title,
            remaining = "0:00",
            progress = 1f,
            ringing = true,
            onAddMinute = { onAction(ActionIntent.AddMinute(timerId)) },
            onStop = { onAction(ActionIntent.StopTimer(timerId)) },
            modifier = modifier
        )
        return
    }

    val tone = when (card.kind.mode) {
        CardMode.Create -> Tone.Paper
        CardMode.Control -> Tone.Latte
        else -> Tone.Paper
    }
    PaperCard(
        modifier
            .fillMaxWidth()
            .animateContentSize(),
        tone = tone,
        elevation = if (card.pinned) Elevation.Lifted else Elevation.Resting
    ) {
        CardHeader(card, onAction)
        when (card.kind) {
            CardKind.List -> ListBody(card, context, onAction)
            CardKind.Picture -> PictureBody(card)
            CardKind.Photo -> PhotoBody(card, context)
            CardKind.Timer -> TimersBody(card, context, onAction)
            CardKind.Stopwatch -> StopwatchBody(context)
            else -> TextBody(card)
        }
        CardActions(card, onAction)
    }
}

@Composable
private fun CardHeader(card: CanvasCard, onAction: (ActionIntent) -> Unit) {
    val group = card.tool?.let { ToolCatalog.info(it)?.group }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = group?.icon() ?: when (card.kind) {
                CardKind.Route -> Icons.Rounded.Route
                CardKind.Timer -> Icons.Rounded.AccessTime
                CardKind.Problem -> Icons.Rounded.ErrorOutline
                else -> Icons.Rounded.AutoAwesome
            },
            contentDescription = null,
            tint = Cafe.colors.accentText,
            modifier = Modifier.size(18.dp)
        )
        Eyebrow(card.kind.label, Modifier.weight(1f).padding(start = Cafe.space.s))
        IconCircle(
            icon = Icons.Rounded.PushPin,
            description = if (card.pinned) "Unpin ${card.title}" else "Pin ${card.title}",
            onClick = { onAction(if (card.pinned) ActionIntent.Unpin(card.id) else ActionIntent.Pin(card.id)) },
            size = 36.dp,
            tint = if (card.pinned) Cafe.colors.accentText else Cafe.colors.cocoa
        )
        IconCircle(
            icon = Icons.Rounded.Close,
            description = "Put ${card.title} away",
            onClick = { onAction(ActionIntent.Dismiss(card.id)) },
            size = 36.dp,
            tint = Cafe.colors.cocoa
        )
    }
    if (card.title.isNotBlank() && card.title != card.kind.label) {
        Text(card.title, style = Cafe.type.title, color = Cafe.colors.espresso, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TextBody(card: CanvasCard) {
    var open by rememberSaveable(card.id) { mutableStateOf(false) }
    val body = card.body
    if (body.isBlank() && card.lines.isEmpty()) return
    VSpace(Cafe.space.xs)
    if (card.lines.isNotEmpty() && card.kind != CardKind.Answer) {
        Column(verticalArrangement = Arrangement.spacedBy(Cafe.space.xs)) {
            card.lines.take(if (open) 40 else 5).forEach { line ->
                Row {
                    Text("•", style = Cafe.type.body, color = Cafe.colors.accentText)
                    Text(line, style = Cafe.type.body, color = Cafe.colors.espresso, modifier = Modifier.padding(start = Cafe.space.s))
                }
            }
        }
        if (card.lines.size > 5) QuietButton(if (open) "Show less" else "Show all ${card.lines.size}", { open = !open })
    } else {
        Text(
            body,
            style = if (card.kind == CardKind.Exact) Cafe.type.headline else Cafe.type.body,
            color = Cafe.colors.espresso,
            maxLines = if (open) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis
        )
        if (!open && body.length > 260) QuietButton("Read it all", { open = true })
    }
}

@Composable
private fun ListBody(card: CanvasCard, context: CardContext, onAction: (ActionIntent) -> Unit) {
    val name = card.slots["list"]
    val list = name?.let { context.lists?.find(it) }
    VSpace(Cafe.space.xs)
    if (list == null) {
        TextBody(card)
        return
    }
    if (list.items.isEmpty()) {
        Text("Nothing on it yet.", style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
        return
    }
    list.items.sortedBy { it.done }.take(12).forEach { item ->
        CheckRow(
            text = item.text,
            checked = item.done,
            onCheckedChange = { onAction(ActionIntent.CheckItem(list.name, item.text, it)) }
        )
    }
}

@Composable
private fun PictureBody(card: CanvasCard) {
    val path = card.image ?: return TextBody(card)
    var image by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        image = withContext(Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    VSpace(Cafe.space.s)
    image?.let {
        Image(
            bitmap = it,
            contentDescription = card.body.ifBlank { "The picture Mochi drew" },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(Cafe.shape.medium)
        )
    }
}

@Composable
private fun PhotoBody(card: CanvasCard, context: CardContext) {
    val stamp = card.image?.removePrefix("photo:")?.toLongOrNull()
    val bitmap = stamp?.let { context.photos[it] }
    VSpace(Cafe.space.s)
    if (bitmap != null) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = "The picture you showed",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(Cafe.shape.medium)
        )
        VSpace(Cafe.space.s)
    }
    TextBody(card)
}

/** A clock that ticks every second, only while the card that needs it is on screen. */
@Composable
private fun ticking(): Long {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun StopwatchBody(context: CardContext) {
    val watch = context.stopwatch ?: return
    val now = if (watch.running) ticking() else context.now
    VSpace(Cafe.space.xs)
    Text(clock(watch.elapsed(now)), style = Cafe.type.pixelLarge, color = Cafe.colors.espresso)
    if (watch.laps.isNotEmpty()) {
        Text(
            watch.laps.takeLast(3).mapIndexed { i, lap -> "Lap ${watch.laps.size - minOf(3, watch.laps.size) + i + 1}: ${clock(lap)}" }.joinToString("  \u00B7  "),
            style = Cafe.type.bodySmall,
            color = Cafe.colors.cocoa
        )
    }
}

@Composable
private fun TimersBody(card: CanvasCard, context: CardContext, onAction: (ActionIntent) -> Unit) {
    if (context.timers.isEmpty()) return TextBody(card)
    val now = ticking()
    VSpace(Cafe.space.s)
    Column(verticalArrangement = Arrangement.spacedBy(Cafe.space.s)) {
        context.timers.forEach { timer ->
            val left = timer.leftMs(now)
            TimerTile(
                label = timer.label,
                remaining = clock(left),
                progress = if (timer.lengthMs > 0) 1f - left.toFloat() / timer.lengthMs else 0f,
                ringing = false,
                onAddMinute = { onAction(ActionIntent.AddMinute(timer.id)) },
                onStop = { onAction(ActionIntent.StopTimer(timer.id)) }
            )
        }
    }
}

/** "4:05", or "1:02:00" past the hour. */
fun clock(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The card's ways forward: its own next step first, then the rest, then follow-ups. */
@Composable
private fun CardActions(card: CanvasCard, onAction: (ActionIntent) -> Unit) {
    val forward = card.actions.filter { it.forward }
    if (forward.isEmpty() && card.followUps.isEmpty()) return
    VSpace(Cafe.space.m)
    Wrap {
        forward.take(3).forEachIndexed { index, action ->
            CafeButton(
                action.label,
                { onAction(action.intent) },
                kind = when {
                    index == 0 && action.primary -> ButtonKind.Primary
                    action.intent is ActionIntent.Undo -> ButtonKind.Secondary
                    else -> ButtonKind.Secondary
                }
            )
        }
        card.followUps.take(2).forEach { follow ->
            FollowChip(follow.label, { onAction(Cards.intentFor(follow)) })
        }
    }
}

/** A card's short description for TalkBack, when only a summary fits. */
fun Modifier.describe(card: CanvasCard): Modifier =
    semantics { contentDescription = "${card.kind.label}: ${card.title}. ${card.body.take(160)}" }
