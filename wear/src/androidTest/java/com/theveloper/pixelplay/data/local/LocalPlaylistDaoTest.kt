package com.theveloper.pixelplay.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented (needs a device/emulator — Room requires a real SQLite driver) coverage for the
 * playlist snapshot DAO added for whole-playlist watch transfer.
 */
@RunWith(AndroidJUnit4::class)
class LocalPlaylistDaoTest {

    private lateinit var database: WearMusicDatabase
    private lateinit var dao: LocalPlaylistDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WearMusicDatabase::class.java,
        ).build()
        dao = database.localPlaylistDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun crossRefs(playlistId: String, songIds: List<String>) =
        songIds.mapIndexed { index, songId -> LocalPlaylistSongCrossRef(playlistId, songId, index) }

    @Test
    fun upsertPlaylist_isVisibleViaObservePlaylists(): Unit = runBlocking {
        val playlist = LocalPlaylistEntity("p1", "Road Trip", createdAt = 1L, updatedAt = 1L)

        dao.upsertPlaylist(playlist, crossRefs("p1", listOf("s1", "s2")))

        val playlists = dao.observePlaylists().first()
        assertThat(playlists).containsExactly(playlist)
    }

    @Test
    fun observePlaylistSongs_isOrderedByPosition() = runBlocking {
        val playlist = LocalPlaylistEntity("p1", "Road Trip", createdAt = 1L, updatedAt = 1L)
        dao.upsertPlaylist(playlist, crossRefs("p1", listOf("s3", "s1", "s2")))

        val songs = dao.observePlaylistSongs("p1").first()

        assertThat(songs.map { it.songId }).containsExactly("s3", "s1", "s2").inOrder()
    }

    @Test
    fun upsertPlaylist_replacesPreviousCrossRefsInsteadOfMerging() = runBlocking {
        val playlist = LocalPlaylistEntity("p1", "Road Trip", createdAt = 1L, updatedAt = 1L)
        dao.upsertPlaylist(playlist, crossRefs("p1", listOf("s1", "s2")))

        // Re-sync with one fewer song and a new one, as if the phone playlist changed.
        dao.upsertPlaylist(playlist, crossRefs("p1", listOf("s2", "s3")))

        val songs = dao.observePlaylistSongs("p1").first()
        assertThat(songs.map { it.songId }).containsExactly("s2", "s3").inOrder()
    }

    @Test
    fun upsertPlaylist_doesNotAffectOtherPlaylists(): Unit = runBlocking {
        val playlistA = LocalPlaylistEntity("p1", "Road Trip", createdAt = 1L, updatedAt = 1L)
        val playlistB = LocalPlaylistEntity("p2", "Gym", createdAt = 2L, updatedAt = 2L)
        dao.upsertPlaylist(playlistA, crossRefs("p1", listOf("s1")))
        dao.upsertPlaylist(playlistB, crossRefs("p2", listOf("s2")))

        dao.upsertPlaylist(playlistA, crossRefs("p1", listOf("s1", "s3")))

        assertThat(dao.observePlaylistSongs("p2").first().map { it.songId }).containsExactly("s2")
        assertThat(dao.observePlaylists().first()).containsExactly(playlistA, playlistB)
    }
}
