package com.lukas.jarvis

import com.lukas.jarvis.voice.FishVoiceOption
import com.lukas.jarvis.voice.VoiceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * From a spoken description to a search in Fish Audio's library: filler goes,
 * German becomes the English the titles use, a language becomes a filter, and
 * the results are ranked by what was asked for — not just by popularity.
 */
class VoiceMatchTest {

    @Test fun aNameIsSearchedWhole() {
        val plan = VoiceMatch.plan("mit der Stimme von Morgan Freeman")
        assertEquals("morgan freeman", plan.queries.first())
        assertNull(plan.language)
    }

    @Test fun germanDescriptionsBecomeTheLibrarysWords() {
        val plan = VoiceMatch.plan("mit einer tiefen männlichen deutschen Stimme")
        assertEquals("de", plan.language)
        assertEquals(listOf("deep", "male"), plan.words)
        assertEquals("deep male", plan.queries.first())
        assertTrue(plan.queries.containsAll(listOf("deep", "male")))
    }

    @Test fun strongWordsAreTriedFirstOnTheirOwn() {
        val plan = VoiceMatch.plan("a calm British narrator")
        assertEquals("en", plan.language)
        assertEquals("calm narrator", plan.queries[0])
        assertEquals("narrator", plan.queries[1])
    }

    @Test fun onlyALanguageStillSearches() {
        val plan = VoiceMatch.plan("eine französische Stimme")
        assertEquals("fr", plan.language)
        assertEquals(listOf(""), plan.queries)
    }

    @Test fun rankedByWhatWasAskedFor() {
        val popularFemale = FishVoiceOption("1", "Energetic Female", listOf("en"), 900_000)
        val deepMale = FishVoiceOption("2", "Deep Male Narrator", listOf("en"), 2_000, tags = listOf("male", "deep"))
        val germanMale = FishVoiceOption("3", "Tiefe Stimme", listOf("de"), 500, tags = listOf("male", "deep"))
        val plan = VoiceMatch.plan("deep male narrator")
        assertEquals(listOf("2", "3", "1"), VoiceMatch.rank(listOf(popularFemale, germanMale, deepMale), plan).map { it.id })
        // Asked in German: the German one comes first.
        val german = VoiceMatch.plan("tiefe männliche deutsche Stimme")
        assertEquals("3", VoiceMatch.rank(listOf(popularFemale, deepMale, germanMale), german).first().id)
    }

    @Test fun maleDoesNotMatchFemale() {
        val female = FishVoiceOption("f", "Calm Female", listOf("en"), 1000)
        val male = FishVoiceOption("m", "Calm Male", listOf("en"), 10)
        assertEquals("m", VoiceMatch.rank(listOf(female, male), VoiceMatch.plan("calm male voice")).first().id)
    }

    @Test fun anotherAndDefault() {
        assertTrue(VoiceMatch.wantsAnother("another"))
        assertTrue(VoiceMatch.wantsAnother("eine andere"))
        assertTrue(VoiceMatch.wantsAnother("the next one"))
        assertFalse(VoiceMatch.wantsAnother("another british voice"))
        assertTrue(VoiceMatch.wantsDefault("default"))
        assertTrue(VoiceMatch.wantsDefault("your normal voice"))
        assertTrue(VoiceMatch.wantsDefault("die normale Stimme"))
        assertFalse(VoiceMatch.wantsDefault("a normal person"))
    }
}
