package com.lukas.jarvis.ui.character

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.kit.PixelTag
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.CafeColors
import com.lukas.jarvis.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Mochi on screen.
 *
 * Frames are composed once per state and kept as tiny bitmaps; drawing is a
 * lookup and a nearest-neighbour blit at a whole-number scale, so the pixels
 * stay crisp at 24 dp and at 160 dp and nothing is allocated per frame. The
 * clock that moves it is read only while drawing, so animating never
 * recomposes the screen around it.
 *
 * [level] is how loud Mochi's voice is right now (0..1); while [state] is
 * talking, the mouth follows it.
 */
@Composable
fun Mochi(
    state: CharacterState,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    level: () -> Float = { 0f },
    showTag: Boolean = size >= 96.dp,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null
) {
    val colors = Cafe.colors
    val reduce = Cafe.reduceMotion
    val idleMoments = Cafe.env.idleCharacter
    val palette = remember(colors) { MochiPalette.from(colors) }

    // Now and then, with nothing else going on, a small idle moment.
    var flourish by remember { mutableStateOf(Flourish.None) }
    LaunchedEffect(state.mood, idleMoments, reduce) {
        flourish = Flourish.None
        if (state.mood != Mood.Idle || !idleMoments || reduce) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(22_000, 42_000))
            flourish = listOf(Flourish.Stretch, Flourish.Sip, Flourish.LookAround).random()
            delay(Choreo.loopLength(state.copy(flourish = flourish)) * (1000L / Choreo.FPS))
            flourish = Flourish.None
        }
    }
    val shown = if (state.mood == Mood.Idle) state.copy(flourish = flourish) else state

    val frames = remember(shown, palette, reduce) { MochiFrames.build(shown, palette, reduce) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(frames, reduce) {
        tick = 0
        if (reduce) return@LaunchedEffect
        while (true) {
            delay(1000L / Choreo.FPS)
            tick++
        }
    }

    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = shown.description
                stateDescription = shown.mood.name
            }
            .then(
                if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                else Modifier
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = tick
            val image: ImageBitmap = frames.at(t, if (shown.talking) Choreo.mouthFor(level()) else null)
            val big = minOf(this.size.width / Sprites.W, this.size.height / Sprites.H).toInt()
            if (big >= 1) {
                val w = Sprites.W * big
                val h = Sprites.H * big
                drawImage(
                    image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(Sprites.W, Sprites.H),
                    dstOffset = IntOffset(((this.size.width - w) / 2).toInt(), (this.size.height - h).toInt()),
                    dstSize = IntSize(w, h),
                    filterQuality = FilterQuality.None
                )
            } else {
                val small = (minOf(this.size.width, this.size.height) / Sprites.TINY).toInt().coerceAtLeast(1)
                val side = Sprites.TINY * small
                drawImage(
                    frames.tiny,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(Sprites.TINY, Sprites.TINY),
                    dstOffset = IntOffset(((this.size.width - side) / 2).toInt(), (this.size.height - side).toInt()),
                    dstSize = IntSize(side, side),
                    filterQuality = FilterQuality.None
                )
            }
        }
        val tag = shown.tag
        if (showTag && tag != null) {
            PixelTag(tag, Modifier.align(Alignment.TopEnd).padding(top = size * 0.08f))
        }
    }
}

/** Mochi's colours in the current café, one per [Ink] role. */
class MochiPalette private constructor(val argb: IntArray) {
    override fun equals(other: Any?) = other is MochiPalette && other.argb.contentEquals(argb)
    override fun hashCode() = argb.contentHashCode()

    companion object {
        fun from(colors: CafeColors): MochiPalette {
            val dark = colors.isDark
            val p = IntArray(Ink.COUNT)
            p[Ink.CLEAR] = 0
            p[Ink.OUTLINE] = 0xFF3A2E28.toInt()
            p[Ink.BODY] = 0xFFFFF7EC.toInt()
            p[Ink.SHADE] = 0xFFF1DEC8.toInt()
            p[Ink.SHINE] = 0xFFFFFFFF.toInt()
            p[Ink.BLUSH] = 0xFFF0A096.toInt()
            p[Ink.EYE] = 0xFF3A2E28.toInt()
            p[Ink.GLINT] = 0xFFFFFFFF.toInt()
            p[Ink.STEAM] = if (dark) 0xFF8C7C6C.toInt() else 0xFFC4B29E.toInt()
            p[Ink.ACCENT] = argbOf(colors.accent)
            p[Ink.PAPER] = 0xFFFFFDF9.toInt()
            p[Ink.COCOA] = 0xFF7B6A5E.toInt()
            p[Ink.SAGE] = Palette.SAGE
            p[Ink.BERRY] = Palette.BERRY
            p[Ink.HONEY] = Palette.HONEY
            p[Ink.GROUND] = if (dark) 0x55000000 else 0x263A2E28
            return MochiPalette(p)
        }

        private fun argbOf(color: androidx.compose.ui.graphics.Color): Int {
            fun ch(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (ch(color.alpha) shl 24) or (ch(color.red) shl 16) or (ch(color.green) shl 8) or ch(color.blue)
        }
    }
}

/**
 * The prepared frames for one state: a loop, and the same loop once per mouth
 * shape when Mochi is talking. Bitmaps are shared through a small cache, so a
 * pose seen before is never drawn twice.
 */
class MochiFrames private constructor(
    private val loop: Array<ImageBitmap>,
    private val talk: Array<Array<ImageBitmap>>?,
    val tiny: ImageBitmap
) {
    fun at(tick: Int, mouth: Mouth?): ImageBitmap {
        val i = tick % loop.size
        val sets = talk ?: return loop[i]
        val m = if (mouth == null) 0 else Choreo.TALK_MOUTHS.indexOf(mouth).coerceAtLeast(0)
        return sets[m][i]
    }

    companion object {
        private data class Key(val pose: Pose, val palette: MochiPalette)

        private val cache = object : LinkedHashMap<Key, ImageBitmap>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImageBitmap>?) = size > 480
        }
        private val tinyCache = HashMap<MochiPalette, ImageBitmap>()

        fun build(state: CharacterState, palette: MochiPalette, reduce: Boolean): MochiFrames {
            val length = if (reduce) 1 else Choreo.loopLength(state)
            val loop = Array(length) { bitmap(Choreo.pose(state, it, reduce), palette) }
            val talk = if (state.talking) {
                Array(Choreo.TALK_MOUTHS.size) { m ->
                    Array(length) { bitmap(Choreo.pose(state, it, reduce, Choreo.TALK_MOUTHS[m]), palette) }
                }
            } else {
                null
            }
            val tiny = synchronized(tinyCache) {
                tinyCache.getOrPut(palette) { render(Sprites.tiny(), Sprites.TINY, Sprites.TINY, palette) }
            }
            return MochiFrames(loop, talk, tiny)
        }

        private fun bitmap(pose: Pose, palette: MochiPalette): ImageBitmap = synchronized(cache) {
            cache.getOrPut(Key(pose, palette)) { render(Sprites.compose(pose), Sprites.W, Sprites.H, palette) }
        }

        private fun render(cells: ByteArray, w: Int, h: Int, palette: MochiPalette): ImageBitmap {
            val pixels = IntArray(w * h) { palette.argb[cells[it].toInt()] }
            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            return bitmap.asImageBitmap()
        }
    }
}
