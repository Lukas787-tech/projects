package com.lukas.jarvis

import com.lukas.jarvis.voice.LanguageGuess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageGuessTest {

    @Test fun tellsTheLatinLanguagesApart() {
        assertEquals("fr", LanguageGuess.of("Bonjour, je suis Jarvis. Il fait beau aujourd'hui à Paris."))
        assertEquals("fr", LanguageGuess.of("C'est une bonne idée, mais je ne sais pas."))
        assertEquals("de", LanguageGuess.of("Das Wetter ist heute schön, aber morgen regnet es."))
        assertEquals("de", LanguageGuess.of("Ich habe den Timer auf fünf Minuten gestellt."))
        assertEquals("en", LanguageGuess.of("The timer is set for five minutes and it will ring."))
        assertEquals("es", LanguageGuess.of("Hola, ¿qué tal? Hoy hace mucho calor en Madrid."))
        assertEquals("it", LanguageGuess.of("Ciao, come stai? Oggi fa molto caldo, perché è estate."))
        assertEquals("pt", LanguageGuess.of("Olá, tudo bem? Não tenho tempo hoje, obrigado."))
        assertEquals("nl", LanguageGuess.of("Het weer is vandaag mooi, maar ik heb geen tijd."))
        assertEquals("pl", LanguageGuess.of("Dziękuję, nie mam czasu, ale jutro jest dobrze."))
        assertEquals("sv", LanguageGuess.of("Tack, jag har inte tid i dag men det är bra."))
        assertEquals("tr", LanguageGuess.of("Teşekkürler, bugün çok güzel bir gün ama işim var."))
    }

    @Test fun unsureWhenThereIsTooLittleToGoOn() {
        assertNull(LanguageGuess.of("OK."))
        assertNull(LanguageGuess.of("Canberra."))
        assertNull(LanguageGuess.of("12:30, 5 km"))
    }
}
