package com.cozyhollow.riverside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

object Cat {
    const val SEED = 0
    const val CROP = 1
    const val FISH = 2
    const val FORAGE = 3
    const val MATERIAL = 4
}

object Icon {
    const val ROOT = 0
    const val CARROT = 1
    const val ROUND = 2
    const val PUMPKIN = 3
    const val CORN = 4
    const val SEED_BAG = 5
    const val FISH = 6
    const val LOG = 7
    const val MUSHROOM = 8
    const val BERRY = 9
    const val ACORN = 10
    const val HONEY = 11
    const val FLOWER = 12
    const val STONE = 13
    const val LEAF = 14
}

class Item(
    val id: String,
    val name: String,
    val price: Int,
    val cat: Int,
    val icon: Int,
    val a: Int,
    val b: Int,
    /** Energy restored when eaten. 0 = not edible. */
    val food: Int = 0
)

class Crop(
    val id: String,
    val name: String,
    val seedId: String,
    val produceId: String,
    val days: Int,
    val seedCost: Int,
    /** Unlocks once the cabin reaches this level. */
    val tier: Int,
    /** Keeps producing after the first harvest instead of clearing the plot. */
    val regrow: Boolean,
    val yieldMin: Int,
    val yieldMax: Int
)

class Fish(
    val id: String,
    val name: String,
    /** 0 = placid, 1 = wild. Drives the reeling mini-game. */
    val difficulty: Float,
    val weight: Float,
    /** Only bites between these minutes of the day. */
    val fromMin: Float,
    val toMin: Float,
    /** Extra weight when it is raining. */
    val rainBonus: Float
)

object Catalog {

    val items = LinkedHashMap<String, Item>()
    val crops = LinkedHashMap<String, Crop>()
    val fish = ArrayList<Fish>()

    private fun item(
        id: String, name: String, price: Int, cat: Int, icon: Int,
        a: String, b: String, food: Int = 0
    ) {
        items[id] = Item(id, name, price, cat, icon, Color.parseColor(a), Color.parseColor(b), food)
    }

    init {
        // ---- seeds ----
        item("seed_turnip", "Turnip Seeds", 12, Cat.SEED, Icon.SEED_BAG, "#E8D7B0", "#C9B489")
        item("seed_carrot", "Carrot Seeds", 20, Cat.SEED, Icon.SEED_BAG, "#E8D7B0", "#E08B45")
        item("seed_tomato", "Tomato Seeds", 34, Cat.SEED, Icon.SEED_BAG, "#E8D7B0", "#D6564C")
        item("seed_corn", "Corn Seeds", 52, Cat.SEED, Icon.SEED_BAG, "#E8D7B0", "#EFC54A")
        item("seed_berry", "Berry Seeds", 78, Cat.SEED, Icon.SEED_BAG, "#E8D7B0", "#D0568A")
        item("seed_pumpkin", "Pumpkin Seeds", 110, Cat.SEED, Icon.SEED_BAG, "#E8D7B0", "#E08240")

        // ---- produce ----
        item("turnip", "Turnip", 26, Cat.CROP, Icon.ROOT, "#F2EFE2", "#C9A9D8", food = 8)
        item("carrot", "Carrot", 42, Cat.CROP, Icon.CARROT, "#E88B3C", "#5C9147", food = 10)
        item("tomato", "Tomato", 68, Cat.CROP, Icon.ROUND, "#D6564C", "#5C9147", food = 14)
        item("corn", "Corn", 96, Cat.CROP, Icon.CORN, "#EFC54A", "#6FA45A", food = 18)
        item("berry", "Sweet Berry", 132, Cat.CROP, Icon.BERRY, "#D0568A", "#5C9147", food = 22)
        item("pumpkin", "Pumpkin", 205, Cat.CROP, Icon.PUMPKIN, "#E08240", "#6FA45A", food = 30)

        // ---- fish ----
        item("f_minnow", "Minnow", 22, Cat.FISH, Icon.FISH, "#B9D9E8", "#6FA0BC")
        item("f_perch", "River Perch", 46, Cat.FISH, Icon.FISH, "#8FBF7F", "#4F7A52")
        item("f_trout", "Speckled Trout", 84, Cat.FISH, Icon.FISH, "#D9A56E", "#9C6A42")
        item("f_catfish", "Whiskered Catfish", 148, Cat.FISH, Icon.FISH, "#7E8A96", "#4C565F")
        item("f_moon", "Moonscale", 240, Cat.FISH, Icon.FISH, "#B8C6F0", "#6E7EC0")
        item("f_carp", "Golden Carp", 420, Cat.FISH, Icon.FISH, "#F2C94C", "#C08A24")
        item("f_king", "River King", 900, Cat.FISH, Icon.FISH, "#8FD9C8", "#2F7A6A")

        // ---- forage / materials ----
        item("wood", "Wood", 9, Cat.MATERIAL, Icon.LOG, "#B98A5A", "#8C6440")
        item("stone", "Stone", 14, Cat.MATERIAL, Icon.STONE, "#B0A79C", "#8A8079")
        item("mushroom", "Mushroom", 34, Cat.FORAGE, Icon.MUSHROOM, "#D06A72", "#F4EAD8", food = 12)
        item("acorn", "Acorn", 18, Cat.FORAGE, Icon.ACORN, "#C08A54", "#7E5A34")
        item("flower", "Wildflower", 30, Cat.FORAGE, Icon.FLOWER, "#E8A0C0", "#F2D45A")
        item("honey", "Wild Honey", 120, Cat.FORAGE, Icon.HONEY, "#E8A93C", "#B8781E", food = 25)

        // ---- crops ----
        crop("turnip", "Turnip", 2, 12, 1, false, 1, 2)
        crop("carrot", "Carrot", 3, 20, 1, false, 1, 2)
        crop("tomato", "Tomato", 4, 34, 2, true, 1, 2)
        crop("corn", "Corn", 5, 52, 2, true, 1, 3)
        crop("berry", "Sweet Berry", 5, 78, 3, true, 2, 3)
        crop("pumpkin", "Pumpkin", 7, 110, 3, false, 1, 2)

        // ---- fish table ----
        fish.add(Fish("f_minnow", "Minnow", 0.12f, 34f, 0f, 1440f, 0.2f))
        fish.add(Fish("f_perch", "River Perch", 0.28f, 26f, 300f, 1200f, 0.4f))
        fish.add(Fish("f_trout", "Speckled Trout", 0.42f, 18f, 300f, 780f, 0.8f))
        fish.add(Fish("f_catfish", "Whiskered Catfish", 0.55f, 12f, 960f, 1440f, 0.9f))
        fish.add(Fish("f_moon", "Moonscale", 0.68f, 7f, 1140f, 1440f, 0.5f))
        fish.add(Fish("f_carp", "Golden Carp", 0.80f, 3f, 480f, 1080f, 1.4f))
        fish.add(Fish("f_king", "River King", 0.95f, 1f, 0f, 1440f, 2.2f))
    }

    private fun crop(
        id: String, name: String, days: Int, seedCost: Int, tier: Int,
        regrow: Boolean, yMin: Int, yMax: Int
    ) {
        crops[id] = Crop(id, name, "seed_$id", id, days, seedCost, tier, regrow, yMin, yMax)
    }

    fun item(id: String): Item = items[id] ?: items["wood"]!!

    fun name(id: String): String = items[id]?.name ?: id

    fun price(id: String): Int = items[id]?.price ?: 1

    fun cropForSeed(seedId: String): Crop? = crops.values.firstOrNull { it.seedId == seedId }

    fun fishById(id: String): Fish? = fish.firstOrNull { it.id == id }
}

/** Draws the little procedural sprites used for every item in the game. */
object IconDraw {

    private val p = Path()
    private val r = RectF()

    fun draw(c: Canvas, item: Item, cx: Float, cy: Float, size: Float, paint: Paint) {
        val s = size * 0.5f
        paint.style = Paint.Style.FILL
        when (item.icon) {
            Icon.ROOT -> root(c, cx, cy, s, item, paint)
            Icon.CARROT -> carrot(c, cx, cy, s, item, paint)
            Icon.ROUND -> round(c, cx, cy, s, item, paint)
            Icon.PUMPKIN -> pumpkin(c, cx, cy, s, item, paint)
            Icon.CORN -> corn(c, cx, cy, s, item, paint)
            Icon.SEED_BAG -> seedBag(c, cx, cy, s, item, paint)
            Icon.FISH -> fish(c, cx, cy, s, item, paint)
            Icon.LOG -> log(c, cx, cy, s, item, paint)
            Icon.MUSHROOM -> mushroom(c, cx, cy, s, item, paint)
            Icon.BERRY -> berry(c, cx, cy, s, item, paint)
            Icon.ACORN -> acorn(c, cx, cy, s, item, paint)
            Icon.HONEY -> honey(c, cx, cy, s, item, paint)
            Icon.FLOWER -> flower(c, cx, cy, s, item, paint)
            Icon.STONE -> stone(c, cx, cy, s, item, paint)
            else -> leaf(c, cx, cy, s, item, paint)
        }
    }

    private fun root(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        c.drawCircle(cx, cy + s * 0.28f, s * 0.6f, paint)
        paint.color = it.a
        c.drawCircle(cx, cy + s * 0.42f, s * 0.52f, paint)
        paint.color = U.shade(it.a, 1.12f)
        c.drawCircle(cx - s * 0.18f, cy + s * 0.28f, s * 0.18f, paint)
        paint.color = Pal.leafDeep
        p.reset()
        p.moveTo(cx, cy - s * 0.1f)
        p.quadTo(cx - s * 0.6f, cy - s * 0.5f, cx - s * 0.22f, cy - s * 0.85f)
        p.quadTo(cx - s * 0.05f, cy - s * 0.45f, cx, cy - s * 0.1f)
        c.drawPath(p, paint)
        p.reset()
        p.moveTo(cx, cy - s * 0.1f)
        p.quadTo(cx + s * 0.6f, cy - s * 0.5f, cx + s * 0.24f, cy - s * 0.88f)
        p.quadTo(cx + s * 0.05f, cy - s * 0.45f, cx, cy - s * 0.1f)
        c.drawPath(p, paint)
    }

    private fun carrot(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.a
        p.reset()
        p.moveTo(cx - s * 0.42f, cy - s * 0.25f)
        p.lineTo(cx + s * 0.42f, cy - s * 0.25f)
        p.quadTo(cx + s * 0.1f, cy + s * 0.5f, cx, cy + s * 0.92f)
        p.quadTo(cx - s * 0.1f, cy + s * 0.5f, cx - s * 0.42f, cy - s * 0.25f)
        p.close()
        c.drawPath(p, paint)
        paint.color = U.shade(it.a, 0.86f)
        c.drawRect(cx - s * 0.26f, cy - s * 0.02f, cx + s * 0.28f, cy + s * 0.06f, paint)
        c.drawRect(cx - s * 0.16f, cy + s * 0.3f, cx + s * 0.18f, cy + s * 0.37f, paint)
        paint.color = it.b
        for (i in -1..1) {
            p.reset()
            p.moveTo(cx, cy - s * 0.25f)
            p.quadTo(cx + i * s * 0.5f, cy - s * 0.6f, cx + i * s * 0.4f, cy - s * 0.95f)
            p.quadTo(cx + i * s * 0.12f, cy - s * 0.55f, cx, cy - s * 0.25f)
            c.drawPath(p, paint)
        }
    }

    private fun round(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = U.shade(it.a, 0.82f)
        c.drawCircle(cx, cy + s * 0.2f, s * 0.66f, paint)
        paint.color = it.a
        c.drawCircle(cx, cy + s * 0.14f, s * 0.62f, paint)
        paint.color = U.withAlpha(Color.WHITE, 0.45f)
        c.drawCircle(cx - s * 0.24f, cy - s * 0.12f, s * 0.16f, paint)
        paint.color = it.b
        for (i in 0..4) {
            val ang = (-90f + i * 72f) * 0.017453f
            p.reset()
            p.moveTo(cx, cy - s * 0.42f)
            p.lineTo(cx + cos(ang) * s * 0.42f, cy - s * 0.42f + sin(ang) * s * 0.3f)
            p.lineTo(cx + cos(ang) * s * 0.16f, cy - s * 0.28f)
            p.close()
            c.drawPath(p, paint)
        }
    }

    private fun pumpkin(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = U.shade(it.a, 0.85f)
        c.drawOval(rect(cx - s * 0.8f, cy - s * 0.42f, cx + s * 0.8f, cy + s * 0.78f), paint)
        paint.color = it.a
        c.drawOval(rect(cx - s * 0.58f, cy - s * 0.46f, cx + s * 0.58f, cy + s * 0.76f), paint)
        paint.color = U.shade(it.a, 1.14f)
        c.drawOval(rect(cx - s * 0.24f, cy - s * 0.46f, cx + s * 0.24f, cy + s * 0.76f), paint)
        paint.color = it.b
        c.drawRect(cx - s * 0.09f, cy - s * 0.78f, cx + s * 0.09f, cy - s * 0.4f, paint)
    }

    private fun corn(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.a
        c.drawRoundRect(rect(cx - s * 0.3f, cy - s * 0.8f, cx + s * 0.3f, cy + s * 0.72f), s * 0.3f, s * 0.3f, paint)
        paint.color = U.shade(it.a, 0.85f)
        var y = cy - s * 0.6f
        while (y < cy + s * 0.6f) {
            c.drawRect(cx - s * 0.28f, y, cx + s * 0.28f, y + s * 0.06f, paint)
            y += s * 0.24f
        }
        paint.color = it.b
        p.reset()
        p.moveTo(cx - s * 0.24f, cy + s * 0.7f)
        p.quadTo(cx - s * 0.9f, cy + s * 0.1f, cx - s * 0.5f, cy - s * 0.7f)
        p.quadTo(cx - s * 0.2f, cy - s * 0.1f, cx - s * 0.24f, cy + s * 0.7f)
        c.drawPath(p, paint)
        p.reset()
        p.moveTo(cx + s * 0.24f, cy + s * 0.7f)
        p.quadTo(cx + s * 0.9f, cy + s * 0.1f, cx + s * 0.5f, cy - s * 0.7f)
        p.quadTo(cx + s * 0.2f, cy - s * 0.1f, cx + s * 0.24f, cy + s * 0.7f)
        c.drawPath(p, paint)
    }

    private fun seedBag(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.a
        p.reset()
        p.moveTo(cx - s * 0.42f, cy - s * 0.3f)
        p.lineTo(cx + s * 0.42f, cy - s * 0.3f)
        p.quadTo(cx + s * 0.72f, cy + s * 0.5f, cx + s * 0.3f, cy + s * 0.8f)
        p.lineTo(cx - s * 0.3f, cy + s * 0.8f)
        p.quadTo(cx - s * 0.72f, cy + s * 0.5f, cx - s * 0.42f, cy - s * 0.3f)
        p.close()
        c.drawPath(p, paint)
        paint.color = U.shade(it.a, 0.88f)
        c.drawRect(cx - s * 0.44f, cy - s * 0.42f, cx + s * 0.44f, cy - s * 0.26f, paint)
        paint.color = it.b
        c.drawCircle(cx - s * 0.14f, cy + s * 0.24f, s * 0.15f, paint)
        c.drawCircle(cx + s * 0.18f, cy + s * 0.4f, s * 0.13f, paint)
        c.drawCircle(cx + s * 0.02f, cy + s * 0.56f, s * 0.11f, paint)
        paint.color = Pal.woodDeep
        c.drawRect(cx - s * 0.24f, cy - s * 0.5f, cx + s * 0.24f, cy - s * 0.4f, paint)
    }

    private fun fish(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        p.reset()
        p.moveTo(cx + s * 0.44f, cy)
        p.lineTo(cx + s * 0.86f, cy - s * 0.38f)
        p.lineTo(cx + s * 0.86f, cy + s * 0.38f)
        p.close()
        c.drawPath(p, paint)
        paint.color = it.a
        c.drawOval(rect(cx - s * 0.86f, cy - s * 0.4f, cx + s * 0.52f, cy + s * 0.4f), paint)
        paint.color = it.b
        p.reset()
        p.moveTo(cx - s * 0.1f, cy - s * 0.3f)
        p.quadTo(cx + s * 0.05f, cy - s * 0.78f, cx + s * 0.3f, cy - s * 0.2f)
        p.close()
        c.drawPath(p, paint)
        paint.color = U.withAlpha(Color.WHITE, 0.35f)
        c.drawOval(rect(cx - s * 0.7f, cy + s * 0.02f, cx + s * 0.2f, cy + s * 0.34f), paint)
        paint.color = Pal.ink
        c.drawCircle(cx - s * 0.52f, cy - s * 0.08f, s * 0.09f, paint)
        paint.color = Color.WHITE
        c.drawCircle(cx - s * 0.55f, cy - s * 0.11f, s * 0.035f, paint)
    }

    private fun log(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        c.drawRoundRect(rect(cx - s * 0.8f, cy - s * 0.34f, cx + s * 0.8f, cy + s * 0.38f), s * 0.2f, s * 0.2f, paint)
        paint.color = it.a
        c.drawOval(rect(cx - s * 0.86f, cy - s * 0.36f, cx - s * 0.32f, cy + s * 0.4f), paint)
        paint.color = U.shade(it.a, 0.8f)
        c.drawOval(rect(cx - s * 0.74f, cy - s * 0.2f, cx - s * 0.44f, cy + s * 0.24f), paint)
        paint.color = U.shade(it.b, 0.85f)
        c.drawRect(cx - s * 0.1f, cy - s * 0.3f, cx + s * 0.0f, cy + s * 0.34f, paint)
        c.drawRect(cx + s * 0.36f, cy - s * 0.28f, cx + s * 0.44f, cy + s * 0.32f, paint)
    }

    private fun mushroom(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        c.drawRoundRect(rect(cx - s * 0.2f, cy - s * 0.05f, cx + s * 0.2f, cy + s * 0.78f), s * 0.16f, s * 0.16f, paint)
        paint.color = it.a
        p.reset()
        p.moveTo(cx - s * 0.78f, cy - s * 0.02f)
        p.quadTo(cx, cy - s * 1.05f, cx + s * 0.78f, cy - s * 0.02f)
        p.close()
        c.drawPath(p, paint)
        paint.color = U.withAlpha(Color.WHITE, 0.7f)
        c.drawCircle(cx - s * 0.3f, cy - s * 0.3f, s * 0.14f, paint)
        c.drawCircle(cx + s * 0.24f, cy - s * 0.22f, s * 0.1f, paint)
        c.drawCircle(cx + s * 0.02f, cy - s * 0.5f, s * 0.09f, paint)
    }

    private fun berry(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        c.drawRect(cx - s * 0.05f, cy - s * 0.8f, cx + s * 0.05f, cy - s * 0.2f, paint)
        paint.color = it.a
        c.drawCircle(cx - s * 0.3f, cy + s * 0.12f, s * 0.34f, paint)
        c.drawCircle(cx + s * 0.3f, cy + s * 0.06f, s * 0.3f, paint)
        c.drawCircle(cx, cy + s * 0.44f, s * 0.32f, paint)
        paint.color = U.withAlpha(Color.WHITE, 0.4f)
        c.drawCircle(cx - s * 0.4f, cy - s * 0.02f, s * 0.1f, paint)
        paint.color = it.b
        p.reset()
        p.moveTo(cx, cy - s * 0.24f)
        p.lineTo(cx - s * 0.42f, cy - s * 0.46f)
        p.lineTo(cx + s * 0.42f, cy - s * 0.46f)
        p.close()
        c.drawPath(p, paint)
    }

    private fun acorn(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.a
        p.reset()
        p.moveTo(cx - s * 0.5f, cy - s * 0.1f)
        p.quadTo(cx, cy + s * 1.05f, cx + s * 0.5f, cy - s * 0.1f)
        p.close()
        c.drawPath(p, paint)
        paint.color = it.b
        c.drawRoundRect(rect(cx - s * 0.58f, cy - s * 0.52f, cx + s * 0.58f, cy - s * 0.06f), s * 0.2f, s * 0.2f, paint)
        c.drawRect(cx - s * 0.06f, cy - s * 0.78f, cx + s * 0.06f, cy - s * 0.44f, paint)
    }

    private fun honey(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        c.drawRoundRect(rect(cx - s * 0.5f, cy - s * 0.4f, cx + s * 0.5f, cy + s * 0.8f), s * 0.18f, s * 0.18f, paint)
        paint.color = it.a
        c.drawRoundRect(rect(cx - s * 0.42f, cy - s * 0.3f, cx + s * 0.42f, cy + s * 0.72f), s * 0.14f, s * 0.14f, paint)
        paint.color = U.withAlpha(Color.WHITE, 0.35f)
        c.drawRect(cx - s * 0.3f, cy - s * 0.2f, cx - s * 0.18f, cy + s * 0.6f, paint)
        paint.color = Pal.woodDeep
        c.drawRect(cx - s * 0.56f, cy - s * 0.56f, cx + s * 0.56f, cy - s * 0.36f, paint)
    }

    private fun flower(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = Pal.leafDeep
        c.drawRect(cx - s * 0.05f, cy - s * 0.1f, cx + s * 0.05f, cy + s * 0.8f, paint)
        paint.color = it.a
        for (i in 0 until 6) {
            val ang = i * 60f * 0.017453f
            c.drawCircle(cx + cos(ang) * s * 0.34f, cy - s * 0.2f + sin(ang) * s * 0.34f, s * 0.24f, paint)
        }
        paint.color = it.b
        c.drawCircle(cx, cy - s * 0.2f, s * 0.2f, paint)
    }

    private fun stone(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        p.reset()
        p.moveTo(cx - s * 0.7f, cy + s * 0.5f)
        p.lineTo(cx - s * 0.5f, cy - s * 0.3f)
        p.lineTo(cx + s * 0.1f, cy - s * 0.6f)
        p.lineTo(cx + s * 0.68f, cy - s * 0.1f)
        p.lineTo(cx + s * 0.5f, cy + s * 0.55f)
        p.close()
        c.drawPath(p, paint)
        paint.color = it.a
        p.reset()
        p.moveTo(cx - s * 0.5f, cy - s * 0.3f)
        p.lineTo(cx + s * 0.1f, cy - s * 0.6f)
        p.lineTo(cx + s * 0.16f, cy + s * 0.05f)
        p.close()
        c.drawPath(p, paint)
    }

    private fun leaf(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.a
        p.reset()
        p.moveTo(cx - s * 0.6f, cy + s * 0.5f)
        p.quadTo(cx - s * 0.6f, cy - s * 0.7f, cx + s * 0.6f, cy - s * 0.5f)
        p.quadTo(cx + s * 0.4f, cy + s * 0.6f, cx - s * 0.6f, cy + s * 0.5f)
        c.drawPath(p, paint)
    }

    private fun rect(l: Float, t: Float, rr: Float, b: Float): RectF {
        r.set(l, t, rr, b)
        return r
    }
}
