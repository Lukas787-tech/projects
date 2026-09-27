package com.lukas.jarvis.voice

import java.util.Locale
import kotlin.math.log10

/**
 * From "a deep male German narrator" or "sprich wie Morgan Freeman" to a
 * search in Fish Audio's library and the best of what it returns.
 *
 * The library is searched by title, and titles are mostly English and short,
 * so the description is boiled down: filler goes, German descriptions become
 * the English words the titles use, and a language named in it becomes a
 * filter rather than a search word. The whole phrase is tried first — a name
 * like "Morgan Freeman" is found that way — then its strongest single words.
 */
object VoiceMatch {

    data class Plan(
        /** Searches to try, best first. */
        val queries: List<String>,
        /** A two-letter language to filter by, if the description named one. */
        val language: String?,
        /** The words that describe the voice, to rank the results by. */
        val words: List<String>
    )

    fun plan(description: String, language: String? = null): Plan {
        val raw = description.lowercase(Locale.ROOT)
            .replace(Regex("['’]s\\b"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        var found = language?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.length == 2 }
        val words = mutableListOf<String>()
        raw.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
            val lang = LANGUAGES[word] ?: LANGUAGES[word.trimEnd('e', 'n', 'r', 's', 'm')]
            when {
                lang != null -> if (found == null) found = lang
                word in FILLER -> {}
                else -> words += TRANSLATE[word] ?: TRANSLATE[word.trimEnd('e', 'n', 'r', 's', 'm')] ?: word
            }
        }
        val distinct = words.distinct()
        val queries = buildList {
            if (distinct.isNotEmpty()) add(distinct.joinToString(" "))
            // Then the words on their own, the most telling first.
            distinct.sortedWith(compareByDescending<String> { it in STRONG }.thenByDescending { it.length })
                .filter { it.length >= 3 }
                .take(3)
                .forEach { add(it) }
            // Nothing to go on but a language: its most used voices.
            if (isEmpty()) add("")
        }.distinct()
        return Plan(queries, found, distinct)
    }

    /**
     * The results in the order to offer them: the most described words in the
     * title, then in the tags and description, then the right language, then
     * how often other people have used the voice.
     */
    fun rank(voices: List<FishVoiceOption>, plan: Plan): List<FishVoiceOption> {
        fun score(voice: FishVoiceOption): Double {
            val title = voice.title.lowercase(Locale.ROOT)
            val rest = (voice.tags + voice.description).joinToString(" ").lowercase(Locale.ROOT)
            var s = 0.0
            plan.words.forEach { word ->
                when {
                    title.contains(word) -> s += 3.0
                    rest.contains(word) -> s += 1.5
                }
                // "male" must not count for "female".
                if (word == "male" && (title.contains("female") || rest.contains("female")) &&
                    !Regex("\\bmale\\b").containsMatchIn("$title $rest")) s -= 4.5
            }
            if (plan.language != null && plan.language in voice.languages) s += 2.0
            if (plan.language != null && voice.languages.isNotEmpty() && plan.language !in voice.languages) s -= 2.0
            return s + 0.4 * log10(voice.uses + 1.0)
        }
        return voices.distinctBy { it.id }.sortedByDescending { score(it) }
    }

    /** "Another one", "the next", "eine andere": the next of the last results, not a new search. */
    fun wantsAnother(description: String): Boolean =
        ANOTHER.matches(description.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?'))

    /** "Your normal voice", "Standardstimme": Fish's default, no reference voice. */
    fun wantsDefault(description: String): Boolean =
        DEFAULT.matches(description.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?'))

    private val ANOTHER = Regex(
        "(?:(?:an|a|eine?|the|die)\\s+)?(?:another|other|different|next|else|andere|anderen|nächste|naechste|neue)" +
            "(?:\\s+(?:one|voice|stimme|bitte|please))*|(?:try|nimm|probier)\\s+(?:another|eine andere|die nächste)(?:\\s+one)?"
    )
    private val DEFAULT = Regex(
        "(?:(?:your|the|deine|die)\\s+)?(?:default|normal|usual|standard|original|alte|normale|übliche)(?:\\s+(?:one|voice|stimme))?"
    )

    private val FILLER = setOf(
        "a", "an", "the", "voice", "voices", "stimme", "stimmen", "like", "wie", "von", "of", "with", "mit",
        "in", "sound", "sounds", "klingt", "klingen", "speak", "talk", "sprich", "sprech", "rede", "please",
        "bitte", "now", "jetzt", "mal", "use", "nimm", "benutze", "benutz", "one", "that", "die", "der",
        "das", "den", "dem", "des", "eine", "einer", "einen", "ein", "einem", "some", "kind", "sort", "art",
        "so", "als", "as", "is", "ist", "und", "and", "me", "mir", "mich", "to", "zu", "you", "du", "dir",
        "your", "deine", "deiner", "for", "für", "im", "auf", "on", "stil", "style", "tone", "ton", "person",
        "someone", "jemand", "jemandem", "who", "der", "sounding", "klingenden", "klingende"
    )

    /** Words that say most about a voice, searched on their own before the rest. */
    private val STRONG = setOf("narrator", "robot", "whisper", "anime", "trailer", "villain", "radio", "news", "asmr")

    private val LANGUAGES = mapOf(
        "german" to "de", "deutsch" to "de", "deutsche" to "de", "deutscher" to "de", "deutschen" to "de",
        "english" to "en", "englisch" to "en", "englische" to "en", "british" to "en", "britisch" to "en",
        "britische" to "en", "american" to "en", "amerikanisch" to "en", "amerikanische" to "en",
        "french" to "fr", "französisch" to "fr", "französische" to "fr", "franzoesisch" to "fr",
        "spanish" to "es", "spanisch" to "es", "spanische" to "es",
        "italian" to "it", "italienisch" to "it", "italienische" to "it",
        "japanese" to "ja", "japanisch" to "ja", "japanische" to "ja",
        "chinese" to "zh", "chinesisch" to "zh", "chinesische" to "zh",
        "portuguese" to "pt", "portugiesisch" to "pt", "portugiesische" to "pt",
        "russian" to "ru", "russisch" to "ru", "russische" to "ru",
        "dutch" to "nl", "niederländisch" to "nl", "holländisch" to "nl",
        "polish" to "pl", "polnisch" to "pl", "polnische" to "pl",
        "turkish" to "tr", "türkisch" to "tr", "türkische" to "tr",
        "korean" to "ko", "koreanisch" to "ko", "koreanische" to "ko",
        "arabic" to "ar", "arabisch" to "ar", "arabische" to "ar"
    )

    /** German descriptions in the English the library's titles use. */
    private val TRANSLATE = mapOf(
        "männlich" to "male", "männliche" to "male", "männlichen" to "male", "mann" to "male", "man" to "male",
        "maennlich" to "male", "guy" to "male", "weiblich" to "female", "weibliche" to "female",
        "weiblichen" to "female", "frau" to "female", "woman" to "female", "girl" to "female", "mädchen" to "female",
        "tief" to "deep", "tiefe" to "deep", "tiefen" to "deep", "hoch" to "high", "hohe" to "high",
        "ruhig" to "calm", "ruhige" to "calm", "ruhigen" to "calm", "sanft" to "soft", "sanfte" to "soft",
        "alt" to "old", "alte" to "old", "alten" to "old", "jung" to "young", "junge" to "young", "jungen" to "young",
        "erzähler" to "narrator", "erzählerin" to "narrator", "sprecher" to "narrator", "sprecherin" to "narrator",
        "roboter" to "robot", "flüstern" to "whisper", "flüsternd" to "whisper", "flüsternde" to "whisper",
        "freundlich" to "friendly", "freundliche" to "friendly", "warm" to "warm", "warme" to "warm",
        "energisch" to "energetic", "energische" to "energetic", "lustig" to "funny", "lustige" to "funny",
        "böse" to "evil", "boese" to "evil", "kinder" to "child", "kinderstimme" to "child", "ernst" to "serious",
        "ernste" to "serious", "rau" to "raspy", "raue" to "raspy", "heiser" to "raspy", "dunkel" to "dark",
        "dunkle" to "dark", "dunklen" to "dark", "nachrichten" to "news", "professionell" to "professional",
        "professionelle" to "professional", "sexy" to "seductive", "verführerisch" to "seductive",
        "opa" to "grandpa", "oma" to "grandma", "held" to "hero", "bösewicht" to "villain", "schurke" to "villain"
    )
}
