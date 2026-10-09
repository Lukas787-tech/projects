package com.lukas.jarvis.ui.character

/** What Mochi is doing, as the person would put it. */
enum class Mood {
    Idle, Listening, Thinking, Working, Speaking, Success, Confused, Sorry,
    /** Holding up a card, waiting for your yes. */
    Waiting,
    /** A timer or reminder ringing. */
    Alert,
    Sleepy, Delighted
}

/** A small idle moment, played now and then while nothing else is going on. */
enum class Flourish { None, Stretch, Sip, LookAround }

/**
 * Everything the renderer needs to draw Mochi right now. [talking] makes the
 * mouth follow the voice; [description] is what TalkBack says Mochi is doing.
 */
data class CharacterState(
    val mood: Mood = Mood.Idle,
    val prop: Prop? = null,
    val flourish: Flourish = Flourish.None,
    val talking: Boolean = false,
    /** A few pixel-font words beside Mochi: "hmm", "zz", "3:00". */
    val tag: String? = null,
    val description: String = "Mochi is here"
)

/**
 * The poses each mood moves through. Mochi animates at [FPS] frames a second,
 * like the pixel art it is; one call gives the pose for one tick of a loop of
 * [loopLength] ticks. With [reduceMotion] every mood holds one still pose and
 * nothing hops, sways or bobs.
 */
object Choreo {

    const val FPS = 8

    fun loopLength(state: CharacterState): Int = when {
        state.flourish != Flourish.None -> 24
        else -> when (state.mood) {
            Mood.Idle -> 48
            Mood.Sleepy -> 32
            Mood.Alert -> 8
            Mood.Success, Mood.Delighted -> 12
            Mood.Thinking -> 12
            else -> 24
        }
    }

    /** The mouth for a speaking level, 0 (silent) to 1 (loud). */
    fun mouthFor(level: Float): Mouth = when {
        level < 0.12f -> Mouth.Smile
        level < 0.42f -> Mouth.Small
        else -> Mouth.Open
    }

    /** How many mouth shapes speech uses; the renderer prepares one loop per shape. */
    val TALK_MOUTHS = listOf(Mouth.Smile, Mouth.Small, Mouth.Open)

    fun pose(state: CharacterState, tick: Int, reduceMotion: Boolean = false, talkMouth: Mouth? = null): Pose {
        val t = if (reduceMotion) 0 else tick
        val base = if (state.flourish != Flourish.None && state.mood == Mood.Idle) {
            flourish(state.flourish, t)
        } else {
            mood(state, t)
        }
        val blinking = !reduceMotion && blinks(state.mood) && t % 40 in 0..1
        var pose = if (blinking && base.eyes == Eyes.Open) base.copy(eyes = Eyes.Blink, rightEye = null) else base
        if (talkMouth != null && state.mood != Mood.Sleepy) pose = pose.copy(mouth = talkMouth)
        return pose
    }

    private fun blinks(mood: Mood) = mood !in setOf(Mood.Sleepy, Mood.Success, Mood.Delighted)

    private fun breathe(t: Int, period: Int) = if (t % period < period / 2) BodyShape.Rest else BodyShape.Squash

    private fun hop(t: Int): Pair<BodyShape, Int> = when (t % 12) {
        0, 1 -> BodyShape.Squash to 0
        2, 3 -> BodyShape.Stretch to 2
        4, 5 -> BodyShape.Stretch to 1
        6, 7 -> BodyShape.Squash to 0
        else -> BodyShape.Rest to 0
    }

    private fun mood(state: CharacterState, t: Int): Pose = when (state.mood) {
        Mood.Idle -> Pose(
            body = breathe(t, 24),
            curl = Curl.Relaxed,
            curlDx = if (t % 48 < 24) 0 else 1
        )
        Mood.Listening -> Pose(
            body = if (t % 12 < 6) BodyShape.Stretch else BodyShape.Rest,
            eyes = Eyes.Wide,
            mouth = Mouth.Small,
            curl = Curl.Perked
        )
        Mood.Thinking -> Pose(
            body = breathe(t, 12),
            look = Look.UpRight,
            mouth = Mouth.Flat,
            curl = when (t % 12) {
                in 0..2 -> Curl.LeanLeft
                in 6..8 -> Curl.LeanRight
                else -> Curl.Relaxed
            }
        )
        Mood.Working -> Pose(
            body = breathe(t, 16),
            look = Look.Right,
            mouth = if (t % 24 < 18) Mouth.Flat else Mouth.Small,
            curl = Curl.Relaxed,
            curlDx = if (t % 16 < 8) 0 else 1,
            prop = state.prop ?: Prop.Notepad,
            propDy = if (t % 8 < 4) 0 else -1
        )
        Mood.Speaking -> Pose(
            body = breathe(t, 16),
            mouth = Mouth.Smile,
            curl = Curl.Relaxed,
            curlDx = if (t % 24 < 12) 0 else 1
        )
        Mood.Success -> hop(t).let { (body, lift) ->
            Pose(
                body = body, lift = lift, eyes = Eyes.Happy, mouth = Mouth.Grin,
                curl = Curl.Star, arms = Arms.WaveLeft, waveDy = if (t % 4 < 2) 0 else -1
            )
        }
        Mood.Delighted -> hop(t).let { (body, lift) ->
            Pose(
                body = body, lift = lift, eyes = Eyes.Happy, mouth = Mouth.Grin, blush = Blush.Strong,
                curl = Curl.Heart, arms = Arms.WaveLeft, waveDy = if (t % 4 < 2) 0 else -1
            )
        }
        Mood.Confused -> Pose(
            body = BodyShape.LeanLeft,
            rightEye = Eyes.Blink,
            brows = Brows.Raised,
            look = Look.Up,
            mouth = if (t % 24 < 12) Mouth.Flat else Mouth.Frown,
            curl = Curl.Question,
            curlDx = -1
        )
        Mood.Sorry -> Pose(
            body = BodyShape.Slump,
            brows = Brows.Worried,
            look = Look.Down,
            mouth = Mouth.Frown,
            curl = Curl.Droop,
            curlDx = if (t % 24 < 12) 0 else -1
        )
        Mood.Waiting -> Pose(
            body = if (t % 16 < 8) BodyShape.Rest else BodyShape.Stretch,
            mouth = Mouth.Smile,
            curl = Curl.Perked,
            prop = state.prop ?: Prop.Card
        )
        Mood.Alert -> Pose(
            body = if (t % 4 < 2) BodyShape.Stretch else BodyShape.Squash,
            lift = if (t % 4 < 2) 1 else 0,
            eyes = Eyes.Wide,
            mouth = Mouth.Open,
            curl = Curl.Bang,
            prop = state.prop ?: Prop.Bell,
            propDy = if (t % 2 == 0) 0 else -1
        )
        Mood.Sleepy -> Pose(
            body = if (t % 32 < 16) BodyShape.Slump else BodyShape.Squash,
            eyes = Eyes.Closed,
            mouth = Mouth.Flat,
            curl = Curl.Zed,
            curlDy = -((t / 4) % 4)
        )
    }

    private fun flourish(flourish: Flourish, t: Int): Pose = when (flourish) {
        Flourish.Stretch -> if (t < 16) {
            Pose(body = BodyShape.Stretch, arms = Arms.BothUp, eyes = Eyes.Closed, mouth = Mouth.Small, curl = Curl.Perked)
        } else {
            Pose(body = BodyShape.Squash, eyes = Eyes.Happy, mouth = Mouth.Smile)
        }
        Flourish.Sip -> Pose(
            prop = Prop.Cup,
            propDy = if (t in 6..15) -2 else 0,
            eyes = if (t in 6..15) Eyes.Closed else Eyes.Open,
            mouth = if (t in 6..15) Mouth.Flat else Mouth.Smile,
            look = if (t in 6..15) Look.Ahead else Look.Right,
            blush = if (t in 10..20) Blush.Strong else Blush.Soft
        )
        Flourish.LookAround -> Pose(
            look = when (t) {
                in 0..7 -> Look.Left
                in 8..9 -> Look.Ahead
                in 10..19 -> Look.UpRight
                else -> Look.Ahead
            },
            mouth = if (t in 10..19) Mouth.Small else Mouth.Smile
        )
        Flourish.None -> Pose()
    }
}
