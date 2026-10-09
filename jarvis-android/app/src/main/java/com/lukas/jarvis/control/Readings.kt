package com.lukas.jarvis.control

/** What a music app says it is playing. */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val app: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long
)

/** The numbers behind the control centre, read fresh each time it is shown. */
data class PhoneLevels(
    val media: Int,
    val ring: Int,
    val alarm: Int,
    val brightness: Int,
    val autoBrightness: Boolean,
    /** Whether brightness may be changed at all; Android grants it on its own page. */
    val canWriteSettings: Boolean,
    /** "normal", "vibrate" or "silent". */
    val ringer: String,
    val quiet: Boolean,
    val quietAccess: Boolean,
    val torch: Boolean
)
