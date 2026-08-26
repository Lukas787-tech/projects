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
    const val STATION = 10
    const val DECOR = 11
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
    const val ENTER = 13
    const val EXIT = 14
    const val STOKE = 15
    const val COOK = 16
    const val CRAFT = 17
    const val JOURNAL = 18
    const val STORE = 19
    const val PET = 20
    const val SPLIT = 21
    const val FEED_BIRDS = 22
    const val FEED_DEER = 23
    const val SOAK = 24
    const val FIREPIT = 25
    const val WINDOW = 26
    const val SLED = 27
}

/**
 * The game.
 *
 * There is nothing here to lose. The cold never kills you, the fish never gets
 * away, nothing rots, the clock cannot run out and no crop wilts. The worst the
 * winter does is slow your walk and suggest, gently, that the fire is that way.
 * Everything the player can do is a pleasant thing, so the whole loop is:
 * potter about in the snow, come in, warm up, watch the light change, go to bed
 * when you feel like it, and do it again tomorrow. Forever, if you like.
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
    var camDist = 13.5f; private set
    var camHeight = 10.5f; private set
    private var camYawTarget = 0f

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
    private var doorPending = 0
    var dayBanner = 0f
    private val castTmp = FloatArray(2)

    /** Which station the recipe screen is showing: stove or workbench. */
    var stationKind = Recipe.STOVE
        private set

    /** Mitten opens an eye when you crouch down to her. */
    var catAwake = false
        private set
    private var catAwakeT = 0f

    /** Holes cut in the ice today. They fill in again overnight. */
    private val holeX = FloatArray(8)
    private val holeZ = FloatArray(8)
    private var holes = 0

    fun holeCount(): Int = holes
    fun holeX(i: Int): Float = holeX[i]
    fun holeZ(i: Int): Float = holeZ[i]

    // ---- warmth feedback ----
    private var warmthWarned = 0
    private var breathAcc = 0f
    private var stepDist = 0f
    private var lastPX = 0f
    private var lastPZ = 0f

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
        player.indoors = false
        player.placeAt(st.playerX, st.playerZ)
        particles.clear()
        holes = 0
        selectedSeed = "seed_greens"
        snapCamera()
        SaveManager.save(ctx, st)
        setMode(Mode.INTRO)
        dayBanner = 3f
    }

    fun continueGame() {
        val loaded = SaveManager.load(ctx)
        st = loaded ?: GameState()
        coinsAtDayStart = st.coins
        player.indoors = st.indoors
        player.placeAt(st.playerX, st.playerZ)
        particles.clear()
        holes = 0
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
        st.indoors = player.indoors
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
        if (catAwakeT > 0f) {
            catAwakeT -= dt
            if (catAwakeT <= 0f) catAwake = false
        }

        SkyKeys.at(st.timeMin % 1440f, sky)
        sky.applyWeather(st.weather, 1f)
        val night = nightAmount()
        audio.mood = night
        audio.indoors = player.indoors
        audio.wind = windAmount()

        // the wind, which everything blowing about agrees on
        val gust = 0.5f + 0.5f * sin(timeMs * 0.00011f)
        val strength = when (st.weather) {
            Weather.BLIZZARD -> 2.6f
            Weather.SNOW -> 0.9f
            else -> 0.55f
        } * (0.6f + gust * 0.8f)
        particles.windX = -0.86f * strength
        particles.windZ = 0.36f * strength

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
        particles.updateAmbient(
            dt, player.x, player.z, night, st.weather,
            settings.particleScale, player.indoors
        )
        if (!player.indoors && st.firepitFuel > 0f) {
            particles.updateFire(
                dt, World.FIRE_X, Terrain.height(World.FIRE_X, World.FIRE_Z) + 0.5f, World.FIRE_Z,
                1f, settings.particleScale
            )
        }

        if (sleepPending && fadeOut > 0.97f) {
            sleepPending = false
            doSleep()
            fadeTarget = 0f
        }
        if (doorPending != 0 && fadeOut > 0.9f) {
            if (doorPending > 0) player.enterInterior() else player.exitInterior()
            doorPending = 0
            snapCamera()
            fadeTarget = 0f
        }
    }

    private fun updateTitle(dt: Float) {
        // the title screen turns slowly round the yard at blue hour
        st.timeMin = 1000f + sin(titleT * 0.05f) * 24f
        camYaw = (titleT * 4.0f) % 360f
        camYawTarget = camYaw
        camTX = U.lerp(camTX, World.CABIN_X + 1.0f, min(1f, dt * 1.4f))
        camTZ = U.lerp(camTZ, World.CABIN_Z + 3.4f, min(1f, dt * 1.4f))
        camTY = Terrain.height(camTX, camTZ)
        camDist = 17f
        camHeight = 11.5f
        player.update(dt, 0f, 0f, st)
    }

    private fun updatePaused(dt: Float) {
        player.update(dt, 0f, 0f, st)
        followCamera(dt)
    }

    private fun updatePlay(dt: Float) {
        // ---- clock ----
        // a full winter day takes about twenty minutes; resting speeds it along
        val clockRate = if (player.sitting) 7f else 1.25f
        st.timeMin += dt * clockRate
        val hours = dt * clockRate / 60f
        if (st.timeMin >= 1400f) {
            toast("It is very late, and very cold", null, Pal.frostDeep)
            beginSleep()
        }

        burnFuel(hours)
        updateWarmth(dt, hours)

        // ---- movement, relative to wherever the camera is looking ----
        val canWalk = !(fishing.active || screens.blocksInput())
        val yawRad = Math.toRadians(camYaw.toDouble())
        val sy = sin(yawRad).toFloat()
        val cy = cos(yawRad).toFloat()
        val dirX = if (canWalk) (moveZ * sy + moveX * cy) else 0f
        val dirZ = if (canWalk) (moveZ * cy - moveX * sy) else 0f
        player.update(dt, dirX, dirZ, st, chillAmount())
        st.playerX = player.x
        st.playerZ = player.z
        st.indoors = player.indoors
        followCamera(dt)

        trackFootprints(dt)
        trackBreath(dt)

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

    /** Footprints, but only every stride, and only in snow deep enough to hold one. */
    private fun trackFootprints(dt: Float) {
        if (player.indoors || !player.moving) { lastPX = player.x; lastPZ = player.z; return }
        val dx = player.x - lastPX
        val dz = player.z - lastPZ
        stepDist += sqrt(dx * dx + dz * dz)
        lastPX = player.x; lastPZ = player.z
        if (stepDist < 0.62f) return
        stepDist = 0f
        if (Terrain.onIce(player.x, player.z)) return
        if (World.distToPath(player.x, player.z) < 0.9f) return
        val side = if ((timeMs.toInt() / 100) % 2 == 0) 0.11f else -0.11f
        val rad = Math.toRadians((player.yaw + 90f).toDouble())
        particles.footprint(
            player.x + sin(rad).toFloat() * side,
            player.z + cos(rad).toFloat() * side,
            player.yaw
        )
        audio.play(Sfx.STEP)
    }

    /** Your breath, whenever it is cold enough to see it. */
    private fun trackBreath(dt: Float) {
        if (player.indoors) return
        breathAcc += dt
        val every = if (player.moving) 1.1f else 2.2f
        if (breathAcc < every) return
        breathAcc = 0f
        particles.breath(
            player.x, player.y + 1.3f, player.z, player.yaw,
            0.8f + chillAmount() * 0.7f
        )
    }

    // =============================================================== warmth

    /** Everything burning, burning down. */
    private fun burnFuel(hours: Float) {
        if (st.hearthLit && st.hearthFuel > 0f) {
            st.hearthFuel = max(0f, st.hearthFuel - hours / st.tier.insulation)
            if (st.hearthFuel <= 0f) {
                st.hearthLit = false
                toast("The stove has burnt down", "firewood", Pal.frostDeep)
            }
        }
        if (st.firepitFuel > 0f) st.firepitFuel = max(0f, st.firepitFuel - hours)
        if (st.comfort > 0f) st.comfort = max(0f, st.comfort - hours * 60f)
    }

    /** How close you are to a fire, a vent or a warm room, 0..1. */
    private fun heatHere(): Float {
        if (player.indoors) {
            return if (st.hearthLit && st.hearthFuel > 0f) 1f else 0.15f
        }
        var best = 0f
        if (st.firepitFuel > 0f) {
            val d = dist2(player.x, player.z, World.FIRE_X, World.FIRE_Z)
            best = max(best, 1f - U.smoothRange(sqrt(d), 1.2f, 4.6f))
        }
        run {
            val d = dist2(player.x, player.z, World.MARKET_X + 3.1f, World.MARKET_Z + 1.6f)
            best = max(best, (1f - U.smoothRange(sqrt(d), 1.0f, 3.6f)) * 0.85f)
        }
        best = max(best, Terrain.springWarmth(player.x, player.z))
        if (player.action == Act.SOAK) best = 1f
        // standing right at the glasshouse door is warmer than the yard
        if (World.inGlasshouse(player.x, player.z, 0.5f)) best = max(best, 0.72f)
        // and so is being inside the hut on the ice
        val dh = sqrt(dist2(player.x, player.z, World.HUT_X, World.HUT_Z))
        best = max(best, (1f - U.smoothRange(dh, 1.6f, 3.4f)) * 0.6f)
        return U.clamp01(best)
    }

    private fun updateWarmth(dt: Float, hours: Float) {
        if (settings.gentle) { st.warmth = 100f; return }
        val heat = heatHere()
        if (heat > 0.25f) {
            st.warmth = min(100f, st.warmth + hours * 260f * heat)
            if (st.warmth >= 99.5f) warmthWarned = 0
        } else {
            // Roughly nine hours of a clear day before a patched coat gives up,
            // which is one good long wander and then a very welcome fire. Four
            // walls and no stove is still far better than standing outside.
            val night = nightAmount()
            val shelter = if (player.indoors) 0.28f else 1f
            val drain = 8f * Weather.chill(st.weather) *
                ToolUp.coatShelter(st.coatLevel) *
                (1f + night * 0.55f) *
                (if (st.comfort > 0f) 0.45f else 1f) * shelter
            st.warmth = max(0f, st.warmth - hours * drain)
        }
        // one quiet nudge at each threshold, never a nag
        val level = when {
            st.warmth < 12f -> 3
            st.warmth < 32f -> 2
            st.warmth < 58f -> 1
            else -> 0
        }
        if (level > warmthWarned) {
            warmthWarned = level
            when (level) {
                1 -> toast("Getting nippy", null, Pal.frost)
                2 -> toast("Hands going numb — find a fire", null, Pal.frostDeep)
                3 -> toast("Properly cold now. Home, or a hot drink.", null, Pal.berry)
            }
        }
    }

    /** How much the cold is slowing you, 0..1. */
    fun chillAmount(): Float {
        if (settings.gentle) return 0f
        return U.clamp01(1f - st.warmth / 55f)
    }

    // =============================================================== camera

    private fun snapCamera() {
        camTX = player.x
        camTZ = player.z
        camTY = if (player.indoors) Interior.FLOOR_Y else Terrain.groundY(player.x, player.z)
        camDist = if (player.indoors) 5.6f else 13.5f
        camHeight = if (player.indoors) 4.2f else 10.5f
        camYaw = 0f
        camYawTarget = 0f
    }

    /** The camera follows a step behind, and turns only when you turn it. */
    private fun followCamera(dt: Float) {
        val k = min(1f, dt * 5.0f)
        camTX = U.lerp(camTX, player.x, k)
        camTZ = U.lerp(camTZ, player.z, k)
        camTY = U.lerp(camTY, player.y, min(1f, dt * 3.4f))

        val wantDist: Float
        val wantHeight: Float
        when {
            player.indoors -> { wantDist = 5.4f; wantHeight = 4.0f }
            player.action == Act.SOAK -> { wantDist = 8.0f; wantHeight = 5.4f }
            player.sitting -> { wantDist = 10.5f; wantHeight = 6.4f }
            fishing.active -> { wantDist = 10.0f; wantHeight = 7.4f }
            else -> { wantDist = 13.5f; wantHeight = 10.5f }
        }
        camDist = U.lerp(camDist, wantDist, min(1f, dt * 1.6f))
        camHeight = U.lerp(camHeight, wantHeight, min(1f, dt * 1.6f))

        // sitting turns the view slowly, like a held breath
        if (player.sitting) camYawTarget += dt * 2.6f
        camYaw = U.lerpAngle(camYaw, camYawTarget, min(1f, dt * 8f))
    }

    fun nightAmount(): Float {
        val m = st.timeMin % 1440f
        return when {
            m < 400f -> 1f
            m < 500f -> 1f - U.smoothRange(m, 400f, 500f)
            m < 960f -> 0f
            m < 1090f -> U.smoothRange(m, 960f, 1090f)
            else -> 1f
        }
    }

    /** How bright every lamp and window in the valley is burning, 0..1. */
    fun lampAmount(): Float {
        val m = st.timeMin % 1440f
        val dark = when {
            m < 460f -> 1f
            m < 560f -> 1f - U.smoothRange(m, 460f, 560f)
            m < 900f -> 0f
            m < 1010f -> U.smoothRange(m, 900f, 1010f)
            else -> 1f
        }
        // a blizzard is dark enough at noon that everyone lights up anyway
        val storm = when (st.weather) {
            Weather.BLIZZARD -> 0.55f
            Weather.SNOW -> 0.22f
            Weather.OVERCAST -> 0.12f
            else -> 0f
        }
        return U.clamp01(max(dark, storm))
    }

    /** The northern lights: clear nights only, and not every one of them. */
    fun auroraAmount(): Float {
        if (st.weather != Weather.CLEAR) return 0f
        if (U.hash(st.day * 613 + 29) > 0.45f) return 0f
        return nightAmount() * (0.55f + 0.45f * sin(timeMs * 0.00007f))
    }

    /** How hard the wind is pushing through the branches right now. */
    fun windAmount(): Float {
        val gust = 0.6f + 0.4f * sin(timeMs * 0.00013f) + 0.2f * sin(timeMs * 0.00047f)
        return when (st.weather) {
            Weather.BLIZZARD -> 0.62f * gust
            Weather.SNOW -> 0.26f * gust
            Weather.OVERCAST -> 0.20f * gust
            else -> 0.15f * gust
        }
    }

    // ============================================================== actions

    class ActionInfo(var kind: Int, var label: String, var target: Int)

    private val actionInfo = ActionInfo(AKind.NONE, "", -1)

    private fun nearestProp(kind: Int, x: Float, z: Float, reach: Float): Int {
        var best = -1
        var bestD = reach * reach
        for (i in World.props.indices) {
            val p = World.props[i]
            if (p.kind != kind) continue
            val d = dist2(x, z, p.x, p.z)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun nearestSeat(x: Float, z: Float, reach: Float): Int {
        var best = -1
        var bestD = reach * reach
        for (i in World.props.indices) {
            val p = World.props[i]
            if (p.kind != World.PKind.BENCH && p.kind != World.PKind.LOG_SEAT) continue
            val d = dist2(x, z, p.x, p.z)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun currentAction(): ActionInfo {
        val a = actionInfo
        a.kind = AKind.NONE; a.label = ""; a.target = -1
        if (player.sitting) {
            a.kind = AKind.STAND
            a.label = if (player.action == Act.SOAK) "Get out" else "Get up"
            return a
        }
        if (fishing.active) {
            a.kind = AKind.REEL_IN
            a.label = if (fishing.phase == FPhase.CAUGHT) "..." else "Pack up"
            return a
        }
        val x = player.x
        val z = player.z

        if (player.indoors) return indoorAction(a, x, z)

        val pi = FarmQuery.nearestPlot(st, x, z, 1.9f)
        if (pi >= 0) {
            val p = st.plots[pi]
            when {
                !p.tilled -> { a.kind = AKind.TILL; a.label = "Dig over"; a.target = pi }
                p.cropId == null -> { a.kind = AKind.PLANT; a.label = "Plant"; a.target = pi }
                p.ready -> { a.kind = AKind.HARVEST; a.label = "Pick"; a.target = pi }
                !p.watered -> { a.kind = AKind.WATER; a.label = "Water"; a.target = pi }
                else -> { a.kind = AKind.NONE; a.label = "Growing" }
            }
            if (a.kind != AKind.NONE) return a
        }
        val fi = FarmQuery.nearestForage(st, x, z, 1.8f)
        if (fi >= 0) { a.kind = AKind.GATHER; a.label = "Gather"; a.target = fi; return a }

        if (dist2(x, z, World.CABIN_DOOR_X, World.CABIN_DOOR_Z) < 2.3f * 2.3f) {
            a.kind = AKind.ENTER; a.label = "Go inside"; return a
        }
        if (dist2(x, z, World.MARKET_X, World.MARKET_Z + 1.8f) < 4.6f * 4.6f) {
            a.kind = AKind.SHOP; a.label = "Pip's stall"; return a
        }
        val cb = nearestProp(World.PKind.CHOP_BLOCK, x, z, 2.0f)
        if (cb >= 0) { a.kind = AKind.SPLIT; a.label = "Split logs"; a.target = cb; return a }
        val bf = nearestProp(World.PKind.BIRD_FEEDER, x, z, 1.9f)
        if (bf >= 0 && st.birdFedDay != st.day) {
            a.kind = AKind.FEED_BIRDS; a.label = "Fill feeder"; a.target = bf; return a
        }
        val df = nearestProp(World.PKind.DEER_FEEDER, x, z, 2.4f)
        if (df >= 0 && st.deerFedDay != st.day) {
            a.kind = AKind.FEED_DEER; a.label = "Leave hay"; a.target = df; return a
        }
        val fp = nearestProp(World.PKind.FIREPIT, x, z, 2.6f)
        if (fp >= 0) {
            a.kind = AKind.FIREPIT
            a.label = if (st.firepitFuel > 0f) "Feed the fire" else "Light a fire"
            a.target = fp
            return a
        }
        if (Terrain.springWarmth(x, z) > 0.55f) {
            a.kind = AKind.SOAK; a.label = "Soak"; return a
        }
        val sl = nearestProp(World.PKind.SLED, x, z, 1.8f)
        if (sl >= 0) { a.kind = AKind.SLED; a.label = "Take the sled"; a.target = sl; return a }
        val bi = nearestSeat(x, z, 1.9f)
        if (bi >= 0) { a.kind = AKind.SIT; a.label = "Sit"; a.target = bi; return a }

        val ti = FarmQuery.nearestTree(st, x, z, 2.0f)
        if (ti >= 0) { a.kind = AKind.CHOP; a.label = "Fell"; a.target = ti; return a }

        if (Terrain.onIce(x, z) && Terrain.nearFishableIce(x, z, 2.2f)) {
            a.kind = AKind.FISH; a.label = "Cut a hole"; return a
        }
        return a
    }

    private fun indoorAction(a: ActionInfo, x: Float, z: Float): ActionInfo {
        val i = Interior.nearest(x, z)
        if (i < 0) return a
        val f = Interior.items[i]
        a.target = i
        a.label = f.label
        a.kind = when (f.kind) {
            Interior.FKind.HEARTH -> AKind.STOKE
            Interior.FKind.STOVE -> AKind.COOK
            Interior.FKind.BED -> AKind.SLEEP
            Interior.FKind.CHAIR -> AKind.SIT
            Interior.FKind.BENCH -> AKind.CRAFT
            Interior.FKind.SHELF -> AKind.JOURNAL
            Interior.FKind.CHEST -> AKind.STORE
            Interior.FKind.DOOR -> AKind.EXIT
            Interior.FKind.WINDOW -> AKind.WINDOW
            Interior.FKind.CAT -> AKind.PET
            else -> AKind.NONE
        }
        if (a.kind == AKind.STOKE) {
            a.label = when {
                st.count("firewood") <= 0 -> "No firewood"
                st.hearthFuel > 10f -> "Fire is roaring"
                else -> "Feed the stove"
            }
        }
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
            AKind.STAND -> { player.stopAction(); audio.play(Sfx.TAP); return }
            AKind.REEL_IN -> {
                if (fishing.phase != FPhase.CAUGHT) {
                    fishing.cancel(); player.stopAction(); audio.play(Sfx.TAP)
                }
                return
            }
            AKind.SHOP -> { audio.play(Sfx.TAP); setMode(Mode.SHOP); return }
            AKind.JOURNAL -> { audio.play(Sfx.TAP); setMode(Mode.JOURNAL); return }
            AKind.STORE -> { audio.play(Sfx.TAP); setMode(Mode.BAG); return }
            AKind.SLEEP -> { audio.play(Sfx.TAP); beginSleep(); return }
            AKind.ENTER -> { audio.play(Sfx.DOOR); beginDoor(1); return }
            AKind.EXIT -> { audio.play(Sfx.DOOR); beginDoor(-1); return }
            AKind.COOK -> { audio.play(Sfx.TAP); stationKind = Recipe.STOVE; setMode(Mode.STATION); return }
            AKind.CRAFT -> { audio.play(Sfx.TAP); stationKind = Recipe.BENCH; setMode(Mode.STATION); return }
            AKind.WINDOW -> {
                audio.play(Sfx.TAP)
                st.cosyMoments++
                toast(windowLine(), null, Pal.frost)
                return
            }
            AKind.PET -> {
                audio.play(Sfx.PURR)
                catAwake = true
                catAwakeT = 4f
                st.catAffection++
                st.cosyMoments++
                st.seenAnimals.add("cat")
                particles.hearts(player.x, player.z, 1.4f, 2)
                toast(catLine(), null, Pal.berry)
                haptic(10)
                return
            }
            AKind.STOKE -> { stokeHearth(); return }
            AKind.FIREPIT -> { lightFirepit(); return }
            AKind.SLED -> { rideSled(); return }
            AKind.SOAK -> {
                player.startAction(Act.SOAK, 999f)
                st.cosyMoments++
                audio.play(Sfx.WATER)
                toast("Steam, snow, and nothing to do", null, Pal.frost)
                return
            }
            AKind.SIT -> {
                if (player.indoors) {
                    player.placeAt(Interior.CHAIR_SIT_X, Interior.CHAIR_SIT_Z + 0.02f)
                    player.yaw = Interior.CHAIR_SIT_YAW
                    camYawTarget = Interior.CHAIR_SIT_YAW
                } else {
                    val p = World.props[a.target]
                    player.placeAt(p.x + sin(Math.toRadians(p.yaw.toDouble())).toFloat() * 0.1f, p.z)
                    player.yaw = p.yaw + 180f
                    camYawTarget = p.yaw + 180f
                }
                player.startAction(Act.SIT, 999f)
                st.cosyMoments++
                audio.play(Sfx.TAP)
                toast(sitLine(), null, Pal.pineDeep)
                return
            }
            AKind.FISH -> {
                if (!Terrain.castSpot(player.x, player.z, 2.6f, castTmp)) return
                player.yaw = Math.toDegrees(
                    kotlin.math.atan2(
                        (castTmp[0] - player.x).toDouble(), (castTmp[1] - player.z).toDouble()
                    )
                ).toFloat()
                player.startAction(Act.FISH, 999f)
                fishing.cast(castTmp[0], castTmp[1], st.rodLevel, st.weather)
                rememberHole(castTmp[0], castTmp[1])
                particles.burstSnow(castTmp[0], castTmp[1], 0.1f, 10)
                audio.play(Sfx.DRILL)
                return
            }
            AKind.PLANT -> {
                val seed = selectedSeed
                if (seed == null || st.count(seed) <= 0) {
                    pickDefaultSeed()
                    if (selectedSeed == null) {
                        toast("Pip keeps seed for you at the stall", null, Pal.pineDeep)
                        audio.play(Sfx.TAP)
                        return
                    }
                }
                player.startAction(Act.PICK, 0.42f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.WATER -> {
                player.startAction(Act.POUR, 0.55f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.HARVEST, AKind.GATHER -> {
                player.startAction(Act.PICK, 0.45f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.FEED_BIRDS, AKind.FEED_DEER -> {
                player.startAction(Act.WORK, 0.6f)
                pendingAction = a.kind; pendingTarget = a.target
            }
            AKind.TILL, AKind.CHOP, AKind.SPLIT -> {
                player.startAction(Act.SWING, if (a.kind == AKind.TILL) 0.5f else 0.6f)
                pendingAction = a.kind; pendingTarget = a.target
            }
        }
    }

    private fun rememberHole(x: Float, z: Float) {
        for (i in 0 until holes) {
            if (dist2(holeX[i], holeZ[i], x, z) < 0.6f) return
        }
        if (holes >= holeX.size) {
            for (i in 1 until holes) { holeX[i - 1] = holeX[i]; holeZ[i - 1] = holeZ[i] }
            holes--
        }
        holeX[holes] = x; holeZ[holes] = z; holes++
    }

    private fun resolveAction(kind: Int, target: Int) {
        when (kind) {
            AKind.TILL -> {
                st.plots[target].tilled = true
                particles.burstHarvest(World.plotX(target), World.plotZ(target), 0.2f, Pal.soil, 6)
                audio.play(Sfx.TILL); haptic(14)
            }
            AKind.PLANT -> {
                val seed = selectedSeed ?: return
                if (!st.take(seed, 1)) return
                val crop = Catalog.cropForSeed(seed) ?: return
                val p = st.plots[target]
                p.cropId = crop.id
                p.growth = 0f
                p.watered = false
                particles.burstHarvest(World.plotX(target), World.plotZ(target), 0.35f, Pal.pine, 6)
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
                particles.burstHarvest(spot.x, spot.z, 0.4f, item.a, 9)
                particles.burstSnow(spot.x, spot.z, 0.1f, 6)
                toast("+1 ${item.name}", spot.itemId, Pal.ink)
                audio.play(Sfx.HARVEST); haptic(14)
            }
            AKind.CHOP -> {
                val t = World.trees[target]
                shakeTreeIndex = target
                shakeAmount = 0.34f
                particles.burstChop(t.x, t.z, 2.2f * t.scale)
                audio.play(Sfx.CHOP); haptic(22)
                screenShake = 0.3f
                val key = target * 13 + st.day
                val logs = ToolUp.axeLogs(st.axeLevel) + (U.hash(key) * 2f).toInt()
                st.add("log", logs)
                st.totalLogs += logs
                if (U.hash(key + 5) < 0.35f) st.add("stone", 1)
                if (U.hash(key + 11) < 0.5f) st.add("kindling", 1)
                st.treeRegrow[target] = st.day + 3 + (U.hash(key + 9) * 3f).toInt()
                toast("+$logs Logs", "log", Pal.ink)
            }
            AKind.SPLIT -> {
                val p = World.props[target]
                if (st.count("log") <= 0) {
                    toast("No logs to split", "log", Pal.inkSoft)
                    audio.play(Sfx.TAP)
                    return
                }
                st.take("log", 1)
                val n = 3 + (if (st.axeLevel >= 3) 1 else 0)
                st.add("firewood", n)
                particles.burstChop(p.x, p.z, 0.8f)
                audio.play(Sfx.CHOP); haptic(18)
                screenShake = 0.18f
                toast("+$n Firewood", "firewood", Pal.ink)
            }
            AKind.FEED_BIRDS -> {
                if (!st.take("birdseed", 1)) {
                    toast("You need birdseed for that", "birdseed", Pal.inkSoft)
                    audio.play(Sfx.TAP); return
                }
                st.birdFedDay = st.day
                st.cosyMoments++
                val isNew = st.seenAnimals.add("chickadee")
                toast(if (isNew) "The chickadees found it at once" else "The feeder is full", "birdseed", Pal.pineDeep)
                audio.play(Sfx.BIRD); haptic(12)
            }
            AKind.FEED_DEER -> {
                if (!st.take("hay", 1)) {
                    toast("You need a bale of hay", "hay", Pal.inkSoft)
                    audio.play(Sfx.TAP); return
                }
                st.deerFedDay = st.day
                st.cosyMoments++
                val isNew = st.seenAnimals.add("deer")
                toast(if (isNew) "Two deer come out of the pines" else "They will be along shortly", "hay", Pal.pineDeep)
                audio.play(Sfx.HARVEST); haptic(12)
            }
        }
    }

    private fun stokeHearth() {
        if (st.count("firewood") <= 0) {
            toast("The basket is empty", "firewood", Pal.inkSoft)
            audio.play(Sfx.TAP)
            return
        }
        if (st.hearthFuel > 10f) {
            toast("It is roaring already", null, Pal.ember)
            audio.play(Sfx.TAP)
            return
        }
        st.take("firewood", 1)
        st.hearthFuel = min(12f, st.hearthFuel + 3.5f)
        st.hearthLit = true
        player.startAction(Act.WORK, 0.7f)
        audio.play(Sfx.FIRE); haptic(16)
        toast("The stove takes hold", "firewood", Pal.ember)
    }

    private fun lightFirepit() {
        if (st.count("firewood") <= 0) {
            toast("You would need firewood", "firewood", Pal.inkSoft)
            audio.play(Sfx.TAP); return
        }
        st.take("firewood", 1)
        st.firepitFuel = min(8f, st.firepitFuel + 3f)
        player.startAction(Act.WORK, 0.7f)
        particles.embers(World.FIRE_X, Terrain.height(World.FIRE_X, World.FIRE_Z) + 0.4f, World.FIRE_Z, 12)
        audio.play(Sfx.FIRE); haptic(16)
        toast("The yard fire catches", "firewood", Pal.ember)
    }

    private fun rideSled() {
        st.cosyMoments++
        audio.play(Sfx.SLED); haptic(30)
        screenShake = 0.5f
        particles.burstSnow(player.x, player.z, 0.2f, 26)
        // it always ends the same way, and that is the joke
        toast("Straight down, into a drift. Excellent.", null, Pal.frost)
        st.timeMin += 25f
        st.warmth = max(0f, st.warmth - 6f)
    }

    private fun beginDoor(dir: Int) {
        if (doorPending != 0) return
        doorPending = dir
        fadeTarget = 1f
        fishing.cancel()
        player.stopAction()
    }

    private fun onFishCaught() {
        val id = fishing.fishId ?: return
        st.add(id, 1)
        st.totalFish++
        val isNew = st.seenFish.add(id)
        if (isNew) toast("New species: ${Catalog.name(id)}!", id, Pal.goldDeep)
        else toast("+1 ${Catalog.name(id)}", id, Pal.ink)
        particles.splash(fishing.bobX, fishing.bobZ, 1.2f)
        particles.hearts(player.x, player.z, 1.9f, 3)
    }

    // ============================================================== making

    /** Cook or craft. Both go through here; only the station differs. */
    fun make(r: Recipe) {
        if (!st.canMake(r)) {
            toast("Not everything you need", null, Pal.inkSoft)
            audio.play(Sfx.TAP)
            return
        }
        if (!st.hasRoomFor(r.outId)) {
            toast("No room in the bag", null, Pal.inkSoft)
            audio.play(Sfx.TAP)
            return
        }
        if (r.station == Recipe.STOVE && !(st.hearthLit && st.hearthFuel > 0f)) {
            toast("The stove is cold — feed it first", "firewood", Pal.frostDeep)
            audio.play(Sfx.TAP)
            return
        }
        st.consume(r)
        val bonus = if (r.station == Recipe.STOVE) ToolUp.kettleBonus(st.kettleLevel) else 0
        val qty = r.outQty + bonus
        st.add(r.outId, qty)
        st.timeMin += r.minutes
        if (r.station == Recipe.STOVE) {
            st.totalCooked += qty
            st.seenMeals.add(r.outId)
        }
        particles.hearts(player.x, player.z, 1.5f, 2)
        toast("+$qty ${Catalog.name(r.outId)}", r.outId, Pal.goldDeep)
        audio.play(if (r.station == Recipe.STOVE) Sfx.COOK else Sfx.CRAFT)
        haptic(18)
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
        val warmNight = st.hearthLit && st.hearthFuel > 0.5f
        for (i in 0 until st.tier.plots) {
            val p = st.plots[i]
            val cid = p.cropId ?: continue
            val crop = Catalog.crops[cid] ?: continue
            if (p.growth < crop.days) {
                // under glass everything comes along, watered or not — just slower
                p.growth += if (p.watered) 1f else 0.55f
                if (p.growth >= crop.days) p.growth = crop.days.toFloat()
            }
            p.watered = false
        }
        st.day++
        st.weather = Weather.roll(st.day)
        st.timeMin = 7.5f * 60f
        holes = 0
        // the fire burns down overnight whatever you did
        if (warmNight) {
            st.nightsWarm++
            st.hearthFuel = max(0f, st.hearthFuel - 5f)
            st.warmth = 100f
        } else {
            st.hearthFuel = 0f
            st.hearthLit = false
            st.warmth = 62f
        }
        st.firepitFuel = 0f
        st.comfort = 0f
        warmthWarned = 0
        // the cat keeps her own counsel about whether she visited
        if (U.hash(st.day * 331 + 7) < 0.5f) st.catAffection++
        readyCount = countReady()
        coinsEarnedToday = st.coins - coinsAtDayStart
        coinsAtDayStart = st.coins
        sleptWarm = warmNight
        if (!player.indoors) player.enterInterior()
        player.placeAt(Interior.DOOR_X - 1.6f, Interior.DOOR_Z - 1.2f)
        player.yaw = 150f
        st.playerX = player.x
        st.playerZ = player.z
        st.indoors = true
        snapCamera()
        SaveManager.save(ctx, st)
        setMode(Mode.SLEEP)
        dayBanner = 0f
    }

    var readyCount = 0
        private set
    var coinsEarnedToday = 0
        private set
    var sleptWarm = true
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
            if (item.cat == Cat.SEED || item.cat == Cat.MATERIAL) continue
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

    /** Seed is a gift. Pip won't hear otherwise. */
    fun buySeed(crop: Crop, qty: Int) {
        st.add(crop.seedId, qty)
        if (selectedSeed == null) selectedSeed = crop.seedId
        toast("+$qty ${Catalog.name(crop.seedId)}", crop.seedId, Pal.pineDeep)
        audio.play(Sfx.TAP); haptic(10)
    }

    /** Everything else on the counter has a price on it. */
    fun buySupply(id: String, qty: Int) {
        val item = Catalog.items[id] ?: return
        val cost = item.price * qty
        if (st.coins < cost) {
            toast("Not quite enough yet — no hurry", null, Pal.inkSoft)
            audio.play(Sfx.TAP); return
        }
        if (!st.hasRoomFor(id)) {
            toast("No room in the bag", null, Pal.inkSoft)
            audio.play(Sfx.TAP); return
        }
        st.spend(cost)
        st.add(id, qty)
        toast("+$qty ${item.name}", id, Pal.ink)
        audio.play(Sfx.COIN); haptic(10)
    }

    fun upgradeCabin() {
        val next = Tiers.next(st.cabinLevel)
        if (next == null) { toast("Your home is complete", null, Pal.inkSoft); return }
        if (st.coins < next.coins || st.count("log") < next.log || st.count("stone") < next.stone) {
            toast("Not quite enough yet — no hurry", null, Pal.inkSoft)
            audio.play(Sfx.TAP); return
        }
        st.spend(next.coins)
        st.take("log", next.log)
        st.take("stone", next.stone)
        st.cabinLevel = next.level
        particles.burstHarvest(World.CABIN_X, World.CABIN_Z, 3.4f, Pal.gold, 22)
        particles.hearts(World.CABIN_X, World.CABIN_Z, 3.8f, 5)
        toast("Home improved: ${next.name}!", null, Pal.goldDeep)
        audio.play(Sfx.UPGRADE); haptic(40)
        SaveManager.save(ctx, st)
    }

    fun buyTool(which: Int) {
        val level = when (which) {
            0 -> st.rodLevel; 1 -> st.kettleLevel; 2 -> st.axeLevel
            3 -> st.coatLevel; else -> st.lanternLevel
        }
        val cost = when (which) {
            0 -> ToolUp.rodCost(level + 1); 1 -> ToolUp.kettleCost(level + 1)
            2 -> ToolUp.axeCost(level + 1); 3 -> ToolUp.coatCost(level + 1)
            else -> ToolUp.lanternCost(level + 1)
        }
        if (level >= 3) { toast("Already the finest", null, Pal.inkSoft); return }
        if (st.coins < cost) { toast("Not quite enough yet — no hurry", null, Pal.inkSoft); audio.play(Sfx.TAP); return }
        st.spend(cost)
        when (which) {
            0 -> st.rodLevel++
            1 -> st.kettleLevel++
            2 -> st.axeLevel++
            3 -> st.coatLevel++
            else -> st.lanternLevel++
        }
        val name = when (which) {
            0 -> ToolUp.rodName(st.rodLevel); 1 -> ToolUp.kettleName(st.kettleLevel)
            2 -> ToolUp.axeName(st.axeLevel); 3 -> ToolUp.coatName(st.coatLevel)
            else -> ToolUp.lanternName(st.lanternLevel)
        }
        toast("Upgraded to $name!", null, Pal.goldDeep)
        audio.play(Sfx.UPGRADE); haptic(30)
    }

    fun buyDecor(dec: Decor) {
        if (st.decorOwned.contains(dec.id)) { placeDecor(dec); return }
        if (st.coins < dec.cost) {
            toast("Not quite enough yet — no hurry", null, Pal.inkSoft)
            audio.play(Sfx.TAP); return
        }
        st.spend(dec.cost)
        st.decorOwned.add(dec.id)
        st.decorPlaced[dec.slot] = dec.id
        toast("Put up: ${dec.name}", null, Pal.goldDeep)
        audio.play(Sfx.UPGRADE); haptic(20)
        SaveManager.save(ctx, st)
    }

    fun placeDecor(dec: Decor) {
        if (!st.decorOwned.contains(dec.id)) return
        if (st.decorPlaced[dec.slot] == dec.id) {
            st.decorPlaced.remove(dec.slot)
            toast("Put away: ${dec.name}", null, Pal.inkSoft)
        } else {
            st.decorPlaced[dec.slot] = dec.id
            toast("Put up: ${dec.name}", null, Pal.ink)
        }
        audio.play(Sfx.TAP)
    }

    fun eat(id: String) {
        val item = Catalog.items[id] ?: return
        if (item.warmth <= 0) { toast("Better sold than eaten", null, Pal.inkSoft); return }
        if (!st.take(id, 1)) return
        st.warmth = min(100f, st.warmth + item.warmth)
        st.comfort = max(st.comfort, item.comfort)
        warmthWarned = 0
        particles.hearts(player.x, player.z, 1.8f, 2)
        particles.breath(player.x, player.y + 1.3f, player.z, player.yaw, 1.6f)
        toast(if (item.cat == Cat.MEAL) "That is better." else "Lovely.", id, Pal.ember)
        audio.play(Sfx.SIP)
        haptic(10)
    }

    // =============================================================== flavour

    private fun sitLine(): String {
        val lines = arrayOf(
            "Nothing to do but watch it come down",
            "The snow does all the moving",
            "Somewhere a branch gives up its load",
            "Cold air, warm coat, no hurry"
        )
        return lines[(U.hash(st.cosyMoments * 71 + 3) * lines.size).toInt().coerceIn(0, lines.size - 1)]
    }

    private fun windowLine(): String {
        val lines = arrayOf(
            "Blue out there. Orange in here.",
            "You can just make out the fence posts",
            "The glasshouse is glowing away to itself",
            "Fresh tracks. Deer, probably."
        )
        return lines[(U.hash(st.cosyMoments * 37 + 11) * lines.size).toInt().coerceIn(0, lines.size - 1)]
    }

    private fun catLine(): String {
        val lines = arrayOf(
            "Mitten does not open her eyes",
            "Mitten rolls over. High praise.",
            "A purr, felt more than heard",
            "She has been by the fire all day"
        )
        return lines[(U.hash(st.catAffection * 53 + 5) * lines.size).toInt().coerceIn(0, lines.size - 1)]
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
        if (seeds.isEmpty()) { selectedSeed = null; toast("No seed in your bag", null, Pal.inkSoft); return }
        val idx = seeds.indexOf(selectedSeed)
        selectedSeed = seeds[(idx + 1) % seeds.size]
        audio.play(Sfx.TAP)
    }

    /** A suggestion, never an instruction. */
    fun currentGoal(): String {
        if (st.count("firewood") <= 0 && st.hearthFuel < 1f) return "Split some logs before dark"
        if (!st.hearthLit) return "The stove wants feeding"
        if (st.plots.none { it.cropId != null } && st.tier.plots > 0) return "Something could be growing under glass"
        if (st.totalFish == 0) return "The pond is frozen thick enough to walk on"
        if (st.totalCooked == 0) return "There is a pot on the stove"
        val next = Tiers.next(st.cabinLevel)
        if (st.totalEarned < 300) return "Pip buys whatever you bring down"
        if (st.seenAnimals.size < 3) return "Something out there would like feeding"
        if (next != null) return "One day: ${next.name}"
        if (st.seenFish.size < Catalog.fish.size) return "Still fish under there you've not met"
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
            stickBaseX = x
            stickBaseY = y
            stickKnobX = stickBaseX
            stickKnobY = stickBaseY
            moveX = 0f; moveZ = 0f
            return
        }
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
        if (player.indoors) {
            return if (a.target >= 0 && a.kind != AKind.NONE) Interior.items[a.target].x else Float.NaN
        }
        return when (a.kind) {
            AKind.TILL, AKind.PLANT, AKind.WATER, AKind.HARVEST -> World.plotX(a.target)
            AKind.CHOP -> World.trees[a.target].x
            AKind.GATHER -> World.forage[a.target].x
            AKind.SIT, AKind.SPLIT, AKind.FEED_BIRDS, AKind.FEED_DEER,
            AKind.FIREPIT, AKind.SLED -> World.props[a.target].x
            AKind.SHOP -> World.MARKET_X
            AKind.ENTER -> World.CABIN_DOOR_X
            AKind.SOAK -> World.SPRING_X
            else -> Float.NaN
        }
    }

    fun hintTargetZ(): Float {
        val a = currentAction()
        if (player.indoors) {
            return if (a.target >= 0) Interior.items[a.target].z else 0f
        }
        return when (a.kind) {
            AKind.TILL, AKind.PLANT, AKind.WATER, AKind.HARVEST -> World.plotZ(a.target)
            AKind.CHOP -> World.trees[a.target].z
            AKind.GATHER -> World.forage[a.target].z
            AKind.SIT, AKind.SPLIT, AKind.FEED_BIRDS, AKind.FEED_DEER,
            AKind.FIREPIT, AKind.SLED -> World.props[a.target].z
            AKind.SHOP -> World.MARKET_Z
            AKind.ENTER -> World.CABIN_DOOR_Z
            AKind.SOAK -> World.SPRING_Z
            else -> 0f
        }
    }

    /** Height above the ground at which to float that marker. */
    fun hintHeight(): Float {
        if (player.indoors) return 1.5f
        return when (currentAction().kind) {
            AKind.CHOP -> 4.4f
            AKind.SHOP -> 4.6f
            AKind.ENTER -> 2.8f
            AKind.HARVEST -> 1.2f
            AKind.SIT -> 1.2f
            else -> 0.9f
        }
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
            Mode.STATION -> { drawHud(c); screens.drawStation(c) }
            Mode.DECOR -> { drawHud(c); screens.drawDecor(c) }
            Mode.JOURNAL -> { drawHud(c); screens.drawJournal(c) }
            Mode.SETTINGS -> { drawHud(c); screens.drawSettings(c) }
            Mode.SLEEP -> screens.drawSleep(c)
            Mode.INTRO -> { drawHud(c); screens.drawIntro(c) }
        }
        Ui.toasts(c, toasts, vw / 2f, 108f)
        if (dayBanner > 0f && mode == Mode.PLAY) drawDayBanner(c)
        if (fadeOut > 0.01f) Ui.scrim(c, vw, vh, fadeOut)
        if (settings.showFps) {
            Ui.text(
                c, "${fps.toInt()} fps", vw - 12f, vh - 12f, 18f,
                U.withAlpha(Pal.cream, 0.6f), Paint.Align.RIGHT
            )
        }
    }

    private fun drawDayBanner(c: Canvas) {
        val a = U.clamp01(dayBanner) * U.clamp01((2.8f - dayBanner) * 3f)
        val cx = vw / 2f
        Ui.textOut(
            c, "Day ${st.day}", cx, vh * 0.30f, 58f, Pal.cream, Pal.shadow,
            Paint.Align.CENTER, Ui.display, 8f, a
        )
        Ui.textOut(
            c, Weather.name(st.weather), cx, vh * 0.30f + 38f, 26f, Pal.frost, Pal.shadow,
            Paint.Align.CENTER, Ui.body, 5f, a
        )
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        val pad = 26f

        Ui.pill(c, pad, pad, 190f, 52f, U.withAlpha(Pal.woodDeep, 0.88f))
        Ui.coin(c, pad + 28f, pad + 26f, 17f)
        Ui.text(c, U.formatCoins(st.coins), pad + 54f, pad + 35f, 27f, Pal.gold, Paint.Align.LEFT, Ui.body)

        Ui.pill(c, pad, pad + 62f, 236f, 44f, U.withAlpha(Pal.woodDeep, 0.80f))
        Ui.text(c, "Day ${st.day}", pad + 18f, pad + 62f + 30f, 22f, Pal.cream, Paint.Align.LEFT)
        Ui.text(
            c, U.formatTime(st.timeMin % 1440f), pad + 120f, pad + 62f + 30f, 22f,
            U.withAlpha(Pal.cream, 0.9f), Paint.Align.LEFT
        )
        weatherGlyph(c, pad + 210f, pad + 62f + 22f)

        // ---- the two gauges that matter: how warm you are, and the stove ----
        if (!settings.gentle) {
            val bw = 150f
            Ui.text(c, "Warmth", pad + 4f, pad + 128f, 15f, U.withAlpha(Pal.cream, 0.8f), Paint.Align.LEFT)
            val warmCol = when {
                st.warmth > 58f -> Pal.ember
                st.warmth > 30f -> Pal.gold
                else -> Pal.frostDeep
            }
            Ui.bar(c, pad + 62f, pad + 116f, bw, 15f, st.warmth / 100f, warmCol)
        }
        run {
            val y = if (settings.gentle) pad + 116f else pad + 138f
            Ui.text(c, "Stove", pad + 4f, y + 12f, 15f, U.withAlpha(Pal.cream, 0.8f), Paint.Align.LEFT)
            val fuel = U.clamp01(st.hearthFuel / 12f)
            Ui.bar(
                c, pad + 62f, y, 150f, 15f, fuel,
                if (st.hearthLit && st.hearthFuel > 0f) Pal.ember else Pal.inkSoft
            )
        }

        val zone = World.zoneName(if (player.indoors) World.Z_INSIDE else World.zoneAt(player.x, player.z))
        Ui.textOut(
            c, zone, vw / 2f, pad + 34f, 26f, Pal.cream, U.withAlpha(Pal.shadow, 0.75f),
            Paint.Align.CENTER, Ui.display, 5f, 0.92f
        )
        Ui.text(c, currentGoal(), vw / 2f, pad + 60f, 17f, U.withAlpha(Pal.cream, 0.72f), Paint.Align.CENTER)

        if (mode != Mode.PLAY) return

        drawStick(c)

        val a = currentAction()
        val accent = when (a.kind) {
            AKind.FISH, AKind.REEL_IN -> Color.parseColor("#4E7EA8")
            AKind.SHOP -> Pal.gold
            AKind.SLEEP -> Color.parseColor("#7A6EA8")
            AKind.SIT, AKind.STAND, AKind.WINDOW -> Color.parseColor("#8A7EA0")
            AKind.CHOP, AKind.SPLIT -> Pal.woodDark
            AKind.STOKE, AKind.COOK, AKind.FIREPIT -> Pal.ember
            AKind.CRAFT -> Color.parseColor("#7A6248")
            AKind.PET -> Pal.berry
            AKind.SOAK -> Color.parseColor("#4E8A94")
            AKind.ENTER, AKind.EXIT -> Color.parseColor("#6A7EA0")
            AKind.NONE -> U.shade(Pal.woodDeep, 1.3f)
            else -> Pal.pine
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
                Ui.text(
                    c, "x${st.count(seed)}", bSeed.cx, bSeed.y + bSeed.h - 8f + bSeed.press * 5f, 15f,
                    Pal.ink, Paint.Align.CENTER
                )
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
        glyphPaint.color = U.withAlpha(U.shade(Pal.woodDeep, 1.25f), 0.55f * a)
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
        glyphPaint.color = U.withAlpha(U.shade(Pal.frost, 1.0f), 0.95f * a)
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
            Weather.BLIZZARD, Weather.SNOW -> {
                glyphPaint.color = U.withAlpha(Pal.cream, 0.92f)
                c.drawCircle(x - 6f, y - 1f, 9f, glyphPaint)
                c.drawCircle(x + 5f, y - 3f, 7f, glyphPaint)
                glyphPaint.color = Pal.frost
                for (i in -1..1) c.drawCircle(x + i * 7f, y + 11f + (i and 1) * 3f, 2.6f, glyphPaint)
                if (st.weather == Weather.BLIZZARD) {
                    glyphPaint.style = Paint.Style.STROKE
                    glyphPaint.strokeWidth = 2.2f
                    glyphPaint.strokeCap = Paint.Cap.ROUND
                    glyphPaint.color = U.withAlpha(Pal.frost, 0.8f)
                    for (i in 0 until 2) c.drawLine(x - 12f, y + 8f + i * 6f, x + 12f, y + 6f + i * 6f, glyphPaint)
                }
            }
            Weather.OVERCAST -> {
                glyphPaint.color = U.withAlpha(Pal.cream, 0.86f)
                c.drawCircle(x - 6f, y + 2f, 9f, glyphPaint)
                c.drawCircle(x + 5f, y, 7f, glyphPaint)
            }
            else -> {
                glyphPaint.color = Pal.gold
                c.drawCircle(x, y + 1f, 8f, glyphPaint)
                glyphPaint.style = Paint.Style.STROKE
                glyphPaint.strokeWidth = 2.6f
                glyphPaint.strokeCap = Paint.Cap.ROUND
                for (i in 0 until 8) {
                    val ang = i * 45f * 0.017453f
                    val dx = cos(ang); val dy = sin(ang)
                    c.drawLine(x + dx * 11f, y + 1f + dy * 11f, x + dx * 15f, y + 1f + dy * 15f, glyphPaint)
                }
            }
        }
    }
}
