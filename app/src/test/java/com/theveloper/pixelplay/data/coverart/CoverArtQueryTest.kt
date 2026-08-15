package com.theveloper.pixelplay.data.coverart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverArtQueryTest {

    @Test
    fun `normalizeAlbum drops bracketed edition noise`() {
        assertEquals("abbey road", CoverArtQuery.normalizeAlbum("Abbey Road (Remastered 2019)"))
        assertEquals(
            "the dark side of the moon",
            CoverArtQuery.normalizeAlbum("The Dark Side of the Moon [50th Anniversary]")
        )
    }

    @Test
    fun `normalizeAlbum drops trailing edition suffixes`() {
        assertEquals("nevermind", CoverArtQuery.normalizeAlbum("Nevermind - Deluxe Edition"))
        assertEquals("in rainbows", CoverArtQuery.normalizeAlbum("In Rainbows - 2016 Remaster"))
    }

    @Test
    fun `normalizeAlbum keeps bracketed content that is part of the title`() {
        assertEquals(
            "blue train the ultimate blue train",
            CoverArtQuery.normalizeAlbum("Blue Train (The Ultimate Blue Train)")
        )
    }

    @Test
    fun `normalizeAlbum removes diacritics and punctuation`() {
        assertEquals(
            "sgt peppers lonely hearts club band",
            CoverArtQuery.normalizeAlbum("Sgt. Pepper's Lonely Hearts Club Band")
        )
        assertEquals("bjork", CoverArtQuery.normalizeAlbum("Björk"))
        assertEquals(
            CoverArtQuery.normalizeAlbum("Sgt. Peppers Lonely Hearts Club Band"),
            CoverArtQuery.normalizeAlbum("Sgt. Pepper’s Lonely Hearts Club Band")
        )
    }

    @Test
    fun `normalizeAlbum keeps a title made entirely of keywords`() {
        assertEquals("live", CoverArtQuery.normalizeAlbum("Live"))
    }

    @Test
    fun `normalizeArtist drops featured credits`() {
        assertEquals("daft punk", CoverArtQuery.normalizeArtist("Daft Punk feat. Pharrell Williams"))
        assertEquals("gorillaz", CoverArtQuery.normalizeArtist("Gorillaz (feat. De La Soul)"))
    }

    @Test
    fun `normalizeArtist spells out ampersands`() {
        assertEquals(
            CoverArtQuery.normalizeArtist("Simon and Garfunkel"),
            CoverArtQuery.normalizeArtist("Simon & Garfunkel")
        )
    }

    @Test
    fun `similarity is one for equal strings and zero for empty input`() {
        assertEquals(1f, CoverArtQuery.similarity("discovery", "discovery"))
        assertEquals(0f, CoverArtQuery.similarity("", "discovery"))
        assertEquals(0f, CoverArtQuery.similarity("discovery", ""))
    }

    @Test
    fun `score ignores the artist when the query has none`() {
        val score = CoverArtQuery.score(
            candidateAlbum = "Random Access Memories",
            candidateArtist = "Daft Punk",
            queryAlbum = "Random Access Memories",
            queryArtist = ""
        )

        assertEquals(1f, score)
    }

    @Test
    fun `score survives edition noise on either side`() {
        val score = CoverArtQuery.score(
            candidateAlbum = "Abbey Road",
            candidateArtist = "The Beatles",
            queryAlbum = "Abbey Road (Remastered 2019)",
            queryArtist = "The Beatles"
        )

        assertEquals(1f, score)
    }

    @Test
    fun `rank puts the best match first and drops unrelated results`() {
        val candidates = listOf(
            candidate(id = "thriller", album = "Thriller", artist = "Michael Jackson"),
            candidate(id = "discovery", album = "Discovery", artist = "Daft Punk"),
            candidate(id = "ram", album = "Random Access Memories", artist = "Daft Punk")
        )

        val ranked = CoverArtQuery.rank(
            candidates = candidates,
            queryAlbum = "Random Access Memories",
            queryArtist = "Daft Punk"
        )

        assertEquals("ram", ranked.first().id)
        assertTrue(ranked.none { it.id == "thriller" }, "unrelated album should be dropped")
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `rank is stable for candidates that score the same`() {
        val candidates = listOf(
            candidate(id = "b", album = "Homework", artist = "Daft Punk"),
            candidate(id = "a", album = "Homework", artist = "Daft Punk")
        )

        val ranked = CoverArtQuery.rank(candidates, "Homework", "Daft Punk")

        assertEquals(listOf("b", "a"), ranked.map { it.id })
    }

    private fun candidate(id: String, album: String, artist: String) = CoverArtCandidate(
        id = id,
        albumTitle = album,
        artistName = artist,
        thumbnailUrl = "https://example.test/$id/250.jpg",
        imageUrl = "https://example.test/$id/1000.jpg",
        source = CoverArtSource.DEEZER
    )
}
