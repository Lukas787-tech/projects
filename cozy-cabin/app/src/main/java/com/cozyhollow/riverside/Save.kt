package com.cozyhollow.riverside

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class CabinTier(
    val level: Int,
    val name: String,
    /** Beds under glass. */
    val plots: Int,
    /** How long the hearth holds its heat, as a multiplier on burn time. */
    val insulation: Float,
    val invSlots: Int,
    val coins: Int,
    val log: Int,
    val stone: Int,
    val blurb: String
)

object Tiers {
    val list = listOf(
        CabinTier(1, "Draughty Cabin", 4, 1.0f, 22, 0, 0, 0, "One room, one stove, and a gap under the door."),
        CabinTier(2, "Snug Cabin", 8, 1.35f, 32, 900, 45, 14, "Sealed, banked and lined. The draught is gone."),
        CabinTier(3, "Warm Lodge", 12, 1.75f, 44, 3400, 120, 50, "A second window, a proper flue, and shelves."),
        CabinTier(4, "Winter Hall", 16, 2.3f, 62, 9500, 280, 140, "Beams, a long table, and heat to spare.")
    )

    fun get(level: Int): CabinTier = list[U.clampI(level - 1, 0, list.size - 1)]
    fun next(level: Int): CabinTier? = if (level >= list.size) null else list[level]
    val max: Int get() = list.size
}

object ToolUp {
    fun rodCost(level: Int) = if (level == 2) 720 else 2700
    fun kettleCost(level: Int) = if (level == 2) 480 else 1900
    fun axeCost(level: Int) = if (level == 2) 540 else 2200
    fun coatCost(level: Int) = if (level == 2) 620 else 2400
    fun lanternCost(level: Int) = if (level == 2) 400 else 1700

    fun rodName(level: Int) = when (level) {
        1 -> "Bent Jig"; 2 -> "Cedar Jig"; else -> "Deepwater Jig"
    }
    fun kettleName(level: Int) = when (level) {
        1 -> "Tin Kettle"; 2 -> "Copper Kettle"; else -> "Big Black Pot"
    }
    fun axeName(level: Int) = when (level) {
        1 -> "Old Axe"; 2 -> "Steel Axe"; else -> "Splitting Maul"
    }
    fun coatName(level: Int) = when (level) {
        1 -> "Patched Coat"; 2 -> "Wool Coat"; else -> "Lined Parka"
    }
    fun lanternName(level: Int) = when (level) {
        1 -> "Stub Candle"; 2 -> "Storm Lantern"; else -> "Brass Beacon"
    }

    /** How fast the cold gets through what you are wearing. Lower is warmer. */
    fun coatShelter(level: Int) = when (level) {
        1 -> 1.0f; 2 -> 0.68f; else -> 0.44f
    }

    /** How far your own light reaches after dark, in metres. */
    fun lampRadius(level: Int) = when (level) {
        1 -> 3.4f; 2 -> 5.2f; else -> 7.4f
    }

    /** Extra portions the good pot gets out of the same ingredients. */
    fun kettleBonus(level: Int) = when (level) {
        1 -> 0; 2 -> 1; else -> 2
    }

    /** Logs a single swing brings down. */
    fun axeLogs(level: Int) = when (level) {
        1 -> 3; 2 -> 4; else -> 6
    }
}

class Plot {
    var tilled = false
    var cropId: String? = null
    var growth = 0f
    var watered = false
    /** Set when a regrowing crop has already given its first harvest. */
    var regrown = false

    val ready: Boolean
        get() {
            val c = cropId ?: return false
            val crop = Catalog.crops[c] ?: return false
            return growth >= crop.days
        }

    fun clear() {
        cropId = null; growth = 0f; watered = false; regrown = false
    }

    fun toJson(): JSONObject = JSONObject()
        .put("t", tilled).put("c", cropId ?: "")
        .put("g", growth.toDouble()).put("w", watered).put("r", regrown)

    fun fromJson(o: JSONObject) {
        tilled = o.optBoolean("t", false)
        cropId = o.optString("c", "").ifEmpty { null }
        growth = o.optDouble("g", 0.0).toFloat()
        watered = o.optBoolean("w", false)
        regrown = o.optBoolean("r", false)
    }
}

class GameState {
    var coins = 200
    var day = 1
    var timeMin = 8f * 60f
    var cabinLevel = 1
    var rodLevel = 1
    var kettleLevel = 1
    var axeLevel = 1
    var coatLevel = 1
    var lanternLevel = 1
    var weather = Weather.SNOW
    var playerX = World.SPAWN_X
    var playerZ = World.SPAWN_Z
    var introDone = false

    /** True when the save was made standing in the cabin. */
    var indoors = false

    // ---- warmth ----
    /** 0..100. Never fatal — it only ever makes the walk home slower. */
    var warmth = 100f
    /** Minutes of lingering glow left from the last hot thing you drank. */
    var comfort = 0f

    // ---- the hearth ----
    /** Hours of burn left in the stove. The cabin is warm while it is above 0. */
    var hearthFuel = 4f
    var hearthLit = true

    // ---- the yard fire ----
    var firepitFuel = 0f

    // ---- who comes to visit ----
    /** Day the bird feeder was last filled, or -1. */
    var birdFedDay = -1
    var deerFedDay = -1
    var catAffection = 0
    var visitStreak = 0

    val inv = LinkedHashMap<String, Int>()
    val plots = ArrayList<Plot>()
    /** Day on which each felled tree grows back. 0 means standing. */
    var treeRegrow = IntArray(World.TREE_COUNT)
    /** Day on which each forage spot was picked. */
    var foragePicked = IntArray(World.FORAGE_COUNT) { -1 }

    val seenFish = HashSet<String>()
    val seenCrops = HashSet<String>()
    val seenMeals = HashSet<String>()
    val seenAnimals = HashSet<String>()
    /** Decorations owned, and which one is showing in each slot. */
    val decorOwned = HashSet<String>()
    val decorPlaced = HashMap<Int, String>()

    var totalEarned = 0
    var totalFish = 0
    var totalHarvest = 0
    var totalLogs = 0
    var totalCooked = 0
    var nightsWarm = 0
    var biggestSale = 0
    /** Little moments the journal keeps count of: sitting, soaking, petting. */
    var cosyMoments = 0

    val tier: CabinTier get() = Tiers.get(cabinLevel)
    val invSlots: Int get() = tier.invSlots

    init {
        for (i in 0 until World.MAX_PLOTS) plots.add(Plot())
        inv["seed_greens"] = 4
        inv["firewood"] = 6
        inv["kindling"] = 4
        inv["cocoa"] = 1
    }

    // ---- inventory ----
    fun count(id: String): Int = inv[id] ?: 0

    /** Counts anything in a category, used by recipes that take "any fish". */
    fun countCat(cat: Int): Int {
        var n = 0
        for ((k, v) in inv) if (Catalog.items[k]?.cat == cat) n += v
        return n
    }

    /** The cheapest thing of a category, so a stew uses the smelt not the king. */
    fun cheapestOf(cat: Int): String? {
        var best: String? = null
        var bestPrice = Int.MAX_VALUE
        for ((k, v) in inv) {
            if (v <= 0) continue
            val item = Catalog.items[k] ?: continue
            if (item.cat != cat) continue
            if (item.price < bestPrice) { bestPrice = item.price; best = k }
        }
        return best
    }

    fun usedSlots(): Int = inv.count { it.value > 0 }

    fun hasRoomFor(id: String): Boolean = count(id) > 0 || usedSlots() < invSlots

    fun add(id: String, n: Int): Boolean {
        if (n <= 0) return true
        if (!hasRoomFor(id)) return false
        inv[id] = count(id) + n
        return true
    }

    fun take(id: String, n: Int): Boolean {
        val have = count(id)
        if (have < n) return false
        if (have == n) inv.remove(id) else inv[id] = have - n
        return true
    }

    fun spend(amount: Int): Boolean {
        if (coins < amount) return false
        coins -= amount
        return true
    }

    fun earn(amount: Int) {
        coins += amount
        totalEarned += amount
    }

    // ---- recipes ----
    /** Can this recipe be made right now? "ANYFISH" matches the whole basket. */
    fun canMake(r: Recipe): Boolean {
        for ((id, n) in r.inputs) {
            if (id == "ANYFISH") {
                if (countCat(Cat.FISH) < n) return false
            } else if (count(id) < n) return false
        }
        return true
    }

    /** Spends a recipe's inputs. Returns false and spends nothing if short. */
    fun consume(r: Recipe): Boolean {
        if (!canMake(r)) return false
        for ((id, n) in r.inputs) {
            if (id == "ANYFISH") {
                var left = n
                while (left > 0) {
                    val pick = cheapestOf(Cat.FISH) ?: return false
                    take(pick, 1)
                    left--
                }
            } else {
                take(id, n)
            }
        }
        return true
    }

    // ---- persistence ----
    private val spawnTmp = FloatArray(2)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 2)
        o.put("coins", coins).put("day", day).put("time", timeMin.toDouble())
        o.put("cabin", cabinLevel)
        o.put("rod", rodLevel).put("kettle", kettleLevel).put("axe", axeLevel)
        o.put("coat", coatLevel).put("lamp", lanternLevel)
        o.put("weather", weather).put("px", playerX.toDouble())
        o.put("pz", playerZ.toDouble()).put("intro", introDone).put("in", indoors)
        o.put("warm", warmth.toDouble()).put("comfort", comfort.toDouble())
        o.put("fuel", hearthFuel.toDouble()).put("lit", hearthLit)
        o.put("pitfuel", firepitFuel.toDouble())
        o.put("bird", birdFedDay).put("deer", deerFedDay)
        o.put("cat", catAffection).put("streak", visitStreak)
        o.put("earned", totalEarned).put("fishN", totalFish)
        o.put("harvestN", totalHarvest).put("logN", totalLogs)
        o.put("cookN", totalCooked).put("warmNights", nightsWarm)
        o.put("bigSale", biggestSale).put("cosy", cosyMoments)

        val invO = JSONObject()
        for ((k, v) in inv) if (v > 0) invO.put(k, v)
        o.put("inv", invO)

        val pa = JSONArray()
        for (p in plots) pa.put(p.toJson())
        o.put("plots", pa)

        o.put("trees", JSONArray().also { a -> treeRegrow.forEach { a.put(it) } })
        o.put("forage", JSONArray().also { a -> foragePicked.forEach { a.put(it) } })
        o.put("seenFish", JSONArray().also { a -> seenFish.forEach { s -> a.put(s) } })
        o.put("seenCrops", JSONArray().also { a -> seenCrops.forEach { s -> a.put(s) } })
        o.put("seenMeals", JSONArray().also { a -> seenMeals.forEach { s -> a.put(s) } })
        o.put("seenAnimals", JSONArray().also { a -> seenAnimals.forEach { s -> a.put(s) } })
        o.put("decor", JSONArray().also { a -> decorOwned.forEach { s -> a.put(s) } })
        val placed = JSONObject()
        for ((k, v) in decorPlaced) placed.put(k.toString(), v)
        o.put("placed", placed)
        return o
    }

    fun fromJson(o: JSONObject) {
        coins = o.optInt("coins", 200)
        day = o.optInt("day", 1).coerceAtLeast(1)
        timeMin = o.optDouble("time", 480.0).toFloat()
        cabinLevel = U.clampI(o.optInt("cabin", 1), 1, Tiers.max)
        rodLevel = U.clampI(o.optInt("rod", 1), 1, 3)
        kettleLevel = U.clampI(o.optInt("kettle", 1), 1, 3)
        axeLevel = U.clampI(o.optInt("axe", 1), 1, 3)
        coatLevel = U.clampI(o.optInt("coat", 1), 1, 3)
        lanternLevel = U.clampI(o.optInt("lamp", 1), 1, 3)
        weather = U.clampI(o.optInt("weather", Weather.SNOW), 0, 3)
        indoors = o.optBoolean("in", false)
        warmth = o.optDouble("warm", 100.0).toFloat().coerceIn(0f, 100f)
        comfort = o.optDouble("comfort", 0.0).toFloat().coerceAtLeast(0f)
        hearthFuel = o.optDouble("fuel", 4.0).toFloat().coerceIn(0f, 48f)
        hearthLit = o.optBoolean("lit", hearthFuel > 0f)
        firepitFuel = o.optDouble("pitfuel", 0.0).toFloat().coerceIn(0f, 24f)
        birdFedDay = o.optInt("bird", -1)
        deerFedDay = o.optInt("deer", -1)
        catAffection = o.optInt("cat", 0)
        visitStreak = o.optInt("streak", 0)

        // a position from outside the bowl would strand you in empty sky, and
        // one up a cliff would strand you just as surely, so anything the save
        // offers is pulled back onto ground you can stand on
        Terrain.clampToValley(
            o.optDouble("px", World.SPAWN_X.toDouble()).toFloat(),
            o.optDouble("pz", World.SPAWN_Z.toDouble()).toFloat(),
            spawnTmp
        )
        if (indoors) {
            playerX = Interior.DOOR_X
            playerZ = Interior.DOOR_Z - 0.6f
        } else if (Terrain.impassable(spawnTmp[0], spawnTmp[1])) {
            playerX = World.SPAWN_X
            playerZ = World.SPAWN_Z
        } else {
            playerX = spawnTmp[0]
            playerZ = spawnTmp[1]
        }
        introDone = o.optBoolean("intro", false)
        totalEarned = o.optInt("earned", 0)
        totalFish = o.optInt("fishN", 0)
        totalHarvest = o.optInt("harvestN", 0)
        totalLogs = o.optInt("logN", 0)
        totalCooked = o.optInt("cookN", 0)
        nightsWarm = o.optInt("warmNights", 0)
        biggestSale = o.optInt("bigSale", 0)
        cosyMoments = o.optInt("cosy", 0)

        inv.clear()
        val invO = o.optJSONObject("inv")
        if (invO != null) {
            val keys = invO.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val n = invO.optInt(k, 0)
                if (n > 0 && Catalog.items.containsKey(k)) inv[k] = n
            }
        }

        val pa = o.optJSONArray("plots")
        for (i in plots.indices) {
            val po = pa?.optJSONObject(i)
            if (po != null) plots[i].fromJson(po) else {
                plots[i].tilled = false; plots[i].clear()
            }
        }

        val ta = o.optJSONArray("trees")
        treeRegrow = IntArray(World.TREE_COUNT) { ta?.optInt(it, 0) ?: 0 }
        val fa = o.optJSONArray("forage")
        foragePicked = IntArray(World.FORAGE_COUNT) { fa?.optInt(it, -1) ?: -1 }

        readSet(o, "seenFish", seenFish)
        readSet(o, "seenCrops", seenCrops)
        readSet(o, "seenMeals", seenMeals)
        readSet(o, "seenAnimals", seenAnimals)
        readSet(o, "decor", decorOwned)

        decorPlaced.clear()
        o.optJSONObject("placed")?.let { po ->
            val keys = po.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val slot = k.toIntOrNull() ?: continue
                val id = po.optString(k, "")
                if (id.isNotEmpty() && Decorations.byId(id) != null) decorPlaced[slot] = id
            }
        }
    }

    private fun readSet(o: JSONObject, key: String, into: HashSet<String>) {
        into.clear()
        o.optJSONArray(key)?.let { a -> for (i in 0 until a.length()) into.add(a.optString(i)) }
    }
}

class Settings {
    var music = 0.6f
    var sfx = 0.8f
    /** 0 = quiet (low), 1 = balanced, 2 = deep winter. */
    var quality = 1
    var showFps = false
    var haptics = true
    var southpaw = false
    /** Turns the warmth meter off entirely for people who want zero pressure. */
    var gentle = false

    val particleScale: Float get() = when (quality) { 0 -> 0.4f; 1 -> 0.8f; else -> 1.25f }
    val extraLayers: Boolean get() = quality >= 1
    val softShadows: Boolean get() = quality >= 2
}

object SaveManager {
    private const val PREFS = "riverside_save"
    private const val KEY_GAME = "game"
    private const val KEY_SETTINGS = "settings"

    fun hasSave(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_GAME)

    fun save(ctx: Context, s: GameState) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_GAME, s.toJson().toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun load(ctx: Context): GameState? {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GAME, null) ?: return null
            val st = GameState()
            st.fromJson(JSONObject(raw))
            st
        } catch (_: Exception) {
            null
        }
    }

    fun wipe(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_GAME).apply()
    }

    fun saveSettings(ctx: Context, s: Settings) {
        try {
            val o = JSONObject()
                .put("music", s.music.toDouble()).put("sfx", s.sfx.toDouble())
                .put("quality", s.quality).put("fps", s.showFps)
                .put("haptics", s.haptics).put("southpaw", s.southpaw)
                .put("gentle", s.gentle)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SETTINGS, o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun loadSettings(ctx: Context): Settings {
        val s = Settings()
        try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SETTINGS, null) ?: return s
            val o = JSONObject(raw)
            s.music = o.optDouble("music", 0.6).toFloat()
            s.sfx = o.optDouble("sfx", 0.8).toFloat()
            s.quality = U.clampI(o.optInt("quality", 1), 0, 2)
            s.showFps = o.optBoolean("fps", false)
            s.haptics = o.optBoolean("haptics", true)
            s.southpaw = o.optBoolean("southpaw", false)
            s.gentle = o.optBoolean("gentle", false)
        } catch (_: Exception) {
        }
        return s
    }
}
