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
    const val MEAL = 5
    const val COUNT = 6
}

object Icon {
    const val ROOT = 0
    const val CARROT = 1
    const val ROUND = 2
    const val SQUASH = 3
    const val LEAFY = 4
    const val SEED_BAG = 5
    const val FISH = 6
    const val LOG = 7
    const val MUSHROOM = 8
    const val BERRY = 9
    const val CONE = 10
    const val MUG = 11
    const val FLOWER = 12
    const val STONE = 13
    const val LEAF = 14
    const val FIREWOOD = 15
    const val BOWL = 16
    const val PIE = 17
    const val HAY = 18
    const val ICE = 19
    const val WOOL = 20
    const val TIN = 21
    const val PEPPER = 22
}

class Item(
    val id: String,
    val name: String,
    val price: Int,
    val cat: Int,
    val icon: Int,
    val a: Int,
    val b: Int,
    /** Warmth restored when eaten or drunk. 0 = not food. */
    val warmth: Int = 0,
    /** How long the glow of a hot meal lingers, in in-game minutes. */
    val comfort: Float = 0f
)

class Crop(
    val id: String,
    val name: String,
    val seedId: String,
    val produceId: String,
    val days: Int,
    val seedCost: Int,
    /** Unlocks once the cabin reaches this comfort level. */
    val tier: Int,
    /** Keeps producing after the first harvest instead of clearing the bed. */
    val regrow: Boolean,
    val yieldMin: Int,
    val yieldMax: Int
)

class Fish(
    val id: String,
    val name: String,
    /** 0 = placid, 1 = wild. Drives the jigging mini-game. */
    val difficulty: Float,
    val weight: Float,
    /** Only bites between these minutes of the day. */
    val fromMin: Float,
    val toMin: Float,
    /** Extra weight when it is snowing hard. */
    val stormBonus: Float
)

/** Something you make. Works for both the stove and the workbench. */
class Recipe(
    val id: String,
    val name: String,
    val outId: String,
    val outQty: Int,
    val inputs: Array<Pair<String, Int>>,
    /** 0 = stove, 1 = workbench. */
    val station: Int,
    val blurb: String,
    /** Minutes of the day it takes to make. */
    val minutes: Float = 20f
) {
    companion object {
        const val STOVE = 0
        const val BENCH = 1
    }
}

object Catalog {

    val items = LinkedHashMap<String, Item>()
    val crops = LinkedHashMap<String, Crop>()
    val fish = ArrayList<Fish>()
    val recipes = ArrayList<Recipe>()

    private fun item(
        id: String, name: String, price: Int, cat: Int, icon: Int,
        a: String, b: String, warmth: Int = 0, comfort: Float = 0f
    ) {
        items[id] = Item(id, name, price, cat, icon, Color.parseColor(a), Color.parseColor(b), warmth, comfort)
    }

    init {
        // ---- seeds, all glasshouse stock ----
        item("seed_greens", "Winter Greens Seeds", 14, Cat.SEED, Icon.SEED_BAG, "#DCD2BC", "#7FA86A")
        item("seed_radish", "Snow Radish Seeds", 24, Cat.SEED, Icon.SEED_BAG, "#DCD2BC", "#D8869A")
        item("seed_beet", "Frostbeet Seeds", 40, Cat.SEED, Icon.SEED_BAG, "#DCD2BC", "#9A5C86")
        item("seed_pepper", "Firepepper Seeds", 62, Cat.SEED, Icon.SEED_BAG, "#DCD2BC", "#D85240")
        item("seed_lantern", "Lantern Squash Seeds", 96, Cat.SEED, Icon.SEED_BAG, "#DCD2BC", "#E8973E")
        item("seed_moonbell", "Moonbell Seeds", 150, Cat.SEED, Icon.SEED_BAG, "#DCD2BC", "#A8C0E8")

        // ---- glasshouse produce ----
        item("greens", "Winter Greens", 28, Cat.CROP, Icon.LEAFY, "#7FA86A", "#4E7A52", warmth = 6)
        item("radish", "Snow Radish", 46, Cat.CROP, Icon.ROOT, "#F2EAE4", "#D8869A", warmth = 8)
        item("beet", "Frostbeet", 78, Cat.CROP, Icon.CARROT, "#9A5C86", "#5E8A56", warmth = 10)
        item("pepper", "Firepepper", 116, Cat.CROP, Icon.PEPPER, "#D85240", "#5E8A56", warmth = 16)
        item("squash", "Lantern Squash", 178, Cat.CROP, Icon.SQUASH, "#E8973E", "#6E9A5A", warmth = 14)
        item("moonbell", "Moonbell", 300, Cat.CROP, Icon.FLOWER, "#A8C0E8", "#E8EEF8", warmth = 8)

        // ---- fish, all pulled up through a hole in the ice ----
        item("f_smelt", "Silver Smelt", 24, Cat.FISH, Icon.FISH, "#CBDCEA", "#7C9CBC")
        item("f_perch", "Ice Perch", 50, Cat.FISH, Icon.FISH, "#8FA86E", "#4E6A4A")
        item("f_char", "Ruby Char", 92, Cat.FISH, Icon.FISH, "#D0705E", "#8A3E38")
        item("f_pike", "Winter Pike", 160, Cat.FISH, Icon.FISH, "#6E8A7C", "#3E5A50")
        item("f_moon", "Moonscale", 262, Cat.FISH, Icon.FISH, "#B8C6F0", "#6E7EC0")
        item("f_goldeye", "Goldeye", 450, Cat.FISH, Icon.FISH, "#F0C25A", "#B8802A")
        item("f_king", "The Old Kingfish", 980, Cat.FISH, Icon.FISH, "#8FD9D0", "#2F6A7A")

        // ---- what the woods give up in winter ----
        item("pinecone", "Pine Cone", 12, Cat.FORAGE, Icon.CONE, "#A87A4E", "#6E4E32")
        item("winterberry", "Winterberry", 34, Cat.FORAGE, Icon.BERRY, "#C8434E", "#4E6A4A", warmth = 5)
        item("capmush", "Snowcap Mushroom", 56, Cat.FORAGE, Icon.MUSHROOM, "#E8DED0", "#B0A08E", warmth = 7)
        item("snowdrop", "Snowdrop", 44, Cat.FORAGE, Icon.FLOWER, "#F2F6FA", "#8FB48E")
        item("kindling", "Kindling", 8, Cat.MATERIAL, Icon.FIREWOOD, "#C09A62", "#8A6438")

        // ---- materials ----
        item("log", "Log", 16, Cat.MATERIAL, Icon.LOG, "#A87A4E", "#7A5636")
        item("firewood", "Firewood", 26, Cat.MATERIAL, Icon.FIREWOOD, "#C08E58", "#8A5E36")
        item("stone", "Stone", 18, Cat.MATERIAL, Icon.STONE, "#9AA0AE", "#71778A")
        item("ice", "Clear Ice", 22, Cat.MATERIAL, Icon.ICE, "#CFE6F4", "#7CA8C8")
        item("wool", "Wool", 40, Cat.MATERIAL, Icon.WOOL, "#E4D8C4", "#B8A88E")
        item("twine", "Twine", 20, Cat.MATERIAL, Icon.WOOL, "#C8B48E", "#9A8460")
        item("oats", "Sack of Oats", 30, Cat.MATERIAL, Icon.TIN, "#DCC894", "#A88E56")
        item("cocoa", "Tin of Cocoa", 55, Cat.MATERIAL, Icon.TIN, "#7A4E3A", "#4E2E22")
        item("birdseed", "Birdseed", 26, Cat.MATERIAL, Icon.HAY, "#D8C08A", "#A08A54")
        item("hay", "Bale of Hay", 44, Cat.MATERIAL, Icon.HAY, "#D8BE72", "#A88E48")
        item("lampoil", "Lamp Oil", 48, Cat.MATERIAL, Icon.TIN, "#E8C27A", "#A8823A")

        // ---- what comes off the stove ----
        item("t_pine", "Pine Needle Tea", 40, Cat.MEAL, Icon.MUG, "#8FB48E", "#4E7A56", warmth = 24, comfort = 90f)
        item("t_berry", "Berry Tea", 62, Cat.MEAL, Icon.MUG, "#C8636E", "#8A3A44", warmth = 30, comfort = 110f)
        item("cocoa_hot", "Hot Cocoa", 90, Cat.MEAL, Icon.MUG, "#8A5A40", "#F2E2CC", warmth = 40, comfort = 180f)
        item("porridge", "Honeyed Porridge", 76, Cat.MEAL, Icon.BOWL, "#E8D6A8", "#C09A54", warmth = 34, comfort = 150f)
        item("soup", "Snowcap Soup", 132, Cat.MEAL, Icon.BOWL, "#E4DCC8", "#A89A80", warmth = 46, comfort = 210f)
        item("stew", "Cabin Stew", 205, Cat.MEAL, Icon.BOWL, "#B8703E", "#7A4426", warmth = 58, comfort = 280f)
        item("roastfish", "Roast Fish", 148, Cat.MEAL, Icon.FISH, "#D8A76E", "#9A6A3E", warmth = 44, comfort = 190f)
        item("pie", "Winterberry Pie", 240, Cat.MEAL, Icon.PIE, "#E0BE82", "#C04A54", warmth = 50, comfort = 260f)
        item("chilli", "Firepepper Chilli", 330, Cat.MEAL, Icon.BOWL, "#C24A32", "#7A2A1E", warmth = 72, comfort = 340f)

        // ---- what grows under glass ----
        crop("greens", "Winter Greens", 2, 14, 1, true, 1, 2)
        crop("radish", "Snow Radish", 3, 24, 1, false, 1, 2)
        crop("beet", "Frostbeet", 4, 40, 2, false, 1, 2)
        crop("pepper", "Firepepper", 5, 62, 2, true, 1, 3)
        crop("squash", "Lantern Squash", 6, 96, 3, false, 1, 2)
        crop("moonbell", "Moonbell", 7, 150, 4, true, 1, 2)

        // ---- the fish table ----
        fish.add(Fish("f_smelt", "Silver Smelt", 0.12f, 34f, 0f, 1440f, 0.2f))
        fish.add(Fish("f_perch", "Ice Perch", 0.26f, 26f, 380f, 1100f, 0.4f))
        fish.add(Fish("f_char", "Ruby Char", 0.40f, 18f, 380f, 800f, 0.8f))
        fish.add(Fish("f_pike", "Winter Pike", 0.56f, 12f, 900f, 1440f, 0.9f))
        fish.add(Fish("f_moon", "Moonscale", 0.68f, 7f, 1040f, 1440f, 0.6f))
        fish.add(Fish("f_goldeye", "Goldeye", 0.80f, 3f, 480f, 1020f, 1.4f))
        fish.add(Fish("f_king", "The Old Kingfish", 0.95f, 1f, 0f, 1440f, 2.4f))

        // ---- the stove ----
        cook("t_pine", "Pine Needle Tea", "t_pine", 1, arrayOf("pinecone" to 2), "Resinous, sharp, and free.", 15f)
        cook("t_berry", "Berry Tea", "t_berry", 1, arrayOf("winterberry" to 2), "Turns the water bright pink.", 15f)
        cook("cocoa_hot", "Hot Cocoa", "cocoa_hot", 1, arrayOf("cocoa" to 1), "Worth every coin Pip charges.", 20f)
        cook("porridge", "Honeyed Porridge", "porridge", 2, arrayOf("oats" to 1, "winterberry" to 1), "Sticks to your ribs all morning.", 25f)
        cook("soup", "Snowcap Soup", "soup", 2, arrayOf("capmush" to 2, "greens" to 1), "Thick, pale and very quiet.", 30f)
        cook("roastfish", "Roast Fish", "roastfish", 1, arrayOf("ANYFISH" to 1, "kindling" to 1), "Straight off the hot plate.", 25f)
        cook("stew", "Cabin Stew", "stew", 2, arrayOf("ANYFISH" to 1, "beet" to 1, "radish" to 1), "The pot goes on and stays on.", 45f)
        cook("pie", "Winterberry Pie", "pie", 2, arrayOf("oats" to 1, "winterberry" to 3), "Burnt at one edge. Always.", 45f)
        cook("chilli", "Firepepper Chilli", "chilli", 2, arrayOf("pepper" to 2, "beet" to 1), "You will feel this in your ears.", 50f)

        // ---- the workbench ----
        bench("kindle", "Bundle Kindling", "kindling", 3, arrayOf("pinecone" to 2), "Snapped small and tied with twine.", 10f)
        bench("split", "Split Firewood", "firewood", 3, arrayOf("log" to 1), "Easier indoors when it is blowing.", 15f)
        bench("twine", "Spin Twine", "twine", 2, arrayOf("wool" to 1), "Rough, but it holds.", 10f)
        bench("seedcake", "Press Birdseed", "birdseed", 2, arrayOf("oats" to 1, "pinecone" to 1), "The chickadees are not fussy.", 10f)
        bench("iceblock", "Cut Clear Ice", "ice", 2, arrayOf("kindling" to 1), "For the cold box under the floor.", 10f)
    }

    private fun crop(
        id: String, name: String, days: Int, seedCost: Int, tier: Int,
        regrow: Boolean, yMin: Int, yMax: Int
    ) {
        crops[id] = Crop(id, name, "seed_$id", id, days, seedCost, tier, regrow, yMin, yMax)
    }

    private fun cook(
        id: String, name: String, out: String, qty: Int,
        inputs: Array<Pair<String, Int>>, blurb: String, minutes: Float
    ) {
        recipes.add(Recipe(id, name, out, qty, inputs, Recipe.STOVE, blurb, minutes))
    }

    private fun bench(
        id: String, name: String, out: String, qty: Int,
        inputs: Array<Pair<String, Int>>, blurb: String, minutes: Float
    ) {
        recipes.add(Recipe(id, name, out, qty, inputs, Recipe.BENCH, blurb, minutes))
    }

    fun item(id: String): Item = items[id] ?: items["log"]!!

    fun name(id: String): String = when (id) {
        "ANYFISH" -> "Any Fish"
        else -> items[id]?.name ?: id
    }

    fun price(id: String): Int = items[id]?.price ?: 1

    fun cropForSeed(seedId: String): Crop? = crops.values.firstOrNull { it.seedId == seedId }

    fun fishById(id: String): Fish? = fish.firstOrNull { it.id == id }

    fun recipesFor(station: Int): List<Recipe> = recipes.filter { it.station == station }

    /** The icon to show for a recipe input, standing in for "any fish". */
    fun inputIcon(id: String): String = if (id == "ANYFISH") "f_smelt" else id

    /** What Pip keeps behind the counter, beyond seeds. */
    val supplyIds = listOf("oats", "cocoa", "wool", "twine", "lampoil", "birdseed", "hay")
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
            Icon.SQUASH -> squash(c, cx, cy, s, item, paint)
            Icon.LEAFY -> leafy(c, cx, cy, s, item, paint)
            Icon.SEED_BAG -> seedBag(c, cx, cy, s, item, paint)
            Icon.FISH -> fish(c, cx, cy, s, item, paint)
            Icon.LOG -> log(c, cx, cy, s, item, paint)
            Icon.MUSHROOM -> mushroom(c, cx, cy, s, item, paint)
            Icon.BERRY -> berry(c, cx, cy, s, item, paint)
            Icon.CONE -> cone(c, cx, cy, s, item, paint)
            Icon.MUG -> mug(c, cx, cy, s, item, paint)
            Icon.FLOWER -> flower(c, cx, cy, s, item, paint)
            Icon.STONE -> stone(c, cx, cy, s, item, paint)
            Icon.FIREWOOD -> firewood(c, cx, cy, s, item, paint)
            Icon.BOWL -> bowl(c, cx, cy, s, item, paint)
            Icon.PIE -> pie(c, cx, cy, s, item, paint)
            Icon.HAY -> hay(c, cx, cy, s, item, paint)
            Icon.ICE -> ice(c, cx, cy, s, item, paint)
            Icon.WOOL -> wool(c, cx, cy, s, item, paint)
            Icon.TIN -> tin(c, cx, cy, s, item, paint)
            Icon.PEPPER -> pepper(c, cx, cy, s, item, paint)
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
        paint.color = Pal.pineDeep
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
        c.drawRect(cx - s * 0.05f, cy - s * 0.72f, cx + s * 0.05f, cy - s * 0.36f, paint)
    }

    private fun squash(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = U.shade(it.a, 0.85f)
        c.drawOval(rect(cx - s * 0.8f, cy - s * 0.42f, cx + s * 0.8f, cy + s * 0.78f), paint)
        paint.color = it.a
        c.drawOval(rect(cx - s * 0.58f, cy - s * 0.46f, cx + s * 0.58f, cy + s * 0.76f), paint)
        paint.color = U.shade(it.a, 1.14f)
        c.drawOval(rect(cx - s * 0.24f, cy - s * 0.46f, cx + s * 0.24f, cy + s * 0.76f), paint)
        paint.color = it.b
        c.drawRect(cx - s * 0.09f, cy - s * 0.78f, cx + s * 0.09f, cy - s * 0.4f, paint)
    }

    private fun leafy(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        for (i in -1..1) {
            p.reset()
            p.moveTo(cx, cy + s * 0.8f)
            p.quadTo(cx + i * s * 0.95f, cy + s * 0.1f, cx + i * s * 0.5f, cy - s * 0.75f)
            p.quadTo(cx + i * s * 0.08f, cy - s * 0.1f, cx, cy + s * 0.8f)
            c.drawPath(p, paint)
        }
        paint.color = it.a
        p.reset()
        p.moveTo(cx, cy + s * 0.8f)
        p.quadTo(cx - s * 0.5f, cy - s * 0.1f, cx, cy - s * 0.92f)
        p.quadTo(cx + s * 0.5f, cy - s * 0.1f, cx, cy + s * 0.8f)
        c.drawPath(p, paint)
    }

    private fun pepper(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.a
        p.reset()
        p.moveTo(cx - s * 0.32f, cy - s * 0.42f)
        p.quadTo(cx + s * 0.62f, cy - s * 0.32f, cx + s * 0.3f, cy + s * 0.5f)
        p.quadTo(cx + s * 0.05f, cy + s * 0.95f, cx - s * 0.22f, cy + s * 0.42f)
        p.quadTo(cx - s * 0.52f, cy - s * 0.02f, cx - s * 0.32f, cy - s * 0.42f)
        p.close()
        c.drawPath(p, paint)
        paint.color = U.withAlpha(Color.WHITE, 0.32f)
        c.drawRoundRect(rect(cx - s * 0.1f, cy - s * 0.2f, cx + s * 0.02f, cy + s * 0.4f), s * 0.08f, s * 0.08f, paint)
        paint.color = it.b
        c.drawRoundRect(rect(cx - s * 0.42f, cy - s * 0.62f, cx + s * 0.06f, cy - s * 0.36f), s * 0.12f, s * 0.12f, paint)
        c.drawRect(cx - s * 0.06f, cy - s * 0.86f, cx + s * 0.06f, cy - s * 0.5f, paint)
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
        paint.color = Pal.woodDark
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
        paint.color = U.withAlpha(Color.WHITE, 0.6f)
        c.drawRoundRect(rect(cx - s * 0.5f, cy - s * 0.44f, cx + s * 0.8f, cy - s * 0.24f), s * 0.1f, s * 0.1f, paint)
    }

    private fun firewood(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        p.reset()
        p.moveTo(cx - s * 0.78f, cy + s * 0.62f)
        p.lineTo(cx + s * 0.2f, cy - s * 0.6f)
        p.lineTo(cx + s * 0.52f, cy - s * 0.34f)
        p.lineTo(cx - s * 0.46f, cy + s * 0.86f)
        p.close()
        c.drawPath(p, paint)
        paint.color = it.a
        p.reset()
        p.moveTo(cx + s * 0.78f, cy + s * 0.62f)
        p.lineTo(cx - s * 0.2f, cy - s * 0.6f)
        p.lineTo(cx - s * 0.52f, cy - s * 0.34f)
        p.lineTo(cx + s * 0.46f, cy + s * 0.86f)
        p.close()
        c.drawPath(p, paint)
        paint.color = Pal.ember
        p.reset()
        p.moveTo(cx, cy + s * 0.46f)
        p.quadTo(cx - s * 0.34f, cy + s * 0.02f, cx, cy - s * 0.44f)
        p.quadTo(cx + s * 0.34f, cy + s * 0.02f, cx, cy + s * 0.46f)
        c.drawPath(p, paint)
        paint.color = Pal.gold
        p.reset()
        p.moveTo(cx, cy + s * 0.4f)
        p.quadTo(cx - s * 0.16f, cy + s * 0.1f, cx, cy - s * 0.16f)
        p.quadTo(cx + s * 0.16f, cy + s * 0.1f, cx, cy + s * 0.4f)
        c.drawPath(p, paint)
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
        paint.color = U.withAlpha(Pal.frost, 0.9f)
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

    private fun cone(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        p.reset()
        p.moveTo(cx, cy - s * 0.92f)
        p.quadTo(cx + s * 0.52f, cy - s * 0.1f, cx, cy + s * 0.88f)
        p.quadTo(cx - s * 0.52f, cy - s * 0.1f, cx, cy - s * 0.92f)
        c.drawPath(p, paint)
        paint.color = it.a
        var row = 0
        var y = cy - s * 0.66f
        while (y < cy + s * 0.6f) {
            val w = s * (0.14f + 0.3f * (1f - kotlin.math.abs((y - cy) / (s * 0.9f))))
            val off = if (row % 2 == 0) 0f else w * 0.75f
            c.drawCircle(cx - w * 0.7f + off, y, w * 0.42f, paint)
            c.drawCircle(cx + w * 0.7f - off, y, w * 0.42f, paint)
            y += s * 0.24f
            row++
        }
    }

    private fun mug(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        // steam
        paint.color = U.withAlpha(Pal.frost, 0.55f)
        for (i in -1..1) {
            p.reset()
            p.moveTo(cx + i * s * 0.24f, cy - s * 0.32f)
            p.quadTo(cx + i * s * 0.24f - s * 0.2f, cy - s * 0.62f, cx + i * s * 0.24f, cy - s * 0.9f)
            p.quadTo(cx + i * s * 0.24f + s * 0.12f, cy - s * 0.6f, cx + i * s * 0.24f + s * 0.07f, cy - s * 0.3f)
            c.drawPath(p, paint)
        }
        paint.color = Pal.paperDeep
        c.drawRoundRect(rect(cx + s * 0.34f, cy - s * 0.16f, cx + s * 0.82f, cy + s * 0.42f), s * 0.24f, s * 0.24f, paint)
        paint.color = Pal.paper
        c.drawRoundRect(rect(cx + s * 0.46f, cy - s * 0.04f, cx + s * 0.7f, cy + s * 0.3f), s * 0.12f, s * 0.12f, paint)
        paint.color = Pal.paperDeep
        c.drawRoundRect(rect(cx - s * 0.62f, cy - s * 0.3f, cx + s * 0.42f, cy + s * 0.72f), s * 0.16f, s * 0.16f, paint)
        paint.color = it.a
        c.drawOval(rect(cx - s * 0.56f, cy - s * 0.36f, cx + s * 0.36f, cy - s * 0.06f), paint)
        paint.color = it.b
        c.drawRect(cx - s * 0.62f, cy + s * 0.24f, cx + s * 0.42f, cy + s * 0.38f, paint)
    }

    private fun bowl(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = U.withAlpha(Pal.frost, 0.5f)
        for (i in -1..1) {
            p.reset()
            p.moveTo(cx + i * s * 0.3f, cy - s * 0.24f)
            p.quadTo(cx + i * s * 0.3f - s * 0.2f, cy - s * 0.56f, cx + i * s * 0.3f, cy - s * 0.86f)
            p.quadTo(cx + i * s * 0.3f + s * 0.12f, cy - s * 0.54f, cx + i * s * 0.3f + s * 0.06f, cy - s * 0.22f)
            c.drawPath(p, paint)
        }
        paint.color = it.a
        c.drawOval(rect(cx - s * 0.66f, cy - s * 0.3f, cx + s * 0.66f, cy + s * 0.06f), paint)
        paint.color = U.shade(it.a, 1.16f)
        c.drawOval(rect(cx - s * 0.42f, cy - s * 0.24f, cx + s * 0.2f, cy - s * 0.04f), paint)
        paint.color = Pal.paperDeep
        p.reset()
        p.moveTo(cx - s * 0.78f, cy - s * 0.16f)
        p.quadTo(cx, cy + s * 0.92f, cx + s * 0.78f, cy - s * 0.16f)
        p.close()
        c.drawPath(p, paint)
        paint.color = Pal.paper
        c.drawRoundRect(rect(cx - s * 0.86f, cy - s * 0.26f, cx + s * 0.86f, cy - s * 0.08f), s * 0.09f, s * 0.09f, paint)
    }

    private fun pie(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = U.shade(it.a, 0.84f)
        c.drawRoundRect(rect(cx - s * 0.86f, cy + s * 0.1f, cx + s * 0.86f, cy + s * 0.62f), s * 0.12f, s * 0.12f, paint)
        paint.color = it.b
        c.drawOval(rect(cx - s * 0.74f, cy - s * 0.5f, cx + s * 0.74f, cy + s * 0.24f), paint)
        paint.color = it.a
        for (i in -2..2) {
            c.drawRoundRect(
                rect(cx + i * s * 0.28f - s * 0.07f, cy - s * 0.48f, cx + i * s * 0.28f + s * 0.07f, cy + s * 0.22f),
                s * 0.05f, s * 0.05f, paint
            )
        }
        for (i in -1..1) {
            c.drawRoundRect(
                rect(cx - s * 0.7f, cy + i * s * 0.24f - s * 0.06f, cx + s * 0.7f, cy + i * s * 0.24f + s * 0.06f),
                s * 0.05f, s * 0.05f, paint
            )
        }
        paint.color = U.shade(it.a, 1.1f)
        c.drawOval(rect(cx - s * 0.9f, cy - s * 0.06f, cx + s * 0.9f, cy + s * 0.26f), paint)
    }

    private fun hay(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.a
        c.drawRoundRect(rect(cx - s * 0.78f, cy - s * 0.46f, cx + s * 0.78f, cy + s * 0.66f), s * 0.2f, s * 0.2f, paint)
        paint.color = U.shade(it.a, 0.86f)
        var x = cx - s * 0.6f
        while (x < cx + s * 0.6f) {
            c.drawRect(x, cy - s * 0.4f, x + s * 0.06f, cy + s * 0.6f, paint)
            x += s * 0.24f
        }
        paint.color = it.b
        c.drawRect(cx - s * 0.82f, cy - s * 0.2f, cx + s * 0.82f, cy - s * 0.06f, paint)
        c.drawRect(cx - s * 0.82f, cy + s * 0.26f, cx + s * 0.82f, cy + s * 0.4f, paint)
    }

    private fun ice(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        p.reset()
        p.moveTo(cx, cy - s * 0.9f)
        p.lineTo(cx + s * 0.72f, cy - s * 0.3f)
        p.lineTo(cx + s * 0.46f, cy + s * 0.8f)
        p.lineTo(cx - s * 0.46f, cy + s * 0.8f)
        p.lineTo(cx - s * 0.72f, cy - s * 0.3f)
        p.close()
        c.drawPath(p, paint)
        paint.color = it.a
        p.reset()
        p.moveTo(cx, cy - s * 0.72f)
        p.lineTo(cx + s * 0.5f, cy - s * 0.24f)
        p.lineTo(cx + s * 0.1f, cy + s * 0.62f)
        p.lineTo(cx - s * 0.26f, cy + s * 0.1f)
        p.close()
        c.drawPath(p, paint)
        paint.color = U.withAlpha(Color.WHITE, 0.7f)
        c.drawRoundRect(rect(cx - s * 0.34f, cy - s * 0.42f, cx - s * 0.2f, cy + s * 0.2f), s * 0.06f, s * 0.06f, paint)
    }

    private fun wool(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = it.b
        c.drawCircle(cx, cy + s * 0.1f, s * 0.76f, paint)
        paint.color = it.a
        c.drawCircle(cx, cy + s * 0.1f, s * 0.66f, paint)
        paint.color = U.shade(it.b, 0.9f)
        for (i in -2..2) {
            p.reset()
            p.moveTo(cx - s * 0.66f, cy + s * 0.1f + i * s * 0.26f)
            p.quadTo(cx, cy + s * 0.1f + i * s * 0.26f - s * 0.34f, cx + s * 0.66f, cy + s * 0.1f + i * s * 0.2f)
            p.quadTo(cx, cy + s * 0.1f + i * s * 0.26f - s * 0.2f, cx - s * 0.66f, cy + s * 0.1f + i * s * 0.26f)
            c.drawPath(p, paint)
        }
    }

    private fun tin(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = U.shade(it.a, 0.82f)
        c.drawRoundRect(rect(cx - s * 0.54f, cy - s * 0.66f, cx + s * 0.54f, cy + s * 0.78f), s * 0.12f, s * 0.12f, paint)
        paint.color = it.a
        c.drawRoundRect(rect(cx - s * 0.46f, cy - s * 0.6f, cx + s * 0.4f, cy + s * 0.72f), s * 0.1f, s * 0.1f, paint)
        paint.color = it.b
        c.drawRoundRect(rect(cx - s * 0.54f, cy - s * 0.2f, cx + s * 0.54f, cy + s * 0.24f), s * 0.06f, s * 0.06f, paint)
        paint.color = Pal.stone
        c.drawOval(rect(cx - s * 0.58f, cy - s * 0.82f, cx + s * 0.58f, cy - s * 0.5f), paint)
        paint.color = U.withAlpha(Color.WHITE, 0.5f)
        c.drawOval(rect(cx - s * 0.4f, cy - s * 0.76f, cx + s * 0.1f, cy - s * 0.6f), paint)
    }

    private fun flower(c: Canvas, cx: Float, cy: Float, s: Float, it: Item, paint: Paint) {
        paint.color = Pal.pineDeep
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
