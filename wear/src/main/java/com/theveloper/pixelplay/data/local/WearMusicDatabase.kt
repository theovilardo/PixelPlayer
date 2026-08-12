package com.theveloper.pixelplay.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for locally stored songs on the watch.
 * Tracks songs that have been transferred from the phone for offline playback.
 */
@Database(
    entities = [LocalSongEntity::class, LocalPlaylistEntity::class, LocalPlaylistSongCrossRef::class],
    version = 7,
    exportSchema = false,
)
abstract class WearMusicDatabase : RoomDatabase() {
    abstract fun localSongDao(): LocalSongDao
    abstract fun localPlaylistDao(): LocalPlaylistDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_songs ADD COLUMN paletteSeedArgb INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_songs ADD COLUMN artworkPath TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_songs ADD COLUMN themePaletteJson TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_songs ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_songs ADD COLUMN favoriteSyncPending INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_playlists (" +
                        "playlistId TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_playlist_songs (" +
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
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE local_playlist_songs ADD COLUMN pendingTitle TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** Every migration this database has ever declared, in order — wire all of them, not just the newest. */
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        )
    }
}
