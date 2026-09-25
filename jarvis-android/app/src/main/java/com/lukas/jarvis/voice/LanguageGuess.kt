package com.lukas.jarvis.voice

import java.util.Locale

/**
 * Which language a stretch of Latin-alphabet text is in, from the small words
 * every sentence is full of and the letters only some languages use.
 *
 * [Scripts] tells Japanese from English by the alphabet; French and German
 * share one, so a German voice read French answers with a heavy accent. The
 * little words give a sentence away quickly — "le", "est", "vous" are French,
 * "der", "nicht", "ist" German — without a model, a download or a network.
 * Unsure (too short, or two languages equally likely) says null, and the
 * voice already in use carries on.
 */
object LanguageGuess {

    /** The languages told apart, as language tags. */
    val SUPPORTED = listOf("en", "de", "fr", "es", "it", "pt", "nl", "pl", "sv", "tr")

    fun of(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        val words = WORD.findAll(lower).map { it.value.trim('\'', '’') }.filter { it.isNotEmpty() }.toList()
        if (words.size < 3) return null
        val score = HashMap<String, Double>()
        for (word in words) {
            WORDS[word]?.forEach { lang -> score[lang] = (score[lang] ?: 0.0) + 1.0 / (WORDS[word]!!.size) }
        }
        for ((letters, langs) in LETTERS) {
            val hits = lower.count { it in letters }
            if (hits > 0) langs.forEach { lang -> score[lang] = (score[lang] ?: 0.0) + minOf(hits, 3) * 1.2 / langs.size }
        }
        val ranked = score.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0.0
        // Enough evidence, and clearly more than for anything else.
        if (best.value < 2.0) return null
        if (best.value < second * 1.5 || best.value - second < 1.0) return null
        return best.key
    }

    private val WORD = Regex("[\\p{L}'’]+")

    private val LISTS = mapOf(
        "en" to "the and is are was were you your it's that this with have has what for not of to i'm it be will can there my me on at would should could from they we do don't does".split(' '),
        "de" to "der die das und ist nicht ich du sie ein eine einen einem mit auf für von zu es wir ihr sind war wie was noch auch aber bitte heute dein mein habe hast kann wird dem den des im ist's gibt sich schon jetzt dann oder nur wenn dass gerne".split(' '),
        "fr" to "le la les et est je tu vous nous il elle une des du pour pas que qui dans sur avec c'est ce cette mais très bonjour merci oui suis sont au aux votre mon ma mes ton ne j'ai n'est d'un l'on qu'il ça".split(' '),
        "es" to "el la los las y es que no yo tú usted una un por para con pero muy está están hola gracias sí del al como qué lo se su mi estoy tengo hay también cuando dónde".split(' '),
        "it" to "il lo la gli e è che non io tu lei una un per con ma molto sono ciao grazie sì del della di questo come cosa anche mi ti ho hai c'è perché dove quando".split(' '),
        "pt" to "o os as e é que não eu você uma um para com mas muito está olá obrigado obrigada sim do da dos das em no na como isso tenho também quando onde".split(' '),
        "nl" to "de het een en is niet ik jij je u wij met op voor van maar heel dank ja zijn dat wat hoe ook er naar bij heb hebt kan wordt".split(' '),
        "pl" to "i w nie się na jest to że z do jak ale co tak jestem dziękuję proszę czy mam być już tylko".split(' '),
        "sv" to "och är att det inte jag du en ett med på för av som har till vad hur också men tack ja".split(' '),
        "tr" to "ve bir bu da de ne için ile çok ben sen var yok mi mı değil ama nasıl teşekkürler evet".split(' ')
    )

    /** Each small word, with every language it belongs to; shared ones count for less. */
    private val WORDS: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        LISTS.forEach { (lang, list) -> list.forEach { getOrPut(it) { mutableListOf() }.add(lang) } }
    }

    /** Letters only a few of the languages use. */
    private val LETTERS = listOf(
        "ß" to listOf("de"),
        "äöü" to listOf("de", "sv", "tr"),
        "œæ" to listOf("fr"),
        "èêëîïôûùâ" to listOf("fr"),
        "ç" to listOf("fr", "pt", "tr"),
        "ñ¿¡" to listOf("es"),
        "ãõ" to listOf("pt"),
        "òì" to listOf("it"),
        "ąćęłńśźż" to listOf("pl"),
        "å" to listOf("sv"),
        "ğış" to listOf("tr")
    )
}
