package com.theveloper.pixelplay.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [WearMusicDatabase.MIGRATION_5_6] and [WearMusicDatabase.MIGRATION_6_7] against
 * hand-built database files.
 *
 * `:wear` doesn't export Room schema JSON (`exportSchema = false`), so [androidx.room.testing.MigrationTestHelper]
 * — which needs those fixtures — isn't available here. Instead this builds a real on-disk SQLite
 * file matching the source version's shape, then opens it through Room with the migration(s)
 * attached, the same way a real upgrading device would.
 */
@RunWith(AndroidJUnit4::class)
class WearMusicDatabaseMigrationTest {

    private val dbName = "migration-test-wear-music.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate5To6_createsPlaylistTablesAndPreservesExistingSongs() = runTest {
        seedVersion5Database()

        val migratedDb = Room.databaseBuilder(context, WearMusicDatabase::class.java, dbName)
            .addMigrations(WearMusicDatabase.MIGRATION_5_6, WearMusicDatabase.MIGRATION_6_7)
            .build()

        try {
            val song = migratedDb.localSongDao().getSongById("song-1")
            assertThat(song).isNotNull()
            assertThat(song?.title).isEqualTo("Existing song")

            // The playlist tables must exist and be queryable — this throws if the migration
            // didn't run (or ran with malformed SQL) rather than returning a false "empty" result.
            val playlists = migratedDb.openHelper.readableDatabase.query("SELECT * FROM local_playlists")
            playlists.use { assertThat(it.count).isEqualTo(0) }

            val playlistSongs = migratedDb.openHelper.readableDatabase.query("SELECT * FROM local_playlist_songs")
            playlistSongs.use { assertThat(it.count).isEqualTo(0) }
        } finally {
            migratedDb.close()
        }
    }

    @Test
    fun migrate6To7_addsPendingTitleColumnDefaultingToEmpty() = runTest {
        seedVersion6DatabaseWithPlaylistSong()

        val migratedDb = Room.databaseBuilder(context, WearMusicDatabase::class.java, dbName)
            .addMigrations(WearMusicDatabase.MIGRATION_6_7)
            .build()

        try {
            // A row written before this migration existed has no pendingTitle — the migration's
            // DEFAULT '' must apply, not a NULL that Room's non-null String column would choke on.
            val crossRef = migratedDb.localPlaylistDao().observePlaylistSongs("p1").first().single()
            assertThat(crossRef.songId).isEqualTo("s1")
            assertThat(crossRef.pendingTitle).isEmpty()
        } finally {
            migratedDb.close()
        }
    }

    /** Hand-writes a v5 database file: the `local_songs` shape frozen right before this migration. */
    private fun seedVersion5Database() {
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(
            "CREATE TABLE local_songs (" +
                "songId TEXT NOT NULL PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "artist TEXT NOT NULL, " +
                "album TEXT NOT NULL, " +
                "albumId INTEGER NOT NULL, " +
                "duration INTEGER NOT NULL, " +
                "mimeType TEXT NOT NULL, " +
                "fileSize INTEGER NOT NULL, " +
                "bitrate INTEGER NOT NULL, " +
                "sampleRate INTEGER NOT NULL, " +
                "isFavorite INTEGER NOT NULL, " +
                "favoriteSyncPending INTEGER NOT NULL, " +
                "paletteSeedArgb INTEGER, " +
                "themePaletteJson TEXT, " +
                "artworkPath TEXT, " +
                "localPath TEXT NOT NULL, " +
                "transferredAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO local_songs (songId, title, artist, album, albumId, duration, mimeType, " +
                "fileSize, bitrate, sampleRate, isFavorite, favoriteSyncPending, paletteSeedArgb, " +
                "themePaletteJson, artworkPath, localPath, transferredAt) VALUES " +
                "('song-1', 'Existing song', 'Artist', 'Album', 1, 180000, 'audio/mp4', 4000000, " +
                "128000, 44100, 0, 0, NULL, NULL, NULL, '/music/song-1.m4a', 1000)"
        )
        db.version = 5
        db.close()
    }

    /** Hand-writes a v6 database file with one playlist and one cross-ref row, the shape frozen
     *  right before [WearMusicDatabase.MIGRATION_6_7] added `pendingTitle`. */
    private fun seedVersion6DatabaseWithPlaylistSong() {
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(
            "CREATE TABLE local_songs (" +
                "songId TEXT NOT NULL PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "artist TEXT NOT NULL, " +
                "album TEXT NOT NULL, " +
                "albumId INTEGER NOT NULL, " +
                "duration INTEGER NOT NULL, " +
                "mimeType TEXT NOT NULL, " +
                "fileSize INTEGER NOT NULL, " +
                "bitrate INTEGER NOT NULL, " +
                "sampleRate INTEGER NOT NULL, " +
                "isFavorite INTEGER NOT NULL, " +
                "favoriteSyncPending INTEGER NOT NULL, " +
                "paletteSeedArgb INTEGER, " +
                "themePaletteJson TEXT, " +
                "artworkPath TEXT, " +
                "localPath TEXT NOT NULL, " +
                "transferredAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE local_playlists (" +
                "playlistId TEXT NOT NULL PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE local_playlist_songs (" +
                "playlistId TEXT NOT NULL, " +
                "songId TEXT NOT NULL, " +
                "position INTEGER NOT NULL, " +
                "PRIMARY KEY(playlistId, songId))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_playlist_songs_playlistId_position " +
                "ON local_playlist_songs(playlistId, position)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_playlist_songs_songId " +
                "ON local_playlist_songs(songId)"
        )
        db.execSQL(
            "INSERT INTO local_playlists (playlistId, name, createdAt, updatedAt) VALUES " +
                "('p1', 'Road trip', 1000, 1000)"
        )
        db.execSQL(
            "INSERT INTO local_playlist_songs (playlistId, songId, position) VALUES ('p1', 's1', 0)"
        )
        db.version = 6
        db.close()
    }
}
