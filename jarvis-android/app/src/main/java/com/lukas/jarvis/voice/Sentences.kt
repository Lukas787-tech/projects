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
     * A whole reply cut for a cloud voice that is fetched a piece at a time:
     * the first sentence alone, so the voice starts quickly, then sentences
     * grouped up to [limit] characters, so it does not sound clipped.
     */
    fun forCloud(text: String, limit: Int = 280): List<String> {
        val sentences = text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        sentences.forEach { sentence ->
            sentence.chunked(limit).forEach { piece ->
                val firstIsDone = out.isEmpty() && current.isNotEmpty()
                if (current.isNotEmpty() && (firstIsDone || current.length + piece.length + 1 > limit)) {
                    out += current.toString()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.ifEmpty { listOf(text.trim()) }.filter { it.isNotBlank() }
    }

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
