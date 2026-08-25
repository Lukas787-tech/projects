package com.cozyhollow.riverside

import android.content.Context
import android.graphics.Canvas
import com.cozyhollow.riverside.gl.Particles3D
import com.cozyhollow.riverside.gl.W3
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object Mode {
    const val TITLE = 0
    const val PLAY = 1
    const val PAUSE = 2
    const val BAG = 3
    const val SHOP = 4
    const val JOURNAL = 5
    const val SETTINGS = 6
    const val SLEEP = 7
    const val CREDITS = 8
    const val INTRO = 9
}

object AKind {
    const val NONE = 0
    const val TILL = 1
    const val PLANT = 2
    const val WATER = 3
    const val HARVEST = 4
    const val CHOP = 5
    const val GATHER = 6
    const val FISH = 7
    const val SHOP = 8
    const val SLEEP = 9
    const val HOOK = 10
    const val REEL = 11
}

class Game(private val ctx: Context, private val host: Host) {

    interface Host {
        fun vibrate(ms: Int)
        fun quitApp()
    }

    var st = GameState()
    var settings: Settings = SaveManager.loadSettings(ctx)
    val sky = MutableSkyKey()
    val player = Player()
    val particles = Particles3D()
    val fishing = Fishing()
    val audio = Audio()
    private val screens = Screens(this)

    // ---- view ----
    var scale = 1f
    var vw = 1280f
    var vh = Ui.DESIGN_H
    var camX = 0f
    var camZ = 0f
    var timeMs = 0f
    var fps = 0f

    // ---- mode ----
    var mode = Mode.TITLE
        private set
    private var prevMode = Mode.TITLE
    var modeT = 1f
        private set
    var titleT = 0f

    // ---- gameplay bits ----
    var selectedSeed: String? = null
    val toasts = ArrayList<Toast>()
    private var coinsAtDayStart = 0
    private var harvestedYesterday = 0
    var shakeTreeIndex = -1
        private set
    var shakeAmount = 0f
        private set
    private var pendingAction = AKind.NONE
    private var pendingTarget = -1
    var screenShake = 0f
        private set
    private var fadeOut = 0f
    private var fadeTarget = 0f
    private var sleepPending = false
    var dayBanner = 0f

    // ---- HUD buttons ----
    val bAction = Btn(102, "").also { it.style = 3 }
    val bBag = Btn(103, "").also { it.style = 3 }
    val bMenu = Btn(104, "").also { it.style = 3 }
    val bSeed = Btn(105, "").also { it.style = 2 }
    private val hudButtons = arrayOf(bBag, bMenu, bSeed, bAction)

    // ---- pointers ----
    private val pActive = BooleanArray(12)
    private val pX = FloatArray(12)
    private val pY = FloatArray(12)
    private var moveX = 0f
    private var moveZ = 0f

    // ---- floating joystick ----
    private var stickPointer = -1
    var stickBaseX = 0f; private set
    var stickBaseY = 0f; private set
    var stickKnobX = 0f; private set
    var stickKnobY = 0f; private set
    val stickActive: Boolean get() = stickPointer >= 0
    var stickHomeX = 0f; private set
    var stickHomeY = 0f; private set
    val stickRadius = 78f
    private var anyHold = false

    init {
        audio.musicVol = settings.music
        audio.sfxVol = settings.sfx
    }

    // ================================================================ setup

    fun onResize(pxW: Int, pxH: Int) {
        scale = pxH / Ui.DESIGN_H
        vw = pxW / scale
        vh = Ui.DESIGN_H
        layoutHud()
    }

    private fun layoutHud() {
        val pad = 26f
        val bigR = 106f
        val moveR = 66f
        val south = settings.southpaw

        val moveBaseX = if (south) vw - pad - moveR * 2f - 200f else pad
        val actBaseX = if (south) pad + 30f else vw - pad - bigR

        stickHomeX = if (south) vw - pad - moveR - 40f else pad + moveR + 40f
        stickHomeY = vh - pad - moveR - 20f
        bAction.set(actBaseX, vh - pad - bigR - 12f, bigR, bigR)
        bBag.set(actBaseX + (bigR - 62f) / 2f, vh - pad - bigR - 96f, 62f, 62f)
        bSeed.set(actBaseX - 96f, vh - pad - bigR - 4f, 84f, 56f)
        bMenu.set(vw - pad - 58f, pad, 58f, 58f)
        if (south) bMenu.set(pad, pad, 58f, 58f)
        bSeed.set(if (south) actBaseX + bigR + 14f else actBaseX - 96f, vh - pad - bigR + 6f, 84f, 60f)
    }

    fun onStart() {
        audio.start()
    }

    fun onStop() {
        if (mode != Mode.TITLE) SaveManager.save(ctx, st)
        SaveManager.saveSettings(ctx, settings)
        audio.stop()
    }

    fun applySettings() {
        audio.musicVol = settings.music
        audio.sfxVol = settings.sfx
        SaveManager.saveSettings(ctx, settings)
        layoutHud()
    }

    // ================================================================ modes

    fun setMode(m: Int) {
        if (m == mode) return
        prevMode = mode
        mode = m
        modeT = 0f
        if (m == Mode.PLAY) clearPointers()
        if (m != Mode.TITLE && m != Mode.PLAY) SaveManager.save(ctx, st)
    }

    fun hasSave(): Boolean = SaveManager.hasSave(ctx)

    fun newGame() {
        st = GameState()
        coinsAtDayStart = st.coins
        player.x = st.playerX
        player.z = st.playerZ
        camX = player.x
        particles.clear()
        selectedSeed = "seed_turnip"
        SaveManager.save(ctx, st)
        setMode(Mode.INTRO)
        dayBanner = 3f
    }

    fun continueGame() {
        val loaded = SaveManager.load(ctx)
        st = loaded ?: GameState()
        coinsAtDayStart = st.coins
        player.x = st.playerX
        player.z = st.playerZ
        camX = player.x
        particles.clear()
        pickDefaultSeed()
        setMode(if (st.introDone) Mode.PLAY else Mode.INTRO)
        dayBanner = 2.4f
    }

    fun saveNow() = SaveManager.save(ctx, st)

    fun gameContext(): Context = ctx

    fun quitToTitle() {
        st.playerX = player.x
        st.playerZ = player.z
        SaveManager.save(ctx, st)
        fishing.cancel()
        setMode(Mode.TITLE)
    }

    fun quitApp() = host.quitApp()

    // ================================================================ update

    fun update(dtRaw: Float) {
        val dt = min(dtRaw, 0.05f)
        timeMs += dt * 1000f
        modeT = min(1f, modeT + dt * 4.2f)
        titleT += dt
        if (dayBanner > 0f) dayBanner -= dt
        if (screenShake > 0f) screenShake = max(0f, screenShake - dt * 2.4f)
        fadeOut += (fadeTarget - fadeOut) * min(1f, dt * 3.4f)

        // sky mood always advances so menus look alive too
        SkyKeys.at(st.timeMin % 1440f, sky)
        val wet = if (st.weather == Weather.CLEAR) 0f else 1f
        sky.applyWeather(st.weather, wet)
        val night = nightAmount()
        audio.mood = night

        when (mode) {
            Mode.TITLE, Mode.CREDITS -> updateTitle(dt)
            Mode.PLAY -> updatePlay(dt)
            else -> updatePaused(dt)
        }

        // toasts
        var i = 0
        while (i < toasts.size) {
            toasts[i].life -= dt
            if (toasts[i].life <= 0f) toasts.removeAt(i) else i++
        }

        particles.update(dt)
        particles.updateAmbient(dt, W3.x(camX), night, st.weather, settings.particleScale)

        if (sleepPending && fadeOut > 0.97f) {
            sleepPending = false
            doSleep()
            fadeTarget = 0f
        }
    }

    private fun updateTitle(dt: Float) {
        // the title screen drifts slowly across the valley at golden hour
        st.timeMin = 1040f + sin(titleT * 0.05f) * 40f
        camX = 1500f + sin(titleT * 0.06f) * 420f
        player.update(dt, 0f, 0f, st)
    }

    private fun updatePaused(dt: Float) {
        player.update(dt, 0f, 0f, st)
        followCamera(dt, snap = false)
    }

    private fun updatePlay(dt: Float) {
        // ---- clock ----
        st.timeMin += dt * 1.7f
        if (st.timeMin >= 1560f) {
            toast("You fell asleep on your feet...", null, Pal.berry)
            beginSleep(exhausted = true)
        }

        // ---- movement ----
        val canWalk = !(fishing.active || screens.blocksInput())
        player.update(dt, if (canWalk) moveX else 0f, if (canWalk) moveZ else 0f, st)
        st.playerX = player.x
        st.playerZ = player.z
        followCamera(dt, snap = false)

        // footstep dust
        if (player.moving && (timeMs % 260f) < dt * 1000f) {
            particles.dust(player.x, player.z)
        }

        // ---- tree shake decay ----
        if (shakeAmount > 0f) {
            shakeAmount -= dt
            if (shakeAmount <= 0f) shakeTreeIndex = -1
        }

        // ---- deferred action payload (fires mid-swing) ----
        if (pendingAction != AKind.NONE && player.action != Act.NONE &&
            player.actionT >= player.actionDur * 0.45f
        ) {
            resolveAction(pendingAction, pendingTarget)
            pendingAction = AKind.NONE
            pendingTarget = -1
        }

        // ---- fishing ----
        if (fishing.active) {
            val s = fishing.update(dt, anyHold, st.timeMin % 1440f, st.weather, st.rodLevel)
            if (s >= 0) {
                audio.play(s)
                if (s == Sfx.BITE) haptic(18)
                if (s == Sfx.CATCH) { onFishCaught(); haptic(34) }
            }
            if (fishing.phase == FPhase.IDLE) player.stopAction()
        }

        // ---- energy trickle when idle & rested ----
        if (st.energy < st.maxEnergy && !player.moving && !player.busy) {
            st.energy = min(st.maxEnergy, st.energy + dt * 0.35f)
        }
    }

    /** Camera tracks x fully and drifts a little with depth, so moving back reads. */
    private fun followCamera(dt: Float, snap: Boolean) {
        val target = (player.x + player.facing * 45f).coerceIn(CAM_MIN, CAM_MAX)
        val targetZ = player.z * 0.30f
        if (snap) { camX = target; camZ = targetZ } else {
            camX = U.lerp(camX, target, min(1f, dt * 4.2f))
            camZ = U.lerp(camZ, targetZ, min(1f, dt * 3.2f))
        }
    }

    companion object {
        /** Keeps the camera from sliding past the ends of the valley. */
        const val CAM_MIN = 520f
        const val CAM_MAX = 3760f
    }

    fun nightAmount(): Float {
        val m = st.timeMin % 1440f
        return when {
            m < 300f -> 1f
            m < 400f -> 1f - U.smoothRange(m, 300f, 400f)
            m < 1090f -> 0f
            m < 1250f -> U.smoothRange(m, 1090f, 1250f)
            else -> 1f
        }
    }

    // ============================================================== actions

    class ActionInfo(var kind: Int, var label: String, var target: Int, var cost: Float)

    private val actionInfo = ActionInfo(AKind.NONE, "", -1, 0f)

    fun currentAction(): ActionInfo {
        val a = actionInfo
        a.kind = AKind.NONE; a.label = ""; a.target = -1; a.cost = 0f
        if (fishing.active) {
            when (fishing.phase) {
                FPhase.BITE -> { a.kind = AKind.HOOK; a.label = "Hook!" }
                FPhase.REEL -> { a.kind = AKind.REEL; a.label = "Reel" }
                else -> { a.kind = AKind.NONE; a.label = "Waiting" }
            }
            return a
        }
        val x = player.x
        val z = player.z

        if (x >= World.RIVER_EDGE - 190f) {
            a.kind = AKind.FISH; a.label = "Fish"; a.cost = 3f; return a
        }
        if (abs(x - World.MARKET_X) < 210f && abs(z - World.MARKET_Z) < 190f) {
            a.kind = AKind.SHOP; a.label = "Market"; return a
        }
        if (abs(x - World.CABIN_DOOR_X) < 150f && abs(z - World.CABIN_DOOR_Z) < 130f) {
            a.kind = AKind.SLEEP; a.label = "Sleep"; return a
        }
        val fi = FarmQuery.nearestForage(st, x, z, 78f)
        if (fi >= 0) { a.kind = AKind.GATHER; a.label = "Gather"; a.target = fi; a.cost = 1f; return a }

        val pi = FarmQuery.nearestPlot(st, x, z, 72f)
        if (pi >= 0) {
            val p = st.plots[pi]
            when {
                !p.tilled -> { a.kind = AKind.TILL; a.label = "Till"; a.target = pi; a.cost = 4f }
                p.cropId == null -> { a.kind = AKind.PLANT; a.label = "Plant"; a.target = pi; a.cost = 1f }
                p.ready -> { a.kind = AKind.HARVEST; a.label = "Harvest"; a.target = pi; a.cost = 2f }
                !p.watered -> { a.kind = AKind.WATER; a.label = "Water"; a.target = pi; a.cost = 2f }
                else -> { a.kind = AKind.NONE; a.label = "Growing" }
            }
            if (a.kind != AKind.NONE) return a
        }
        val ti = FarmQuery.nearestTree(st, x, z, 78f)
        if (ti >= 0) { a.kind = AKind.CHOP; a.label = "Chop"; a.target = ti; a.cost = 5f; return a }
        return a
    }

    private fun tryAction() {
        val a = currentAction()
        when (a.kind) {
            AKind.NONE -> return
            AKind.HOOK -> { if (fishing.hook()) { audio.play(Sfx.TAP); haptic(12) }; return }
            AKind.REEL -> return
            AKind.SHOP -> { audio.play(Sfx.TAP); setMode(Mode.SHOP); return }
            AKind.SLEEP -> { audio.play(Sfx.TAP); beginSleep(false); return }
        }
        if (st.energy < a.cost) {
            toast("Too tired — get some sleep", null, Pal.berry)
            audio.play(Sfx.FAIL)
            return
        }
        when (a.kind) {
            AKind.FISH -> {
                st.useEnergy(a.cost)
                player.facing = 1
                player.startAction(Act.FISH, 999f)
                val target = min(player.x + 220f, World.WORLD_W - 90f)
                    .coerceAtLeast(World.RIVER_EDGE + 70f)
                fishing.cast(target, st.rodLevel, st.weather)
                audio.play(Sfx.WATER)
            }
            AKind.PLANT -> {
                val seed = selectedSeed
                if (seed == null || st.count(seed) <= 0) {
                    pickDefaultSeed()
                    if (selectedSeed == null) {
                        toast("No seeds — visit the market", null, Pal.berry)
                        audio.play(Sfx.FAIL)
                        return
                    }
                }
                st.useEnergy(a.cost)
                player.startAction(Act.PICK, 0.42f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.WATER -> {
                st.useEnergy(a.cost)
                player.startAction(Act.WATER, 0.55f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.HARVEST, AKind.GATHER -> {
                st.useEnergy(a.cost)
                player.startAction(Act.PICK, 0.45f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.TILL, AKind.CHOP -> {
                st.useEnergy(a.cost)
                player.startAction(Act.SWING, if (a.kind == AKind.CHOP) 0.55f else 0.5f)
                pendingAction = a.kind; pendingTarget = a.target
            }
        }
    }

    private fun resolveAction(kind: Int, target: Int) {
        when (kind) {
            AKind.TILL -> {
                st.plots[target].tilled = true
                particles.dust(World.plotX(target), World.plotZ(target))
                audio.play(Sfx.TILL); haptic(14)
            }
            AKind.PLANT -> {
                val seed = selectedSeed ?: return
                if (!st.take(seed, 1)) { toast("Out of ${Catalog.name(seed)}", null, Pal.berry); return }
                val crop = Catalog.cropForSeed(seed) ?: return
                val p = st.plots[target]
                p.cropId = crop.id
                p.growth = 0f
                p.watered = st.weather == Weather.RAIN
                particles.burstHarvest(World.plotX(target), World.plotZ(target), 0.35f, Pal.leaf, 6)
                audio.play(Sfx.PLANT); haptic(10)
                if (st.count(seed) <= 0) pickDefaultSeed()
            }
            AKind.WATER -> {
                val spread = ToolUp.canSpread(st.canLevel)
                var n = 0
                for (k in target until min(target + spread, st.tier.plots)) {
                    val p = st.plots[k]
                    if (p.cropId != null && !p.watered) {
                        p.watered = true
                        particles.burstWater(World.plotX(k), World.plotZ(k), 0.30f)
                        n++
                    }
                }
                if (n == 0) {
                    val p = st.plots[target]
                    if (p.cropId != null && !p.watered) { p.watered = true; n = 1 }
                }
                audio.play(Sfx.WATER); haptic(10)
            }
            AKind.HARVEST -> {
                val p = st.plots[target]
                val crop = Catalog.crops[p.cropId ?: return] ?: return
                val amount = crop.yieldMin + (U.hash(st.day * 71 + target) * (crop.yieldMax - crop.yieldMin + 1)).toInt()
                    .coerceAtMost(crop.yieldMax - crop.yieldMin)
                if (!st.add(crop.produceId, amount)) {
                    toast("Your bag is full!", null, Pal.berry)
                    audio.play(Sfx.FAIL)
                    return
                }
                st.totalHarvest += amount
                st.seenCrops.add(crop.id)
                harvestedYesterday += amount
                val item = Catalog.item(crop.produceId)
                particles.burstHarvest(World.plotX(target), World.plotZ(target), 0.55f, item.a, 12)
                toast("+$amount ${item.name}", crop.produceId, Pal.ink)
                audio.play(Sfx.HARVEST); haptic(20)
                if (crop.regrow && !p.regrown) {
                    p.regrown = true
                    p.growth = crop.days * 0.45f
                    p.watered = false
                } else {
                    p.clear()
                }
            }
            AKind.GATHER -> {
                val spot = World.forage[target]
                if (!st.add(spot.itemId, 1)) {
                    toast("Your bag is full!", null, Pal.berry)
                    audio.play(Sfx.FAIL)
                    return
                }
                st.foragePicked[target] = st.day
                val item = Catalog.item(spot.itemId)
                particles.burstHarvest(spot.x, spot.z, 0.5f, item.a, 9)
                toast("+1 ${item.name}", spot.itemId, Pal.ink)
                audio.play(Sfx.HARVEST); haptic(14)
            }
            AKind.CHOP -> {
                val t = World.trees[target]
                shakeTreeIndex = target
                shakeAmount = 0.34f
                particles.burstChop(t.x, t.z, 1.7f * t.scale)
                audio.play(Sfx.CHOP); haptic(22)
                screenShake = 0.35f
                val key = target * 13 + st.day
                chopCounter[target] = (chopCounter[target] ?: 0) + 1
                if (chopCounter[target]!! >= ToolUp.axeChops(st.axeLevel)) {
                    chopCounter[target] = 0
                    val wood = 2 + (U.hash(key) * 3f).toInt()
                    val stones = if (U.hash(key + 5) < 0.3f) 1 else 0
                    st.add("wood", wood)
                    if (stones > 0) st.add("stone", stones)
                    st.totalChopped++
                    st.treeRegrow[target] = st.day + 3 + (U.hash(key + 9) * 3f).toInt()
                    toast("+$wood Wood", "wood", Pal.ink)
                    particles.burstHarvest(t.x, t.z, 1.1f, Pal.woodDark, 14)
                }
            }
        }
    }

    private val chopCounter = HashMap<Int, Int>()

    private fun onFishCaught() {
        val id = fishing.fishId ?: return
        if (!st.add(id, 1)) {
            toast("Your bag is full!", null, Pal.berry)
            return
        }
        st.totalFish++
        val isNew = st.seenFish.add(id)
        if (isNew) toast("New species: ${Catalog.name(id)}!", id, Pal.goldDeep)
        else toast("+1 ${Catalog.name(id)}", id, Pal.ink)
        particles.splash(fishing.bobX, player.z, 1.4f)
        particles.hearts(player.x, player.z, 1.9f, 3)
    }

    // ================================================================ sleep

    fun beginSleep(exhausted: Boolean) {
        if (sleepPending) return
        sleepPending = true
        fadeTarget = 1f
        exhaustedSleep = exhausted
        fishing.cancel()
        player.stopAction()
        audio.play(Sfx.SLEEP)
    }

    private var exhaustedSleep = false

    private fun doSleep() {
        val raining = st.weather == Weather.RAIN
        harvestedYesterday = 0
        var grew = 0
        for (i in 0 until st.tier.plots) {
            val p = st.plots[i]
            val cid = p.cropId ?: continue
            val crop = Catalog.crops[cid] ?: continue
            if (p.growth < crop.days) {
                p.growth += if (p.watered || raining) 1f else 0.35f
                if (p.growth >= crop.days) { p.growth = crop.days.toFloat(); grew++ }
            }
            p.watered = false
        }
        st.day++
        st.weather = Weather.roll(st.day)
        if (st.weather == Weather.RAIN) {
            for (i in 0 until st.tier.plots) if (st.plots[i].cropId != null) st.plots[i].watered = true
        }
        st.timeMin = 6f * 60f
        st.energy = if (exhaustedSleep) st.maxEnergy * 0.55f else st.maxEnergy
        readyCount = countReady()
        coinsEarnedToday = st.coins - coinsAtDayStart
        coinsAtDayStart = st.coins
        chopCounter.clear()
        player.x = World.CABIN_DOOR_X
        player.z = World.CABIN_DOOR_Z
        st.playerX = player.x
        st.playerZ = player.z
        followCamera(0f, snap = true)
        SaveManager.save(ctx, st)
        setMode(Mode.SLEEP)
        dayBanner = 0f
    }

    var readyCount = 0
        private set
    var coinsEarnedToday = 0
        private set

    fun countReady(): Int {
        var n = 0
        for (i in 0 until st.tier.plots) if (st.plots[i].ready) n++
        return n
    }

    fun wakeUp() {
        setMode(Mode.PLAY)
        dayBanner = 2.6f
    }

    // ============================================================ commerce

    fun sell(id: String, qty: Int) {
        val have = st.count(id)
        val n = min(qty, have)
        if (n <= 0) return
        st.take(id, n)
        val gain = Catalog.price(id) * n
        st.earn(gain)
        if (gain > st.biggestSale) st.biggestSale = gain
        particles.burstCoins(player.x, player.z, 1.3f, min(10, 3 + n))
        toast("+${U.formatCoins(gain)} coins", null, Pal.goldDeep)
        audio.play(Sfx.COIN)
        haptic(12)
    }

    fun sellAll(filterCat: Int) {
        var gain = 0
        val ids = st.inv.keys.toList()
        for (id in ids) {
            val item = Catalog.items[id] ?: continue
            if (item.cat == Cat.SEED) continue
            if (filterCat >= 0 && item.cat != filterCat) continue
            val n = st.count(id)
            gain += item.price * n
            st.take(id, n)
        }
        if (gain <= 0) { toast("Nothing to sell", null, Pal.inkSoft); audio.play(Sfx.FAIL); return }
        st.earn(gain)
        if (gain > st.biggestSale) st.biggestSale = gain
        particles.burstCoins(player.x, player.z, 1.3f, 12)
        toast("+${U.formatCoins(gain)} coins", null, Pal.goldDeep)
        audio.play(Sfx.COIN); haptic(18)
    }

    fun buySeed(crop: Crop, qty: Int) {
        val cost = crop.seedCost * qty
        if (st.coins < cost) { toast("Not enough coins", null, Pal.berry); audio.play(Sfx.FAIL); return }
        if (!st.hasRoomFor(crop.seedId)) { toast("Your bag is full!", null, Pal.berry); audio.play(Sfx.FAIL); return }
        st.spend(cost)
        st.add(crop.seedId, qty)
        if (selectedSeed == null) selectedSeed = crop.seedId
        toast("+$qty ${Catalog.name(crop.seedId)}", crop.seedId, Pal.ink)
        audio.play(Sfx.TAP); haptic(10)
    }

    fun upgradeCabin() {
        val next = Tiers.next(st.cabinLevel)
        if (next == null) { toast("Your home is complete", null, Pal.inkSoft); return }
        if (st.coins < next.coins || st.count("wood") < next.wood || st.count("stone") < next.stone) {
            toast("Not enough materials", null, Pal.berry); audio.play(Sfx.FAIL); return
        }
        st.spend(next.coins)
        st.take("wood", next.wood)
        st.take("stone", next.stone)
        st.cabinLevel = next.level
        st.energy = st.maxEnergy
        particles.burstHarvest(World.CABIN_X, World.CABIN_Z, 3.2f, Pal.gold, 22)
        particles.hearts(World.CABIN_X, World.CABIN_Z, 3.8f, 5)
        toast("Home upgraded: ${next.name}!", null, Pal.goldDeep)
        audio.play(Sfx.UPGRADE); haptic(40)
        SaveManager.save(ctx, st)
    }

    fun buyTool(which: Int) {
        val (level, cost) = when (which) {
            0 -> st.rodLevel to ToolUp.rodCost(st.rodLevel + 1)
            1 -> st.canLevel to ToolUp.canCost(st.canLevel + 1)
            else -> st.axeLevel to ToolUp.axeCost(st.axeLevel + 1)
        }
        if (level >= 3) { toast("Already the finest", null, Pal.inkSoft); return }
        if (st.coins < cost) { toast("Not enough coins", null, Pal.berry); audio.play(Sfx.FAIL); return }
        st.spend(cost)
        when (which) {
            0 -> st.rodLevel++
            1 -> st.canLevel++
            else -> st.axeLevel++
        }
        val name = when (which) {
            0 -> ToolUp.rodName(st.rodLevel)
            1 -> ToolUp.canName(st.canLevel)
            else -> ToolUp.axeName(st.axeLevel)
        }
        toast("Upgraded to $name!", null, Pal.goldDeep)
        audio.play(Sfx.UPGRADE); haptic(30)
    }

    fun eat(id: String) {
        val item = Catalog.items[id] ?: return
        if (item.food <= 0) { toast("That isn't food", null, Pal.inkSoft); return }
        if (st.energy >= st.maxEnergy) { toast("You're not hungry", null, Pal.inkSoft); return }
        if (!st.take(id, 1)) return
        st.energy = min(st.maxEnergy, st.energy + item.food)
        particles.hearts(player.x, player.z, 1.8f, 2)
        toast("+${item.food} energy", id, Pal.leafDeep)
        audio.play(Sfx.HARVEST)
    }

    // =============================================================== helpers

    fun toast(text: String, itemId: String?, color: Int) {
        if (toasts.size > 4) toasts.removeAt(0)
        toasts.add(Toast(text, itemId, color))
    }

    fun haptic(ms: Int) {
        if (settings.haptics) host.vibrate(ms)
    }

    fun pickDefaultSeed() {
        if (selectedSeed != null && st.count(selectedSeed!!) > 0) return
        selectedSeed = st.inv.keys.firstOrNull { Catalog.items[it]?.cat == Cat.SEED && st.count(it) > 0 }
    }

    fun cycleSeed() {
        val seeds = st.inv.keys.filter { Catalog.items[it]?.cat == Cat.SEED && st.count(it) > 0 }
        if (seeds.isEmpty()) { selectedSeed = null; toast("No seeds in your bag", null, Pal.inkSoft); return }
        val idx = seeds.indexOf(selectedSeed)
        selectedSeed = seeds[(idx + 1) % seeds.size]
        audio.play(Sfx.TAP)
    }

    fun currentGoal(): String {
        if (st.plots.none { it.cropId != null } && st.tier.plots > 0) return "Till a plot, then plant a seed"
        if (st.totalFish == 0) return "Cast a line at the river"
        if (st.totalEarned < 200) return "Sell your goods at the market"
        val next = Tiers.next(st.cabinLevel)
        if (next != null) return "Upgrade your home to ${next.name}"
        if (st.seenFish.size < Catalog.fish.size) return "Complete your fishing journal"
        return "Enjoy the valley"
    }

    // ================================================================ input

    private fun clearPointers() {
        for (i in pActive.indices) pActive[i] = false
        releaseStick()
        anyHold = false
    }

    fun onPointerDown(id: Int, x: Float, y: Float) {
        if (id in pActive.indices) { pActive[id] = true; pX[id] = x; pY[id] = y }
        recomputePointers()
        if (screens.onDown(x, y)) return
        if (mode != Mode.PLAY) return
        // HUD taps
        if (bMenu.hit(x, y)) { bMenu.press = 1f; return }
        if (bBag.hit(x, y)) { bBag.press = 1f; return }
        if (bSeed.hit(x, y)) { bSeed.press = 1f; return }
        if (bAction.hit(x, y)) { bAction.press = 1f; return }
        // anywhere on the walking half of the screen drops the stick under your thumb
        if (stickPointer < 0 && inStickZone(x, y)) {
            stickPointer = id
            stickBaseX = x.coerceIn(stickRadius + 12f, vw - stickRadius - 12f)
            stickBaseY = y.coerceIn(stickRadius + 12f, vh - stickRadius - 12f)
            stickKnobX = stickBaseX
            stickKnobY = stickBaseY
            moveX = 0f; moveZ = 0f
        }
    }

    /** The half of the screen given over to walking, clear of the HUD buttons. */
    private fun inStickZone(x: Float, y: Float): Boolean {
        if (y < 150f) return false
        return if (settings.southpaw) x > vw * 0.5f else x < vw * 0.5f
    }

    private fun updateStick(x: Float, y: Float) {
        var dx = x - stickBaseX
        var dy = y - stickBaseY
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len > stickRadius) {
            dx = dx / len * stickRadius
            dy = dy / len * stickRadius
        }
        stickKnobX = stickBaseX + dx
        stickKnobY = stickBaseY + dy
        // a small dead zone stops the farmer twitching when your thumb rests
        val mag = kotlin.math.sqrt(dx * dx + dy * dy) / stickRadius
        if (mag < 0.16f) {
            moveX = 0f; moveZ = 0f
        } else {
            moveX = dx / stickRadius
            moveZ = dy / stickRadius
        }
    }

    fun onPointerMove(id: Int, x: Float, y: Float) {
        if (id in pActive.indices && pActive[id]) { pX[id] = x; pY[id] = y }
        if (id == stickPointer) updateStick(x, y)
        recomputePointers()
        screens.onMove(x, y)
    }

    fun onPointerUp(id: Int, x: Float, y: Float) {
        if (id in pActive.indices) pActive[id] = false
        if (id == stickPointer) releaseStick()
        recomputePointers()
        if (screens.onUp(x, y)) return
        if (mode != Mode.PLAY) return
        if (bMenu.hit(x, y) && bMenu.press > 0f) { audio.play(Sfx.TAP); setMode(Mode.PAUSE) }
        else if (bBag.hit(x, y) && bBag.press > 0f) { audio.play(Sfx.TAP); setMode(Mode.BAG) }
        else if (bSeed.hit(x, y) && bSeed.press > 0f) cycleSeed()
        else if (bAction.hit(x, y) && bAction.press > 0f) tryAction()
        for (b in hudButtons) b.press = 0f
    }

    private fun releaseStick() {
        stickPointer = -1
        moveX = 0f; moveZ = 0f
    }

    fun onCancelTouch() {
        clearPointers()
        for (b in hudButtons) b.press = 0f
        screens.onCancel()
    }

    private fun recomputePointers() {
        var hold = false
        for (i in pActive.indices) if (pActive[i]) { hold = true; break }
        anyHold = hold
    }

    fun onBack(): Boolean {
        return when (mode) {
            Mode.PLAY -> { audio.play(Sfx.BACK); setMode(Mode.PAUSE); true }
            Mode.TITLE -> false
            Mode.CREDITS -> { audio.play(Sfx.BACK); setMode(Mode.TITLE); true }
            Mode.SLEEP -> true
            Mode.INTRO -> { st.introDone = true; setMode(Mode.PLAY); true }
            else -> { audio.play(Sfx.BACK); setMode(Mode.PLAY); true }
        }
    }

    // ================================================================= draw

    /** The flat layer: HUD, menus and toasts, rasterised at the pixel resolution. */
    fun drawUi(c: Canvas) {
        drawOverlays(c)
    }

    /** World x of whatever the action button will act on, or NaN when nothing is. */
    fun hintTargetX(): Float {
        if (mode != Mode.PLAY || fishing.active) return Float.NaN
        val a = currentAction()
        return when (a.kind) {
            AKind.TILL, AKind.PLANT, AKind.WATER, AKind.HARVEST -> World.plotX(a.target)
            AKind.CHOP -> World.trees[a.target].x
            AKind.GATHER -> World.forage[a.target].x
            AKind.SHOP -> World.MARKET_X
            AKind.SLEEP -> World.CABIN_DOOR_X
            else -> Float.NaN
        }
    }

    /** World z of the same target. */
    fun hintTargetZ(): Float {
        val a = currentAction()
        return when (a.kind) {
            AKind.TILL, AKind.PLANT, AKind.WATER, AKind.HARVEST -> World.plotZ(a.target)
            AKind.CHOP -> World.trees[a.target].z
            AKind.GATHER -> World.forage[a.target].z
            AKind.SHOP -> World.MARKET_Z
            AKind.SLEEP -> World.CABIN_DOOR_Z
            else -> 0f
        }
    }

    /** Height in metres at which to float that marker. */
    fun hintHeight(): Float = when (currentAction().kind) {
        AKind.CHOP -> 3.8f
        AKind.SHOP -> 4.6f
        AKind.SLEEP -> 2.9f
        AKind.HARVEST -> 1.4f
        else -> 1.0f
    }

    private fun drawOverlays(c: Canvas) {
        when (mode) {
            Mode.TITLE -> screens.drawTitle(c)
            Mode.CREDITS -> screens.drawCredits(c)
            Mode.PLAY -> {
                drawHud(c)
                if (fishing.active) fishing.drawUi(c, vw, vh, timeMs)
            }
            Mode.PAUSE -> { drawHud(c); screens.drawPause(c) }
            Mode.BAG -> { drawHud(c); screens.drawBag(c) }
            Mode.SHOP -> { drawHud(c); screens.drawShop(c) }
            Mode.JOURNAL -> { drawHud(c); screens.drawJournal(c) }
            Mode.SETTINGS -> { drawHud(c); screens.drawSettings(c) }
            Mode.SLEEP -> screens.drawSleep(c)
            Mode.INTRO -> { drawHud(c); screens.drawIntro(c) }
        }
        Ui.toasts(c, toasts, vw / 2f, 96f)
        if (dayBanner > 0f && mode == Mode.PLAY) drawDayBanner(c)
        if (fadeOut > 0.01f) Ui.scrim(c, vw, vh, fadeOut)
        if (settings.showFps) {
            Ui.text(c, "${fps.toInt()} fps", vw - 12f, vh - 12f, 18f,
                U.withAlpha(Pal.cream, 0.6f), Paint.Align.RIGHT)
        }
    }

    private fun drawDayBanner(c: Canvas) {
        val a = U.clamp01(dayBanner) * U.clamp01((2.8f - dayBanner) * 3f)
        val cx = vw / 2f
        Ui.textOut(c, "Day ${st.day}", cx, vh * 0.30f, 58f, Pal.cream, Pal.woodDeep,
            Paint.Align.CENTER, Ui.display, 8f, a)
        Ui.textOut(c, Weather.name(st.weather), cx, vh * 0.30f + 38f, 26f, Pal.gold, Pal.woodDeep,
            Paint.Align.CENTER, Ui.body, 5f, a)
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        val pad = 26f

        // ---- coins ----
        Ui.pill(c, pad, pad, 190f, 52f, U.withAlpha(Pal.woodDeep, 0.88f))
        Ui.coin(c, pad + 28f, pad + 26f, 17f)
        Ui.text(c, U.formatCoins(st.coins), pad + 54f, pad + 35f, 27f, Pal.gold, Paint.Align.LEFT, Ui.body)

        // ---- day + clock ----
        Ui.pill(c, pad, pad + 62f, 236f, 44f, U.withAlpha(Pal.woodDeep, 0.80f))
        Ui.text(c, "Day ${st.day}", pad + 18f, pad + 62f + 30f, 22f, Pal.cream, Paint.Align.LEFT)
        Ui.text(c, U.formatTime(st.timeMin % 1440f), pad + 120f, pad + 62f + 30f, 22f,
            U.withAlpha(Pal.cream, 0.9f), Paint.Align.LEFT)
        weatherGlyph(c, pad + 210f, pad + 62f + 22f)

        // ---- energy ----
        val ex = pad
        val ey = pad + 116f
        Ui.text(c, "Energy", ex + 2f, ey + 2f, 17f, U.withAlpha(Pal.cream, 0.85f), Paint.Align.LEFT)
        Ui.bar(c, ex, ey + 10f, 200f, 20f, st.energy / st.maxEnergy,
            if (st.energy / st.maxEnergy < 0.25f) Pal.berry else Pal.leaf)

        // ---- location + goal ----
        val zone = World.zoneName(World.zoneAt(player.x))
        Ui.textOut(c, zone, vw / 2f, pad + 34f, 26f, Pal.cream, U.withAlpha(Pal.shadow, 0.7f),
            Paint.Align.CENTER, Ui.display, 5f, 0.92f)
        Ui.text(c, currentGoal(), vw / 2f, pad + 60f, 17f, U.withAlpha(Pal.cream, 0.72f), Paint.Align.CENTER)

        if (mode != Mode.PLAY) return

        // ---- walking stick ----
        drawStick(c)

        // ---- action ----
        val a = currentAction()
        bAction.enabled = a.kind != AKind.NONE
        val accent = when (a.kind) {
            AKind.FISH, AKind.REEL, AKind.HOOK -> Color.parseColor("#4B96B4")
            AKind.SHOP -> Pal.gold
            AKind.SLEEP -> Color.parseColor("#8A7BB8")
            AKind.CHOP -> Pal.woodDark
            AKind.NONE -> U.shade(Pal.woodDeep, 1.2f)
            else -> Pal.leaf
        }
        bAction.accent = accent
        bAction.label = if (a.kind == AKind.NONE) "..." else a.label
        bAction.enabled = true
        Ui.button(c, bAction, if (a.kind == AKind.NONE) 0.55f else 1f)
        if (a.cost > 0f) {
            Ui.text(c, "-${a.cost.toInt()}", bAction.cx, bAction.y + bAction.h + 22f, 16f,
                U.withAlpha(Pal.cream, 0.75f), Paint.Align.CENTER)
        }

        // ---- bag ----
        bBag.accent = Pal.woodDark
        bBag.label = ""
        Ui.button(c, bBag)
        bagGlyph(c, bBag.cx, bBag.cy + bBag.press * 5f)

        // ---- seed chip ----
        val seed = selectedSeed
        bSeed.visible = a.kind == AKind.PLANT || a.kind == AKind.TILL
        if (bSeed.visible) {
            Ui.button(c, bSeed)
            if (seed != null) {
                IconDraw.draw(c, Catalog.item(seed), bSeed.cx, bSeed.cy - 6f + bSeed.press * 5f, 38f, Ui.txt)
                Ui.text(c, "x${st.count(seed)}", bSeed.cx, bSeed.y + bSeed.h - 8f + bSeed.press * 5f, 15f,
                    Pal.ink, Paint.Align.CENTER)
            } else {
                Ui.text(c, "no\nseed", bSeed.cx, bSeed.cy + 4f, 15f, Pal.inkSoft, Paint.Align.CENTER)
            }
        }

        // ---- menu ----
        bMenu.accent = Pal.woodDark
        bMenu.label = ""
        Ui.button(c, bMenu)
        menuGlyph(c, bMenu.cx, bMenu.cy + bMenu.press * 5f)
    }

    /** Floating thumbstick: rests in a corner, jumps to wherever you press. */
    private fun drawStick(c: Canvas) {
        val bx = if (stickActive) stickBaseX else stickHomeX
        val by = if (stickActive) stickBaseY else stickHomeY
        val kx = if (stickActive) stickKnobX else stickHomeX
        val ky = if (stickActive) stickKnobY else stickHomeY
        val a = if (stickActive) 1f else 0.5f

        glyphPaint.style = Paint.Style.FILL
        glyphPaint.color = U.withAlpha(Pal.shadow, 0.26f * a)
        c.drawCircle(bx, by + 4f, stickRadius, glyphPaint)
        glyphPaint.color = U.withAlpha(U.shade(Pal.woodDeep, 1.15f), 0.62f * a)
        c.drawCircle(bx, by, stickRadius, glyphPaint)
        glyphPaint.color = U.withAlpha(Pal.woodDeep, 0.5f * a)
        c.drawCircle(bx, by, stickRadius - 7f, glyphPaint)

        // four little pips so it reads as a direction pad
        glyphPaint.color = U.withAlpha(Pal.cream, 0.32f * a)
        val pip = stickRadius - 20f
        c.drawCircle(bx, by - pip, 4f, glyphPaint)
        c.drawCircle(bx, by + pip, 4f, glyphPaint)
        c.drawCircle(bx - pip, by, 4f, glyphPaint)
        c.drawCircle(bx + pip, by, 4f, glyphPaint)

        glyphPaint.color = U.withAlpha(Pal.shadow, 0.3f * a)
        c.drawCircle(kx, ky + 5f, 34f, glyphPaint)
        glyphPaint.color = U.withAlpha(Pal.cream, 0.94f * a)
        c.drawCircle(kx, ky, 33f, glyphPaint)
        glyphPaint.color = U.withAlpha(U.shade(Pal.wood, 1.05f), 0.95f * a)
        c.drawCircle(kx, ky, 25f, glyphPaint)
    }

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun bagGlyph(c: Canvas, x: Float, y: Float) {
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 3.2f
        glyphPaint.strokeCap = Paint.Cap.ROUND
        glyphPaint.color = Pal.cream
        // shoulder straps
        c.drawArc(x - 11f, y - 21f, x + 11f, y - 1f, 200f, 140f, false, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
        // body
        c.drawRoundRect(x - 15f, y - 11f, x + 15f, y + 16f, 7f, 7f, glyphPaint)
        // flap
        glyphPaint.color = Pal.woodDeep
        c.drawRoundRect(x - 15f, y - 11f, x + 15f, y + 2f, 7f, 7f, glyphPaint)
        glyphPaint.color = Pal.cream
        c.drawRoundRect(x - 15f, y + 4f, x + 15f, y + 16f, 6f, 6f, glyphPaint)
        // buckle
        glyphPaint.color = Pal.gold
        c.drawRoundRect(x - 4f, y - 1f, x + 4f, y + 7f, 2.4f, 2.4f, glyphPaint)
    }

    private fun menuGlyph(c: Canvas, x: Float, y: Float) {
        glyphPaint.style = Paint.Style.FILL
        glyphPaint.color = Pal.cream
        for (i in -1..1) {
            c.drawRoundRect(x - 15f, y + i * 9f - 2.4f, x + 15f, y + i * 9f + 2.4f, 2.4f, 2.4f, glyphPaint)
        }
    }

    private fun weatherGlyph(c: Canvas, x: Float, y: Float) {
        glyphPaint.style = Paint.Style.FILL
        when (st.weather) {
            Weather.RAIN -> {
                glyphPaint.color = U.withAlpha(Pal.cream, 0.9f)
                c.drawCircle(x - 6f, y, 9f, glyphPaint)
                c.drawCircle(x + 5f, y - 2f, 7f, glyphPaint)
                glyphPaint.color = Color.parseColor("#8FC7E8")
                for (i in -1..1) c.drawRoundRect(x + i * 7f - 1.6f, y + 8f, x + i * 7f + 1.6f, y + 16f, 1.6f, 1.6f, glyphPaint)
            }
            Weather.CLOUDY -> {
                glyphPaint.color = U.withAlpha(Pal.cream, 0.9f)
                c.drawCircle(x - 6f, y + 2f, 9f, glyphPaint)
                c.drawCircle(x + 5f, y, 7f, glyphPaint)
            }
            else -> {
                glyphPaint.color = Pal.gold
                c.drawCircle(x, y + 1f, 9f, glyphPaint)
                glyphPaint.style = Paint.Style.STROKE
                glyphPaint.strokeWidth = 2.6f
                glyphPaint.strokeCap = Paint.Cap.ROUND
                for (i in 0 until 8) {
                    val ang = i * 45f * 0.017453f
                    val dx = kotlin.math.cos(ang); val dy = kotlin.math.sin(ang)
                    c.drawLine(x + dx * 12f, y + 1f + dy * 12f, x + dx * 16f, y + 1f + dy * 16f, glyphPaint)
                }
            }
        }
    }
}
