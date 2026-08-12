package com.theveloper.pixelplay.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LocalPlaylistDaoTest {

    private lateinit var dao: LocalPlaylistDao
    private lateinit var db: WearMusicDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, WearMusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.localPlaylistDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun upsertPlaylist_isVisibleViaObservePlaylists() = runTest {
        val playlist = LocalPlaylistEntity(playlistId = "p1", name = "Running mix", createdAt = 1L, updatedAt = 1L)
        val songs = listOf(
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s1", position = 0),
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s2", position = 1),
        )

        dao.upsertPlaylist(playlist, songs)

        val stored = dao.observePlaylists().first()
        assertThat(stored).containsExactly(playlist)
    }

    @Test
    fun observePlaylistSongs_isOrderedByPosition() = runTest {
        val playlist = LocalPlaylistEntity(playlistId = "p1", name = "Running mix", createdAt = 1L, updatedAt = 1L)
        // Inserted out of order on purpose — the DAO's ORDER BY position must correct this, not
        // the insertion order.
        val songs = listOf(
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "third", position = 2),
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "first", position = 0),
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "second", position = 1),
        )

        dao.upsertPlaylist(playlist, songs)

        val ordered = dao.observePlaylistSongs("p1").first().map { it.songId }
        assertThat(ordered).containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun upsertPlaylist_replacesPreviousCrossRefsInsteadOfMerging() = runTest {
        val playlist = LocalPlaylistEntity(playlistId = "p1", name = "Running mix", createdAt = 1L, updatedAt = 1L)
        dao.upsertPlaylist(
            playlist,
            listOf(
                LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s1", position = 0),
                LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s2", position = 1),
            ),
        )

        // Re-sync with a song removed and the remaining one's position shifted — simulates the
        // phone re-sending after the user edited the playlist.
        dao.upsertPlaylist(
            playlist.copy(updatedAt = 2L),
            listOf(LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s2", position = 0)),
        )

        val songIds = dao.observePlaylistSongs("p1").first().map { it.songId }
        assertThat(songIds).containsExactly("s2")
    }

    @Test
    fun upsertPlaylist_doesNotAffectOtherPlaylists() = runTest {
        dao.upsertPlaylist(
            LocalPlaylistEntity(playlistId = "p1", name = "Running mix", createdAt = 1L, updatedAt = 1L),
            listOf(LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s1", position = 0)),
        )
        dao.upsertPlaylist(
            LocalPlaylistEntity(playlistId = "p2", name = "Chill mix", createdAt = 1L, updatedAt = 1L),
            listOf(LocalPlaylistSongCrossRef(playlistId = "p2", songId = "s2", position = 0)),
        )

        // Re-sync p1 only.
        dao.upsertPlaylist(
            LocalPlaylistEntity(playlistId = "p1", name = "Running mix", createdAt = 1L, updatedAt = 2L),
            listOf(LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s1-updated", position = 0)),
        )

        val p2Songs = dao.observePlaylistSongs("p2").first().map { it.songId }
        assertThat(p2Songs).containsExactly("s2")
    }

    @Test
    fun observeAllPlaylistSongCrossRefs_spansEveryPlaylist() = runTest {
        dao.upsertPlaylist(
            LocalPlaylistEntity(playlistId = "p1", name = "Running mix", createdAt = 1L, updatedAt = 1L),
            listOf(LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s1", position = 0)),
        )
        dao.upsertPlaylist(
            LocalPlaylistEntity(playlistId = "p2", name = "Chill mix", createdAt = 1L, updatedAt = 1L),
            listOf(LocalPlaylistSongCrossRef(playlistId = "p2", songId = "s2", position = 0)),
        )

        val allSongIds = dao.observeAllPlaylistSongCrossRefs().first().map { it.songId }
        assertThat(allSongIds).containsExactly("s1", "s2")
    }

    @Test
    fun getPlaylistById_returnsNullForAnUnknownPlaylist() = runTest {
        assertThat(dao.getPlaylistById("missing")).isNull()
    }
}
