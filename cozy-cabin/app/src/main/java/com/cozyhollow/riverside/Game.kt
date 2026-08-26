package com.cozyhollow.riverside

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.cozyhollow.riverside.gl.Particles3D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
    const val SIT = 10
    const val STAND = 11
    const val REEL_IN = 12
}

/**
 * The game.
 *
 * There is nothing here to lose. No stamina bar empties, no bag fills up, no
 * fish gets away, no crop wilts, and the clock cannot run out on you. The only
 * things the player can do are pleasant ones, so the whole loop is: wander,
 * potter, watch the light change, go to bed when you feel like it.
 */
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
    var timeMs = 0f
    var fps = 0f

    // ---- camera ----
    /** Compass heading of the camera, degrees. 0 looks north up the valley. */
    var camYaw = 0f
        private set
    var camTX = 0f; private set
    var camTY = 0f; private set
    var camTZ = 0f; private set
    var camDist = 9.6f; private set
    var camHeight = 5.2f; private set
    private var camYawTarget = 0f
    private var restDrift = 0f

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
    private val castTmp = FloatArray(2)

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

    // ---- camera drag ----
    private var lookPointer = -1
    private var lookLastX = 0f

    init {
        audio.musicVol = settings.music
        audio.sfxVol = settings.sfx
        player.placeAt(World.SPAWN_X, World.SPAWN_Z)
        snapCamera()
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

        val actBaseX = if (south) pad + 30f else vw - pad - bigR
        stickHomeX = if (south) vw - pad - moveR - 40f else pad + moveR + 40f
        stickHomeY = vh - pad - moveR - 20f
        bAction.set(actBaseX, vh - pad - bigR - 12f, bigR, bigR)
        bBag.set(actBaseX + (bigR - 62f) / 2f, vh - pad - bigR - 96f, 62f, 62f)
        bMenu.set(if (south) pad else vw - pad - 58f, pad, 58f, 58f)
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
        player.placeAt(st.playerX, st.playerZ)
        particles.clear()
        selectedSeed = "seed_turnip"
        snapCamera()
        SaveManager.save(ctx, st)
        setMode(Mode.INTRO)
        dayBanner = 3f
    }

    fun continueGame() {
        val loaded = SaveManager.load(ctx)
        st = loaded ?: GameState()
        coinsAtDayStart = st.coins
        player.placeAt(st.playerX, st.playerZ)
        particles.clear()
        pickDefaultSeed()
        snapCamera()
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

        var i = 0
        while (i < toasts.size) {
            toasts[i].life -= dt
            if (toasts[i].life <= 0f) toasts.removeAt(i) else i++
        }

        particles.update(dt)
        particles.updateAmbient(dt, player.x, player.z, night, st.weather, settings.particleScale)

        if (sleepPending && fadeOut > 0.97f) {
            sleepPending = false
            doSleep()
            fadeTarget = 0f
        }
    }

    private fun updateTitle(dt: Float) {
        // the title screen drifts slowly round the hollow at golden hour
        st.timeMin = 1050f + sin(titleT * 0.05f) * 30f
        camYaw = (titleT * 4.5f) % 360f
        camYawTarget = camYaw
        camTX = U.lerp(camTX, World.CABIN_X + 1.5f, min(1f, dt * 1.4f))
        camTZ = U.lerp(camTZ, World.CABIN_Z + 3.0f, min(1f, dt * 1.4f))
        camTY = Terrain.height(camTX, camTZ)
        camDist = 13f
        camHeight = 6.4f
        player.update(dt, 0f, 0f, st)
    }

    private fun updatePaused(dt: Float) {
        player.update(dt, 0f, 0f, st)
        followCamera(dt)
    }

    private fun updatePlay(dt: Float) {
        // ---- clock ----
        // a full day takes about twenty minutes, and resting speeds it along
        val clockRate = if (player.sitting) 7f else 1.25f
        st.timeMin += dt * clockRate
        if (st.timeMin >= 1560f) {
            toast("The stars are out — time for bed", null, Pal.sky)
            beginSleep()
        }

        // ---- movement, relative to wherever the camera is looking ----
        val canWalk = !(fishing.active || screens.blocksInput())
        val yawRad = Math.toRadians(camYaw.toDouble())
        val sy = sin(yawRad).toFloat()
        val cy = cos(yawRad).toFloat()
        // The camera sits at +z looking up the valley, so away from it is -z.
        // Push the stick up and you walk into the scene; pull it down and you
        // walk back toward yourself. Both z terms carried the wrong sign, so
        // the whole vertical axis was upside down.
        val dirX = if (canWalk) (moveZ * sy + moveX * cy) else 0f
        val dirZ = if (canWalk) (moveZ * cy - moveX * sy) else 0f
        player.update(dt, dirX, dirZ, st)
        st.playerX = player.x
        st.playerZ = player.z
        followCamera(dt)

        if (player.moving && (timeMs % 300f) < dt * 1000f) {
            particles.dust(player.x, player.z)
        }

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
            val s = fishing.update(dt, st.timeMin % 1440f, st.weather, st.rodLevel)
            if (s >= 0) {
                audio.play(s)
                if (s == Sfx.CATCH) { onFishCaught(); haptic(24) }
            }
            if (fishing.phase == FPhase.IDLE) player.stopAction()
        }
    }

    // =============================================================== camera

    private fun snapCamera() {
        camTX = player.x
        camTZ = player.z
        camTY = Terrain.groundY(player.x, player.z)
        camDist = 9.6f
        camHeight = 5.2f
        // face north up the valley, so a new day always starts on a known
        // heading rather than wherever the title screen's slow spin left off
        camYaw = 0f
        camYawTarget = 0f
    }

    /** The camera follows a step behind, and turns only when you turn it. */
    private fun followCamera(dt: Float) {
        val k = min(1f, dt * 5.0f)
        camTX = U.lerp(camTX, player.x, k)
        camTZ = U.lerp(camTZ, player.z, k)
        camTY = U.lerp(camTY, player.y, min(1f, dt * 3.4f))

        // sitting on a bench pulls the view out and turns it slowly, like a
        // held breath: the whole point of the bench is the view from it
        if (player.sitting) {
            restDrift += dt * 3.2f
            camYawTarget += dt * 3.2f
            camDist = U.lerp(camDist, 7.4f, min(1f, dt * 1.2f))
            camHeight = U.lerp(camHeight, 3.6f, min(1f, dt * 1.2f))
        } else {
            restDrift = 0f
            camDist = U.lerp(camDist, if (fishing.active) 8.2f else 9.6f, min(1f, dt * 1.6f))
            camHeight = U.lerp(camHeight, if (fishing.active) 4.2f else 5.2f, min(1f, dt * 1.6f))
        }
        camYaw = U.lerpAngle(camYaw, camYawTarget, min(1f, dt * 8f))
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

    /** How hard the breeze is blowing through the grass right now. */
    fun windAmount(): Float {
        val gust = 0.6f + 0.4f * sin(timeMs * 0.00013f) + 0.2f * sin(timeMs * 0.00047f)
        return when (st.weather) {
            Weather.RAIN -> 0.34f * gust
            Weather.CLOUDY -> 0.24f * gust
            else -> 0.17f * gust
        }
    }

    // ============================================================== actions

    class ActionInfo(var kind: Int, var label: String, var target: Int)

    private val actionInfo = ActionInfo(AKind.NONE, "", -1)

    private fun nearestBench(x: Float, z: Float, reach: Float): Int {
        var best = -1
        var bestD = reach * reach
        for (i in World.props.indices) {
            val p = World.props[i]
            if (p.kind != World.PKind.BENCH) continue
            val dx = x - p.x
            val dz = z - p.z
            val d = dx * dx + dz * dz
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun currentAction(): ActionInfo {
        val a = actionInfo
        a.kind = AKind.NONE; a.label = ""; a.target = -1
        if (player.sitting) {
            a.kind = AKind.STAND; a.label = "Get up"
            return a
        }
        if (fishing.active) {
            a.kind = AKind.REEL_IN
            a.label = if (fishing.phase == FPhase.CAUGHT) "..." else "Reel in"
            return a
        }
        val x = player.x
        val z = player.z

        // a bed is 1.5 m across, so 1.8 m from its centre meant standing almost
        // on top of it before the button woke up
        val pi = FarmQuery.nearestPlot(st, x, z, 2.2f)
        if (pi >= 0) {
            val p = st.plots[pi]
            when {
                !p.tilled -> { a.kind = AKind.TILL; a.label = "Till"; a.target = pi }
                p.cropId == null -> { a.kind = AKind.PLANT; a.label = "Plant"; a.target = pi }
                p.ready -> { a.kind = AKind.HARVEST; a.label = "Harvest"; a.target = pi }
                !p.watered -> { a.kind = AKind.WATER; a.label = "Water"; a.target = pi }
                else -> { a.kind = AKind.NONE; a.label = "Growing" }
            }
            if (a.kind != AKind.NONE) return a
        }
        val fi = FarmQuery.nearestForage(st, x, z, 1.7f)
        if (fi >= 0) { a.kind = AKind.GATHER; a.label = "Gather"; a.target = fi; return a }

        // the stall is over five metres wide, so a tight circle round one point
        // in front of it meant hunting for the exact spot to stand
        if (dist2(x, z, World.MARKET_X, World.MARKET_Z + 1.8f) < 4.6f * 4.6f) {
            a.kind = AKind.SHOP; a.label = "Market"; return a
        }
        if (dist2(x, z, World.CABIN_DOOR_X, World.CABIN_DOOR_Z) < 2.4f * 2.4f) {
            a.kind = AKind.SLEEP; a.label = "Sleep"; return a
        }
        val bi = nearestBench(x, z, 1.9f)
        if (bi >= 0) { a.kind = AKind.SIT; a.label = "Sit"; a.target = bi; return a }

        val ti = FarmQuery.nearestTree(st, x, z, 2.0f)
        if (ti >= 0) { a.kind = AKind.CHOP; a.label = "Chop"; a.target = ti; return a }

        if (Terrain.nearWater(x, z, 3.4f)) { a.kind = AKind.FISH; a.label = "Fish"; return a }
        return a
    }

    private fun dist2(ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = ax - bx
        val dz = az - bz
        return dx * dx + dz * dz
    }

    private fun tryAction() {
        val a = currentAction()
        when (a.kind) {
            AKind.NONE -> return
            AKind.STAND -> {
                player.stopAction()
                audio.play(Sfx.TAP)
                return
            }
            AKind.REEL_IN -> {
                if (fishing.phase != FPhase.CAUGHT) {
                    fishing.cancel()
                    player.stopAction()
                    audio.play(Sfx.TAP)
                }
                return
            }
            AKind.SHOP -> { audio.play(Sfx.TAP); setMode(Mode.SHOP); return }
            AKind.SLEEP -> { audio.play(Sfx.TAP); beginSleep(); return }
            AKind.SIT -> {
                val p = World.props[a.target]
                player.placeAt(p.x + sin(Math.toRadians(p.yaw.toDouble())).toFloat() * 0.1f, p.z)
                player.yaw = p.yaw + 180f
                player.startAction(Act.SIT, 999f)
                camYawTarget = p.yaw + 180f
                audio.play(Sfx.TAP)
                toast("Nothing to do but watch the light", null, Pal.leafDeep)
                return
            }
            AKind.FISH -> {
                if (!Terrain.castSpot(player.x, player.z, 4.6f, castTmp)) return
                player.yaw = Math.toDegrees(
                    kotlin.math.atan2(
                        (castTmp[0] - player.x).toDouble(), (castTmp[1] - player.z).toDouble()
                    )
                ).toFloat()
                player.startAction(Act.FISH, 999f)
                fishing.cast(castTmp[0], castTmp[1], st.rodLevel, st.weather)
                audio.play(Sfx.WATER)
                return
            }
            AKind.PLANT -> {
                val seed = selectedSeed
                if (seed == null || st.count(seed) <= 0) {
                    pickDefaultSeed()
                    if (selectedSeed == null) {
                        toast("Pip keeps seeds for you at the stall", null, Pal.leafDeep)
                        audio.play(Sfx.TAP)
                        return
                    }
                }
                player.startAction(Act.PICK, 0.42f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.WATER -> {
                player.startAction(Act.WATER, 0.55f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.HARVEST, AKind.GATHER -> {
                player.startAction(Act.PICK, 0.45f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.TILL, AKind.CHOP -> {
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
                if (!st.take(seed, 1)) return
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
                // the can always reaches the whole row: watering is a pleasure,
                // not a chore to be optimised
                val row = target / World.PLOT_COLS
                var n = 0
                for (k in 0 until st.tier.plots) {
                    if (k / World.PLOT_COLS != row) continue
                    val p = st.plots[k]
                    if (p.cropId != null && !p.watered) {
                        p.watered = true
                        particles.burstWater(World.plotX(k), World.plotZ(k), 0.30f)
                        n++
                    }
                }
                if (n == 0) st.plots[target].watered = true
                audio.play(Sfx.WATER); haptic(10)
            }
            AKind.HARVEST -> {
                val p = st.plots[target]
                val crop = Catalog.crops[p.cropId ?: return] ?: return
                val span = crop.yieldMax - crop.yieldMin + 1
                val amount = crop.yieldMin + (U.hash(st.day * 71 + target) * span).toInt().coerceIn(0, span - 1)
                st.add(crop.produceId, amount)
                st.totalHarvest += amount
                st.seenCrops.add(crop.id)
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
                st.add(spot.itemId, 1)
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
                particles.burstChop(t.x, t.z, 1.8f * t.scale)
                audio.play(Sfx.CHOP); haptic(22)
                screenShake = 0.3f
                // one good swing is enough; the tree is back in a few days
                val key = target * 13 + st.day
                val wood = 3 + (U.hash(key) * 3f).toInt()
                st.add("wood", wood)
                if (U.hash(key + 5) < 0.4f) st.add("stone", 1)
                st.totalChopped++
                st.treeRegrow[target] = st.day + 2 + (U.hash(key + 9) * 3f).toInt()
                toast("+$wood Wood", "wood", Pal.ink)
                particles.burstHarvest(t.x, t.z, 1.2f, Pal.woodDark, 14)
            }
        }
    }

    private fun onFishCaught() {
        val id = fishing.fishId ?: return
        st.add(id, 1)
        st.totalFish++
        val isNew = st.seenFish.add(id)
        if (isNew) toast("New species: ${Catalog.name(id)}!", id, Pal.goldDeep)
        else toast("+1 ${Catalog.name(id)}", id, Pal.ink)
        particles.splash(fishing.bobX, fishing.bobZ, 1.4f)
        particles.hearts(player.x, player.z, 1.9f, 3)
    }

    // ================================================================ sleep

    fun beginSleep() {
        if (sleepPending) return
        sleepPending = true
        fadeTarget = 1f
        fishing.cancel()
        player.stopAction()
        audio.play(Sfx.SLEEP)
    }

    private fun doSleep() {
        val raining = st.weather == Weather.RAIN
        for (i in 0 until st.tier.plots) {
            val p = st.plots[i]
            val cid = p.cropId ?: continue
            val crop = Catalog.crops[cid] ?: continue
            if (p.growth < crop.days) {
                // unwatered crops still come along, just at their own pace
                p.growth += if (p.watered || raining) 1f else 0.6f
                if (p.growth >= crop.days) p.growth = crop.days.toFloat()
            }
            p.watered = false
        }
        st.day++
        st.weather = Weather.roll(st.day)
        if (st.weather == Weather.RAIN) {
            for (i in 0 until st.tier.plots) if (st.plots[i].cropId != null) st.plots[i].watered = true
        }
        st.timeMin = 6.5f * 60f
        readyCount = countReady()
        coinsEarnedToday = st.coins - coinsAtDayStart
        coinsAtDayStart = st.coins
        player.placeAt(World.CABIN_DOOR_X, World.CABIN_DOOR_Z)
        player.yaw = 0f
        st.playerX = player.x
        st.playerZ = player.z
        snapCamera()
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
        if (gain <= 0) { toast("Nothing to sell today", null, Pal.inkSoft); audio.play(Sfx.TAP); return }
        st.earn(gain)
        if (gain > st.biggestSale) st.biggestSale = gain
        particles.burstCoins(player.x, player.z, 1.3f, 12)
        toast("+${U.formatCoins(gain)} coins", null, Pal.goldDeep)
        audio.play(Sfx.COIN); haptic(18)
    }

    /** Seeds are a gift. Pip won't hear otherwise. */
    fun buySeed(crop: Crop, qty: Int) {
        st.add(crop.seedId, qty)
        if (selectedSeed == null) selectedSeed = crop.seedId
        toast("+$qty ${Catalog.name(crop.seedId)}", crop.seedId, Pal.leafDeep)
        audio.play(Sfx.TAP); haptic(10)
    }

    fun upgradeCabin() {
        val next = Tiers.next(st.cabinLevel)
        if (next == null) { toast("Your home is complete", null, Pal.inkSoft); return }
        if (st.coins < next.coins || st.count("wood") < next.wood || st.count("stone") < next.stone) {
            toast("Not quite enough yet — no hurry", null, Pal.inkSoft)
            audio.play(Sfx.TAP); return
        }
        st.spend(next.coins)
        st.take("wood", next.wood)
        st.take("stone", next.stone)
        st.cabinLevel = next.level
        particles.burstHarvest(World.CABIN_X, World.CABIN_Z, 3.4f, Pal.gold, 22)
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
        if (st.coins < cost) { toast("Not quite enough yet — no hurry", null, Pal.inkSoft); audio.play(Sfx.TAP); return }
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
        if (item.food <= 0) { toast("Better sold than eaten", null, Pal.inkSoft); return }
        if (!st.take(id, 1)) return
        particles.hearts(player.x, player.z, 1.8f, 2)
        toast("Lovely.", id, Pal.leafDeep)
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

    /** A suggestion, never an instruction. */
    fun currentGoal(): String {
        if (st.plots.none { it.cropId != null } && st.tier.plots > 0) return "Till a plot, plant something"
        if (st.totalFish == 0) return "The pond is good for fishing"
        val next = Tiers.next(st.cabinLevel)
        if (st.totalEarned < 200) return "Pip buys anything you bring"
        if (next != null) return "One day: ${next.name}"
        if (st.seenFish.size < Catalog.fish.size) return "Still fish out there you've not met"
        return "Nothing at all to do. Lovely."
    }

    // ================================================================ input

    private fun clearPointers() {
        for (i in pActive.indices) pActive[i] = false
        releaseStick()
        lookPointer = -1
    }

    fun onPointerDown(id: Int, x: Float, y: Float) {
        if (id in pActive.indices) { pActive[id] = true; pX[id] = x; pY[id] = y }
        if (screens.onDown(x, y)) return
        if (mode != Mode.PLAY) return
        if (bMenu.hit(x, y)) { bMenu.press = 1f; return }
        if (bBag.hit(x, y)) { bBag.press = 1f; return }
        if (bSeed.hit(x, y) && bSeed.visible) { bSeed.press = 1f; return }
        if (bAction.hit(x, y)) { bAction.press = 1f; return }
        if (stickPointer < 0 && inStickZone(x, y)) {
            stickPointer = id
            stickBaseX = x.coerceIn(stickRadius + 12f, vw - stickRadius - 12f)
            stickBaseY = y.coerceIn(stickRadius + 12f, vh - stickRadius - 12f)
            stickKnobX = stickBaseX
            stickKnobY = stickBaseY
            moveX = 0f; moveZ = 0f
            return
        }
        // anywhere else: drag to swing the camera round
        if (lookPointer < 0) {
            lookPointer = id
            lookLastX = x
        }
    }

    /** The half of the screen given over to walking, clear of the HUD buttons. */
    private fun inStickZone(x: Float, y: Float): Boolean {
        if (y < 150f) return false
        return if (settings.southpaw) x > vw * 0.55f else x < vw * 0.45f
    }

    private fun updateStick(x: Float, y: Float) {
        var dx = x - stickBaseX
        var dy = y - stickBaseY
        val len = sqrt(dx * dx + dy * dy)
        if (len > stickRadius) {
            dx = dx / len * stickRadius
            dy = dy / len * stickRadius
        }
        stickKnobX = stickBaseX + dx
        stickKnobY = stickBaseY + dy
        val mag = sqrt(dx * dx + dy * dy) / stickRadius
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
        if (id == lookPointer) {
            camYawTarget -= (x - lookLastX) * 0.22f
            lookLastX = x
        }
        screens.onMove(x, y)
    }

    fun onPointerUp(id: Int, x: Float, y: Float) {
        if (id in pActive.indices) pActive[id] = false
        if (id == stickPointer) releaseStick()
        if (id == lookPointer) lookPointer = -1
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

    fun drawUi(c: Canvas) {
        drawOverlays(c)
    }

    /** World x of whatever the action button will act on, or NaN when nothing is. */
    fun hintTargetX(): Float {
        if (mode != Mode.PLAY || fishing.active || player.sitting) return Float.NaN
        val a = currentAction()
        return when (a.kind) {
            AKind.TILL, AKind.PLANT, AKind.WATER, AKind.HARVEST -> World.plotX(a.target)
            AKind.CHOP -> World.trees[a.target].x
            AKind.GATHER -> World.forage[a.target].x
            AKind.SIT -> World.props[a.target].x
            AKind.SHOP -> World.MARKET_X
            AKind.SLEEP -> World.CABIN_DOOR_X
            else -> Float.NaN
        }
    }

    fun hintTargetZ(): Float {
        val a = currentAction()
        return when (a.kind) {
            AKind.TILL, AKind.PLANT, AKind.WATER, AKind.HARVEST -> World.plotZ(a.target)
            AKind.CHOP -> World.trees[a.target].z
            AKind.GATHER -> World.forage[a.target].z
            AKind.SIT -> World.props[a.target].z
            AKind.SHOP -> World.MARKET_Z
            AKind.SLEEP -> World.CABIN_DOOR_Z
            else -> 0f
        }
    }

    /** Height above the ground at which to float that marker. */
    fun hintHeight(): Float = when (currentAction().kind) {
        AKind.CHOP -> 4.2f
        AKind.SHOP -> 4.4f
        AKind.SLEEP -> 2.6f
        AKind.HARVEST -> 1.2f
        AKind.SIT -> 1.2f
        else -> 0.8f
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

        Ui.pill(c, pad, pad, 190f, 52f, U.withAlpha(Pal.woodDeep, 0.88f))
        Ui.coin(c, pad + 28f, pad + 26f, 17f)
        Ui.text(c, U.formatCoins(st.coins), pad + 54f, pad + 35f, 27f, Pal.gold, Paint.Align.LEFT, Ui.body)

        Ui.pill(c, pad, pad + 62f, 236f, 44f, U.withAlpha(Pal.woodDeep, 0.80f))
        Ui.text(c, "Day ${st.day}", pad + 18f, pad + 62f + 30f, 22f, Pal.cream, Paint.Align.LEFT)
        Ui.text(c, U.formatTime(st.timeMin % 1440f), pad + 120f, pad + 62f + 30f, 22f,
            U.withAlpha(Pal.cream, 0.9f), Paint.Align.LEFT)
        weatherGlyph(c, pad + 210f, pad + 62f + 22f)

        val zone = World.zoneName(World.zoneAt(player.x, player.z))
        Ui.textOut(c, zone, vw / 2f, pad + 34f, 26f, Pal.cream, U.withAlpha(Pal.shadow, 0.7f),
            Paint.Align.CENTER, Ui.display, 5f, 0.92f)
        Ui.text(c, currentGoal(), vw / 2f, pad + 60f, 17f, U.withAlpha(Pal.cream, 0.72f), Paint.Align.CENTER)

        if (mode != Mode.PLAY) return

        drawStick(c)

        val a = currentAction()
        val accent = when (a.kind) {
            AKind.FISH, AKind.REEL_IN -> Color.parseColor("#4B96B4")
            AKind.SHOP -> Pal.gold
            AKind.SLEEP -> Color.parseColor("#8A7BB8")
            AKind.SIT, AKind.STAND -> Color.parseColor("#B08A5E")
            AKind.CHOP -> Pal.woodDark
            AKind.NONE -> U.shade(Pal.woodDeep, 1.2f)
            else -> Pal.leaf
        }
        bAction.accent = accent
        bAction.label = if (a.kind == AKind.NONE) "..." else a.label
        bAction.enabled = true
        Ui.button(c, bAction, if (a.kind == AKind.NONE) 0.55f else 1f)

        bBag.accent = Pal.woodDark
        bBag.label = ""
        Ui.button(c, bBag)
        bagGlyph(c, bBag.cx, bBag.cy + bBag.press * 5f)

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
        val a = if (stickActive) 1f else 0.34f
        val r = if (stickActive) stickRadius else stickRadius * 0.72f

        glyphPaint.style = Paint.Style.FILL
        glyphPaint.color = U.withAlpha(Pal.shadow, 0.22f * a)
        c.drawCircle(bx, by + 4f, r, glyphPaint)
        glyphPaint.color = U.withAlpha(U.shade(Pal.woodDeep, 1.15f), 0.55f * a)
        c.drawCircle(bx, by, r, glyphPaint)
        glyphPaint.color = U.withAlpha(Pal.woodDeep, 0.45f * a)
        c.drawCircle(bx, by, r - 7f, glyphPaint)

        glyphPaint.color = U.withAlpha(Pal.cream, 0.32f * a)
        val pip = r - 18f
        c.drawCircle(bx, by - pip, 4f, glyphPaint)
        c.drawCircle(bx, by + pip, 4f, glyphPaint)
        c.drawCircle(bx - pip, by, 4f, glyphPaint)
        c.drawCircle(bx + pip, by, 4f, glyphPaint)

        val kr = if (stickActive) 33f else 24f
        glyphPaint.color = U.withAlpha(Pal.shadow, 0.3f * a)
        c.drawCircle(kx, ky + 5f, kr + 1f, glyphPaint)
        glyphPaint.color = U.withAlpha(Pal.cream, 0.94f * a)
        c.drawCircle(kx, ky, kr, glyphPaint)
        glyphPaint.color = U.withAlpha(U.shade(Pal.wood, 1.05f), 0.95f * a)
        c.drawCircle(kx, ky, kr * 0.76f, glyphPaint)
    }

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun bagGlyph(c: Canvas, x: Float, y: Float) {
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 3.2f
        glyphPaint.strokeCap = Paint.Cap.ROUND
        glyphPaint.color = Pal.cream
        c.drawArc(x - 11f, y - 21f, x + 11f, y - 1f, 200f, 140f, false, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
        c.drawRoundRect(x - 15f, y - 11f, x + 15f, y + 16f, 7f, 7f, glyphPaint)
        glyphPaint.color = Pal.woodDeep
        c.drawRoundRect(x - 15f, y - 11f, x + 15f, y + 2f, 7f, 7f, glyphPaint)
        glyphPaint.color = Pal.cream
        c.drawRoundRect(x - 15f, y + 4f, x + 15f, y + 16f, 6f, 6f, glyphPaint)
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
                    val dx = cos(ang); val dy = sin(ang)
                    c.drawLine(x + dx * 12f, y + 1f + dy * 12f, x + dx * 16f, y + 1f + dy * 16f, glyphPaint)
                }
            }
        }
    }
}
