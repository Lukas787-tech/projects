package com.cozyhollow.riverside

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class CabinTier(
    val level: Int,
    val name: String,
    val plots: Int,
    val maxEnergy: Int,
    val invSlots: Int,
    val coins: Int,
    val wood: Int,
    val stone: Int,
    val blurb: String
)

object Tiers {
    val list = listOf(
        CabinTier(1, "Little Cabin", 4, 100, 20, 0, 0, 0, "A roof, a fire, and the sound of the river."),
        CabinTier(2, "Warm Cabin", 8, 125, 30, 900, 40, 12, "A porch for evenings and room for more crates."),
        CabinTier(3, "Riverside Cottage", 12, 155, 42, 3200, 110, 45, "Two floors, a proper chimney, window boxes."),
        CabinTier(4, "Hollow Manor", 16, 200, 60, 9000, 260, 130, "The cosiest house in the whole valley.")
    )

    fun get(level: Int): CabinTier = list[U.clampI(level - 1, 0, list.size - 1)]
    fun next(level: Int): CabinTier? = if (level >= list.size) null else list[level]
    val max: Int get() = list.size
}

object ToolUp {
    /** Cost to reach the given level (2 or 3). */
    fun rodCost(level: Int) = if (level == 2) 700 else 2600
    fun canCost(level: Int) = if (level == 2) 450 else 1800
    fun axeCost(level: Int) = if (level == 2) 520 else 2100

    fun rodName(level: Int) = when (level) {
        1 -> "Bent Rod"; 2 -> "Cedar Rod"; else -> "Rivermaster Rod"
    }
    fun canName(level: Int) = when (level) {
        1 -> "Tin Can"; 2 -> "Copper Can"; else -> "Rainmaker Can"
    }
    fun axeName(level: Int) = when (level) {
        1 -> "Old Axe"; 2 -> "Steel Axe"; else -> "Woodsong Axe"
    }

    /** How many plots a single watering covers. */
    fun canSpread(level: Int) = when (level) {
        1 -> 1; 2 -> 2; else -> 4
    }

    /** Chops needed to fell a tree. */
    fun axeChops(level: Int) = when (level) {
        1 -> 3; 2 -> 2; else -> 1
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
    var coins = 180
    var day = 1
    var timeMin = 6f * 60f
    var energy = 100f
    var cabinLevel = 1
    var rodLevel = 1
    var canLevel = 1
    var axeLevel = 1
    var weather = Weather.CLEAR
    var playerX = World.SPAWN_X
    var playerZ = World.SPAWN_Z
    var introDone = false

    val inv = LinkedHashMap<String, Int>()
    val plots = ArrayList<Plot>()
    /** Day on which each tree grows back. 0 means standing. */
    var treeRegrow = IntArray(World.TREE_COUNT)
    /** Day on which each forage spot was picked. */
    var foragePicked = IntArray(World.FORAGE_COUNT) { -1 }

    val seenFish = HashSet<String>()
    val seenCrops = HashSet<String>()
    var totalEarned = 0
    var totalFish = 0
    var totalHarvest = 0
    var totalChopped = 0
    var biggestSale = 0

    val tier: CabinTier get() = Tiers.get(cabinLevel)
    val maxEnergy: Float get() = tier.maxEnergy.toFloat()
    val invSlots: Int get() = tier.invSlots

    init {
        for (i in 0 until World.MAX_PLOTS) plots.add(Plot())
        inv["seed_turnip"] = 4
    }

    // ---- inventory ----
    fun count(id: String): Int = inv[id] ?: 0

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

    fun useEnergy(amount: Float): Boolean {
        if (energy < amount) return false
        energy -= amount
        return true
    }

    // ---- persistence ----
    private val spawnTmp = FloatArray(2)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 1)
        o.put("coins", coins).put("day", day).put("time", timeMin.toDouble())
        o.put("energy", energy.toDouble()).put("cabin", cabinLevel)
        o.put("rod", rodLevel).put("can", canLevel).put("axe", axeLevel)
        o.put("weather", weather).put("px", playerX.toDouble())
        o.put("pz", playerZ.toDouble()).put("intro", introDone)
        o.put("earned", totalEarned).put("fishN", totalFish)
        o.put("harvestN", totalHarvest).put("chopN", totalChopped).put("bigSale", biggestSale)

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
        return o
    }

    fun fromJson(o: JSONObject) {
        coins = o.optInt("coins", 180)
        day = o.optInt("day", 1).coerceAtLeast(1)
        timeMin = o.optDouble("time", 360.0).toFloat()
        energy = o.optDouble("energy", 100.0).toFloat()
        cabinLevel = U.clampI(o.optInt("cabin", 1), 1, Tiers.max)
        rodLevel = U.clampI(o.optInt("rod", 1), 1, 3)
        canLevel = U.clampI(o.optInt("can", 1), 1, 3)
        axeLevel = U.clampI(o.optInt("axe", 1), 1, 3)
        weather = U.clampI(o.optInt("weather", 0), 0, 2)
        // a position from outside the bowl would strand you in empty sky, and
        // one in the river or up a cliff would strand you just as surely, so
        // anything the save offers is pulled back onto ground you can stand on
        Terrain.clampToValley(
            o.optDouble("px", World.SPAWN_X.toDouble()).toFloat(),
            o.optDouble("pz", World.SPAWN_Z.toDouble()).toFloat(),
            spawnTmp
        )
        if (Terrain.impassable(spawnTmp[0], spawnTmp[1])) {
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
        totalChopped = o.optInt("chopN", 0)
        biggestSale = o.optInt("bigSale", 0)

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

        seenFish.clear()
        o.optJSONArray("seenFish")?.let { a -> for (i in 0 until a.length()) seenFish.add(a.optString(i)) }
        seenCrops.clear()
        o.optJSONArray("seenCrops")?.let { a -> for (i in 0 until a.length()) seenCrops.add(a.optString(i)) }

        energy = energy.coerceIn(0f, maxEnergy)
    }
}

class Settings {
    var music = 0.6f
    var sfx = 0.8f
    /** 0 = cosy (low), 1 = balanced, 2 = lush. */
    var quality = 1
    var showFps = false
    var haptics = true
    var southpaw = false

    val particleScale: Float get() = when (quality) { 0 -> 0.35f; 1 -> 0.7f; else -> 1f }
    val extraLayers: Boolean get() = quality >= 1
    val softShadows: Boolean get() = quality >= 2
    val reflections: Boolean get() = quality >= 1
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
        } catch (_: Exception) {
        }
        return s
    }
}
