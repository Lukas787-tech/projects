package com.lukas.jarvis.voice

/**
 * Where a reply that is still being written can be cut for speaking.
 *
 * A sentence is done at a line break, or when its full stop, question mark,
 * exclamation mark or ellipsis is followed by white space — "3.5" in the
 * middle of a number does not end anything. Very short pieces wait for company,
 * so "Hi." is not spoken alone and then followed by a pause.
 */
object Sentences {

    /**
     * The end of the last whole sentence in [text] after [from], or [from]
     * itself when no sentence has finished yet.
     */
    fun boundary(text: String, from: Int, minimum: Int = 24): Int {
        var end = from
        var i = from
        while (i < text.length - 1) {
            val c = text[i]
            val ends = c == '\n' ||
                ((c == '.' || c == '!' || c == '?' || c == '…') && text[i + 1].isWhitespace())
            if (ends) {
                if (i + 1 - from >= minimum || end > from) end = i + 1
            }
            i++
        }
        return end
    }
}
