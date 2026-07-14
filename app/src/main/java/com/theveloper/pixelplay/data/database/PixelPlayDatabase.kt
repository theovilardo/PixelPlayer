package com.theveloper.pixelplay.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlbumArtThemeEntity::class,
        SearchHistoryEntity::class,
        SongEntity::class,
        SongSearchFtsEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        TransitionRuleEntity::class,
        SongArtistCrossRef::class,
        TelegramSongEntity::class,
        TelegramChannelEntity::class,
        SongEngagementEntity::class,
        FavoritesEntity::class,
        LyricsEntity::class,
        NeteaseSongEntity::class,
        NeteasePlaylistEntity::class,
        GDriveSongEntity::class,
        GDriveFolderEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        QqMusicSongEntity::class,
        QqMusicPlaylistEntity::class,
        NavidromeSongEntity::class,
        NavidromePlaylistEntity::class,
        TelegramTopicEntity::class,
        JellyfinSongEntity::class,
        JellyfinPlaylistEntity::class,
        SpotifySongEntity::class,
        SpotifyPlaylistEntity::class,
        AiCacheEntity::class,
        AiUsageEntity::class
    ],
    version = 43,
    exportSchema = true
)
abstract class PixelPlayDatabase : RoomDatabase() {
    abstract fun albumArtThemeDao(): AlbumArtThemeDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun musicDao(): MusicDao
    abstract fun transitionDao(): TransitionDao
    abstract fun telegramDao(): TelegramDao
    abstract fun engagementDao(): EngagementDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun neteaseDao(): NeteaseDao
    abstract fun gdriveDao(): GDriveDao
    abstract fun localPlaylistDao(): LocalPlaylistDao
    abstract fun qqmusicDao(): QqMusicDao
    abstract fun navidromeDao(): NavidromeDao
    abstract fun jellyfinDao(): JellyfinDao
    abstract fun spotifyDao(): SpotifyDao
    abstract fun aiCacheDao(): AiCacheDao
    abstract fun aiUsageDao(): AiUsageDao

    companion object {
        // Gap-bridging no-op migrations for missing version ranges.
        // These versions predate Telegram features; affected tables have since been
        // recreated by later migrations (e.g. 15→16 drops/recreates album_art_themes).
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no-op gap bridge */ }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no-op gap bridge */ }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no-op gap bridge */ }
        }

        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS spotify_songs (
                        id TEXT NOT NULL PRIMARY KEY,
                        spotify_id TEXT NOT NULL,
                        playlist_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        album_id TEXT,
                        duration_ms INTEGER NOT NULL,
                        album_art_url TEXT,
                        track_number INTEGER NOT NULL,
                        disc_number INTEGER,
                        explicit INTEGER NOT NULL,
                        date_added INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_songs_spotify_id ON spotify_songs(spotify_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_songs_playlist_id ON spotify_songs(playlist_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spotify_songs_date_added ON spotify_songs(date_added)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS spotify_playlists (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT,
                        owner_name TEXT,
                        cover_url TEXT,
                        song_count INTEGER NOT NULL,
                        is_public INTEGER,
                        collaborative INTEGER NOT NULL,
                        last_sync_time INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN parent_directory_path TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN lyrics TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN mime_type TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN bitrate INTEGER")
                db.execSQL("ALTER TABLE songs ADD COLUMN sample_rate INTEGER")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN album_artist TEXT DEFAULT NULL")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS song_artist_cross_ref (
                        song_id INTEGER NOT NULL,
                        artist_id INTEGER NOT NULL,
                        is_primary INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (song_id, artist_id),
                        FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE,
                        FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS index_song_artist_cross_ref_song_id ON song_artist_cross_ref(song_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_song_artist_cross_ref_artist_id ON song_artist_cross_ref(artist_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_song_artist_cross_ref_is_primary ON song_artist_cross_ref(is_primary)")

                db.execSQL("""
                    INSERT OR REPLACE INTO song_artist_cross_ref (song_id, artist_id, is_primary)
                    SELECT id, artist_id, 1 FROM songs WHERE artist_id IS NOT NULL
                """.trimIndent())
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artists ADD COLUMN image_url TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS telegram_songs (
                        id TEXT NOT NULL PRIMARY KEY,
                        chat_id INTEGER NOT NULL,
                        message_id INTEGER NOT NULL,
                        file_id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        duration INTEGER NOT NULL,
                        file_path TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        date_added INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE telegram_songs ADD COLUMN album_art_uri TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS telegram_channels (
                        chat_id INTEGER NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        username TEXT,
                        song_count INTEGER NOT NULL DEFAULT 0,
                        last_sync_time INTEGER NOT NULL DEFAULT 0,
                        photo_path TEXT
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
             override fun migrate(db: SupportSQLiteDatabase) {
                // Create song_engagements table for tracking play statistics
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS song_engagements (
                        song_id TEXT NOT NULL PRIMARY KEY,
                        play_count INTEGER NOT NULL DEFAULT 0,
                        total_play_duration_ms INTEGER NOT NULL DEFAULT 0,
                        last_played_timestamp INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Fix for album_art_themes schema mismatch (Backport upstream MIGRATION_14_15 logic)
                // Since this table is a cache and the schema is complex (100 columns), it is safer to DROP and RECREATE
                // to ensure it exactly matches AlbumArtThemeEntity and avoid validation crashes.
                db.execSQL("DROP TABLE IF EXISTS album_art_themes")

                val colorColumns = listOf(
                    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
                    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
                    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
                    "background", "onBackground", "surface", "onSurface",
                    "surfaceVariant", "onSurfaceVariant", "error", "onError",
                    "outline", "errorContainer", "onErrorContainer",
                    "inversePrimary", "inverseSurface", "inverseOnSurface",
                    "surfaceTint", "outlineVariant", "scrim",
                    "surfaceBright", "surfaceDim",
                    "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest",
                    "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
                    "secondaryFixed", "secondaryFixedDim", "onSecondaryFixed", "onSecondaryFixedVariant",
                    "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant"
                )

                val themePrefixes = listOf("light_", "dark_")
                val columnDefinitions = StringBuilder()

                // Add standard columns
                columnDefinitions.append("albumArtUriString TEXT NOT NULL, ")
                columnDefinitions.append("paletteStyle TEXT NOT NULL, ")

                // Add dynamic color columns
                themePrefixes.forEach { prefix ->
                    colorColumns.forEach { column ->
                        columnDefinitions.append("${prefix}${column} TEXT NOT NULL, ")
                    }
                }

                // Remove trailing comma and space
                val columnsSql = columnDefinitions.toString().trimEnd(',', ' ')

                db.execSQL("CREATE TABLE IF NOT EXISTS album_art_themes ($columnsSql, PRIMARY KEY(albumArtUriString))")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE songs ADD COLUMN telegram_chat_id INTEGER DEFAULT NULL")
                } catch (e: Exception) {
                    // Column might already exist
                }
                try {
                    db.execSQL("ALTER TABLE songs ADD COLUMN telegram_file_id INTEGER DEFAULT NULL")
                } catch (e: Exception) {
                    // Column might already exist
                }

                // Fix for album_art_themes schema mismatch if user is coming from version 16 (where the schema might be broken)
                // We re-apply the DROP and RECREATE strategy here to ensure everyone ends up with the correct schema.
                db.execSQL("DROP TABLE IF EXISTS album_art_themes")

                val colorColumns = listOf(
                    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
                    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
                    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
                    "background", "onBackground", "surface", "onSurface",
                    "surfaceVariant", "onSurfaceVariant", "error", "onError",
                    "outline", "errorContainer", "onErrorContainer",
                    "inversePrimary", "inverseSurface", "inverseOnSurface",
                    "surfaceTint", "outlineVariant", "scrim",
                    "surfaceBright", "surfaceDim",
                    "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest",
                    "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
                    "secondaryFixed", "secondaryFixedDim", "onSecondaryFixed", "onSecondaryFixedVariant",
                    "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant"
                )

                val themePrefixes = listOf("light_", "dark_")
                val columnDefinitions = StringBuilder()

                // Add standard columns
                columnDefinitions.append("albumArtUriString TEXT NOT NULL, ")
                columnDefinitions.append("paletteStyle TEXT NOT NULL, ")

                // Add dynamic color columns
                themePrefixes.forEach { prefix ->
                    colorColumns.forEach { column ->
                        columnDefinitions.append("${prefix}${column} TEXT NOT NULL, ")
                    }
                }

                // Remove trailing comma and space
                val columnsSql = columnDefinitions.toString().trimEnd(',', ' ')

                db.execSQL("CREATE TABLE IF NOT EXISTS album_art_themes ($columnsSql, PRIMARY KEY(albumArtUriString))")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorites (
                        songId INTEGER NOT NULL PRIMARY KEY,
                        isFavorite INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())

                // Migrate existing favorites from songs table if possible
                // Note: We need to cast is_favorite (boolean/int) to ensure compatibility
                db.execSQL("""
                    INSERT OR IGNORE INTO favorites (songId, isFavorite, timestamp)
                    SELECT id, is_favorite, ? FROM songs WHERE is_favorite = 1
                """, arrayOf(System.currentTimeMillis()))
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lyrics` (`songId` INTEGER NOT NULL, `content` TEXT NOT NULL, `isSynced` INTEGER NOT NULL DEFAULT 0, `source` TEXT, PRIMARY KEY(`songId`))"
                )
                database.execSQL(
                    "INSERT INTO lyrics (songId, content) SELECT id, lyrics FROM songs WHERE lyrics IS NOT NULL AND lyrics != ''"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE album_art_themes ADD COLUMN paletteStyle TEXT NOT NULL DEFAULT 'tonal_spot'"
                )

                val newRoleColumns = listOf(
                    "surfaceBright",
                    "surfaceDim",
                    "surfaceContainer",
                    "surfaceContainerHigh",
                    "surfaceContainerHighest",
                    "surfaceContainerLow",
                    "surfaceContainerLowest",
                    "primaryFixed",
                    "primaryFixedDim",
                    "onPrimaryFixed",
                    "onPrimaryFixedVariant",
                    "secondaryFixed",
                    "secondaryFixedDim",
                    "onSecondaryFixed",
                    "onSecondaryFixedVariant",
                    "tertiaryFixed",
                    "tertiaryFixedDim",
                    "onTertiaryFixed",
                    "onTertiaryFixedVariant"
                )

                val prefixes = listOf("light_", "dark_")
                prefixes.forEach { prefix ->
                    newRoleColumns.forEach { role ->
                        database.execSQL(
                            "ALTER TABLE album_art_themes ADD COLUMN ${prefix}${role} TEXT NOT NULL DEFAULT '#00000000'"
                        )
                    }
                }

                // The table is a cache; wipe stale rows so we always regenerate with full token data.
                database.execSQL("DELETE FROM album_art_themes")
            }
        }

        /**
         * Reconcile Telegram tables: drop and recreate to match current entity definitions.
         * Telegram data is re-syncable cache, so this is safe.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop existing Telegram tables that may have schema drift
                db.execSQL("DROP TABLE IF EXISTS telegram_songs")
                db.execSQL("DROP TABLE IF EXISTS telegram_channels")

                // Recreate telegram_songs matching TelegramSongEntity exactly
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS telegram_songs (
                        id TEXT NOT NULL PRIMARY KEY,
                        chat_id INTEGER NOT NULL,
                        message_id INTEGER NOT NULL,
                        file_id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        duration INTEGER NOT NULL,
                        file_path TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        date_added INTEGER NOT NULL,
                        album_art_uri TEXT
                    )
                """.trimIndent())

                // Recreate telegram_channels matching TelegramChannelEntity exactly
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS telegram_channels (
                        chat_id INTEGER NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        username TEXT,
                        song_count INTEGER NOT NULL DEFAULT 0,
                        last_sync_time INTEGER NOT NULL DEFAULT 0,
                        photo_path TEXT
                    )
                """.trimIndent())
            }
        }

        /**
         * Add Netease Cloud Music tables.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS netease_songs (
                        id TEXT NOT NULL PRIMARY KEY,
                        netease_id INTEGER NOT NULL,
                        playlist_id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        album_id INTEGER NOT NULL,
                        duration INTEGER NOT NULL,
                        album_art_url TEXT,
                        mime_type TEXT NOT NULL,
                        bitrate INTEGER,
                        date_added INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS netease_playlists (
                        id INTEGER NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        cover_url TEXT,
                        song_count INTEGER NOT NULL,
                        last_sync_time INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * Add Google Drive tables.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS gdrive_songs (
                        id TEXT NOT NULL PRIMARY KEY,
                        drive_file_id TEXT NOT NULL,
                        folder_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        album_id INTEGER NOT NULL,
                        duration INTEGER NOT NULL,
                        album_art_url TEXT,
                        mime_type TEXT NOT NULL,
                        bitrate INTEGER,
                        file_size INTEGER NOT NULL,
                        date_added INTEGER NOT NULL,
                        date_modified INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS gdrive_folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        song_count INTEGER NOT NULL DEFAULT 0,
                        last_sync_time INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * Add custom_image_uri column to artists table.
         * Allows users to associate a custom image with each artist.
         * Nullable with DEFAULT NULL so this migration is safe and additive.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artists ADD COLUMN custom_image_uri TEXT DEFAULT NULL")
            }
        }

        /**
         * Add missing indexes for frequently filtered and sorted queries.
         *
         * Safety: the `date_added` column may be absent on databases that were
         * created before it was part of the songs schema and later restored via
         * Android auto-backup, so we repair the table defensively before indexing.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSongsTableHasDateAdded(db)
                createSongsEntityIndexes(db)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_timestamp ON favorites(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_song_engagements_play_count ON song_engagements(play_count)")
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Lyrics are already persisted in the dedicated lyrics table. Keeping a duplicate
                // copy in songs rows makes broad SELECTs vulnerable to CursorWindow overflows.
                db.execSQL("UPDATE songs SET lyrics = NULL WHERE lyrics IS NOT NULL AND lyrics != ''")
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Cloud/source tables: add query indexes.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telegram_songs_chat_id ON telegram_songs(chat_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telegram_songs_message_id ON telegram_songs(message_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telegram_songs_file_id ON telegram_songs(file_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telegram_songs_chat_id_message_id ON telegram_songs(chat_id, message_id)")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_netease_songs_netease_id ON netease_songs(netease_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_netease_songs_playlist_id ON netease_songs(playlist_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_netease_songs_playlist_id_date_added ON netease_songs(playlist_id, date_added)")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_gdrive_songs_drive_file_id ON gdrive_songs(drive_file_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gdrive_songs_folder_id ON gdrive_songs(folder_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gdrive_songs_folder_id_date_added ON gdrive_songs(folder_id, date_added)")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_album_art_themes_albumArtUriString_paletteStyle ON album_art_themes(albumArtUriString, paletteStyle)")

                // favorites table is the source of truth; keep songs.is_favorite mirrored by trigger.
                db.execSQL(
                    """
                        UPDATE songs
                        SET is_favorite = CASE
                            WHEN id IN (SELECT songId FROM favorites WHERE isFavorite = 1) THEN 1
                            ELSE 0
                        END
                    """.trimIndent()
                )
                installFavoriteSyncTriggers(db)

                recreatePlaylistsTable(db)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlists_last_modified ON playlists(last_modified)")

                recreatePlaylistSongsTable(db)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_playlist_id_sort_order ON playlist_songs(playlist_id, sort_order)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_song_id ON playlist_songs(song_id)")
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                recreatePlaylistsTable(db)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlists_last_modified ON playlists(last_modified)")

                recreatePlaylistSongsTable(db)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_playlist_id_sort_order ON playlist_songs(playlist_id, sort_order)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_song_id ON playlist_songs(song_id)")
                installFavoriteSyncTriggers(db)
            }
        }
        
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_cache (
                        promptHash TEXT NOT NULL PRIMARY KEY,
                        responseJson TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `jellyfin_songs` (
                        `id` TEXT NOT NULL,
                        `jellyfin_id` TEXT NOT NULL,
                        `playlist_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `artist_id` TEXT,
                        `album` TEXT NOT NULL,
                        `album_id` TEXT,
                        `duration` INTEGER NOT NULL,
                        `track_number` INTEGER NOT NULL,
                        `disc_number` INTEGER NOT NULL,
                        `year` INTEGER NOT NULL,
                        `genre` TEXT,
                        `bitRate` INTEGER,
                        `mime_type` TEXT,
                        `path` TEXT NOT NULL,
                        `date_added` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_jellyfin_songs_jellyfin_id` ON `jellyfin_songs` (`jellyfin_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_jellyfin_songs_playlist_id` ON `jellyfin_songs` (`playlist_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_jellyfin_songs_playlist_id_date_added` ON `jellyfin_songs` (`playlist_id`, `date_added`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `jellyfin_playlists` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `song_count` INTEGER NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `last_sync_time` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_usage (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        promptType TEXT NOT NULL,
                        promptTokens INTEGER NOT NULL,
                        outputTokens INTEGER NOT NULL,
                        thoughtTokens INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_file_path ON songs(file_path)")
            }
        }

        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_songs_parent_directory_path_source_type_album_id " +
                        "ON songs(parent_directory_path, source_type, album_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_songs_parent_directory_path_source_type_id " +
                        "ON songs(parent_directory_path, source_type, id)"
                )
            }
        }

        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if ("album_artist" !in getTableColumns(db, "albums")) {
                    db.execSQL("ALTER TABLE albums ADD COLUMN album_artist TEXT DEFAULT NULL")
                }
                db.execSQL(
                    """
                    UPDATE albums
                    SET album_artist = (
                        SELECT s.album_artist
                        FROM songs s
                        WHERE s.album_id = albums.id
                          AND s.album_artist IS NOT NULL
                          AND TRIM(s.album_artist) != ''
                        GROUP BY s.album_artist
                        ORDER BY COUNT(*) DESC, LENGTH(s.album_artist) DESC
                        LIMIT 1
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_albums_album_artist ON albums(album_artist)")
            }
        }

        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate navidrome_songs table to change disc_number to NOT NULL (Room expected)
                db.execSQL("DROP TABLE IF EXISTS navidrome_songs_new")
                db.execSQL(
                    """
                        CREATE TABLE navidrome_songs_new (
                            id TEXT NOT NULL PRIMARY KEY,
                            navidrome_id TEXT NOT NULL,
                            playlist_id TEXT NOT NULL,
                            title TEXT NOT NULL,
                            artist TEXT NOT NULL,
                            artist_id TEXT,
                            album TEXT NOT NULL,
                            album_id TEXT,
                            cover_art_id TEXT,
                            duration INTEGER NOT NULL,
                            track_number INTEGER NOT NULL,
                            disc_number INTEGER NOT NULL,
                            year INTEGER NOT NULL,
                            genre TEXT,
                            bitRate INTEGER,
                            mime_type TEXT,
                            suffix TEXT,
                            path TEXT NOT NULL,
                            date_added INTEGER NOT NULL
                        )
                    """.trimIndent()
                )

                if (tableExists(db, "navidrome_songs")) {
                    val columns = getTableColumns(db, "navidrome_songs")
                    val artistIdExpr = columnExpr(columns, "artist_id", "NULL")
                    val albumIdExpr = columnExpr(columns, "album_id", "NULL")
                    val coverArtIdExpr = columnExpr(columns, "cover_art_id", "NULL")
                    val genreExpr = columnExpr(columns, "genre", "NULL")
                    val bitRateExpr = columnExpr(columns, "bitRate", "NULL")
                    val mimeTypeExpr = columnExpr(columns, "mime_type", "NULL")
                    val suffixExpr = columnExpr(columns, "suffix", "NULL")
                    val discNumberExpr = if ("disc_number" in columns) "COALESCE(disc_number, 0)" else "0"

                    db.execSQL(
                        """
                            INSERT OR REPLACE INTO navidrome_songs_new (
                                id,
                                navidrome_id,
                                playlist_id,
                                title,
                                artist,
                                artist_id,
                                album,
                                album_id,
                                cover_art_id,
                                duration,
                                track_number,
                                disc_number,
                                year,
                                genre,
                                bitRate,
                                mime_type,
                                suffix,
                                path,
                                date_added
                            )
                            SELECT
                                id,
                                navidrome_id,
                                playlist_id,
                                title,
                                artist,
                                $artistIdExpr,
                                album,
                                $albumIdExpr,
                                $coverArtIdExpr,
                                duration,
                                track_number,
                                $discNumberExpr,
                                year,
                                $genreExpr,
                                $bitRateExpr,
                                $mimeTypeExpr,
                                $suffixExpr,
                                path,
                                date_added
                            FROM navidrome_songs
                        """.trimIndent()
                    )

                    db.execSQL("DROP TABLE navidrome_songs")
                }

                db.execSQL("ALTER TABLE navidrome_songs_new RENAME TO navidrome_songs")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_navidrome_songs_navidrome_id ON navidrome_songs(navidrome_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_navidrome_songs_playlist_id ON navidrome_songs(playlist_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_navidrome_songs_playlist_id_date_added ON navidrome_songs(playlist_id, date_added)")
            }
        }

        private fun ensureSongsTableHasDateAdded(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "songs")) {
                recreateSongsTable(db)
                return
            }

            if ("date_added" in getTableColumns(db, "songs")) {
                return
            }

            try {
                db.execSQL("ALTER TABLE songs ADD COLUMN date_added INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {
                // Some restored databases report the right version but still carry
                // a drifted songs table. If ALTER TABLE did not stick, rebuild it.
            }

            if ("date_added" !in getTableColumns(db, "songs")) {
                recreateSongsTable(db)
            }
        }

        private fun recreateSongsTable(db: SupportSQLiteDatabase) {
            val songsTableExists = tableExists(db, "songs")
            val columns = if (songsTableExists) getTableColumns(db, "songs") else emptySet()

            db.execSQL("DROP TABLE IF EXISTS songs_new")
            db.execSQL(
                """
                    CREATE TABLE songs_new (
                        id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        artist_name TEXT NOT NULL,
                        artist_id INTEGER NOT NULL,
                        album_artist TEXT,
                        album_name TEXT NOT NULL,
                        album_id INTEGER NOT NULL,
                        content_uri_string TEXT NOT NULL,
                        album_art_uri_string TEXT,
                        duration INTEGER NOT NULL,
                        genre TEXT,
                        file_path TEXT NOT NULL,
                        parent_directory_path TEXT NOT NULL,
                        is_favorite INTEGER NOT NULL DEFAULT 0,
                        lyrics TEXT DEFAULT null,
                        track_number INTEGER NOT NULL DEFAULT 0,
                        disc_number INTEGER DEFAULT null,
                        year INTEGER NOT NULL DEFAULT 0,
                        date_added INTEGER NOT NULL DEFAULT 0,
                        mime_type TEXT,
                        bitrate INTEGER,
                        sample_rate INTEGER,
                        telegram_chat_id INTEGER,
                        telegram_file_id INTEGER,
                        PRIMARY KEY(id),
                        FOREIGN KEY(album_id) REFERENCES albums(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(artist_id) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent()
            )

            val requiredColumns = setOf(
                "id",
                "title",
                "artist_name",
                "artist_id",
                "album_name",
                "album_id",
                "content_uri_string",
                "duration",
                "file_path"
            )

            // If the restored table still has the core song columns, preserve rows.
            // Otherwise prefer a clean empty table over another migration-time crash.
            if (songsTableExists && requiredColumns.all(columns::contains)) {
                val albumArtistExpr = columnExpr(columns, "album_artist", "NULL")
                val albumArtUriExpr = columnExpr(columns, "album_art_uri_string", "NULL")
                val genreExpr = columnExpr(columns, "genre", "NULL")
                val parentDirectoryPathExpr = columnExpr(columns, "parent_directory_path", "''")
                val isFavoriteExpr = columnExpr(columns, "is_favorite", "0")
                val lyricsExpr = columnExpr(columns, "lyrics", "NULL")
                val trackNumberExpr = columnExpr(columns, "track_number", "0")
                val discNumberExpr = columnExpr(columns, "disc_number", "NULL")
                val yearExpr = columnExpr(columns, "year", "0")
                val dateAddedExpr = columnExpr(columns, "date_added", "0")
                val mimeTypeExpr = columnExpr(columns, "mime_type", "NULL")
                val bitrateExpr = columnExpr(columns, "bitrate", "NULL")
                val sampleRateExpr = columnExpr(columns, "sample_rate", "NULL")
                val telegramChatIdExpr = columnExpr(columns, "telegram_chat_id", "NULL")
                val telegramFileIdExpr = columnExpr(columns, "telegram_file_id", "NULL")

                db.execSQL(
                    """
                        INSERT OR REPLACE INTO songs_new (
                            id,
                            title,
                            artist_name,
                            artist_id,
                            album_artist,
                            album_name,
                            album_id,
                            content_uri_string,
                            album_art_uri_string,
                            duration,
                            genre,
                            file_path,
                            parent_directory_path,
                            is_favorite,
                            lyrics,
                            track_number,
                            disc_number,
                            year,
                            date_added,
                            mime_type,
                            bitrate,
                            sample_rate,
                            telegram_chat_id,
                            telegram_file_id
                        )
                        SELECT
                            id,
                            title,
                            artist_name,
                            artist_id,
                            $albumArtistExpr,
                            album_name,
                            album_id,
                            content_uri_string,
                            $albumArtUriExpr,
                            duration,
                            $genreExpr,
                            file_path,
                            $parentDirectoryPathExpr,
                            $isFavoriteExpr,
                            $lyricsExpr,
                            $trackNumberExpr,
                            $discNumberExpr,
                            $yearExpr,
                            $dateAddedExpr,
                            $mimeTypeExpr,
                            $bitrateExpr,
                            $sampleRateExpr,
                            $telegramChatIdExpr,
                            $telegramFileIdExpr
                        FROM songs
                        WHERE id IS NOT NULL
                          AND title IS NOT NULL
                          AND artist_name IS NOT NULL
                          AND artist_id IS NOT NULL
                          AND album_name IS NOT NULL
                          AND album_id IS NOT NULL
                          AND content_uri_string IS NOT NULL
                          AND duration IS NOT NULL
                          AND file_path IS NOT NULL
                    """.trimIndent()
                )
            }

            if (songsTableExists) {
                db.execSQL("DROP TABLE songs")
            }

            db.execSQL("ALTER TABLE songs_new RENAME TO songs")
            createSongsEntityIndexes(db)
        }

        private fun createSongsEntityIndexes(db: SupportSQLiteDatabase) {
            val columns = getTableColumns(db, "songs")

            fun createIndexIfColumnExists(columnName: String, indexName: String) {
                if (columnName in columns) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS $indexName ON songs($columnName)")
                }
            }

            fun createCompositeIndexIfColumnsExist(indexName: String, vararg columnNames: String) {
                if (columnNames.all(columns::contains)) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS $indexName ON songs(${columnNames.joinToString(", ")})"
                    )
                }
            }

            createIndexIfColumnExists("title", "index_songs_title")
            createIndexIfColumnExists("album_id", "index_songs_album_id")
            createIndexIfColumnExists("artist_id", "index_songs_artist_id")
            createIndexIfColumnExists("artist_name", "index_songs_artist_name")
            createIndexIfColumnExists("genre", "index_songs_genre")
            createIndexIfColumnExists("parent_directory_path", "index_songs_parent_directory_path")
            createIndexIfColumnExists("file_path", "index_songs_file_path")
            createIndexIfColumnExists("content_uri_string", "index_songs_content_uri_string")
            createIndexIfColumnExists("date_added", "index_songs_date_added")
            createIndexIfColumnExists("duration", "index_songs_duration")
            createIndexIfColumnExists("source_type", "index_songs_source_type")
            createCompositeIndexIfColumnsExist(
                "index_songs_parent_directory_path_source_type_album_id",
                "parent_directory_path",
                "source_type",
                "album_id"
            )
            createCompositeIndexIfColumnsExist(
                "index_songs_parent_directory_path_source_type_id",
                "parent_directory_path",
                "source_type",
                "id"
            )
        }

        private fun recreatePlaylistsTable(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS playlists_new")
            db.execSQL(
                """
                    CREATE TABLE playlists_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_modified INTEGER NOT NULL,
                        is_ai_generated INTEGER NOT NULL,
                        is_queue_generated INTEGER NOT NULL,
                        cover_image_uri TEXT,
                        cover_color_argb INTEGER,
                        cover_icon_name TEXT,
                        cover_shape_type TEXT,
                        cover_shape_detail_1 REAL,
                        cover_shape_detail_2 REAL,
                        cover_shape_detail_3 REAL,
                        cover_shape_detail_4 REAL,
                        source TEXT NOT NULL
                    )
                """.trimIndent()
            )

            if (tableExists(db, "playlists")) {
                val columns = getTableColumns(db, "playlists")
                if ("id" in columns && "name" in columns) {
                    val nowMs = "(CAST(strftime('%s','now') AS INTEGER) * 1000)"
                    val createdAtExpr = columnExpr(columns, "created_at", nowMs)
                    val lastModifiedExpr = columnExpr(columns, "last_modified", createdAtExpr)
                    val isAiGeneratedExpr = columnExpr(columns, "is_ai_generated", "0")
                    val isQueueGeneratedExpr = columnExpr(columns, "is_queue_generated", "0")
                    val coverImageUriExpr = columnExpr(columns, "cover_image_uri", "NULL")
                    val coverColorArgbExpr = columnExpr(columns, "cover_color_argb", "NULL")
                    val coverIconNameExpr = columnExpr(columns, "cover_icon_name", "NULL")
                    val coverShapeTypeExpr = columnExpr(columns, "cover_shape_type", "NULL")
                    val coverShapeDetail1Expr = columnExpr(columns, "cover_shape_detail_1", "NULL")
                    val coverShapeDetail2Expr = columnExpr(columns, "cover_shape_detail_2", "NULL")
                    val coverShapeDetail3Expr = columnExpr(columns, "cover_shape_detail_3", "NULL")
                    val coverShapeDetail4Expr = columnExpr(columns, "cover_shape_detail_4", "NULL")
                    val sourceExpr = columnExpr(columns, "source", "'LOCAL'")

                    db.execSQL(
                        """
                            INSERT OR REPLACE INTO playlists_new (
                                id,
                                name,
                                created_at,
                                last_modified,
                                is_ai_generated,
                                is_queue_generated,
                                cover_image_uri,
                                cover_color_argb,
                                cover_icon_name,
                                cover_shape_type,
                                cover_shape_detail_1,
                                cover_shape_detail_2,
                                cover_shape_detail_3,
                                cover_shape_detail_4,
                                source
                            )
                            SELECT
                                id,
                                name,
                                $createdAtExpr,
                                $lastModifiedExpr,
                                $isAiGeneratedExpr,
                                $isQueueGeneratedExpr,
                                $coverImageUriExpr,
                                $coverColorArgbExpr,
                                $coverIconNameExpr,
                                $coverShapeTypeExpr,
                                $coverShapeDetail1Expr,
                                $coverShapeDetail2Expr,
                                $coverShapeDetail3Expr,
                                $coverShapeDetail4Expr,
                                $sourceExpr
                            FROM playlists
                            WHERE id IS NOT NULL AND name IS NOT NULL
                        """.trimIndent()
                    )
                }
                db.execSQL("DROP TABLE playlists")
            }

            db.execSQL("ALTER TABLE playlists_new RENAME TO playlists")
        }

        private fun recreatePlaylistSongsTable(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS playlist_songs_new")
            db.execSQL(
                """
                    CREATE TABLE playlist_songs_new (
                        playlist_id TEXT NOT NULL,
                        song_id TEXT NOT NULL,
                        sort_order INTEGER NOT NULL,
                        PRIMARY KEY(playlist_id, song_id)
                    )
                """.trimIndent()
            )

            if (tableExists(db, "playlist_songs")) {
                val columns = getTableColumns(db, "playlist_songs")
                if ("playlist_id" in columns && "song_id" in columns) {
                    val sortOrderExpr = columnExpr(columns, "sort_order", "0")
                    db.execSQL(
                        """
                            INSERT OR REPLACE INTO playlist_songs_new (
                                playlist_id,
                                song_id,
                                sort_order
                            )
                            SELECT
                                playlist_id,
                                song_id,
                                $sortOrderExpr
                            FROM playlist_songs
                            WHERE playlist_id IS NOT NULL AND song_id IS NOT NULL
                        """.trimIndent()
                    )
                }
                db.execSQL("DROP TABLE playlist_songs")
            }

            db.execSQL("ALTER TABLE playlist_songs_new RENAME TO playlist_songs")
        }

        private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
            db.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(tableName)
            ).use { cursor ->
                return cursor.moveToFirst()
            }
        }

        private fun getTableColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex == -1) return columns
                while (cursor.moveToNext()) {
                    columns += cursor.getString(nameIndex)
                }
            }
            return columns
        }

        private fun getTableColumnDefaultValue(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String
        ): String? {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val defaultValueIndex = cursor.getColumnIndex("dflt_value")
                if (nameIndex == -1 || defaultValueIndex == -1) return null

                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        return cursor.getString(defaultValueIndex)
                    }
                }
            }
            return null
        }

        private fun ensureSongsTableHasDiscNumber(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "songs")) {
                recreateSongsTable(db)
                return
            }

            val columns = getTableColumns(db, "songs")
            if ("disc_number" !in columns) {
                try {
                    db.execSQL("ALTER TABLE songs ADD COLUMN disc_number INTEGER DEFAULT null")
                } catch (_: Exception) {
                    // Restored/drifted databases may already contain a partially applied column.
                }
            }

            val refreshedColumns = getTableColumns(db, "songs")
            val discNumberDefault = getTableColumnDefaultValue(db, "songs", "disc_number")

            if ("disc_number" !in refreshedColumns || !discNumberDefault.equals("null", ignoreCase = true)) {
                recreateSongsTable(db)
            }
        }

        private fun columnExpr(columns: Set<String>, columnName: String, fallbackExpr: String): String {
            return if (columnName in columns) {
                "COALESCE($columnName, $fallbackExpr)"
            } else {
                fallbackExpr
            }
        }

        fun installFavoriteSyncTriggers(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_insert_sync_song")
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_update_sync_song")
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_delete_sync_song")

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_insert_sync_song
                    AFTER INSERT ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = NEW.isFavorite WHERE id = NEW.songId;
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_update_sync_song
                    AFTER UPDATE ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = NEW.isFavorite WHERE id = NEW.songId;
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_delete_sync_song
                    AFTER DELETE ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = 0 WHERE id = OLD.songId;
                    END
                """.trimIndent()
            )
        }

        private fun createSongsSearchVirtualTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                    CREATE VIRTUAL TABLE IF NOT EXISTS songs_fts
                    USING fts4(
                        title,
                        artist_name,
                        tokenize=unicode61
                    )
                """.trimIndent()
            )
        }

        fun installSongsSearchSyncTriggers(db: SupportSQLiteDatabase) {
            createSongsSearchVirtualTable(db)

            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_insert")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_update")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_delete")

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_insert
                    AFTER INSERT ON songs
                    BEGIN
                        INSERT INTO songs_fts(rowid, title, artist_name)
                        VALUES (NEW.id, NEW.title, NEW.artist_name);
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_update
                    AFTER UPDATE ON songs
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                        INSERT INTO songs_fts(rowid, title, artist_name)
                        VALUES (NEW.id, NEW.title, NEW.artist_name);
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_delete
                    AFTER DELETE ON songs
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                    END
                """.trimIndent()
            )
        }

        private fun rebuildSongsSearchIndex(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM songs_fts")
            db.execSQL(
                """
                    INSERT INTO songs_fts(rowid, title, artist_name)
                    SELECT id, title, artist_name
                    FROM songs
                """.trimIndent()
            )
        }

        fun createRuntimeArtifactsCallback(): RoomDatabase.Callback {
            return object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    installFavoriteSyncTriggers(db)
                    installSongsSearchSyncTriggers(db)
                }
            }
        }

        /**
         * Add QQ Music support tables.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS qqmusic_playlists (
                        id INTEGER NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        cover_url TEXT,
                        song_count INTEGER NOT NULL,
                        last_sync_time INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS qqmusic_songs (
                        id TEXT NOT NULL PRIMARY KEY,
                        song_mid TEXT NOT NULL,
                        playlist_id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        album_mid TEXT,
                        duration INTEGER NOT NULL,
                        album_art_url TEXT,
                        mime_type TEXT NOT NULL,
                        bitrate INTEGER,
                        date_added INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * Add Navidrome/Subsonic support tables.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS navidrome_playlists (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        comment TEXT,
                        owner TEXT,
                        cover_art_id TEXT,
                        song_count INTEGER NOT NULL,
                        duration INTEGER NOT NULL,
                        public INTEGER NOT NULL,
                        last_sync_time INTEGER NOT NULL
                    )
                """.trimIndent())

                recreateNavidromeSongsTable(db)
            }
        }

        /**
         * Reconcile older Navidrome caches that were created with playlist_id stored as INTEGER.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                recreateNavidromeSongsTable(db)
            }
        }

        /**
         * Add disc_number to songs table.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSongsTableHasDiscNumber(db)
            }
        }

        private fun recreateNavidromeSongsTable(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS navidrome_songs_new")
            db.execSQL(
                """
                    CREATE TABLE navidrome_songs_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        navidrome_id TEXT NOT NULL,
                        playlist_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        artist_id TEXT,
                        album TEXT NOT NULL,
                        album_id TEXT,
                        cover_art_id TEXT,
                        duration INTEGER NOT NULL,
                        track_number INTEGER NOT NULL,
                        disc_number INTEGER NOT NULL,
                        year INTEGER NOT NULL,
                        genre TEXT,
                        bitRate INTEGER,
                        mime_type TEXT,
                        suffix TEXT,
                        path TEXT NOT NULL,
                        date_added INTEGER NOT NULL
                    )
                """.trimIndent()
            )

            if (tableExists(db, "navidrome_songs")) {
                val columns = getTableColumns(db, "navidrome_songs")
                val requiredColumns = setOf(
                    "id",
                    "navidrome_id",
                    "playlist_id",
                    "title",
                    "artist",
                    "album",
                    "duration",
                    "track_number",
                    "disc_number",
                    "year",
                    "path",
                    "date_added"
                )

                if (requiredColumns.all(columns::contains)) {
                    val artistIdExpr = columnExpr(columns, "artist_id", "NULL")
                    val albumIdExpr = columnExpr(columns, "album_id", "NULL")
                    val coverArtIdExpr = columnExpr(columns, "cover_art_id", "NULL")
                    val genreExpr = columnExpr(columns, "genre", "NULL")
                    val bitRateExpr = columnExpr(columns, "bitRate", "NULL")
                    val mimeTypeExpr = columnExpr(columns, "mime_type", "NULL")
                    val suffixExpr = columnExpr(columns, "suffix", "NULL")

                    db.execSQL(
                        """
                            INSERT OR REPLACE INTO navidrome_songs_new (
                                id,
                                navidrome_id,
                                playlist_id,
                                title,
                                artist,
                                artist_id,
                                album,
                                album_id,
                                cover_art_id,
                                duration,
                                track_number,
                                disc_number,
                                year,
                                genre,
                                bitRate,
                                mime_type,
                                suffix,
                                path,
                                date_added
                            )
                            SELECT
                                id,
                                navidrome_id,
                                CAST(playlist_id AS TEXT),
                                title,
                                artist,
                                $artistIdExpr,
                                album,
                                $albumIdExpr,
                                $coverArtIdExpr,
                                duration,
                                track_number,
                                COALESCE(disc_number, 0),
                                year,
                                $genreExpr,
                                $bitRateExpr,
                                $mimeTypeExpr,
                                $suffixExpr,
                                path,
                                date_added
                            FROM navidrome_songs
                            WHERE id IS NOT NULL
                              AND navidrome_id IS NOT NULL
                              AND playlist_id IS NOT NULL
                              AND title IS NOT NULL
                              AND artist IS NOT NULL
                              AND album IS NOT NULL
                              AND path IS NOT NULL
                        """.trimIndent()
                    )
                }

                db.execSQL("DROP TABLE navidrome_songs")
            }

            db.execSQL("ALTER TABLE navidrome_songs_new RENAME TO navidrome_songs")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_navidrome_songs_navidrome_id ON navidrome_songs(navidrome_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_navidrome_songs_playlist_id ON navidrome_songs(playlist_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_navidrome_songs_playlist_id_date_added ON navidrome_songs(playlist_id, date_added)")
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add thread_id column to telegram_songs
                db.execSQL("ALTER TABLE telegram_songs ADD COLUMN thread_id INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telegram_songs_thread_id ON telegram_songs(thread_id)")

                // 2. Create telegram_topics table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS telegram_topics (
                        id TEXT NOT NULL PRIMARY KEY,
                        chat_id INTEGER NOT NULL,
                        thread_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        song_count INTEGER NOT NULL DEFAULT 0,
                        last_sync_time INTEGER NOT NULL DEFAULT 0,
                        icon_emoji TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telegram_topics_chat_id ON telegram_topics(chat_id)")
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if ("date_added" !in getTableColumns(db, "albums")) {
                    db.execSQL("ALTER TABLE albums ADD COLUMN date_added INTEGER NOT NULL DEFAULT 0")
                }

                db.execSQL(
                    """
                        UPDATE albums
                        SET date_added = COALESCE(
                            (
                                SELECT MAX(songs.date_added)
                                FROM songs
                                WHERE songs.album_id = albums.id
                            ),
                            0
                        )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN artists_json TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN source_type INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_source_type ON songs(source_type)")
                // Backfill source_type from content_uri_string for existing rows
                db.execSQL("UPDATE songs SET source_type = 1 WHERE content_uri_string LIKE 'telegram://%'")
                db.execSQL("UPDATE songs SET source_type = 2 WHERE content_uri_string LIKE 'netease://%'")
                db.execSQL("UPDATE songs SET source_type = 3 WHERE content_uri_string LIKE 'gdrive://%'")
                db.execSQL("UPDATE songs SET source_type = 4 WHERE content_uri_string LIKE 'qqmusic://%'")
                db.execSQL("UPDATE songs SET source_type = 5 WHERE content_uri_string LIKE 'navidrome://%'")
            }
        }

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createSongsSearchVirtualTable(db)
                installSongsSearchSyncTriggers(db)
                rebuildSongsSearchIndex(db)
            }
        }

    }
}
