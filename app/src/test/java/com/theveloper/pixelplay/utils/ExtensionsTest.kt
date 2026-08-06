package com.theveloper.pixelplay.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionsTest {

    @Test
    fun foldDiacritics_stripsSpanishAccentsAndTilde() {
        assertEquals("Que ganas de bailar", "Qué ganas de bailar".foldDiacritics())
        assertEquals("Manana", "Mañana".foldDiacritics())
        assertEquals("Cancion", "Canción".foldDiacritics())
    }

    @Test
    fun foldDiacritics_stripsAccentsAcrossOtherLatinLanguages() {
        assertEquals("Deja vu", "Déjà vu".foldDiacritics())
        assertEquals("uber", "über".foldDiacritics())
        assertEquals("naive", "naïve".foldDiacritics())
    }

    @Test
    fun foldDiacritics_stringWithoutDiacritics_isUnchanged() {
        assertEquals("Bohemian Rhapsody", "Bohemian Rhapsody".foldDiacritics())
    }

    @Test
    fun foldDiacritics_emptyString_returnsEmptyString() {
        assertEquals("", "".foldDiacritics())
    }

    @Test
    fun foldDiacritics_preservesCase() {
        // Folding removes marks only; it does not lowercase — callers combine with
        // ignoreCase separately (see SongFilterUtils.matchesTitleOrArtist).
        assertEquals("QUE", "QUÉ".foldDiacritics())
        assertEquals("que", "qué".foldDiacritics())
    }

    @Test
    fun foldDiacritics_nonLatinScript_isUnaffected() {
        // No combining marks to strip; the string round-trips unchanged.
        assertEquals("こんにちは", "こんにちは".foldDiacritics())
    }
}
