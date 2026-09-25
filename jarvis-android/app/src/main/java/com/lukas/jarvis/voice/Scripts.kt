package com.lukas.jarvis.voice

/**
 * Finds the stretches of a reply written in another alphabet, so each can be
 * read by a voice that knows it.
 *
 * "Thank you in Japanese is ありがとう" read by an English voice comes out as
 * silence or a spelled-out mess; the same sentence with the Japanese handed
 * to a Japanese voice sounds like someone who speaks both. Only scripts give
 * a language away — Spanish looks like English to a machine that has not read
 * it — so this deals in alphabets, and leaves everything else to the voice
 * already in use.
 */
object Scripts {

    data class Piece(val text: String, val language: String?)

    /**
     * The text cut where the alphabet changes. Spaces, digits and punctuation
     * belong to whatever surrounds them. A single piece with no language is
     * the common case and means "read it as usual".
     */
    fun split(text: String): List<Piece> {
        if (text.isEmpty()) return emptyList()
        // Kanji alone could be Chinese or Japanese; any kana settles it.
        val japanese = text.any { isKana(it) }
        val pieces = mutableListOf<Piece>()
        val current = StringBuilder()
        var language: String? = null
        var decided = false
        for (char in text) {
            val script = scriptOf(char, japanese)
            when {
                script == NEUTRAL -> current.append(char)
                !decided -> {
                    language = script
                    decided = true
                    current.append(char)
                }
                script == language -> current.append(char)
                else -> {
                    pieces += Piece(current.toString(), language)
                    current.setLength(0)
                    current.append(char)
                    language = script
                }
            }
        }
        if (current.isNotEmpty()) pieces += Piece(current.toString(), language)
        return merge(pieces)
    }

    /** True when some of the text needs a voice of its own. */
    fun mixed(text: String): Boolean = split(text).any { it.language != null }

    /**
     * Stray letters are not worth a change of voice: "Tokyo (東京)" keeps its
     * parentheses with the Japanese, and a lone "é" never gets here since
     * Latin is the default. Pieces of the same language that ended up next to
     * each other are joined.
     */
    private fun merge(pieces: List<Piece>): List<Piece> {
        val out = mutableListOf<Piece>()
        for (piece in pieces) {
            val last = out.lastOrNull()
            if (last != null && last.language == piece.language) {
                out[out.lastIndex] = Piece(last.text + piece.text, last.language)
            } else if (piece.text.isNotBlank() || last == null) {
                out += piece
            } else {
                out[out.lastIndex] = Piece(last.text + piece.text, last.language)
            }
        }
        return out
    }

    private fun isKana(char: Char): Boolean {
        val block = Character.UnicodeBlock.of(char)
        return block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA
    }

    private const val NEUTRAL = "neutral"

    /** A language tag for the alphabet [char] belongs to, null for Latin, [NEUTRAL] for neither. */
    private fun scriptOf(char: Char, japanese: Boolean): String? {
        if (!char.isLetter()) return NEUTRAL
        return when (Character.UnicodeScript.of(char.code)) {
            Character.UnicodeScript.LATIN -> null
            // The long-vowel mark in コーヒー and marks shared between
            // alphabets belong to whatever they sit in.
            Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> NEUTRAL
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "ja"
            Character.UnicodeScript.HAN -> if (japanese) "ja" else "zh"
            Character.UnicodeScript.HANGUL -> "ko"
            Character.UnicodeScript.CYRILLIC -> "ru"
            Character.UnicodeScript.GREEK -> "el"
            Character.UnicodeScript.ARABIC -> "ar"
            Character.UnicodeScript.HEBREW -> "he"
            Character.UnicodeScript.THAI -> "th"
            Character.UnicodeScript.DEVANAGARI -> "hi"
            Character.UnicodeScript.BENGALI -> "bn"
            Character.UnicodeScript.TAMIL -> "ta"
            Character.UnicodeScript.GEORGIAN -> "ka"
            Character.UnicodeScript.ARMENIAN -> "hy"
            else -> null
        }
    }
}
