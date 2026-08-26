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
    const val TAB0 = 20
    const val TAB1 = 21
    const val TAB2 = 22
    const val TAB3 = 23
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
    const val SLIDER_MUSIC = 600
    const val SLIDER_SFX = 601
    const val QUALITY = 610   // + level
    const val TOG_FPS = 620
    const val TOG_HAPTIC = 621
    const val TOG_SOUTH = 622
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

    // ================================================================= input

    fun onDown(x: Float, y: Float): Boolean {
        if (!blocksInput()) return false
        layout()
        pressedTag = 0
        for (btn in btns) {
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
            layout()
            val btn = pool[dragging] ?: return
            applySlider(btn, x)
        }
    }

    fun onUp(x: Float, y: Float): Boolean {
        if (!blocksInput()) { dragging = 0; return false }
        layout()
        if (dragging != 0) {
            dragging = 0
            g.applySettings()
            for (btn in btns) btn.press = 0f
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
        return handled || blocksInput()
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
            T.TAB3 -> { shopTab = 3; bagPage = 0 }
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
            else -> when {
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
            Mode.SHOP, Mode.BAG, Mode.JOURNAL -> g.setMode(Mode.PLAY)
            Mode.PAUSE -> g.setMode(Mode.PLAY)
            else -> g.setMode(Mode.PLAY)
        }
    }

    private fun buySeedAt(index: Int, qty: Int) {
        val list = availableCrops()
        if (index in list.indices) g.buySeed(list[index], qty)
    }

    private fun onItemTap(index: Int) {
        if (g.mode == Mode.SHOP && shopTab == 2) {
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
        var y = g.vh * 0.44f
        if (g.hasSave()) {
            b(T.CONTINUE, "Continue", 1).set(x, y, bw, bh); y += bh + 20f
        }
        b(T.NEW, if (g.hasSave()) "New Valley" else "Begin", if (g.hasSave()) 0 else 1).set(x, y, bw, bh); y += bh + 20f
        b(T.SETTINGS, "Settings").set(x, y, bw * 0.48f, bh)
        b(T.CREDITS, "About").set(x + bw * 0.52f, y, bw * 0.48f, bh)
        y += bh + 20f
        b(T.QUIT, "Quit", 2).set(x + bw * 0.26f, y, bw * 0.48f, 54f)
    }

    private fun layoutSimpleBack() {
        b(T.BACK, "Back", 2).set(g.vw / 2f - 110f, g.vh - 96f, 220f, 62f)
    }

    // ---------------------------------------------------------------- pause

    /**
     * The pause menu's box. Five buttons have to fit inside 480 units of
     * design height with the panel round them - the old spacing ran Save &
     * Exit off the bottom of the screen entirely - so panel and stack come
     * off the same numbers.
     */
    private val pauseTop = 62f
    private val pausePanelH = 402f
    private val pauseStep = 66f

    private fun layoutPause() {
        val bw = 300f; val bh = 54f
        val x = g.vw / 2f - bw / 2f
        var y = pauseTop + 46f
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
            b(T.PAGE_PREV, "<", 2).set(p[0] + 24f, p[1] + p[2] * 0f + p[3] - 92f, 64f, 56f)
            b(T.PAGE_NEXT, ">", 2).set(p[0] + p[2] - 88f, p[1] + p[3] - 92f, 64f, 56f)
        }
        val by = p[1] + p[3] - 96f
        val item = bagSel?.let { Catalog.items[it] }
        val paired = item != null && (item.food > 0 || item.cat == Cat.SEED)
        if (item != null) {
            if (item.food > 0) b(T.EAT, "Eat", 1).set(g.vw / 2f - 170f, by, 160f, 58f)
            if (item.cat == Cat.SEED) b(T.USE_SEED, "Select", 1).set(g.vw / 2f - 170f, by, 160f, 58f)
        }
        // on its own Close sits in the middle; beside Eat or Select it shares
        b(T.BACK, "Close", 2).set(if (paired) g.vw / 2f + 20f else g.vw / 2f - 80f, by, 160f, 58f)
    }

    // ----------------------------------------------------------------- shop

    /**
     * The market's box and the three lines it hangs everything off: the tab
     * row, where a tab's contents start, and the button row along the bottom.
     * Cards have to stop above that row - they used to run through it and, on
     * the seeds and home tabs, off the bottom of the screen as well.
     */
    private fun shopRect(): FloatArray {
        val w = min(g.vw - 90f, 1020f)
        return floatArrayOf((g.vw - w) / 2f, 40f, w, g.vh - 84f)
    }

    private val shopTopOff = 120f
    private val seedCardH = 96f
    private val toolCardH = 206f
    private fun shopButtonY(p: FloatArray) = p[1] + p[3] - 58f

    private fun layoutShop() {
        val p = shopRect()
        val tabW = (p[2] - 80f) / 4f
        for (i in 0 until 4) {
            b(T.TAB0 + i, arrayOf("Seeds", "Tools", "Sell", "Home")[i], if (shopTab == i) 1 else 2)
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
                val cols = 3
                val cw = (p[2] - 100f - (cols - 1) * 18f) / cols
                for (i in 0 until 3) {
                    val cx = p[0] + 50f + i * (cw + 18f)
                    val lvl = toolLevel(i)
                    val cost = when (i) {
                        0 -> ToolUp.rodCost(lvl + 1); 1 -> ToolUp.canCost(lvl + 1); else -> ToolUp.axeCost(lvl + 1)
                    }
                    // the price rides on the button, which saves a row the card
                    // no longer has the height for
                    val btn = b(
                        T.TOOL + i,
                        if (lvl >= 3) "Maxed" else "Upgrade  ${U.formatCoins(cost)}",
                        if (lvl >= 3) 2 else 1
                    )
                    btn.set(cx + 16f, top + toolCardH - 44f, cw - 32f, 44f)
                    btn.enabled = lvl < 3
                }
            }
            2 -> {
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
                b(T.SELL_ALL, "Sell Everything", 1).set(g.vw / 2f - 200f, shopButtonY(p), 250f, 52f)
            }
            3 -> {
                val next = Tiers.next(g.st.cabinLevel)
                if (next != null) {
                    val btn = b(T.UPGRADE, "Build it", 1)
                    btn.set(g.vw / 2f - 130f, shopButtonY(p), 260f, 52f)
                    btn.enabled = g.st.coins >= next.coins &&
                        g.st.count("wood") >= next.wood && g.st.count("stone") >= next.stone
                }
            }
        }
        b(T.BACK, "Close", 2).set(
            if (shopTab == 2 || shopTab == 3) g.vw / 2f + 66f else g.vw / 2f - 90f,
            shopButtonY(p), 180f, 52f
        )
    }

    private fun toolLevel(i: Int) = when (i) {
        0 -> g.st.rodLevel; 1 -> g.st.canLevel; else -> g.st.axeLevel
    }

    // -------------------------------------------------------------- journal

    /**
     * The journal gets a slightly taller box than the other panels: a tab row,
     * a count line and two rows of cards all have to fit between the ribbon
     * and the Close button.
     */
    private fun journalRect(): FloatArray {
        val w = min(g.vw - 90f, 1020f)
        return floatArrayOf((g.vw - w) / 2f, 40f, w, g.vh - 84f)
    }

    private fun layoutJournal() {
        val p = journalRect()
        val tabW = (p[2] - 80f) / 3f
        for (i in 0 until 3) {
            b(T.TAB0 + i, arrayOf("Fish", "Crops", "Story")[i], if (jTab == i) 1 else 2)
                .set(p[0] + 40f + i * tabW, p[1] + 50f, tabW - 10f, 46f)
        }
        b(T.BACK, "Close", 2).set(g.vw / 2f - 90f, p[1] + p[3] - 64f, 180f, 52f)
    }

    // ------------------------------------------------------------- settings

    /**
     * Row centres for the settings page, measured down from the top of the
     * panel. Six rows and a button row have to sit inside 384 units; the old
     * spacing put the last two toggles below the bottom of the screen and ran
     * the buttons through the graphics row.
     */
    private val setRows = floatArrayOf(60f, 104f, 150f, 198f, 238f, 278f)

    private fun layoutSettings() {
        val p = panelRect()
        val lx = p[0] + 80f
        val rw = p[2] - 300f
        b(T.SLIDER_MUSIC).set(lx + 170f, p[1] + setRows[0] - 26f, rw, 52f)
        b(T.SLIDER_SFX).set(lx + 170f, p[1] + setRows[1] - 26f, rw, 52f)
        val qw = rw / 3f - 10f
        for (i in 0 until 3) {
            b(T.QUALITY + i, arrayOf("Cosy", "Balanced", "Lush")[i], if (g.settings.quality == i) 1 else 2)
                .set(lx + 170f + i * (qw + 14f), p[1] + setRows[2] - 22f, qw, 44f)
        }
        // further right than the sliders: "Left-handed layout" ran under its own
        // toggle at the sliders' column
        b(T.TOG_FPS).set(lx + 250f, p[1] + setRows[3] - 22f, 68f, 44f)
        b(T.TOG_HAPTIC).set(lx + 250f, p[1] + setRows[4] - 22f, 68f, 44f)
        b(T.TOG_SOUTH).set(lx + 250f, p[1] + setRows[5] - 22f, 68f, 44f)
        val by = p[1] + p[3] - 64f
        if (confirmReset) {
            b(T.RESET_YES, "Erase", 4).set(g.vw / 2f - 210f, by, 180f, 52f)
            b(T.RESET_NO, "Keep", 2).set(g.vw / 2f - 20f, by, 180f, 52f)
        } else {
            b(T.RESET, "Erase Save", 4).set(p[0] + 60f, by, 220f, 52f)
        }
        b(T.BACK, "Back", 2).set(p[0] + p[2] - 220f, by, 160f, 52f)
    }

    /**
     * The night panel's box, shared by the layout and the drawing so the
     * "Good morning" button cannot drift on top of the line above it.
     */
    private val sleepTop: Float get() = g.vh * 0.13f
    private val sleepPanelH = 356f

    private fun layoutSleep() {
        b(T.SLEEP_OK, "Good morning", 1)
            .set(g.vw / 2f - 150f, sleepTop + sleepPanelH - 72f, 300f, 60f)
    }

    private fun layoutIntro() {
        b(T.BEGIN, "Let's begin", 1).set(g.vw / 2f - 140f, g.vh * 0.74f, 280f, 64f)
    }

    // ================================================================= draw

    /** Item slots are drawn as tiles by their screen, so skip them here. */
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
        Ui.scrim(c, g.vw, g.vh, 0.34f)
        val cx = g.vw / 2f
        val bob = sin(g.titleT * 0.8f) * 4f
        Ui.textOut(c, "Riverside", cx, g.vh * 0.15f + bob, 86f, Pal.cream, Pal.woodDeep,
            Paint.Align.CENTER, Ui.display, 11f)
        Ui.textOut(c, "Hollow", cx, g.vh * 0.15f + 74f + bob, 86f, Pal.gold, Pal.woodDeep,
            Paint.Align.CENTER, Ui.display, 11f)
        Ui.text(c, "a small, quiet life by the water", cx, g.vh * 0.15f + 108f + bob, 22f,
            U.withAlpha(Pal.cream, 0.85f), Paint.Align.CENTER)
        drawButtons(c)
        Ui.text(c, "v1.0", g.vw - 18f, g.vh - 16f, 16f, U.withAlpha(Pal.cream, 0.45f), Paint.Align.RIGHT)
    }

    fun drawCredits(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.5f)
        val p = panelRect()
        Ui.panel(c, p[0], p[1], p[2], p[3])
        Ui.ribbon(c, g.vw / 2f, p[1] - 18f, 340f, "About")
        val tx = p[0] + 60f
        var y = p[1] + 110f
        val body = listOf(
            "Riverside Hollow is a small handmade farming game.",
            "",
            "Every tree, cloud, fish and note of music in this game is",
            "generated by code as you play - there are no image or",
            "audio files anywhere in the app. That is why it installs",
            "in a couple of megabytes and runs smoothly on old phones.",
            "",
            "How to play",
            "  Walk with the arrows, or tap where you want to go.",
            "  The big button changes to match what is in front of you:",
            "  till, plant, water, harvest, chop, gather, fish or shop.",
            "  Sleep at your cabin door to end the day.",
            "  Rain waters every crop for you.",
            "",
            "Thanks for visiting the valley."
        )
        for (line in body) {
            val bold = line == "How to play"
            Ui.text(c, line, tx, y, if (bold) 24f else 21f,
                if (bold) Pal.ink else Pal.inkSoft, Paint.Align.LEFT,
                if (bold) Ui.display else Ui.body)
            y += if (line.isEmpty()) 14f else 30f
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
        Ui.text(c, "${g.st.usedSlots()} / ${g.st.invSlots} slots", p[0] + p[2] - 40f, p[1] + 78f, 20f,
            Pal.inkSoft, Paint.Align.RIGHT)

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
                if (item.food > 0) {
                    Ui.text(c, "+${item.food} energy", p[0] + 220f, iy + 58f, 20f, Pal.leafDeep, Paint.Align.LEFT)
                }
            }
        } else {
            Ui.text(c, "Tap an item to see what it is.", g.vw / 2f, p[1] + p[3] - 120f, 19f,
                Pal.inkSoft, Paint.Align.CENTER)
        }
        drawButtons(c)
        c.restore()
    }

    private fun categoryName(cat: Int) = when (cat) {
        Cat.SEED -> "Seed"
        Cat.CROP -> "Harvest"
        Cat.FISH -> "Fish"
        Cat.FORAGE -> "Foraged"
        else -> "Material"
    }

    private fun drawItemTile(c: Canvas, btn: Btn, id: String, count: Int, selected: Boolean) {
        Ui.tile(c, btn.x, btn.y, btn.w, btn.h, selected)
        IconDraw.draw(c, Catalog.item(id), btn.cx, btn.cy - 6f, btn.w * 0.62f, Ui.txt)
        if (count > 1) {
            Ui.textOut(c, "$count", btn.x + btn.w - 8f, btn.y + btn.h - 8f, 20f, Pal.ink, Pal.paper,
                Paint.Align.RIGHT, Ui.body, 4f)
        }
    }

    // ----------------------------------------------------------------- shop

    fun drawShop(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.46f * U.clamp01(g.modeT * 2f))
        beginPop(c)
        val p = shopRect()
        Ui.panel(c, p[0], p[1], p[2], p[3])
        Ui.ribbon(c, g.vw / 2f, p[1] - 18f, 420f, "Pip's Market")
        // above the tabs, not across them
        Ui.coin(c, p[0] + p[2] - 116f, p[1] + 26f, 15f)
        Ui.text(c, U.formatCoins(g.st.coins), p[0] + p[2] - 96f, p[1] + 34f, 24f, Pal.goldDeep, Paint.Align.LEFT)

        val top = p[1] + shopTopOff
        when (shopTab) {
            0 -> drawSeedShop(c, p, top)
            1 -> drawToolShop(c, p, top)
            2 -> drawSellTab(c, p, top)
            3 -> drawHomeTab(c, p, top)
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
            Ui.text(c, "${crop.days} days${if (crop.regrow) " - regrows" else ""}", cx + 62f, cy + 42f, 14f,
                Pal.inkSoft, Paint.Align.LEFT)
            Ui.coin(c, cx + cw - 78f, cy + 22f, 11f)
            Ui.text(c, "${crop.seedCost}", cx + cw - 62f, cy + 28f, 19f, Pal.goldDeep, Paint.Align.LEFT)
            Ui.text(c, "sells ${Catalog.price(crop.produceId)}", cx + cw - 16f, cy + 46f, 14f,
                Pal.leafDeep, Paint.Align.RIGHT)
        }
        if (Catalog.crops.size > crops.size) {
            Ui.text(c, "Upgrade your home to unlock finer seeds.", g.vw / 2f, p[1] + 110f, 17f,
                Pal.inkSoft, Paint.Align.CENTER)
        }
    }

    private fun drawToolShop(c: Canvas, p: FloatArray, top: Float) {
        val cols = 3
        val cw = (p[2] - 100f - (cols - 1) * 18f) / cols
        val ch = toolCardH
        val names = arrayOf("Fishing Rod", "Watering Can", "Axe")
        val descs = arrayOf(
            "Bites come faster and rare fish visit more often.",
            "Waters more plots with a single pour.",
            "Fells a tree in fewer swings."
        )
        for (i in 0 until 3) {
            val cx = p[0] + 50f + i * (cw + 18f)
            Ui.tile(c, cx, top, cw, ch, false)
            Ui.text(c, names[i], cx + cw / 2f, top + 30f, 22f, Pal.ink, Paint.Align.CENTER, Ui.display)
            toolGlyph(c, cx + cw / 2f, top + 70f, i)
            val lvl = toolLevel(i)
            for (k in 0 until 3) {
                val px = cx + cw / 2f - 26f + k * 26f
                Ui.txt.style = Paint.Style.FILL
                Ui.txt.color = if (k < lvl) Pal.gold else U.withAlpha(Pal.inkSoft, 0.3f)
                c.drawCircle(px, top + 104f, 7f, Ui.txt)
            }
            val label = when (i) {
                0 -> ToolUp.rodName(lvl); 1 -> ToolUp.canName(lvl); else -> ToolUp.axeName(lvl)
            }
            Ui.text(c, label, cx + cw / 2f, top + 128f, 18f, Pal.inkSoft, Paint.Align.CENTER)
            lines.clear()
            Ui.wrap(descs[i], 15f, cw - 40f, lines)
            var ly = top + 148f
            for (l in lines) {
                Ui.text(c, l, cx + cw / 2f, ly, 15f, Pal.inkSoft, Paint.Align.CENTER); ly += 17f
            }
        }
    }

    private fun toolGlyph(c: Canvas, x: Float, y: Float, kind: Int) {
        val pt = Ui.txt
        pt.style = Paint.Style.STROKE
        pt.strokeWidth = 6f
        pt.strokeCap = Paint.Cap.ROUND
        pt.color = Pal.woodDark
        when (kind) {
            0 -> {
                c.drawLine(x - 22f, y + 22f, x + 18f, y - 20f, pt)
                pt.strokeWidth = 2f
                pt.color = U.withAlpha(Pal.ink, 0.5f)
                c.drawLine(x + 18f, y - 20f, x + 24f, y + 16f, pt)
                pt.style = Paint.Style.FILL
                pt.color = Pal.berry
                c.drawCircle(x + 24f, y + 20f, 6f, pt)
            }
            1 -> {
                pt.style = Paint.Style.FILL
                pt.color = Color.parseColor("#8FA8B8")
                c.drawRoundRect(x - 22f, y - 12f, x + 12f, y + 22f, 7f, 7f, pt)
                pt.style = Paint.Style.STROKE
                pt.strokeWidth = 6f
                c.drawLine(x + 12f, y - 4f, x + 28f, y + 6f, pt)
                pt.style = Paint.Style.FILL
                pt.color = Color.parseColor("#9FD4E8")
                c.drawCircle(x + 30f, y + 14f, 4f, pt)
            }
            else -> {
                c.drawLine(x - 16f, y + 22f, x + 8f, y - 18f, pt)
                pt.style = Paint.Style.FILL
                pt.color = Color.parseColor("#B8BEC6")
                c.drawRoundRect(x + 2f, y - 26f, x + 30f, y - 6f, 4f, 4f, pt)
            }
        }
        pt.style = Paint.Style.FILL
    }

    private fun drawSellTab(c: Canvas, p: FloatArray, top: Float) {
        if (sellIds.isEmpty()) {
            Ui.text(c, "Nothing to sell just yet.", g.vw / 2f, g.vh * 0.44f, 26f, Pal.inkSoft,
                Paint.Align.CENTER, Ui.display)
            return
        }
        Ui.text(c, "Tap an item to sell one.", g.vw / 2f, top, 19f, Pal.inkSoft, Paint.Align.CENTER)
        var total = 0
        for (id in sellIds) total += Catalog.price(id) * g.st.count(id)
        for (btn in btns) {
            if (btn.tag < T.ITEM || btn.tag >= T.ITEM + 200) continue
            val idx = btn.tag - T.ITEM
            if (idx >= sellIds.size) continue
            val id = sellIds[idx]
            Ui.tile(c, btn.x, btn.y, btn.w, btn.h, false)
            IconDraw.draw(c, Catalog.item(id), btn.cx, btn.cy - 10f, btn.w * 0.58f, Ui.txt)
            Ui.textOut(c, "${g.st.count(id)}", btn.x + btn.w - 8f, btn.y + 24f, 19f, Pal.ink, Pal.paper,
                Paint.Align.RIGHT, Ui.body, 4f)
            Ui.text(c, "${Catalog.price(id)}", btn.cx, btn.y + btn.h - 10f, 17f, Pal.goldDeep, Paint.Align.CENTER)
        }
        Ui.text(c, "Everything: ${U.formatCoins(total)} coins", g.vw / 2f + 90f, p[1] + p[3] - 58f, 20f,
            Pal.goldDeep, Paint.Align.LEFT)
    }

    private fun drawHomeTab(c: Canvas, p: FloatArray, top: Float) {
        val cur = g.st.tier
        val next = Tiers.next(g.st.cabinLevel)
        Ui.text(c, cur.name, g.vw / 2f, top + 12f, 28f, Pal.ink, Paint.Align.CENTER, Ui.display)
        Ui.text(c, cur.blurb, g.vw / 2f, top + 38f, 18f, Pal.inkSoft, Paint.Align.CENTER)

        val cardW = (p[2] - 140f) / 2f
        val cardY = top + 56f
        val cardH = 130f

        // current perks
        Ui.tile(c, p[0] + 50f, cardY, cardW, cardH, false)
        Ui.text(c, "Now", p[0] + 50f + cardW / 2f, cardY + 26f, 20f, Pal.inkSoft, Paint.Align.CENTER, Ui.display)
        perkRow(c, p[0] + 78f, cardY + 56f, "Field plots", "${cur.plots}")
        perkRow(c, p[0] + 78f, cardY + 84f, "Energy", "${cur.maxEnergy}")
        perkRow(c, p[0] + 78f, cardY + 112f, "Bag slots", "${cur.invSlots}")

        if (next == null) {
            Ui.tile(c, p[0] + 90f + cardW, cardY, cardW, cardH, false)
            Ui.text(c, "Your home is complete.", p[0] + 90f + cardW + cardW / 2f, cardY + cardH / 2f,
                22f, Pal.leafDeep, Paint.Align.CENTER, Ui.display)
            return
        }
        Ui.tile(c, p[0] + 90f + cardW, cardY, cardW, cardH, true)
        val nx = p[0] + 90f + cardW
        Ui.text(c, next.name, nx + cardW / 2f, cardY + 26f, 20f, Pal.ink, Paint.Align.CENTER, Ui.display)
        perkRow(c, nx + 28f, cardY + 56f, "Field plots", "${next.plots}")
        perkRow(c, nx + 28f, cardY + 84f, "Energy", "${next.maxEnergy}")
        perkRow(c, nx + 28f, cardY + 112f, "Bag slots", "${next.invSlots}")

        // cost row, between the cards and the button along the bottom
        val cy = cardY + cardH + 34f
        costChip(c, g.vw / 2f - 220f, cy, null, next.coins, g.st.coins)
        costChip(c, g.vw / 2f - 40f, cy, "wood", next.wood, g.st.count("wood"))
        costChip(c, g.vw / 2f + 130f, cy, "stone", next.stone, g.st.count("stone"))
    }

    private fun perkRow(c: Canvas, x: Float, y: Float, label: String, value: String) {
        Ui.text(c, label, x, y, 19f, Pal.inkSoft, Paint.Align.LEFT)
        Ui.text(c, value, x + 190f, y, 22f, Pal.ink, Paint.Align.RIGHT, Ui.display)
    }

    private fun costChip(c: Canvas, x: Float, y: Float, itemId: String?, need: Int, have: Int) {
        val ok = have >= need
        Ui.pill(c, x, y - 24f, 160f, 48f, if (ok) U.withAlpha(Pal.leaf, 0.24f) else U.withAlpha(Pal.berry, 0.20f))
        if (itemId == null) Ui.coin(c, x + 26f, y, 15f)
        else IconDraw.draw(c, Catalog.item(itemId), x + 26f, y, 34f, Ui.txt)
        Ui.text(c, "$have / $need", x + 52f, y + 8f, 21f, if (ok) Pal.leafDeep else Pal.berry, Paint.Align.LEFT)
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
            1 -> drawCollection(c, p, top, Catalog.crops.values.map { it.produceId },
                HashSet(g.st.seenCrops.mapNotNull { Catalog.crops[it]?.produceId }))
            else -> drawStats(c, p, p[1] + 118f)
        }
        drawButtons(c)
        c.restore()
    }

    private fun drawCollection(c: Canvas, p: FloatArray, top: Float, ids: List<String>, seen: Set<String>) {
        val cols = 4
        val cw = (p[2] - 120f - (cols - 1) * 16f) / cols
        val ch = 76f
        for (i in ids.indices) {
            val id = ids[i]
            val cx = p[0] + 60f + (i % cols) * (cw + 16f)
            val cy = top + (i / cols) * (ch + 14f)
            val known = seen.contains(id)
            Ui.tile(c, cx, cy, cw, ch, false)
            if (known) {
                IconDraw.draw(c, Catalog.item(id), cx + 36f, cy + ch / 2f, 46f, Ui.txt)
                Ui.text(c, Catalog.name(id), cx + 66f, cy + 34f, 18f, Pal.ink, Paint.Align.LEFT, Ui.display)
                Ui.coin(c, cx + 72f, cy + 52f, 10f)
                Ui.text(c, "${Catalog.price(id)}", cx + 84f, cy + 57f, 16f, Pal.goldDeep, Paint.Align.LEFT)
            } else {
                Ui.text(c, "?", cx + cw / 2f, cy + ch / 2f + 13f, 36f,
                    U.withAlpha(Pal.inkSoft, 0.4f), Paint.Align.CENTER, Ui.display)
            }
        }
        Ui.text(c, "${seen.count { ids.contains(it) }} of ${ids.size} discovered",
            g.vw / 2f, p[1] + 128f, 20f, Pal.inkSoft, Paint.Align.CENTER)
    }

    private fun drawStats(c: Canvas, p: FloatArray, top: Float) {
        val rows = listOf(
            "Days in the valley" to "${g.st.day}",
            "Coins earned" to U.formatCoins(g.st.totalEarned),
            "Best single sale" to U.formatCoins(g.st.biggestSale),
            "Crops harvested" to "${g.st.totalHarvest}",
            "Fish caught" to "${g.st.totalFish}",
            "Trees felled" to "${g.st.totalChopped}",
            "Species known" to "${g.st.seenFish.size} / ${Catalog.fish.size}",
            "Home" to g.st.tier.name
        )
        // fit the rows into whatever is left above the Close button rather
        // than running them off the bottom of the panel
        val step = min(44f, ((p[1] + p[3] - 80f) - top) / rows.size)
        var y = top
        for ((k, v) in rows) {
            Ui.text(c, k, p[0] + 90f, y, 22f, Pal.inkSoft, Paint.Align.LEFT)
            Ui.text(c, v, p[0] + p[2] - 90f, y, 24f, Pal.ink, Paint.Align.RIGHT, Ui.display)
            Ui.txt.style = Paint.Style.FILL
            Ui.txt.color = U.withAlpha(Pal.wood, 0.25f)
            c.drawRect(p[0] + 90f, y + 12f, p[0] + p[2] - 90f, y + 13.4f, Ui.txt)
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
        if (ms != null) Ui.slider(c, ms.x, ms.y + ms.h / 2f, ms.w, g.settings.music, Pal.leaf)
        Ui.text(c, "${(g.settings.music * 100).toInt()}%", p[0] + p[2] - 60f, p[1] + setRows[0] + 8f, 20f,
            Pal.inkSoft, Paint.Align.RIGHT)

        Ui.text(c, "Sounds", lx, p[1] + setRows[1] + 8f, 23f, Pal.ink, Paint.Align.LEFT, Ui.display)
        val ss = pool[T.SLIDER_SFX]
        if (ss != null) Ui.slider(c, ss.x, ss.y + ss.h / 2f, ss.w, g.settings.sfx, Pal.leaf)
        Ui.text(c, "${(g.settings.sfx * 100).toInt()}%", p[0] + p[2] - 60f, p[1] + setRows[1] + 8f, 20f,
            Pal.inkSoft, Paint.Align.RIGHT)

        Ui.text(c, "Graphics", lx, p[1] + setRows[2] + 8f, 23f, Pal.ink, Paint.Align.LEFT, Ui.display)

        toggleRow(c, lx, p[1] + setRows[3], "Show frame rate", g.settings.showFps, T.TOG_FPS)
        toggleRow(c, lx, p[1] + setRows[4], "Vibration", g.settings.haptics, T.TOG_HAPTIC)
        toggleRow(c, lx, p[1] + setRows[5], "Left-handed layout", g.settings.southpaw, T.TOG_SOUTH)

        if (confirmReset) {
            Ui.text(c, "Erase your valley for good?", g.vw / 2f, p[1] + p[3] - 80f, 22f,
                Pal.berry, Paint.Align.CENTER, Ui.display)
        }
        for (btn in btns) {
            if (btn.tag == T.SLIDER_MUSIC || btn.tag == T.SLIDER_SFX) continue
            if (btn.tag == T.TOG_FPS || btn.tag == T.TOG_HAPTIC || btn.tag == T.TOG_SOUTH) continue
            Ui.button(c, btn)
        }
        c.restore()
    }

    private fun toggleRow(c: Canvas, x: Float, y: Float, label: String, on: Boolean, tag: Int) {
        Ui.text(c, label, x, y + 8f, 22f, Pal.ink, Paint.Align.LEFT)
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
        Ui.text(c, "Day ${g.st.day - 1} is done", g.vw / 2f, y + 76f, 30f, Pal.ink, Paint.Align.CENTER, Ui.display)

        val rows = listOf(
            "Coins earned" to U.formatCoins(g.coinsEarnedToday),
            "Crops ready to pick" to "${g.readyCount}",
            "Tomorrow" to Weather.name(g.st.weather)
        )
        var ry = y + 140f
        for ((k, v) in rows) {
            Ui.text(c, k, x + 60f, ry, 22f, Pal.inkSoft, Paint.Align.LEFT)
            Ui.text(c, v, x + w - 60f, ry, 24f, Pal.ink, Paint.Align.RIGHT, Ui.display)
            ry += 44f
        }
        val tip = when {
            g.st.weather == Weather.RAIN -> "Rain tonight - your crops will water themselves."
            g.readyCount > 0 -> "Something is ready in the field."
            else -> "Sleep well."
        }
        Ui.text(c, tip, g.vw / 2f, y + h - 92f, 19f, Pal.leafDeep, Paint.Align.CENTER)
        drawButtons(c)
        c.restore()
    }

    // ---------------------------------------------------------------- intro

    fun drawIntro(c: Canvas) {
        layout()
        Ui.scrim(c, g.vw, g.vh, 0.55f)
        beginPop(c)
        val w = min(g.vw - 180f, 700f)
        val h = 400f
        val x = (g.vw - w) / 2f
        val y = g.vh * 0.16f
        Ui.panel(c, x, y, w, h)
        Ui.ribbon(c, g.vw / 2f, y - 18f, 380f, "A letter for you")
        val body = "The old cabin by the river is yours now. It leans a little, " +
            "and the field behind it has gone to grass, but the water still runs clear " +
            "and the woods are full of good things.\n\n" +
            "Take your time. Plant something. Catch something. " +
            "Come see me at the market when your bag gets heavy.\n\n" +
            "- Pip"
        var ty = y + 92f
        for (para in body.split("\n")) {
            if (para.isEmpty()) { ty += 12f; continue }
            lines.clear()
            Ui.wrap(para, 21f, w - 110f, lines)
            for (l in lines) {
                Ui.text(c, l, x + 55f, ty, 21f, Pal.ink, Paint.Align.LEFT)
                ty += 30f
            }
        }
        drawButtons(c)
        c.restore()
    }
}
