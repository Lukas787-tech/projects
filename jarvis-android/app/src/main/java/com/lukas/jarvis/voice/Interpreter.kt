package com.lukas.jarvis.voice

/**
 * A conversation between two people who share no language, with the phone
 * held between them.
 *
 * Each side has a button that listens in that side's language; what was said
 * is translated into the other language and spoken aloud in a voice for it.
 * Hands-free, the phone then listens for the answer in the other language, so
 * after the first tap the two can simply talk.
 */
data class InterpreterState(
    /** The user's language, as a two-letter code. */
    val mine: String,
    /** The other person's language. */
    val theirs: String,
    /** "English", for the user's button and the header. */
    val mineName: String,
    /** "Español": the other person's language in their own words, for their button. */
    val theirsName: String,
    val lines: List<InterpretedLine> = emptyList(),
    /** Which side is being listened to, if any. */
    val listening: Side? = null,
    val working: Boolean = false,
    /** A short word on what went wrong, shown under the buttons. */
    val note: String? = null
) {
    enum class Side { Me, Them }

    fun languageOf(side: Side): String = if (side == Side.Me) mine else theirs

    fun other(side: Side): Side = if (side == Side.Me) Side.Them else Side.Me

    fun heard(side: Side, said: String, translated: String): InterpreterState =
        copy(
            lines = (lines + InterpretedLine(side, said, translated)).takeLast(MAX_LINES),
            listening = null,
            working = false,
            note = null
        )

    companion object {
        const val MAX_LINES = 40
    }
}

data class InterpretedLine(
    val side: InterpreterState.Side,
    val said: String,
    val translated: String
)
