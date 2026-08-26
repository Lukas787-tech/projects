package com.cozyhollow.riverside

import kotlin.math.abs

/**
 * Inside the cabin.
 *
 * The interior is its own little world with its own coordinates: the floor is
 * flat at y=0, the room runs -HALF_X..HALF_X by -HALF_Z..HALF_Z, and the
 * player walks around it with the same stick and the same camera as outside.
 * Nothing here is a menu you open — the stove, the hearth, the bench and the
 * chair are objects you walk up to, which is the difference between a house
 * and a screen with buttons on it.
 *
 * North (-z) is the chimney wall. The door and the big window are in the south
 * (+z) wall, so when you sit in the chair you are looking out at the snow.
 */
object Interior {

    const val HALF_X = 3.3f
    const val HALF_Z = 2.6f
    const val WALL_H = 2.55f
    const val FLOOR_Y = 0f

    /** Just inside the front door — where you arrive, and where you leave from. */
    const val DOOR_X = 1.5f
    val DOOR_Z = HALF_Z - 0.55f

    /** How much of the room the hearth's light reaches, in metres. */
    const val HEARTH_X = 1.55f
    val HEARTH_Z = -HALF_Z + 0.42f

    const val STOVE_X = -1.95f
    val STOVE_Z = -HALF_Z + 0.46f

    /** The window you can stand at and watch it snow. */
    const val WINDOW_X = -1.25f
    val WINDOW_Z = HALF_Z - 0.12f

    object FKind {
        const val HEARTH = 0
        const val STOVE = 1
        const val BED = 2
        const val CHAIR = 3
        const val BENCH = 4
        const val SHELF = 5
        const val CHEST = 6
        const val DOOR = 7
        const val WINDOW = 8
        const val CAT = 9
        const val TABLE = 10
        const val RUG = 11
        const val LAMP = 12
        const val PLANT = 13
        const val COUNT = 14
    }

    /**
     * One thing in the room. [reach] is how close you have to stand for the
     * action button to notice it; [radius] is how much of the floor it takes
     * up. A radius of zero means you can walk right over it — a rug, say.
     */
    class Furniture(
        val kind: Int,
        val x: Float,
        val z: Float,
        val yaw: Float,
        val radius: Float,
        val reach: Float,
        val label: String
    )

    val items = arrayOf(
        Furniture(FKind.HEARTH, HEARTH_X, HEARTH_Z, 0f, 0.0f, 1.55f, "Stoke the fire"),
        Furniture(FKind.STOVE, STOVE_X, STOVE_Z, 0f, 0.0f, 1.45f, "Cook"),
        Furniture(FKind.BED, -HALF_X + 0.9f, 0.75f, 90f, 0.0f, 1.5f, "Sleep"),
        // no radius: you are placed exactly on the chair when you sit down, and
        // a collision circle there would trap you the moment you stood up
        Furniture(FKind.CHAIR, 0.35f, 0.72f, 168f, 0f, 1.25f, "Sit"),
        Furniture(FKind.BENCH, HALF_X - 0.55f, -0.9f, 270f, 0.0f, 1.45f, "Workbench"),
        Furniture(FKind.SHELF, HALF_X - 0.4f, 1.1f, 270f, 0.0f, 1.35f, "Journal"),
        Furniture(FKind.CHEST, -HALF_X + 0.8f, -1.75f, 20f, 0.5f, 1.35f, "Store"),
        Furniture(FKind.DOOR, DOOR_X, HALF_Z - 0.1f, 0f, 0f, 1.35f, "Go outside"),
        Furniture(FKind.WINDOW, WINDOW_X, WINDOW_Z, 0f, 0f, 1.2f, "Watch the snow"),
        Furniture(FKind.CAT, HEARTH_X - 1.05f, HEARTH_Z + 1.05f, -28f, 0f, 1.1f, "Pet Mitten"),
        Furniture(FKind.TABLE, -0.85f, 0.4f, 12f, 0.42f, 0f, ""),
        Furniture(FKind.RUG, 0.35f, -0.15f, 0f, 0f, 0f, ""),
        Furniture(FKind.LAMP, -0.85f, 0.4f, 0f, 0f, 0f, ""),
        Furniture(FKind.PLANT, -HALF_X + 0.55f, HALF_Z - 0.6f, 0f, 0.28f, 0f, "")
    )

    /** Where a chair sits you, and which way it turns you to face. */
    const val CHAIR_SIT_X = 0.35f
    const val CHAIR_SIT_Z = 0.72f
    const val CHAIR_SIT_YAW = 172f

    /**
     * Solid furniture, plus the walls. The bed, hearth, stove, bench and shelf
     * are all up against a wall, so the wall margin already keeps you out of
     * them and they carry no radius of their own.
     */
    fun blocked(x: Float, z: Float): Boolean {
        // walls: leave a body's width of clearance so you never clip a corner
        if (x < -HALF_X + 0.34f || x > HALF_X - 0.34f) return true
        if (z < -HALF_Z + 0.34f || z > HALF_Z - 0.30f) return true
        // the deep furniture along the north wall and the bed along the west
        if (z < -HALF_Z + 0.95f && (abs(x - HEARTH_X) < 1.05f || abs(x - STOVE_X) < 0.72f)) return true
        if (x < -HALF_X + 1.42f && abs(z - 0.75f) < 1.12f) return true
        if (x > HALF_X - 0.95f && z > -1.75f && z < 1.75f) return true
        for (f in items) {
            if (f.radius <= 0f) continue
            val dx = x - f.x
            val dz = z - f.z
            if (dx * dx + dz * dz < f.radius * f.radius) return true
        }
        return false
    }

    /** Nearest thing worth pressing the button at, or -1. */
    fun nearest(x: Float, z: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in items.indices) {
            val f = items[i]
            if (f.reach <= 0f) continue
            val dx = x - f.x
            val dz = z - f.z
            val d = dx * dx + dz * dz
            if (d < f.reach * f.reach && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Keeps a walk inside the four walls. */
    fun clamp(px: Float, pz: Float, out: FloatArray) {
        out[0] = px.coerceIn(-HALF_X + 0.35f, HALF_X - 0.35f)
        out[1] = pz.coerceIn(-HALF_Z + 0.35f, HALF_Z - 0.31f)
    }
}

/**
 * The things you can hang on the walls and stand on the shelves.
 *
 * Decorations do nothing mechanical at all. They are the reward for a good
 * week: something new to look at when you come in out of the dark.
 */
class Decor(
    val id: String,
    val name: String,
    val cost: Int,
    val slot: Int,
    val blurb: String
) {
    companion object {
        const val SLOT_WALL = 0
        const val SLOT_MANTEL = 1
        const val SLOT_FLOOR = 2
        const val SLOT_WINDOW = 3
    }
}

object Decorations {
    val list = listOf(
        Decor("wreath", "Pine Wreath", 260, Decor.SLOT_WALL, "Cones, berries and a red ribbon."),
        Decor("garland", "Paper Garland", 340, Decor.SLOT_WALL, "Strung across the beam over the hearth."),
        Decor("antlers", "Shed Antlers", 520, Decor.SLOT_WALL, "Found in the pinewood. Nobody minded."),
        Decor("painting", "Little Painting", 780, Decor.SLOT_WALL, "The pond, badly, but fondly."),
        Decor("clock", "Brass Clock", 640, Decor.SLOT_MANTEL, "Runs four minutes slow. Always has."),
        Decor("candles", "Candle Row", 300, Decor.SLOT_MANTEL, "Five stubs in five mismatched holders."),
        Decor("jar", "Firefly Jar", 900, Decor.SLOT_MANTEL, "Empty now, but it still glows a little."),
        Decor("rug", "Woven Rug", 420, Decor.SLOT_FLOOR, "Warm underfoot on a cold morning."),
        Decor("basket", "Log Basket", 240, Decor.SLOT_FLOOR, "Keeps the hearth tidy. Mostly."),
        Decor("catbed", "Cat Basket", 380, Decor.SLOT_FLOOR, "Mitten sleeps beside it, on principle."),
        Decor("sill", "Window Herbs", 290, Decor.SLOT_WINDOW, "Rosemary and thyme, thriving indoors."),
        Decor("frost", "Frost Chimes", 560, Decor.SLOT_WINDOW, "Glass on glass, whenever the door opens.")
    )

    fun byId(id: String): Decor? = list.firstOrNull { it.id == id }

    fun inSlot(slot: Int): List<Decor> = list.filter { it.slot == slot }
}
