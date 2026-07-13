package com.theveloper.pixelplay.data.nlp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NlpCommandParserTest {

    @Test
    fun `parse CreatePlaylist commands with various formats`() {
        val testCases = listOf(
            "create playlist of Nirvana" to listOf("Nirvana"),
            "create a playlist for Queen" to listOf("Queen"),
            "make a playlist from the beatles" to listOf("the beatles"),
            "build playlist Linkin Park" to listOf("Linkin Park"),
            "generate playlist with Eminem" to listOf("Eminem"),
            "create playlist called jazz" to listOf("jazz"),
            "create a playlist of Pop Smoke and Nirvana" to listOf("Pop Smoke", "Nirvana"),
            "make a playlist from Queen, the beatles and Eminem" to listOf("Queen", "the beatles", "Eminem"),
            "create playlist of Nirvana's songs" to listOf("Nirvana"),
            "create playlist of songs by Queen" to listOf("Queen"),
            "create playlist of Ashvan's music and Pop Smoke's tracks" to listOf("Ashvan", "Pop Smoke")
        )

        for ((input, expectedTargets) in testCases) {
            val intent = NlpCommandParser.parse(input)
            assertTrue(intent is NlpCommandIntent.CreatePlaylist, "Failed on input: $input")
            val createPlaylist = intent as NlpCommandIntent.CreatePlaylist
            assertEquals(expectedTargets, createPlaylist.targetQueries)
        }
    }

    @Test
    fun `parse DeleteArtist commands with various formats`() {
        val testCases = listOf(
            "delete artist Linkin Park" to listOf("Linkin Park"),
            "delete singer Michael Jackson" to listOf("Michael Jackson"),
            "remove artist Eminem" to listOf("Eminem"),
            "delete Nirvana" to listOf("Nirvana"),
            "remove Queen" to listOf("Queen"),
            "erase Nirvana" to listOf("Nirvana"),
            "delete Pop Smoke and Nirvana" to listOf("Pop Smoke", "Nirvana"),
            "delete Nirvana's songs" to listOf("Nirvana")
        )

        for ((input, expectedTargets) in testCases) {
            val intent = NlpCommandParser.parse(input)
            assertTrue(intent is NlpCommandIntent.DeleteArtist, "Failed on input: $input")
            val deleteArtist = intent as NlpCommandIntent.DeleteArtist
            assertEquals(expectedTargets, deleteArtist.targetQueries)
        }
    }

    @Test
    fun `parse CategorizeGenre commands with various formats`() {
        val testCases = listOf(
            "categorize songs by genre rock" to listOf("rock"),
            "group music by rock" to listOf("rock"),
            "organize by genre jazz" to listOf("jazz"),
            "categorize genre pop" to listOf("pop"),
            "group songs by classical" to listOf("classical"),
            "sort by genre metal" to listOf("metal"),
            "group songs by rock and jazz" to listOf("rock", "jazz")
        )

        for ((input, expectedTargets) in testCases) {
            val intent = NlpCommandParser.parse(input)
            assertTrue(intent is NlpCommandIntent.CategorizeGenre, "Failed on input: $input")
            val categorizeGenre = intent as NlpCommandIntent.CategorizeGenre
            assertEquals(expectedTargets, categorizeGenre.genreNames)
        }
    }

    @Test
    fun `parse invalid or unknown commands`() {
        val invalidInputs = listOf(
            "",
            "   ",
            "play some jazz",
            "find lyrics for hello",
            "open settings",
            "increase volume"
        )

        for (input in invalidInputs) {
            val intent = NlpCommandParser.parse(input)
            assertEquals(NlpCommandIntent.Unknown, intent, "Failed on input: $input")
        }
    }

    @Test
    fun `levenshtein distance calculates correctly`() {
        assertEquals(0, NlpFuzzyMatcher.levenshteinDistance("abc", "abc"))
        assertEquals(1, NlpFuzzyMatcher.levenshteinDistance("Nirvna", "Nirvana"))
        assertEquals(1, NlpFuzzyMatcher.levenshteinDistance("Nirvana", "Nirvna"))
        assertEquals(1, NlpFuzzyMatcher.levenshteinDistance("Nirvana", "Nirvama"))
        assertEquals(3, NlpFuzzyMatcher.levenshteinDistance("kitten", "sitting"))
        assertEquals(5, NlpFuzzyMatcher.levenshteinDistance("intention", "execution"))
    }

    @Test
    fun `find best fuzzy match within threshold`() {
        val candidates = listOf("Nirvana", "Linkin Park", "Queen", "The Beatles", "Eminem")

        assertEquals("Nirvana", NlpFuzzyMatcher.findBestMatch("Nirvana", candidates))
        assertEquals("Nirvana", NlpFuzzyMatcher.findBestMatch("nirvana", candidates))

        assertEquals("Linkin Park", NlpFuzzyMatcher.findBestMatch("Linkin", candidates))

        assertEquals("Nirvana", NlpFuzzyMatcher.findBestMatch("Nirvna", candidates))
        assertEquals("Queen", NlpFuzzyMatcher.findBestMatch("Quen", candidates))

        assertNull(NlpFuzzyMatcher.findBestMatch("Radiohead", candidates))
        assertNull(NlpFuzzyMatcher.findBestMatch("Queeeen", candidates))
    }

    @Test
    fun `find all fuzzy matches sorted by distance`() {
        val candidates = listOf("Rock", "Hard Rock", "Classic Rock", "Pop", "Jazz", "Punk Rock")

        val matches = NlpFuzzyMatcher.findAllMatches("rock", candidates)

        assertEquals(4, matches.size)
        assertEquals("Rock", matches[0])
        assertTrue(matches.contains("Hard Rock"))
        assertTrue(matches.contains("Classic Rock"))
        assertTrue(matches.contains("Punk Rock"))
    }
}
