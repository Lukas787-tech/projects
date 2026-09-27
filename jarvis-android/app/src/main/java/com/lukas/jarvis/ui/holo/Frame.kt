package com.lukas.jarvis.ui.holo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentBright
import com.lukas.jarvis.ui.theme.Hairline
import com.lukas.jarvis.ui.theme.InkCard
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import com.lukas.jarvis.ui.theme.ThemeState
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One projected panel: cut corners, a film of light the core shows faintly
 * through, scan lines, and a title bar that is also the handle — flick it up
 * or down and the panel is put away.
 *
 * [progress] is the entrance (0 → 1) or exit (1 → 0) of the projection, read
 * while drawing. [onTouch] hears every finger on the panel without taking it,
 * which is how a panel being used knows to stay up.
 */
@Composable
fun HoloFrame(
    title: String,
    icon: ImageVector,
    progress: () -> Float,
    onClose: () -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    code: String? = null,
    live: Boolean = false,
    onExpand: (() -> Unit)? = null,
    /** Take all the height given, as a map does; otherwise hug the content. */
    fill: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val calm = ThemeState.reduceMotion
    val shape = remember { ChamferShape() }
    val density = LocalDensity.current
    val cut = with(density) { 16.dp.toPx() }
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val threshold = with(density) { 90.dp.toPx() }
    val touch by rememberUpdatedState(onTouch)
    val close by rememberUpdatedState(onClose)

    val band = if (calm) null else rememberInfiniteTransition(label = "holo").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5600, easing = LinearEasing), RepeatMode.Restart),
        label = "band"
    )

    val accent = Accent
    val bright = AccentBright
    val film = Brush.verticalGradient(
        listOf(
            lerp(InkCard, accent, 0.10f).copy(alpha = 0.90f),
            InkCard.copy(alpha = 0.84f),
            lerp(InkCard, accent, 0.05f).copy(alpha = 0.90f)
        )
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                translationY = drag.value
                alpha = 1f - (abs(drag.value) / threshold).coerceIn(0f, 1f) * 0.6f
            }
            .materialize(progress)
            .clip(shape)
            .background(film)
            .border(
                1.dp,
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.18f))),
                shape
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) touch()
                    }
                }
            }
            .hologram(phase = { band?.value ?: 0f }, calm = calm)
            .drawWithContent {
                drawContent()
                drawHoloTrim(accent, bright, cut)
            }
    ) {
        Column(modifier = if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (abs(drag.value) > threshold) close()
                                else scope.launch { drag.animateTo(0f, tween(220)) }
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f, tween(220)) } },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                scope.launch { drag.snapTo(drag.value + amount) }
                            }
                        )
                    }
                    .padding(start = 22.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.2.sp),
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (live) LiveMark(calm)
                code?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                        color = TextFaint,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
                onExpand?.let { FrameButton(Icons.Default.OpenInFull, "Open $title full screen", it) }
                FrameButton(Icons.Default.Close, "Put $title away", onClose)
            }
            Box(
                Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(listOf(accent.copy(alpha = 0.5f), Hairline, Color.Transparent))
                    )
            )
            Box(
                modifier = if (fill) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().padding(bottom = 10.dp),
                content = content
            )
        }
    }
}

@Composable
private fun FrameButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = TextSecondary, modifier = Modifier.size(16.dp))
    }
}

/** A dot that blinks beside the word LIVE, for a panel showing something as it happens. */
@Composable
private fun LiveMark(calm: Boolean) {
    val blink = if (calm) null else rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "blink"
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(
            Modifier
                .size(6.dp)
                .graphicsLayer { alpha = blink?.value ?: 1f }
                .clip(CircleShape)
                .background(com.lukas.jarvis.ui.theme.Negative)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "LIVE",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
            color = TextPrimary
        )
    }
}
