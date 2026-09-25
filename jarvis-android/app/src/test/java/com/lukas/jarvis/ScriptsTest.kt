package com.lukas.jarvis

/*
 * The stretches of a reply in another alphabet, found so each can be read by
 * a voice that knows it.
 */
import com.lukas.jarvis.voice.Scripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptsTest {

    private fun languages(text: String) = Scripts.split(text).map { it.language }

    @Test fun plainTextIsOnePiece() {
        assertEquals(listOf(null), languages("It's 18 degrees and sunny, Lukas."))
        assertFalse(Scripts.mixed("Ça coûte 3,50 € — très bien!"))
        assertTrue(Scripts.split("").isEmpty())
    }

    @Test fun japaneseInsideEnglish() {
        val pieces = Scripts.split("Thank you in Japanese is ありがとう (arigatou).")
        assertEquals(listOf(null, "ja", null), pieces.map { it.language })
        assertEquals("Thank you in Japanese is ", pieces[0].text)
        assertTrue(pieces[1].text.startsWith("ありがとう"))
        // Nothing is lost in the cutting.
        assertEquals("Thank you in Japanese is ありがとう (arigatou).", pieces.joinToString("") { it.text })
    }

    @Test fun theLongVowelMarkStaysInsideTheWord() {
        assertEquals(listOf(null, "ja"), languages("Coffee is コーヒー"))
    }

    @Test fun kanjiAloneIsChineseAndWithKanaIsJapanese() {
        assertEquals(listOf(null, "zh"), languages("Hello in Mandarin: 你好"))
        assertEquals(listOf(null, "ja"), languages("Tokyo is 東京です"))
    }

    @Test fun otherAlphabets() {
        assertEquals(listOf(null, "ru"), languages("Thank you in Russian: спасибо"))
        assertEquals(listOf(null, "ko", null), languages("Say 감사합니다 to thank someone"))
        assertEquals(listOf(null, "ar"), languages("Hello: مرحبا"))
        assertEquals(listOf(null, "el"), languages("Thanks in Greek is ευχαριστώ"))
    }
}
