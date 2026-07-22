package com.theveloper.pixelplay.utils

import com.theveloper.pixelplay.data.model.Song
import org.junit.Assert.assertFalse
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

    private fun buildSong(
        title: String,
        artist: String,
        album: String = "Album"
    ): Song = Song(
        id = "song-1",
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
