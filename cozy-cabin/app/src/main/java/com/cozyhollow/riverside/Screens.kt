package com.cozyhollow.riverside

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private object T {
    const val NONE = 0
    const val CONTINUE = 1
    const val NEW = 2
    const val SETTINGS = 3
    const val CREDITS = 4
    const val QUIT = 5
    const val BACK = 6
    const val RESUME = 7
    const val BAG = 8
    const val JOURNAL = 9
    const val TO_TITLE = 10
    const val DECOR = 11
    const val TAB0 = 20
    const val TAB1 = 21
    const val TAB2 = 22
    const val TAB3 = 23
    const val TAB4 = 24
    const val PAGE_PREV = 30
    const val PAGE_NEXT = 31
    const val SELL_ALL = 32
    const val UPGRADE = 33
    const val EAT = 34
    const val USE_SEED = 35
    const val SLEEP_OK = 36
    const val BEGIN = 37
    const val RESET = 38
    const val RESET_YES = 39
    const val RESET_NO = 40
    const val ITEM = 100      // + index
    const val BUY1 = 300      // + index
    const val BUY5 = 400      // + index
    const val TOOL = 500      // + index
    const val SUPPLY = 520    // + index
    const val MAKE = 560      // + index
    const val DEC = 620       // + index
    const val SLIDER_MUSIC = 700
    const val SLIDER_SFX = 701
    const val QUALITY = 710   // + level
    const val TOG_FPS = 720
    const val TOG_HAPTIC = 721
    const val TOG_SOUTH = 722
    const val TOG_GENTLE = 723
}

/** Every menu in the game. Layout is recomputed each frame so it adapts to any screen. */
class Screens(private val g: Game) {

    private val btns = ArrayList<Btn>()
    private val pool = HashMap<Int, Btn>()
    private val lines = ArrayList<String>()

    private var shopTab = 0
    private var jTab = 0
    private var bagPage = 0
    private var bagSel: String? = null
    private var confirmReset = false
    private var dragging = 0
    private var pressedTag = 0

    private val bagIds = ArrayList<String>()
    private val sellIds = ArrayList<String>()
    private val recipeList = ArrayList<Recipe>()

    private fun b(tag: Int, label: String = "", style: Int = 0): Btn {
        val btn = pool.getOrPut(tag) { Btn(tag) }
        btn.label = label
        btn.style = style
        btn.enabled = true
        btn.visible = true
        btn.iconItem = null
        btn.sub = null
        btn.accent = 0
        btns.add(btn)
        return btn
    }

    fun blocksInput(): Boolean = g.mode != Mode.PLAY

    // ================================================================ input

    fun onDown(x: Float, y: Float): Boolean {
        if (g.mode == Mode.PLAY) return false
        layout()
        for (btn in btns) {
            if (!btn.visible || !btn.enabled) continue
            if (btn.hit(x, y)) {
                btn.press = 1f
                pressedTag = btn.tag
                if (btn.tag == T.SLIDER_MUSIC || btn.tag == T.SLIDER_SFX) {
                    dragging = btn.tag
                    applySlider(btn, x)
                }
                return true
            }
        }
        return true
    }

    fun onMove(x: Float, y: Float) {
        if (dragging != 0) {
            val btn = pool[dragging] ?: return
            applySlider(btn, x)
        }
    }

    fun onUp(x: Float, y: Float): Boolean {
        if (g.mode == Mode.PLAY) return false
        if (dragging != 0) {
            dragging = 0
            pressedTag = 0
            for (btn in btns) btn.press = 0f
            g.applySettings()
            return true
        }
        var handled = false
        for (btn in btns) {
            if (btn.press > 0f && btn.hit(x, y) && btn.tag == pressedTag) {
                activate(btn.tag)
                handled = true
            }
            btn.press = 0f
        }
        pressedTag = 0
        return handled || true
    }

    fun onCancel() {
        dragging = 0
        pressedTag = 0
        for (btn in btns) btn.press = 0f
    }

    private fun applySlider(btn: Btn, x: Float) {
        val v = U.clamp01((x - btn.x) / btn.w)
        if (btn.tag == T.SLIDER_MUSIC) g.settings.music = v else g.settings.sfx = v
        g.audio.musicVol = g.settings.music
        g.audio.sfxVol = g.settings.sfx
    }

    private fun activate(tag: Int) {
        g.audio.play(if (tag == T.BACK || tag == T.TO_TITLE) Sfx.BACK else Sfx.TAP)
        when (tag) {
            T.CONTINUE -> g.continueGame()
            T.NEW -> g.newGame()
            T.SETTINGS -> { confirmReset = false; g.setMode(Mode.SETTINGS) }
            T.CREDITS -> g.setMode(Mode.CREDITS)
            T.QUIT -> g.quitApp()
            T.BACK -> back()
            T.RESUME -> g.setMode(Mode.PLAY)
            T.BAG -> g.setMode(Mode.BAG)
            T.JOURNAL -> g.setMode(Mode.JOURNAL)
            T.DECOR -> g.setMode(Mode.DECOR)
            T.TO_TITLE -> g.quitToTitle()
            T.BEGIN -> { g.st.introDone = true; g.saveNow(); g.setMode(Mode.PLAY) }
            T.SLEEP_OK -> g.wakeUp()
            T.SELL_ALL -> g.sellAll(-1)
            T.UPGRADE -> g.upgradeCabin()
            T.PAGE_PREV -> bagPage = max(0, bagPage - 1)
            T.PAGE_NEXT -> bagPage++
            T.TAB0 -> { if (g.mode == Mode.SHOP) shopTab = 0 else jTab = 0; bagPage = 0 }
            T.TAB1 -> { if (g.mode == Mode.SHOP) shopTab = 1 else jTab = 1; bagPage = 0 }
            T.TAB2 -> { if (g.mode == Mode.SHOP) shopTab = 2 else jTab = 2; bagPage = 0 }
            T.TAB3 -> { if (g.mode == Mode.SHOP) shopTab = 3 else jTab = 3; bagPage = 0 }
            T.TAB4 -> { shopTab = 4; bagPage = 0 }
            T.EAT -> bagSel?.let { g.eat(it); if (g.st.count(it) <= 0) bagSel = null }
            T.USE_SEED -> bagSel?.let { g.selectedSeed = it; g.toast("Seed selected", it, Pal.ink) }
            T.RESET -> confirmReset = true
            T.RESET_NO -> confirmReset = false
            T.RESET_YES -> {
                confirmReset = false
                SaveManager.wipe(g.gameContext())
                g.toast("Save erased", null, Pal.berry)
                g.quitToTitle()
            }
            T.TOG_FPS -> { g.settings.showFps = !g.settings.showFps; g.applySettings() }
            T.TOG_HAPTIC -> { g.settings.haptics = !g.settings.haptics; g.applySettings() }
            T.TOG_SOUTH -> { g.settings.southpaw = !g.settings.southpaw; g.applySettings() }
            T.TOG_GENTLE -> {
                g.settings.gentle = !g.settings.gentle
                g.applySettings()
                g.toast(
                    if (g.settings.gentle) "Warmth turned off. Just potter."
                    else "Warmth back on.", null, Pal.frost
                )
            }
            else -> when {
                tag >= T.DEC && tag < T.DEC + 40 -> {
                    val list = Decorations.list
                    val i = tag - T.DEC
                    if (i in list.indices) g.buyDecor(list[i])
                }
                tag >= T.MAKE && tag < T.MAKE + 40 -> {
                    val i = tag - T.MAKE
                    if (i in recipeList.indices) g.make(recipeList[i])
                }
                tag >= T.SUPPLY && tag < T.SUPPLY + 30 -> {
                    val i = tag - T.SUPPLY
                    if (i in Catalog.supplyIds.indices) g.buySupply(Catalog.supplyIds[i], 1)
                }
                tag >= T.TOOL && tag < T.TOOL + 10 -> g.buyTool(tag - T.TOOL)
                tag >= T.BUY5 && tag < T.BUY5 + 30 -> buySeedAt(tag - T.BUY5, 5)
                tag >= T.BUY1 && tag < T.BUY1 + 30 -> buySeedAt(tag - T.BUY1, 1)
                tag >= T.QUALITY && tag < T.QUALITY + 3 -> {
                    g.settings.quality = tag - T.QUALITY; g.applySettings()
                }
                tag >= T.ITEM && tag < T.ITEM + 200 -> onItemTap(tag - T.ITEM)
            }
        }
    }

    private fun back() {
        when (g.mode) {
            Mode.SETTINGS -> g.setMode(if (g.hasSave() && g.st.introDone) Mode.PAUSE else Mode.TITLE)
            Mode.CREDITS -> g.setMode(Mode.TITLE)
            Mode.DECOR -> g.setMode(Mode.SHOP)
            else -> g.setMode(Mode.PLAY)
        }
    }

    private fun buySeedAt(index: Int, qty: Int) {
        val list = availableCrops()
        if (index in list.indices) g.buySeed(list[index], qty)
    }

    private fun onItemTap(index: Int) {
        if (g.mode == Mode.SHOP && shopTab == 3) {
            if (index in sellIds.indices) g.sell(sellIds[index], 1)
        } else if (g.mode == Mode.BAG) {
            if (index in bagIds.indices) {
                bagSel = if (bagSel == bagIds[index]) null else bagIds[index]
                g.audio.play(Sfx.TAP)
            }
        }
    }

    private fun availableCrops(): List<Crop> =
        Catalog.crops.values.filter { it.tier <= g.st.cabinLevel }

    // ================================================================ layout

    private fun layout() {
        btns.clear()
        when (g.mode) {
            Mode.TITLE -> layoutTitle()
            Mode.CREDITS -> layoutSimpleBack()
            Mode.PAUSE -> layoutPause()
            Mode.BAG -> layoutBag()
            Mode.SHOP -> layoutShop()
            Mode.STATION -> layoutStation()
            Mode.DECOR -> layoutDecor()
            Mode.JOURNAL -> layoutJournal()
            Mode.SETTINGS -> layoutSettings()
            Mode.SLEEP -> layoutSleep()
            Mode.INTRO -> layoutIntro()
        }
    }

    private fun panelRect(): FloatArray {
        val w = min(g.vw - 90f, 1020f)
        val h = g.vh - 96f
        return floatArrayOf((g.vw - w) / 2f, 52f, w, h)
    }

    // ---------------------------------------------------------------- title

    private fun layoutTitle() {
        val bw = 320f
        val bh = 66f
        val x = g.vw * 0.5f - bw / 2f
        var y = g.vh * 0.46f
        if (g.hasSave()) {
            b(T.CONTINUE, "Continue", 1).set(x, y, bw, bh); y += bh + 20f
        }
        b(T.NEW, if (g.hasSave()) "New Winter" else "Begin", if (g.hasSave()) 0 else 1).set(x, y, bw, bh)
        y += bh + 20f
        b(T.SETTINGS, "Settings").set(x, y, bw * 0.48f, bh)
        b(T.CREDITS, "About").set(x + bw * 0.52f, y, bw * 0.48f, bh)
        y += bh + 20f
        b(T.QUIT, "Quit", 2).set(x + bw * 0.26f, y, bw * 0.48f, 54f)
    }

    private fun layoutSimpleBack() {
        b(T.BACK, "Back", 2).set(g.vw / 2f - 110f, g.vh - 96f, 220f, 62f)
    }

    // ---------------------------------------------------------------- pause

    private val pauseTop = 54f
    private val pausePanelH = 400f
    private val pauseStep = 62f

    private fun layoutPause() {
        val bw = 300f; val bh = 52f
        val x = g.vw / 2f - bw / 2f
        var y = pauseTop + 44f
        b(T.RESUME, "Resume", 1).set(x, y, bw, bh); y += pauseStep
        b(T.BAG, "Backpack").set(x, y, bw, bh); y += pauseStep
        b(T.JOURNAL, "Journal").set(x, y, bw, bh); y += pauseStep
        b(T.SETTINGS, "Settings").set(x, y, bw, bh); y += pauseStep
        b(T.TO_TITLE, "Save & Exit", 2).set(x, y, bw, bh)
    }

    // ------------------------------------------------------------------ bag

    private val bagCols = 8
    private val bagRows = 4

    private fun refreshBagIds() {
        bagIds.clear()
        for ((k, v) in g.st.inv) if (v > 0) bagIds.add(k)
    }

    private fun layoutBag() {
        refreshBagIds()
        val p = panelRect()
        val gridW = p[2] - 72f
        val cell = min((gridW - (bagCols - 1) * 12f) / bagCols, 96f)
        val startX = p[0] + (p[2] - (cell * bagCols + 12f * (bagCols - 1))) / 2f
        val startY = p[1] + 96f
        val perPage = bagCols * bagRows
        val pages = max(1, (bagIds.size + perPage - 1) / perPage)
        if (bagPage >= pages) bagPage = pages - 1
        for (i in 0 until perPage) {
            val idx = bagPage * perPage + i
            if (idx >= bagIds.size) break
            val cx = startX + (i % bagCols) * (cell + 12f)
            val cy = startY + (i / bagCols) * (cell + 12f)
            b(T.ITEM + idx).set(cx, cy, cell, cell).also { it.visible = true }
        }
        if (pages > 1) {
            b(T.PAGE_PREV, "<", 2).set(p[0] + 24f, p[1] + p[3] - 92f, 64f, 56f)
            b(T.PAGE_NEXT, ">", 2).set(p[0] + p[2] - 88f, p[1] + p[3] - 92f, 64f, 56f)
        }
        val by = p[1] + p[3] - 96f
        val item = bagSel?.let { Catalog.items[it] }
        val paired = item != null && (item.warmth > 0 || item.cat == Cat.SEED)
        if (item != null) {
            if (item.warmth > 0) {
                val verb = if (item.cat == Cat.MEAL) "Tuck in" else "Eat"
                b(T.EAT, verb, 1).set(g.vw / 2f - 170f, by, 160f, 58f)
            }
            if (item.cat == Cat.SEED) b(T.USE_SEED, "Select", 1).set(g.vw / 2f - 170f, by, 160f, 58f)
        }
        b(T.BACK, "Close", 2).set(if (paired) g.vw / 2f + 20f else g.vw / 2f - 80f, by, 160f, 58f)
    }

    // ----------------------------------------------------------------- shop

    private fun shopRect(): FloatArray {
        val w = min(g.vw - 90f, 1060f)
        return floatArrayOf((g.vw - w) / 2f, 40f, w, g.vh - 84f)
    }

    private val shopTopOff = 120f
    private val seedCardH = 96f
    private val toolCardH = 188f
    private fun shopButtonY(p: FloatArray) = p[1] + p[3] - 58f

    private val shopTabs = arrayOf("Seed", "Supplies", "Gear", "Sell", "Home")

    private fun layoutShop() {
        val p = shopRect()
        val tabW = (p[2] - 80f) / 5f
        for (i in 0 until 5) {
            b(T.TAB0 + i, shopTabs[i], if (shopTab == i) 1 else 2)
                .set(p[0] + 40f + i * tabW, p[1] + 50f, tabW - 10f, 44f)
        }
        val top = p[1] + shopTopOff
        when (shopTab) {
            0 -> {
                val crops = availableCrops()
                val cols = 3
                val cw = (p[2] - 100f - (cols - 1) * 18f) / cols
                for (i in crops.indices) {
                    val cx = p[0] + 50f + (i % cols) * (cw + 18f)
                    val cy = top + (i / cols) * (seedCardH + 12f)
                    b(T.BUY1 + i, "x1", 1).set(cx + 10f, cy + seedCardH - 42f, cw / 2f - 16f, 38f)
                    b(T.BUY5 + i, "x5").set(cx + cw / 2f + 4f, cy + seedCardH - 42f, cw / 2f - 16f, 38f)
                }
            }
            1 -> {
                val cols = 4
                val cw = (p[2] - 100f - (cols - 1) * 16f) / cols
                for (i in Catalog.supplyIds.indices) {
                    val cx = p[0] + 50f + (i % cols) * (cw + 16f)
                    val cy = top + (i / cols) * (seedCardH + 12f)
                    val id = Catalog.supplyIds[i]
                    val cost = Catalog.price(id)
                    val btn = b(T.SUPPLY + i, "Buy  ${U.formatCoins(cost)}", 1)
                    btn.set(cx + 10f, cy + seedCardH - 42f, cw - 20f, 38f)
                    btn.enabled = g.st.coins >= cost
                }
            }
            2 -> {
                val cols = 5
                val cw = (p[2] - 100f - (cols - 1) * 14f) / cols
                for (i in 0 until 5) {
                    val cx = p[0] + 50f + i * (cw + 14f)
                    val lvl = toolLevel(i)
                    val cost = toolCost(i, lvl + 1)
                    val btn = b(
                        T.TOOL + i,
                        if (lvl >= 3) "Maxed" else U.formatCoins(cost),
                        if (lvl >= 3) 2 else 1
                    )
                    btn.set(cx + 12f, top + toolCardH - 44f, cw - 24f, 44f)
                    btn.enabled = lvl < 3
                }
            }
            3 -> {
                sellIds.clear()
                for ((k, v) in g.st.inv) {
                    if (v <= 0) continue
                    val it = Catalog.items[k] ?: continue
                    if (it.cat == Cat.SEED) continue
                    sellIds.add(k)
                }
                val cols = 8
                val cell = min((p[2] - 100f - (cols - 1) * 12f) / cols, 96f)
                val sx = p[0] + (p[2] - (cell * cols + 12f * (cols - 1))) / 2f
                for (i in sellIds.indices) {
                    if (i >= cols * 3) break
                    b(T.ITEM + i).set(sx + (i % cols) * (cell + 12f), top + 26f + (i / cols) * (cell + 14f), cell, cell)
                }
                b(T.SELL_ALL, "Sell the basket", 1).set(g.vw / 2f - 210f, shopButtonY(p), 250f, 52f)
            }
            4 -> {
                val next = Tiers.next(g.st.cabinLevel)
                if (next != null) {
                    val btn = b(T.UPGRADE, "Build it", 1)
                    btn.set(g.vw / 2f - 250f, shopButtonY(p), 220f, 52f)
                    btn.enabled = g.st.coins >= next.coins &&
                        g.st.count("log") >= next.log && g.st.count("stone") >= next.stone
                }
                b(T.DECOR, "Decorate", 0).set(g.vw / 2f - 20f, shopButtonY(p), 200f, 52f)
            }
        }
        b(T.BACK, "Close", 2).set(
            if (shopTab >= 3) g.vw / 2f + 190f else g.vw / 2f - 90f,
            shopButtonY(p), 170f, 52f
        )
    }

    private fun toolLevel(i: Int) = when (i) {
        0 -> g.st.rodLevel; 1 -> g.st.kettleLevel; 2 -> g.st.axeLevel
        3 -> g.st.coatLevel; else -> g.st.lanternLevel
    }

    private fun toolCost(i: Int, lvl: Int) = when (i) {
        0 -> ToolUp.rodCost(lvl); 1 -> ToolUp.kettleCost(lvl); 2 -> ToolUp.axeCost(lvl)
        3 -> ToolUp.coatCost(lvl); else -> ToolUp.lanternCost(lvl)
    }

    private fun toolName(i: Int, lvl: Int) = when (i) {
        0 -> ToolUp.rodName(lvl); 1 -> ToolUp.kettleName(lvl); 2 -> ToolUp.axeName(lvl)
        3 -> ToolUp.coatName(lvl); else -> ToolUp.lanternName(lvl)
    }

    // -------------------------------------------------------------- station

    private fun layoutStation() {
        recipeList.clear()
        recipeList.addAll(Catalog.recipesFor(g.stationKind))
        val p = shopRect()
        val cols = 3
        val cw = (p[2] - 100f - (cols - 1) * 18f) / cols
        val ch = 118f
        for (i in recipeList.indices) {
            val cx = p[0] + 50f + (i % cols) * (cw + 18f)
            val cy = p[1] + 96f + (i / cols) * (ch + 12f)
            val btn = b(T.MAKE + i, "Make", 1)
            btn.set(cx + cw - 118f, cy + ch - 46f, 100f, 38f)
            btn.enabled = g.st.canMake(recipeList[i])
        }
        b(T.BACK, "Close", 2).set(g.vw / 2f - 90f, shopButtonY(p), 180f, 52f)
    }

    // ---------------------------------------------------------------- decor

    private fun layoutDecor() {
        val p = shopRect()
        val cols = 4
        val cw = (p[2] - 100f - (cols - 1) * 16f) / cols
        val ch = 104f
        for (i in Decorations.list.indices) {
            val cx = p[0] + 50f + (i % cols) * (cw + 16f)
            val cy = p[1] + 96f + (i / cols) * (ch + 12f)
            val dec = Decorations.list[i]
            val owned = g.st.decorOwned.contains(dec.id)
            val up = g.st.decorPlaced[dec.slot] == dec.id
            val btn = b(
                T.DEC + i,
                if (!owned) U.formatCoins(dec.cost) else if (up) "Take down" else "Put up",
                if (!owned) 1 else if (up) 2 else 0
            )
            btn.set(cx + 12f, cy + ch - 44f, cw - 24f, 38f)
            btn.enabled = owned || g.st.coins >= dec.cost
        }
        b(T.BACK, "Back", 2).set(g.vw / 2f - 90f, shopButtonY(p), 180f, 52f)
    }

    // -------------------------------------------------------------- journal

    private fun journalRect(): FloatArray {
        val w = min(g.vw - 90f, 1020f)
        return floatArrayOf((g.vw - w) / 2f, 40f, w, g.vh - 84f)
    }

    private val jTabs = arrayOf("Fish", "Glasshouse", "Kitchen", "Winter")

    private fun layoutJournal() {
        val p = journalRect()
        val tabW = (p[2] - 80f) / 4f
        for (i in 0 until 4) {
            b(T.TAB0 + i, jTabs[i], if (jTab == i) 1 else 2)
                .set(p[0] + 40f + i * tabW, p[1] + 50f, tabW - 10f, 46f)
        }
        b(T.BACK, "Close", 2).set(g.vw / 2f - 90f, p[1] + p[3] - 64f, 180f, 52f)
    }

    // ------------------------------------------------------------- settings

    private val setRows = floatArrayOf(52f, 94f, 138f, 184f, 220f, 256f, 292f)

    private fun layoutSettings() {
        val p = panelRect()
        val lx = p[0] + 80f
        val rw = p[2] - 300f
        b(T.SLIDER_MUSIC).set(lx + 170f, p[1] + setRows[0] - 26f, rw, 52f)
        b(T.SLIDER_SFX).set(lx + 170f, p[1] + setRows[1] - 26f, rw, 52f)
        val qw = rw / 3f - 10f
        for (i in 0 until 3) {
            b(T.QUALITY + i, arrayOf("Quiet", "Balanced", "Deep")[i], if (g.settings.quality == i) 1 else 2)
                .set(lx + 170f + i * (qw + 14f), p[1] + setRows[2] - 22f, qw, 44f)
        }
        b(T.TOG_GENTLE).set(lx + 280f, p[1] + setRows[3] - 20f, 68f, 40f)
        b(T.TOG_FPS).set(lx + 280f, p[1] + setRows[4] - 20f, 68f, 40f)
        b(T.TOG_HAPTIC).set(lx + 280f, p[1] + setRows[5] - 20f, 68f, 40f)
        b(T.TOG_SOUTH).set(lx + 280f, p[1] + setRows[6] - 20f, 68f, 40f)
        val by = p[1] + p[3] - 64f
        if (confirmReset) {
            b(T.RESET_YES, "Erase", 4).set(g.vw / 2f - 210f, by, 180f, 52f)
            b(T.RESET_NO, "Keep", 2).set(g.vw / 2f - 20f, by, 180f, 52f)
        } else {
            b(T.RESET, "Erase Save", 4).set(p[0] + 60f, by, 220f, 52f)
        }
        b(T.BACK, "Back", 2).set(p[0] + p[2] - 220f, by, 160f, 52f)
    }

    private val sleepTop: Float get() = g.vh * 0.12f
    private val sleepPanelH = 366f

    private fun layoutSleep() {
        b(T.SLEEP_OK, "Good morning", 1)
            .set(g.vw / 2f - 150f, sleepTop + sleepPanelH - 72f, 300f, 60f)
    }

    private fun layoutIntro() {
        b(T.BEGIN, "Let's begin", 1).set(g.vw / 2f - 140f, g.vh * 0.76f, 280f, 64f)
    }

    // ================================================================= draw

    private fun drawButtons(c: Canvas, alpha: Float = 1f) {
        for (btn in btns) {
            if (btn.tag >= T.ITEM && btn.tag < T.ITEM + 200) continue
            Ui.button(c, btn, alpha)
        }
    }

    private fun pop(): Float = U.easeBack(U.clamp01(g.modeT))

    private fun beginPop(c: Canvas) {
        val s = U.lerp(0.9f, 1f, pop())
        c.save()
        c.translate(g.vw / 2f, g.vh / 2f)
        c.scale(s, s)
        c.translate(-g.vw / 2f, -g.vh / 2f)
    }

    // ---------------------------------------------------------------- title

    fun drawTitle(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.30f)
        val cx = g.vw / 2f
        val bob = sin(g.titleT * 0.8f) * 4f
        Ui.textOut(
            c, "Frostfall", cx, g.vh * 0.15f + bob, 86f, Pal.cream, Pal.shadow,
            Paint.Align.CENTER, Ui.display, 11f
        )
        Ui.textOut(
            c, "Hollow", cx, g.vh * 0.15f + 74f + bob, 86f, Pal.gold, Pal.shadow,
            Paint.Align.CENTER, Ui.display, 11f
        )
        Ui.text(
            c, "a long, quiet winter with a fire in it", cx, g.vh * 0.15f + 110f + bob, 22f,
            U.withAlpha(Pal.cream, 0.85f), Paint.Align.CENTER
        )
        drawButtons(c)
        Ui.text(c, "v2.0", g.vw - 18f, g.vh - 16f, 16f, U.withAlpha(Pal.cream, 0.45f), Paint.Align.RIGHT)
    }

    fun drawCredits(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.5f)
        val p = panelRect()
        Ui.panel(c, p[0], p[1], p[2], p[3])
        Ui.ribbon(c, g.vw / 2f, p[1] - 18f, 340f, "About")
        val tx = p[0] + 60f
        var y = p[1] + 100f
        val body = listOf(
            "Frostfall Hollow is a small handmade winter game.",
            "",
            "Every drift, pine, flake and note of music is generated by",
            "code as you play — there are no image or audio files",
            "anywhere in the app. That is why it installs in a couple of",
            "megabytes and runs on old phones.",
            "",
            "How to play",
            "  Walk with the stick; drag anywhere to turn the camera.",
            "  The big button changes to match whatever is in front of",
            "  you: fell, split, dig, plant, gather, cut a hole in the ice,",
            "  stoke, cook, sit, or pet the cat.",
            "  Warmth only ever slows you down — it can never hurt you,",
            "  and you can switch it off entirely in Settings.",
            "  Sleep whenever you like. There is no end to reach.",
            "",
            "Thanks for coming out in this weather."
        )
        for (line in body) {
            val bold = line == "How to play"
            Ui.text(
                c, line, tx, y, if (bold) 24f else 20f,
                if (bold) Pal.ink else Pal.inkSoft, Paint.Align.LEFT,
                if (bold) Ui.display else Ui.body
            )
            y += if (line.isEmpty()) 12f else 27f
        }
        drawButtons(c)
    }

    // ---------------------------------------------------------------- pause

    fun drawPause(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.46f * U.clamp01(g.modeT * 2f))
        beginPop(c)
        val w = 380f
        Ui.panel(c, g.vw / 2f - w / 2f, pauseTop, w, pausePanelH)
        Ui.ribbon(c, g.vw / 2f, pauseTop - 18f, 260f, "Paused")
        drawButtons(c)
        c.restore()
    }

    // ------------------------------------------------------------------ bag

    fun drawBag(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.46f * U.clamp01(g.modeT * 2f))
        beginPop(c)
        val p = panelRect()
        Ui.panel(c, p[0], p[1], p[2], p[3])
        Ui.ribbon(c, g.vw / 2f, p[1] - 18f, 380f, "Backpack")
        Ui.text(
            c, "${g.st.usedSlots()} / ${g.st.invSlots} slots", p[0] + p[2] - 40f, p[1] + 78f, 20f,
            Pal.inkSoft, Paint.Align.RIGHT
        )

        if (bagIds.isEmpty()) {
            Ui.text(c, "Your bag is empty.", g.vw / 2f, g.vh * 0.44f, 26f, Pal.inkSoft, Paint.Align.CENTER, Ui.display)
        }
        for (btn in btns) {
            if (btn.tag < T.ITEM || btn.tag >= T.ITEM + 200) continue
            val idx = btn.tag - T.ITEM
            if (idx >= bagIds.size) continue
            val id = bagIds[idx]
            drawItemTile(c, btn, id, g.st.count(id), id == bagSel)
        }

        val sel = bagSel
        if (sel != null) {
            val item = Catalog.items[sel]
            if (item != null) {
                val iy = p[1] + p[3] - 168f
                Ui.text(c, item.name, p[0] + 60f, iy, 27f, Pal.ink, Paint.Align.LEFT, Ui.display)
                Ui.text(c, categoryName(item.cat), p[0] + 60f, iy + 28f, 19f, Pal.inkSoft, Paint.Align.LEFT)
                Ui.coin(c, p[0] + 66f, iy + 52f, 13f)
                Ui.text(c, "${item.price} each", p[0] + 84f, iy + 58f, 20f, Pal.goldDeep, Paint.Align.LEFT)
                if (item.warmth > 0) {
                    Ui.text(c, "+${item.warmth} warmth", p[0] + 230f, iy + 58f, 20f, Pal.ember, Paint.Align.LEFT)
                }
            }
        } else {
            Ui.text(
                c, "Tap an item to see what it is.", g.vw / 2f, p[1] + p[3] - 120f, 19f,
                Pal.inkSoft, Paint.Align.CENTER
            )
        }
        drawButtons(c)
        c.restore()
    }

    private fun categoryName(cat: Int) = when (cat) {
        Cat.SEED -> "Seed"
        Cat.CROP -> "Grown under glass"
        Cat.FISH -> "Through the ice"
        Cat.FORAGE -> "Foraged"
        Cat.MEAL -> "Off the stove"
        else -> "Material"
    }

    private fun drawItemTile(c: Canvas, btn: Btn, id: String, count: Int, selected: Boolean) {
        Ui.tile(c, btn.x, btn.y, btn.w, btn.h, selected)
        IconDraw.draw(c, Catalog.item(id), btn.cx, btn.cy - 6f, btn.w * 0.62f, Ui.txt)
        if (count > 1) {
            Ui.textOut(
                c, "$count", btn.x + btn.w - 8f, btn.y + btn.h - 8f, 20f, Pal.ink, Pal.paper,
                Paint.Align.RIGHT, Ui.body, 4f
            )
        }
    }

    // ----------------------------------------------------------------- shop

    fun drawShop(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.46f * U.clamp01(g.modeT * 2f))
        beginPop(c)
        val p = shopRect()
        Ui.panel(c, p[0], p[1], p[2], p[3])
        Ui.ribbon(c, g.vw / 2f, p[1] - 18f, 420f, "Pip's Stall")
        Ui.coin(c, p[0] + p[2] - 116f, p[1] + 26f, 15f)
        Ui.text(c, U.formatCoins(g.st.coins), p[0] + p[2] - 96f, p[1] + 34f, 24f, Pal.goldDeep, Paint.Align.LEFT)

        val top = p[1] + shopTopOff
        when (shopTab) {
            0 -> drawSeedShop(c, p, top)
            1 -> drawSupplyShop(c, p, top)
            2 -> drawToolShop(c, p, top)
            3 -> drawSellTab(c, p, top)
            4 -> drawHomeTab(c, p, top)
        }
        drawButtons(c)
        c.restore()
    }

    private fun drawSeedShop(c: Canvas, p: FloatArray, top: Float) {
        val crops = availableCrops()
        val cols = 3
        val cw = (p[2] - 100f - (cols - 1) * 18f) / cols
        val ch = seedCardH
        for (i in crops.indices) {
            val crop = crops[i]
            val cx = p[0] + 50f + (i % cols) * (cw + 18f)
            val cy = top + (i / cols) * (ch + 12f)
            Ui.tile(c, cx, cy, cw, ch, false)
            IconDraw.draw(c, Catalog.item(crop.seedId), cx + 34f, cy + 30f, 42f, Ui.txt)
            Ui.text(c, crop.name, cx + 62f, cy + 24f, 19f, Pal.ink, Paint.Align.LEFT, Ui.display)
            Ui.text(
                c, "${crop.days} days${if (crop.regrow) " · regrows" else ""}", cx + 62f, cy + 42f, 14f,
                Pal.inkSoft, Paint.Align.LEFT
            )
            Ui.text(c, "free", cx + cw - 16f, cy + 28f, 18f, Pal.pineDeep, Paint.Align.RIGHT)
            Ui.text(
                c, "sells ${Catalog.price(crop.produceId)}", cx + cw - 16f, cy + 46f, 14f,
                Pal.goldDeep, Paint.Align.RIGHT
            )
        }
        Ui.text(
            c, "Seed is a gift. Pip will not hear otherwise.", g.vw / 2f, p[1] + 110f, 17f,
            Pal.inkSoft, Paint.Align.CENTER
        )
    }

    private fun drawSupplyShop(c: Canvas, p: FloatArray, top: Float) {
        val cols = 4
        val cw = (p[2] - 100f - (cols - 1) * 16f) / cols
        val ch = seedCardH
        for (i in Catalog.supplyIds.indices) {
            val id = Catalog.supplyIds[i]
            val item = Catalog.item(id)
            val cx = p[0] + 50f + (i % cols) * (cw + 16f)
            val cy = top + (i / cols) * (ch + 12f)
            Ui.tile(c, cx, cy, cw, ch, false)
            IconDraw.draw(c, item, cx + 34f, cy + 28f, 40f, Ui.txt)
            Ui.text(c, item.name, cx + 60f, cy + 26f, 17f, Pal.ink, Paint.Align.LEFT, Ui.display)
            Ui.text(c, "have ${g.st.count(id)}", cx + 60f, cy + 44f, 14f, Pal.inkSoft, Paint.Align.LEFT)
        }
        Ui.text(
            c, "Everything else on the counter has a price on it.", g.vw / 2f, p[1] + 110f, 17f,
            Pal.inkSoft, Paint.Align.CENTER
        )
    }

    private fun drawToolShop(c: Canvas, p: FloatArray, top: Float) {
        val cols = 5
        val cw = (p[2] - 100f - (cols - 1) * 14f) / cols
        val ch = toolCardH
        val names = arrayOf("Jig Rod", "Kettle", "Axe", "Coat", "Lantern")
        val descs = arrayOf(
            "Bites come faster and the deep fish rise.",
            "The good pot gets more out of the same pantry.",
            "More logs from every tree you take.",
            "The cold takes far longer to find you.",
            "Your own light reaches further after dark."
        )
        for (i in 0 until 5) {
            val cx = p[0] + 50f + i * (cw + 14f)
            Ui.tile(c, cx, top, cw, ch, false)
            Ui.text(c, names[i], cx + cw / 2f, top + 28f, 20f, Pal.ink, Paint.Align.CENTER, Ui.display)
            toolGlyph(c, cx + cw / 2f, top + 64f, i)
            val lvl = toolLevel(i)
            for (k in 0 until 3) {
                val px = cx + cw / 2f - 22f + k * 22f
                Ui.txt.style = Paint.Style.FILL
                Ui.txt.color = if (k < lvl) Pal.gold else U.withAlpha(Pal.inkSoft, 0.3f)
                c.drawCircle(px, top + 96f, 6f, Ui.txt)
            }
            Ui.text(c, toolName(i, lvl), cx + cw / 2f, top + 118f, 16f, Pal.inkSoft, Paint.Align.CENTER)
            lines.clear()
            Ui.wrap(descs[i], 13f, cw - 28f, lines)
            var ly = top + 136f
            for (l in lines) {
                Ui.text(c, l, cx + cw / 2f, ly, 13f, Pal.inkSoft, Paint.Align.CENTER); ly += 15f
            }
        }
    }

    private fun toolGlyph(c: Canvas, x: Float, y: Float, kind: Int) {
        val pt = Ui.txt
        pt.style = Paint.Style.STROKE
        pt.strokeWidth = 5f
        pt.strokeCap = Paint.Cap.ROUND
        pt.color = Pal.woodDark
        when (kind) {
            0 -> {
                c.drawLine(x - 18f, y + 18f, x + 14f, y - 16f, pt)
                pt.strokeWidth = 2f
                pt.color = U.withAlpha(Pal.ink, 0.5f)
                c.drawLine(x + 14f, y - 16f, x + 18f, y + 14f, pt)
                pt.style = Paint.Style.FILL
                pt.color = Pal.berry
                c.drawCircle(x + 18f, y + 17f, 5f, pt)
            }
            1 -> {
                pt.style = Paint.Style.FILL
                pt.color = Color.parseColor("#8A8E9C")
                c.drawRoundRect(x - 18f, y - 8f, x + 10f, y + 18f, 8f, 8f, pt)
                pt.style = Paint.Style.STROKE
                pt.strokeWidth = 5f
                c.drawLine(x + 10f, y - 2f, x + 22f, y + 6f, pt)
                pt.style = Paint.Style.FILL
                pt.color = Pal.frost
                c.drawCircle(x - 4f, y - 15f, 4f, pt)
                c.drawCircle(x + 4f, y - 19f, 3f, pt)
            }
            2 -> {
                c.drawLine(x - 14f, y + 18f, x + 6f, y - 14f, pt)
                pt.style = Paint.Style.FILL
                pt.color = Color.parseColor("#B8BEC6")
                c.drawRoundRect(x, y - 22f, x + 24f, y - 4f, 4f, 4f, pt)
            }
            3 -> {
                pt.style = Paint.Style.FILL
                pt.color = Color.parseColor("#3E5A78")
                c.drawRoundRect(x - 16f, y - 16f, x + 16f, y + 18f, 8f, 8f, pt)
                pt.color = Pal.berry
                c.drawRoundRect(x - 18f, y - 18f, x + 18f, y - 8f, 5f, 5f, pt)
                pt.color = U.withAlpha(Color.BLACK, 0.25f)
                c.drawRect(x - 2f, y - 8f, x + 2f, y + 18f, pt)
            }
            else -> {
                pt.style = Paint.Style.FILL
                pt.color = Pal.gold
                c.drawRoundRect(x - 10f, y - 10f, x + 10f, y + 12f, 5f, 5f, pt)
                pt.color = Color.parseColor("#6E7280")
                c.drawRoundRect(x - 13f, y + 10f, x + 13f, y + 17f, 3f, 3f, pt)
                c.drawRoundRect(x - 13f, y - 16f, x + 13f, y - 9f, 3f, 3f, pt)
                pt.style = Paint.Style.STROKE
                pt.strokeWidth = 3f
                c.drawArc(x - 8f, y - 26f, x + 8f, y - 12f, 180f, 180f, false, pt)
            }
        }
        pt.style = Paint.Style.FILL
    }

    private fun drawSellTab(c: Canvas, p: FloatArray, top: Float) {
        if (sellIds.isEmpty()) {
            Ui.text(
                c, "Nothing to sell just yet.", g.vw / 2f, g.vh * 0.44f, 26f, Pal.inkSoft,
                Paint.Align.CENTER, Ui.display
            )
            return
        }
        Ui.text(c, "Tap an item to sell one.", g.vw / 2f, top, 19f, Pal.inkSoft, Paint.Align.CENTER)
        var total = 0
        for (id in sellIds) {
            val it = Catalog.items[id] ?: continue
            if (it.cat == Cat.MATERIAL) continue
            total += it.price * g.st.count(id)
        }
        for (btn in btns) {
            if (btn.tag < T.ITEM || btn.tag >= T.ITEM + 200) continue
            val idx = btn.tag - T.ITEM
            if (idx >= sellIds.size) continue
            val id = sellIds[idx]
            Ui.tile(c, btn.x, btn.y, btn.w, btn.h, false)
            IconDraw.draw(c, Catalog.item(id), btn.cx, btn.cy - 10f, btn.w * 0.58f, Ui.txt)
            Ui.textOut(
                c, "${g.st.count(id)}", btn.x + btn.w - 8f, btn.y + 24f, 19f, Pal.ink, Pal.paper,
                Paint.Align.RIGHT, Ui.body, 4f
            )
            Ui.text(c, "${Catalog.price(id)}", btn.cx, btn.y + btn.h - 10f, 17f, Pal.goldDeep, Paint.Align.CENTER)
        }
        Ui.text(
            c, "The basket: ${U.formatCoins(total)} coins", g.vw / 2f + 50f, p[1] + p[3] - 58f, 20f,
            Pal.goldDeep, Paint.Align.LEFT
        )
    }

    private fun drawHomeTab(c: Canvas, p: FloatArray, top: Float) {
        val cur = g.st.tier
        val next = Tiers.next(g.st.cabinLevel)
        Ui.text(c, cur.name, g.vw / 2f, top + 12f, 28f, Pal.ink, Paint.Align.CENTER, Ui.display)
        Ui.text(c, cur.blurb, g.vw / 2f, top + 38f, 18f, Pal.inkSoft, Paint.Align.CENTER)

        val cardW = (p[2] - 140f) / 2f
        val cardY = top + 56f
        val cardH = 130f

        Ui.tile(c, p[0] + 50f, cardY, cardW, cardH, false)
        Ui.text(c, "Now", p[0] + 50f + cardW / 2f, cardY + 26f, 20f, Pal.inkSoft, Paint.Align.CENTER, Ui.display)
        perkRow(c, p[0] + 78f, cardY + 56f, "Beds under glass", "${cur.plots}")
        perkRow(c, p[0] + 78f, cardY + 84f, "Heat held", "${(cur.insulation * 100).toInt()}%")
        perkRow(c, p[0] + 78f, cardY + 112f, "Bag slots", "${cur.invSlots}")

        if (next == null) {
            Ui.tile(c, p[0] + 90f + cardW, cardY, cardW, cardH, false)
            Ui.text(
                c, "Your home is complete.", p[0] + 90f + cardW + cardW / 2f, cardY + cardH / 2f,
                22f, Pal.pineDeep, Paint.Align.CENTER, Ui.display
            )
            return
        }
        Ui.tile(c, p[0] + 90f + cardW, cardY, cardW, cardH, true)
        val nx = p[0] + 90f + cardW
        Ui.text(c, next.name, nx + cardW / 2f, cardY + 26f, 20f, Pal.ink, Paint.Align.CENTER, Ui.display)
        perkRow(c, nx + 28f, cardY + 56f, "Beds under glass", "${next.plots}")
        perkRow(c, nx + 28f, cardY + 84f, "Heat held", "${(next.insulation * 100).toInt()}%")
        perkRow(c, nx + 28f, cardY + 112f, "Bag slots", "${next.invSlots}")

        val cy = cardY + cardH + 34f
        costChip(c, g.vw / 2f - 260f, cy, null, next.coins, g.st.coins)
        costChip(c, g.vw / 2f - 80f, cy, "log", next.log, g.st.count("log"))
        costChip(c, g.vw / 2f + 100f, cy, "stone", next.stone, g.st.count("stone"))
    }

    private fun perkRow(c: Canvas, x: Float, y: Float, label: String, value: String) {
        Ui.text(c, label, x, y, 19f, Pal.inkSoft, Paint.Align.LEFT)
        Ui.text(c, value, x + 200f, y, 22f, Pal.ink, Paint.Align.RIGHT, Ui.display)
    }

    private fun costChip(c: Canvas, x: Float, y: Float, itemId: String?, need: Int, have: Int) {
        val ok = have >= need
        Ui.pill(c, x, y - 24f, 160f, 48f, if (ok) U.withAlpha(Pal.pine, 0.24f) else U.withAlpha(Pal.berry, 0.20f))
        if (itemId == null) Ui.coin(c, x + 26f, y, 15f)
        else IconDraw.draw(c, Catalog.item(itemId), x + 26f, y, 34f, Ui.txt)
        Ui.text(c, "$have / $need", x + 52f, y + 8f, 21f, if (ok) Pal.pineDeep else Pal.berry, Paint.Align.LEFT)
    }

    // -------------------------------------------------------------- station

    fun drawStation(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.46f * U.clamp01(g.modeT * 2f))
        beginPop(c)
        val p = shopRect()
        Ui.panel(c, p[0], p[1], p[2], p[3])
        val title = if (g.stationKind == Recipe.STOVE) "On the Stove" else "The Workbench"
        Ui.ribbon(c, g.vw / 2f, p[1] - 18f, 400f, title)

        if (g.stationKind == Recipe.STOVE && !(g.st.hearthLit && g.st.hearthFuel > 0f)) {
            Ui.text(
                c, "The stove is cold. Feed it a log first.", g.vw / 2f, p[1] + 74f, 19f,
                Pal.berry, Paint.Align.CENTER
            )
        }

        val cols = 3
        val cw = (p[2] - 100f - (cols - 1) * 18f) / cols
        val ch = 118f
        for (i in recipeList.indices) {
            val r = recipeList[i]
            val cx = p[0] + 50f + (i % cols) * (cw + 18f)
            val cy = p[1] + 96f + (i / cols) * (ch + 12f)
            val can = g.st.canMake(r)
            Ui.tile(c, cx, cy, cw, ch, can)
            IconDraw.draw(c, Catalog.item(r.outId), cx + 34f, cy + 32f, 44f, Ui.txt)
            Ui.text(c, r.name, cx + 64f, cy + 26f, 19f, Pal.ink, Paint.Align.LEFT, Ui.display)
            Ui.text(c, r.blurb, cx + 64f, cy + 44f, 13f, Pal.inkSoft, Paint.Align.LEFT)
            // what it takes
            var ix = cx + 20f
            for ((id, n) in r.inputs) {
                val have = if (id == "ANYFISH") g.st.countCat(Cat.FISH) else g.st.count(id)
                IconDraw.draw(c, Catalog.item(Catalog.inputIcon(id)), ix + 12f, cy + 76f, 26f, Ui.txt)
                Ui.text(
                    c, "$have/$n", ix + 26f, cy + 86f, 15f,
                    if (have >= n) Pal.pineDeep else Pal.berry, Paint.Align.LEFT
                )
                ix += 62f
            }
            val out = Catalog.item(r.outId)
            if (out.warmth > 0) {
                Ui.text(
                    c, "+${out.warmth} warmth", cx + cw - 18f, cy + 30f, 15f,
                    Pal.ember, Paint.Align.RIGHT
                )
            }
            Ui.text(
                c, "${r.minutes.toInt()} min", cx + cw - 18f, cy + 50f, 14f,
                Pal.inkSoft, Paint.Align.RIGHT
            )
        }
        drawButtons(c)
        c.restore()
    }

    // ---------------------------------------------------------------- decor

    fun drawDecor(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.46f * U.clamp01(g.modeT * 2f))
        beginPop(c)
        val p = shopRect()
        Ui.panel(c, p[0], p[1], p[2], p[3])
        Ui.ribbon(c, g.vw / 2f, p[1] - 18f, 400f, "Bits & Pieces")
        Ui.coin(c, p[0] + p[2] - 116f, p[1] + 26f, 15f)
        Ui.text(c, U.formatCoins(g.st.coins), p[0] + p[2] - 96f, p[1] + 34f, 24f, Pal.goldDeep, Paint.Align.LEFT)
        Ui.text(
            c, "None of this does anything at all. That is the point.",
            g.vw / 2f, p[1] + 76f, 17f, Pal.inkSoft, Paint.Align.CENTER
        )

        val cols = 4
        val cw = (p[2] - 100f - (cols - 1) * 16f) / cols
        val ch = 104f
        val slotName = arrayOf("wall", "mantel", "floor", "window")
        for (i in Decorations.list.indices) {
            val dec = Decorations.list[i]
            val cx = p[0] + 50f + (i % cols) * (cw + 16f)
            val cy = p[1] + 96f + (i / cols) * (ch + 12f)
            val owned = g.st.decorOwned.contains(dec.id)
            val up = g.st.decorPlaced[dec.slot] == dec.id
            Ui.tile(c, cx, cy, cw, ch, up)
            Ui.text(c, dec.name, cx + 16f, cy + 26f, 18f, Pal.ink, Paint.Align.LEFT, Ui.display)
            Ui.text(
                c, "on the ${slotName[dec.slot]}", cx + cw - 16f, cy + 26f, 13f,
                Pal.inkSoft, Paint.Align.RIGHT
            )
            lines.clear()
            Ui.wrap(dec.blurb, 14f, cw - 32f, lines)
            var ly = cy + 46f
            for (l in lines) { Ui.text(c, l, cx + 16f, ly, 14f, Pal.inkSoft, Paint.Align.LEFT); ly += 16f }
            if (owned && !up) {
                Ui.text(c, "in the chest", cx + cw - 16f, cy + 46f, 13f, Pal.pineDeep, Paint.Align.RIGHT)
            }
        }
        drawButtons(c)
        c.restore()
    }

    // -------------------------------------------------------------- journal

    fun drawJournal(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.46f * U.clamp01(g.modeT * 2f))
        beginPop(c)
        val p = journalRect()
        Ui.panel(c, p[0], p[1], p[2], p[3])
        Ui.ribbon(c, g.vw / 2f, p[1] - 18f, 360f, "Journal")
        val top = p[1] + 152f
        when (jTab) {
            0 -> drawCollection(c, p, top, Catalog.fish.map { it.id }, g.st.seenFish)
            1 -> drawCollection(
                c, p, top, Catalog.crops.values.map { it.produceId },
                HashSet(g.st.seenCrops.mapNotNull { Catalog.crops[it]?.produceId })
            )
            2 -> drawCollection(
                c, p, top,
                Catalog.recipesFor(Recipe.STOVE).map { it.outId }.distinct(), g.st.seenMeals
            )
            else -> drawStats(c, p, p[1] + 118f)
        }
        drawButtons(c)
        c.restore()
    }

    private fun drawCollection(c: Canvas, p: FloatArray, top: Float, ids: List<String>, seen: Set<String>) {
        val cols = 4
        val cw = (p[2] - 120f - (cols - 1) * 16f) / cols
        val ch = 72f
        for (i in ids.indices) {
            val id = ids[i]
            val cx = p[0] + 60f + (i % cols) * (cw + 16f)
            val cy = top + (i / cols) * (ch + 12f)
            val known = seen.contains(id)
            Ui.tile(c, cx, cy, cw, ch, false)
            if (known) {
                IconDraw.draw(c, Catalog.item(id), cx + 36f, cy + ch / 2f, 44f, Ui.txt)
                Ui.text(c, Catalog.name(id), cx + 66f, cy + 32f, 17f, Pal.ink, Paint.Align.LEFT, Ui.display)
                Ui.coin(c, cx + 72f, cy + 50f, 10f)
                Ui.text(c, "${Catalog.price(id)}", cx + 84f, cy + 55f, 16f, Pal.goldDeep, Paint.Align.LEFT)
            } else {
                Ui.text(
                    c, "?", cx + cw / 2f, cy + ch / 2f + 13f, 34f,
                    U.withAlpha(Pal.inkSoft, 0.4f), Paint.Align.CENTER, Ui.display
                )
            }
        }
        Ui.text(
            c, "${seen.count { ids.contains(it) }} of ${ids.size} discovered",
            g.vw / 2f, p[1] + 128f, 20f, Pal.inkSoft, Paint.Align.CENTER
        )
    }

    private fun drawStats(c: Canvas, p: FloatArray, top: Float) {
        val visitors = listOf("chickadee" to "Chickadees", "deer" to "Deer", "cat" to "Mitten")
        val met = visitors.count { g.st.seenAnimals.contains(it.first) }
        val rows = listOf(
            "Days in the hollow" to "${g.st.day}",
            "Warm nights" to "${g.st.nightsWarm}",
            "Coins earned" to U.formatCoins(g.st.totalEarned),
            "Logs brought in" to "${g.st.totalLogs}",
            "Fish through the ice" to "${g.st.totalFish}",
            "Meals off the stove" to "${g.st.totalCooked}",
            "Visitors met" to "$met of ${visitors.size}",
            "Quiet moments" to "${g.st.cosyMoments}",
            "Home" to g.st.tier.name
        )
        val step = min(40f, ((p[1] + p[3] - 80f) - top) / rows.size)
        var y = top
        for ((k, v) in rows) {
            Ui.text(c, k, p[0] + 90f, y, 21f, Pal.inkSoft, Paint.Align.LEFT)
            Ui.text(c, v, p[0] + p[2] - 90f, y, 23f, Pal.ink, Paint.Align.RIGHT, Ui.display)
            Ui.txt.style = Paint.Style.FILL
            Ui.txt.color = U.withAlpha(Pal.wood, 0.25f)
            c.drawRect(p[0] + 90f, y + 11f, p[0] + p[2] - 90f, y + 12.4f, Ui.txt)
            y += step
        }
    }

    // ------------------------------------------------------------- settings

    fun drawSettings(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.5f * U.clamp01(g.modeT * 2f))
        beginPop(c)
        val p = panelRect()
        Ui.panel(c, p[0], p[1], p[2], p[3])
        Ui.ribbon(c, g.vw / 2f, p[1] - 18f, 340f, "Settings")
        val lx = p[0] + 80f

        Ui.text(c, "Music", lx, p[1] + setRows[0] + 8f, 23f, Pal.ink, Paint.Align.LEFT, Ui.display)
        val ms = pool[T.SLIDER_MUSIC]
        if (ms != null) Ui.slider(c, ms.x, ms.y + ms.h / 2f, ms.w, g.settings.music, Pal.pine)
        Ui.text(
            c, "${(g.settings.music * 100).toInt()}%", p[0] + p[2] - 60f, p[1] + setRows[0] + 8f, 20f,
            Pal.inkSoft, Paint.Align.RIGHT
        )

        Ui.text(c, "Sounds", lx, p[1] + setRows[1] + 8f, 23f, Pal.ink, Paint.Align.LEFT, Ui.display)
        val ss = pool[T.SLIDER_SFX]
        if (ss != null) Ui.slider(c, ss.x, ss.y + ss.h / 2f, ss.w, g.settings.sfx, Pal.pine)
        Ui.text(
            c, "${(g.settings.sfx * 100).toInt()}%", p[0] + p[2] - 60f, p[1] + setRows[1] + 8f, 20f,
            Pal.inkSoft, Paint.Align.RIGHT
        )

        Ui.text(c, "Graphics", lx, p[1] + setRows[2] + 8f, 23f, Pal.ink, Paint.Align.LEFT, Ui.display)

        toggleRow(c, lx, p[1] + setRows[3], "Gentle mode (no warmth)", g.settings.gentle, T.TOG_GENTLE)
        toggleRow(c, lx, p[1] + setRows[4], "Show frame rate", g.settings.showFps, T.TOG_FPS)
        toggleRow(c, lx, p[1] + setRows[5], "Vibration", g.settings.haptics, T.TOG_HAPTIC)
        toggleRow(c, lx, p[1] + setRows[6], "Left-handed layout", g.settings.southpaw, T.TOG_SOUTH)

        if (confirmReset) {
            Ui.text(
                c, "Erase this winter for good?", g.vw / 2f, p[1] + p[3] - 80f, 22f,
                Pal.berry, Paint.Align.CENTER, Ui.display
            )
        }
        for (btn in btns) {
            if (btn.tag == T.SLIDER_MUSIC || btn.tag == T.SLIDER_SFX) continue
            if (btn.tag >= T.TOG_FPS && btn.tag <= T.TOG_GENTLE) continue
            Ui.button(c, btn)
        }
        c.restore()
    }

    private fun toggleRow(c: Canvas, x: Float, y: Float, label: String, on: Boolean, tag: Int) {
        Ui.text(c, label, x, y + 8f, 21f, Pal.ink, Paint.Align.LEFT)
        val btn = pool[tag] ?: return
        Ui.toggle(c, btn.x, btn.y + btn.h / 2f, on, if (on) 1f else 0f)
    }

    // ---------------------------------------------------------------- sleep

    fun drawSleep(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.72f)
        beginPop(c)
        val w = min(g.vw - 160f, 640f)
        val h = sleepPanelH
        val x = (g.vw - w) / 2f
        val y = sleepTop
        Ui.panel(c, x, y, w, h)
        Ui.ribbon(c, g.vw / 2f, y - 18f, 320f, "Good night")
        Ui.text(c, "Day ${g.st.day - 1} is done", g.vw / 2f, y + 72f, 30f, Pal.ink, Paint.Align.CENTER, Ui.display)

        val rows = listOf(
            "Coins earned" to U.formatCoins(g.coinsEarnedToday),
            "Ready under glass" to "${g.readyCount}",
            "The night" to (if (g.sleptWarm) "Warm, with the stove in" else "Cold — the stove went out"),
            "Tomorrow" to Weather.name(g.st.weather)
        )
        var ry = y + 128f
        for ((k, v) in rows) {
            Ui.text(c, k, x + 56f, ry, 21f, Pal.inkSoft, Paint.Align.LEFT)
            Ui.text(c, v, x + w - 56f, ry, 21f, Pal.ink, Paint.Align.RIGHT, Ui.display)
            ry += 40f
        }
        val tip = when {
            !g.sleptWarm -> "Split a few logs before dark tomorrow."
            g.st.weather == Weather.BLIZZARD -> "It will blow hard tomorrow. Stay near the fire."
            g.readyCount > 0 -> "Something is ready in the glasshouse."
            else -> "Sleep well."
        }
        Ui.text(c, tip, g.vw / 2f, y + h - 92f, 19f, Pal.pineDeep, Paint.Align.CENTER)
        drawButtons(c)
        c.restore()
    }

    // ---------------------------------------------------------------- intro

    fun drawIntro(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.55f)
        beginPop(c)
        val w = min(g.vw - 180f, 700f)
        val h = 420f
        val x = (g.vw - w) / 2f
        val y = g.vh * 0.14f
        Ui.panel(c, x, y, w, h)
        Ui.ribbon(c, g.vw / 2f, y - 18f, 380f, "A note on the table")
        val body = "The cabin is yours for the winter. The stove draws well once " +
            "it gets going, there is an axe in the block outside, and the pond has " +
            "been frozen thick since the end of November.\n\n" +
            "Keep the fire in and you will be fine. Everything else — the glasshouse, " +
            "the feeders, the hole in the ice — is only there because it is nice to " +
            "have something to do.\n\n" +
            "Come down to the stall when your bag gets heavy. I keep the brazier lit.\n\n" +
            "— Pip"
        var ty = y + 88f
        for (para in body.split("\n")) {
            if (para.isEmpty()) { ty += 10f; continue }
            lines.clear()
            Ui.wrap(para, 20f, w - 110f, lines)
            for (l in lines) {
                Ui.text(c, l, x + 55f, ty, 20f, Pal.ink, Paint.Align.LEFT)
                ty += 28f
            }
        }
        drawButtons(c)
        c.restore()
    }
}
