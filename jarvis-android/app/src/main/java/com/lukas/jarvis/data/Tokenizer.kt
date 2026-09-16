package com.lukas.jarvis.data

/**
 * Small, dependency-free text normaliser used both to build the inverted index
 * and to turn a spoken question into lookup terms.
 */
object Tokenizer {

    private val STOPWORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "did", "do", "does",
        "for", "from", "had", "has", "have", "he", "her", "him", "his", "how", "i", "if",
        "in", "into", "is", "it", "its", "just", "me", "my", "of", "on", "or", "our", "out",
        "she", "so", "than", "that", "the", "their", "them", "then", "there", "these",
        "they", "this", "to", "too", "up", "was", "we", "were", "what", "when", "where",
        "which", "who", "why", "will", "with", "you", "your", "am", "been", "being", "get",
        "got", "im", "ive", "dont", "doesnt", "would", "could", "should", "about", "again"
    )

    private val SPLIT = Regex("[^\\p{L}\\p{Nd}]+")

    /** Lowercased, de-accented, stopword-free terms with a crude plural trim. */
    fun tokenize(text: String): List<String> =
        SPLIT.split(text.lowercase())
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filter { it !in STOPWORDS }
            .map { stem(it) }
            .filter { it.length >= 2 }
            .toList()

    /** Term -> occurrence count, which is what the index stores. */
    fun termFrequencies(text: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (t in tokenize(text)) counts[t] = (counts[t] ?: 0) + 1
        return counts
    }

    /**
     * Deliberately conservative suffix trimming. Aggressive stemming hurts more
     * than it helps on short personal notes, so this only folds obvious plurals
     * and a couple of common verb endings.
     */
    private fun stem(word: String): String {
        if (word.length <= 3) return word
        return when {
            word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
            word.endsWith("sses") -> word.dropLast(2)
            word.endsWith("ss") -> word
            word.endsWith("s") && !word.endsWith("us") -> word.dropLast(1)
            word.endsWith("ing") && word.length > 5 -> word.dropLast(3)
            word.endsWith("ed") && word.length > 4 -> word.dropLast(2)
            else -> word
        }
    }
}
