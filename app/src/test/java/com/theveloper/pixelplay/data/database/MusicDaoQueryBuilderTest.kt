package com.theveloper.pixelplay.data.database

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the FTS4 MATCH query builders. These must use
 * implicit AND (plain spaces) rather than the literal "AND" keyword, which
 * only behaves as a boolean operator on SQLite builds compiled with
 * SQLITE_ENABLE_FTS3_PARENTHESIS - not guaranteed on every Android device
 * (confirmed absent on at least one real device during manual testing).
 */
class MusicDaoQueryBuilderTest {

    @Test
    fun buildSongSearchMatchQuery_singleWord_returnsSinglePrefixTerm() {
        assertEquals("que*", buildSongSearchMatchQuery("que"))
    }

    @Test
    fun buildSongSearchMatchQuery_multipleWords_joinsWithImplicitAndNotLiteralKeyword() {
        val result = buildSongSearchMatchQuery("que ganas")

        assertEquals("que* ganas*", result)
        assertEquals(false, result.contains("AND", ignoreCase = true))
    }

    @Test
    fun buildSongSearchMatchQuery_blankQuery_returnsEmptyQuerySentinel() {
        assertEquals("pixelplayemptyquery*", buildSongSearchMatchQuery(""))
        assertEquals("pixelplayemptyquery*", buildSongSearchMatchQuery("   "))
    }

    @Test
    fun buildSongSearchMatchQuery_capsAtSixTokens() {
        val result = buildSongSearchMatchQuery("one two three four five six seven eight")

        assertEquals("one* two* three* four* five* six*", result)
    }

    @Test
    fun buildSongTitleSearchMatchQuery_singleWord_scopesToTitleColumn() {
        assertEquals("title:que*", buildSongTitleSearchMatchQuery("que"))
    }

    @Test
    fun buildSongTitleSearchMatchQuery_multipleWords_joinsWithImplicitAnd() {
        val result = buildSongTitleSearchMatchQuery("que ganas")

        assertEquals("title:que* title:ganas*", result)
        assertEquals(false, result.contains("AND", ignoreCase = true))
    }

    @Test
    fun buildSongTitleSearchMatchQuery_blankQuery_returnsEmptyQuerySentinel() {
        assertEquals("pixelplayemptyquery*", buildSongTitleSearchMatchQuery(""))
    }
}
