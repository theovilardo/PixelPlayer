package com.theveloper.pixelplay.utils

import com.theveloper.pixelplay.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SongFilterUtilsTest {

    @Test
    fun matchesTitleOrArtist_blankQuery_alwaysMatches() {
        val song = buildSong(title = "Anything", artist = "Anyone")

        assertTrue(song.matchesTitleOrArtist(""))
        assertTrue(song.matchesTitleOrArtist("   "))
    }

    @Test
    fun matchesTitleOrArtist_matchesTitleCaseInsensitively() {
        val song = buildSong(title = "Bohemian Rhapsody", artist = "Queen")

        assertTrue(song.matchesTitleOrArtist("rhapsody"))
        assertTrue(song.matchesTitleOrArtist("BOHEMIAN"))
    }

    @Test
    fun matchesTitleOrArtist_matchesArtistCaseInsensitively() {
        val song = buildSong(title = "Bohemian Rhapsody", artist = "Queen")

        assertTrue(song.matchesTitleOrArtist("que"))
    }

    @Test
    fun matchesTitleOrArtist_noMatch_returnsFalse() {
        val song = buildSong(title = "Bohemian Rhapsody", artist = "Queen")

        assertFalse(song.matchesTitleOrArtist("metallica"))
    }

    @Test
    fun matchesTitleOrArtist_ignoresAlbumField() {
        val song = buildSong(title = "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera")

        assertFalse(song.matchesTitleOrArtist("opera"))
    }

    @Test
    fun filterByQuery_blankQuery_returnsOriginalListUnfiltered() {
        val songs = listOf(
            buildSong(id = "song-1", title = "Bohemian Rhapsody", artist = "Queen"),
            buildSong(id = "song-2", title = "Yesterday", artist = "The Beatles")
        )

        val result = songs.filterByQuery("")

        assertSame(songs, result)
    }

    @Test
    fun filterByQuery_returnsOnlyMatchingSongsPreservingOrder() {
        val songs = listOf(
            buildSong(id = "song-1", title = "Bohemian Rhapsody", artist = "Queen"),
            buildSong(id = "song-2", title = "Yesterday", artist = "The Beatles"),
            buildSong(id = "song-3", title = "Under Pressure", artist = "Queen"),
            buildSong(id = "song-4", title = "Imagine", artist = "John Lennon")
        )

        val result = songs.filterByQuery("queen")

        assertEquals(listOf(songs[0], songs[2]), result)
    }

    @Test
    fun filterByQuery_noMatches_returnsEmptyList() {
        val songs = listOf(
            buildSong(id = "song-1", title = "Bohemian Rhapsody", artist = "Queen"),
            buildSong(id = "song-2", title = "Yesterday", artist = "The Beatles")
        )

        val result = songs.filterByQuery("metallica")

        assertTrue(result.isEmpty())
    }

    @Test
    fun filterByQuery_emptyInputList_returnsEmptyList() {
        val result = emptyList<Song>().filterByQuery("queen")

        assertTrue(result.isEmpty())
    }

    @Test
    fun filterByQuery_matchesAcrossTitleAndArtistMixed() {
        val songs = listOf(
            buildSong(id = "song-1", title = "Bohemian Rhapsody", artist = "Queen"),
            buildSong(id = "song-2", title = "Yesterday", artist = "The Beatles")
        )

        val result = songs.filterByQuery("rhapsody")
        val resultByArtist = songs.filterByQuery("beatles")

        assertEquals(listOf(songs[0]), result)
        assertEquals(listOf(songs[1]), resultByArtist)
    }

    private fun buildSong(
        title: String,
        artist: String,
        album: String = "Album",
        id: String = "song-1"
    ): Song = Song(
        id = id,
        title = title,
        artist = artist,
        artistId = 1L,
        album = album,
        albumId = 1L,
        path = "/tmp/song-1.mp3",
        contentUriString = "content://pixelplay/song/1",
        albumArtUriString = null,
        duration = 180_000L,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )
}
