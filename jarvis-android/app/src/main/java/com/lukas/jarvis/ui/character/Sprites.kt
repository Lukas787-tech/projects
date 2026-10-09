package com.lukas.jarvis.ui.character

/**
 * What each pixel of Mochi is, before the theme says what colour that is.
 * The art is drawn in roles, so the night café can warm the steam or dim the
 * shadow without anyone redrawing a frame, and the props take the person's
 * own accent.
 */
object Ink {
    const val CLEAR = 0
    const val OUTLINE = 1
    const val BODY = 2
    const val SHADE = 3
    const val SHINE = 4
    const val BLUSH = 5
    const val EYE = 6
    const val GLINT = 7
    const val STEAM = 8
    const val ACCENT = 9
    const val PAPER = 10
    const val COCOA = 11
    const val SAGE = 12
    const val BERRY = 13
    const val HONEY = 14
    const val GROUND = 15
    const val COUNT = 16

    fun of(ch: Char): Int = when (ch) {
        'o' -> OUTLINE
        'b' -> BODY
        's', 'd' -> SHADE
        'h' -> SHINE
        'p' -> BLUSH
        'e' -> EYE
        'w' -> GLINT
        'm' -> STEAM
        'a' -> ACCENT
        'c' -> PAPER
        'k', 'l' -> COCOA
        'g' -> SAGE
        'r' -> BERRY
        'y' -> HONEY
        else -> CLEAR
    }
}

/** One authored piece of art, parsed once: [cells] holds an [Ink] role per pixel. */
class Sheet(val width: Int, val height: Int, val cells: ByteArray) {
    operator fun get(x: Int, y: Int): Int = cells[y * width + x].toInt()

    companion object {
        fun of(rows: Array<String>): Sheet {
            val w = rows.maxOf { it.length }
            val cells = ByteArray(w * rows.size)
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, ch -> cells[y * w + x] = Ink.of(ch).toByte() }
            }
            return Sheet(w, rows.size, cells)
        }
    }
}

enum class Eyes(internal val sheet: Sheet) {
    Open(Sheet.of(Art.EYES_OPEN)),
    Blink(Sheet.of(Art.EYES_BLINK)),
    Happy(Sheet.of(Art.EYES_HAPPY)),
    Closed(Sheet.of(Art.EYES_CLOSED)),
    Wide(Sheet.of(Art.EYES_WIDE))
}

enum class Brows { None, Worried, Raised }

enum class Mouth(internal val sheet: Sheet, internal val top: Int) {
    Smile(Sheet.of(Art.MOUTH_SMILE), 13),
    Flat(Sheet.of(Art.MOUTH_FLAT), 13),
    Small(Sheet.of(Art.MOUTH_SMALL), 12),
    Open(Sheet.of(Art.MOUTH_OPEN), 12),
    Frown(Sheet.of(Art.MOUTH_FROWN), 13),
    Grin(Sheet.of(Art.MOUTH_GRIN), 12)
}

/** The steam curl on top: Mochi's mood antenna. */
enum class Curl(internal val sheet: Sheet) {
    Relaxed(Sheet.of(Art.CURL_RELAXED)),
    LeanLeft(Sheet.of(Art.CURL_LEAN_LEFT)),
    LeanRight(Sheet.of(Art.CURL_LEAN_RIGHT)),
    Perked(Sheet.of(Art.CURL_PERKED)),
    Droop(Sheet.of(Art.CURL_DROOP)),
    Question(Sheet.of(Art.CURL_QUESTION)),
    Bang(Sheet.of(Art.CURL_BANG)),
    Heart(Sheet.of(Art.CURL_HEART)),
    Star(Sheet.of(Art.CURL_STAR)),
    /** Floats off to one side rather than sitting on the knot. */
    Zed(Sheet.of(Art.CURL_Z))
}

/** What Mochi holds while it works. Each family of tools has one. */
enum class Prop(internal val sheet: Sheet) {
    Notepad(Sheet.of(Art.PROP_NOTEPAD)),
    Coins(Sheet.of(Art.PROP_COINS)),
    Magnifier(Sheet.of(Art.PROP_MAGNIFIER)),
    Umbrella(Sheet.of(Art.PROP_UMBRELLA)),
    Map(Sheet.of(Art.PROP_MAP)),
    Calendar(Sheet.of(Art.PROP_CALENDAR)),
    Envelope(Sheet.of(Art.PROP_ENVELOPE)),
    Phone(Sheet.of(Art.PROP_PHONE)),
    Note(Sheet.of(Art.PROP_NOTE)),
    Camera(Sheet.of(Art.PROP_CAMERA)),
    Gear(Sheet.of(Art.PROP_GEAR)),
    Bubble(Sheet.of(Art.PROP_BUBBLE)),
    Brush(Sheet.of(Art.PROP_BRUSH)),
    Die(Sheet.of(Art.PROP_DIE)),
    House(Sheet.of(Art.PROP_HOUSE)),
    Bell(Sheet.of(Art.PROP_BELL)),
    Cup(Sheet.of(Art.PROP_CUP)),
    /** The card it is asking about, held up for your yes. */
    Card(Sheet.of(Art.PROP_CARD))
}

enum class BodyShape { Rest, Squash, Stretch, Slump, LeanLeft, LeanRight }

enum class Arms { None, HoldRight, WaveLeft, BothUp }

enum class Look(internal val dx: Int, internal val dy: Int) {
    Ahead(0, 0), Left(-1, 0), Right(1, 0), Up(0, -1), Down(0, 1), UpRight(1, -1)
}

enum class Blush { None, Soft, Strong }

/** One frame's worth of choices. Composed into pixels by [Sprites.compose]. */
data class Pose(
    val body: BodyShape = BodyShape.Rest,
    val eyes: Eyes = Eyes.Open,
    /** Set when the right eye differs: a squint. */
    val rightEye: Eyes? = null,
    val brows: Brows = Brows.None,
    val mouth: Mouth = Mouth.Smile,
    val curl: Curl = Curl.Relaxed,
    val curlDx: Int = 0,
    val curlDy: Int = 0,
    val prop: Prop? = null,
    val propDy: Int = 0,
    val arms: Arms = if (prop != null) Arms.HoldRight else Arms.None,
    val waveDy: Int = 0,
    val look: Look = Look.Ahead,
    val blush: Blush = Blush.Soft,
    /** How far the whole body is off the ground, for a hop. */
    val lift: Int = 0
)

/**
 * Turns a [Pose] into pixels: a [W] x [H] grid of [Ink] roles. Pure and
 * cheap, so poses are composed once and cached as images by the renderer.
 */
object Sprites {
    const val W = 40
    const val H = 32
    const val TINY = 16

    private val body = Sheet.of(Art.BODY)
    private val hand = Sheet.of(Art.ARM)
    private val armLeft = Sheet.of(Art.ARM_LEFT)
    private val armRight = Sheet.of(Art.ARM_RIGHT)
    private val tiny = Sheet.of(Art.TINY)
    private val worriedLeft = Sheet.of(Art.BROW_WORRIED_LEFT)
    private val worriedRight = Sheet.of(Art.BROW_WORRIED_RIGHT)
    private val raised = Sheet.of(Art.BROW_RAISED)

    /** Where the body's left edge sits in the frame, and the ground line. */
    private const val BODY_X = 8
    private const val GROUND_Y = 31
    private const val LEFT_EYE_X = 5
    private const val RIGHT_EYE_X = 15
    private const val EYE_Y = 8

    fun compose(pose: Pose): ByteArray {
        val face = faceOn(pose)
        val rows = shape(face, pose.body)
        val frame = ByteArray(W * H)

        // A soft shadow on the ground, smaller while hopping.
        val shadow = 20 - 2 * pose.lift.coerceIn(0, 4)
        val shadowX = BODY_X + 12 - shadow / 2
        for (x in shadowX until shadowX + shadow) set(frame, x, GROUND_Y, Ink.GROUND)

        val bottom = GROUND_Y - 1 - pose.lift
        val top = bottom - rows.size + 1
        rows.forEachIndexed { i, row ->
            row.forEachIndexed { x, cell -> if (cell.toInt() != Ink.CLEAR) set(frame, x + BODY_X - 1, top + i, cell.toInt()) }
        }

        // The curl rises from the knot on top; the z floats off to the side.
        val curl = pose.curl.sheet
        if (pose.curl == Curl.Zed) {
            stamp(frame, curl, BODY_X + 20 + pose.curlDx, top - 6 + pose.curlDy)
        } else {
            stamp(frame, curl, BODY_X + 12 - curl.width / 2 + pose.curlDx, top - curl.height + pose.curlDy)
        }

        when (pose.arms) {
            Arms.HoldRight -> {
                val prop = pose.prop
                if (prop != null) {
                    val sheet = prop.sheet
                    val px = W - sheet.width
                    val pyBottom = bottom - 3 + pose.propDy
                    stamp(frame, sheet, px, pyBottom - sheet.height + 1)
                    stamp(frame, hand, px - 2, pyBottom - 3)
                } else {
                    stamp(frame, armRight, BODY_X + 22, top + 9)
                }
            }
            // The nubs overlap the body's outline by a pixel, so they read as attached.
            Arms.WaveLeft -> stamp(frame, armLeft, BODY_X - 3, top + 5 + pose.waveDy)
            Arms.BothUp -> {
                stamp(frame, armLeft, BODY_X - 2, top + 3)
                stamp(frame, armRight, BODY_X + 22, top + 3)
            }
            Arms.None -> pose.prop?.let { prop ->
                stamp(frame, prop.sheet, W - prop.sheet.width, bottom - 3 + pose.propDy - prop.sheet.height + 1)
            }
        }
        return frame
    }

    /** The 16 x 16 Mochi for places too small for the full one: a status bar, a notification. */
    fun tiny(): ByteArray = tiny.cells.copyOf()

    /** The body with this pose's face drawn on it, as rows. */
    private fun faceOn(pose: Pose): Array<ByteArray> {
        val grid = Array(body.height) { y -> ByteArray(body.width) { x -> body[x, y].toByte() } }
        val dx = pose.look.dx
        val dy = pose.look.dy
        stampRows(grid, pose.eyes.sheet, LEFT_EYE_X + dx, EYE_Y + dy)
        stampRows(grid, (pose.rightEye ?: pose.eyes).sheet, RIGHT_EYE_X + dx, EYE_Y + dy)
        when (pose.brows) {
            Brows.Worried -> {
                stampRows(grid, worriedLeft, LEFT_EYE_X + dx, EYE_Y - 3)
                stampRows(grid, worriedRight, RIGHT_EYE_X + dx, EYE_Y - 3)
            }
            Brows.Raised -> stampRows(grid, raised, RIGHT_EYE_X + dx, EYE_Y - 2)
            Brows.None -> Unit
        }
        if (pose.blush != Blush.None) {
            val rows = if (pose.blush == Blush.Strong) 12..13 else 12..12
            for (y in rows) for (x in listOf(3, 4, 19, 20)) grid[y][x] = Ink.BLUSH.toByte()
        }
        stampRows(grid, pose.mouth.sheet, 10, pose.mouth.top)
        return grid
    }

    /** Squash, stretch and lean, by taking rows out of the dome, adding them, or shearing it. */
    private fun shape(rows: Array<ByteArray>, shape: BodyShape): List<ByteArray> {
        val list = rows.toMutableList()
        when (shape) {
            BodyShape.Rest -> Unit
            BodyShape.Squash -> list.removeAt(5)
            BodyShape.Stretch -> list.add(5, list[5].copyOf())
            BodyShape.Slump -> {
                list.removeAt(6)
                list.removeAt(4)
            }
            BodyShape.LeanLeft, BodyShape.LeanRight -> {
                val step = if (shape == BodyShape.LeanLeft) -1 else 1
                for (i in 0..5) list[i] = shifted(list[i], step)
            }
        }
        // One cell of room on the left so a lean to the left stays in the frame.
        return list.map { row -> ByteArray(row.size + 2).also { row.copyInto(it, 1) } }
    }

    private fun shifted(row: ByteArray, by: Int): ByteArray {
        val out = ByteArray(row.size)
        for (x in row.indices) {
            val from = x - by
            if (from in row.indices) out[x] = row[from]
        }
        return out
    }

    private fun stampRows(grid: Array<ByteArray>, sheet: Sheet, x0: Int, y0: Int) {
        for (y in 0 until sheet.height) for (x in 0 until sheet.width) {
            val cell = sheet[x, y]
            val gx = x0 + x
            val gy = y0 + y
            if (cell != Ink.CLEAR && gy in grid.indices && gx in grid[gy].indices) grid[gy][gx] = cell.toByte()
        }
    }

    private fun stamp(frame: ByteArray, sheet: Sheet, x0: Int, y0: Int) {
        for (y in 0 until sheet.height) for (x in 0 until sheet.width) {
            val cell = sheet[x, y]
            if (cell != Ink.CLEAR) set(frame, x0 + x, y0 + y, cell)
        }
    }

    private fun set(frame: ByteArray, x: Int, y: Int, ink: Int) {
        if (x in 0 until W && y in 0 until H) frame[y * W + x] = ink.toByte()
    }
}
