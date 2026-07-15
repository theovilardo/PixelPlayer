package com.theveloper.pixelplay.data.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import com.theveloper.pixelplay.utils.AudioMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

private val SONG_SEARCH_QUERY_TOKEN_REGEX = Regex("""[\p{L}\p{N}]+""")
private const val EMPTY_SONG_SEARCH_MATCH_QUERY = "pixelplayemptyquery*"

private fun buildSongTitleSearchMatchQuery(query: String): String {
    val tokens = SONG_SEARCH_QUERY_TOKEN_REGEX
        .findAll(query)
        .map { it.value.trim() }
        .filter { it.isNotEmpty() }
        .take(6)
        .toList()

    if (tokens.isEmpty()) return EMPTY_SONG_SEARCH_MATCH_QUERY

    return tokens.joinToString(separator = " AND ") { "title:${it}*" }
}

private fun buildSongSearchMatchQuery(query: String): String {
    val tokens = SONG_SEARCH_QUERY_TOKEN_REGEX
        .findAll(query)
        .map { it.value.trim() }
        .filter { it.isNotEmpty() }
        .take(6)
        .toList()

    if (tokens.isEmpty()) return EMPTY_SONG_SEARCH_MATCH_QUERY

    return tokens.joinToString(separator = " AND ") { "${it}*" }
}

private const val SONG_DETAIL_PROJECTION = """
    songs.id AS id,
    songs.title AS title,
    songs.artist_name AS artist_name,
    songs.artist_id AS artist_id,
    songs.album_artist AS album_artist,
    songs.album_name AS album_name,
    songs.album_id AS album_id,
    songs.content_uri_string AS content_uri_string,
    songs.album_art_uri_string AS album_art_uri_string,
    songs.duration AS duration,
    songs.genre AS genre,
    songs.file_path AS file_path,
    songs.parent_directory_path AS parent_directory_path,
    songs.is_favorite AS is_favorite,
    COALESCE(song_lyrics.content, songs.lyrics) AS lyrics,
    songs.track_number AS track_number,
    songs.disc_number AS disc_number,
    songs.year AS year,
    songs.date_added AS date_added,
    songs.mime_type AS mime_type,
    songs.bitrate AS bitrate,
    songs.sample_rate AS sample_rate,
    songs.telegram_chat_id AS telegram_chat_id,
    songs.telegram_file_id AS telegram_file_id,
    songs.artists_json AS artists_json,
    songs.source_type AS source_type
"""

// Projection for list queries: excludes lyrics to prevent CursorWindow overflow (2MB limit)
// when loading large libraries. Lyrics are only needed for the Now Playing screen (getSongById).
private const val SONG_LIST_PROJECTION = """
    id, title, artist_name, artist_id, album_artist, album_name, album_id,
    content_uri_string, album_art_uri_string, duration, genre, file_path,
    parent_directory_path, is_favorite, NULL AS lyrics, track_number, disc_number,
    year, date_added, mime_type, bitrate, sample_rate, telegram_chat_id,
    telegram_file_id, artists_json, source_type
"""

data class DeviceCapabilitySongRow(
    val filePath: String,
    val contentUriString: String,
    val mimeType: String?,
    val duration: Long,
    val bitrate: Int?,
    val sampleRate: Int?,
    val sourceType: Int
)

/**
 * Aggregate audio statistics for the diagnostic performance report.
 * Computed in a single SQL pass so it stays cheap even on large libraries —
 * we never materialize every row. File-size figures are *estimated* from
 * bitrate × duration because raw byte sizes are not stored in the DB; this
 * avoids per-file filesystem stat calls just to build a report.
 */
data class LibraryAudioStatsRow(
    val totalCount: Int,
    val localCount: Int,
    val cloudCount: Int,
    val hiResCount: Int,
    val ultraHiResCount: Int,
    val likelyExpensiveCount: Int,
    val maxBitrate: Int?,
    val minSampleRate: Int?,
    val maxSampleRate: Int?,
    val estMinBytes: Long?,
    val estAvgBytes: Double?,
    val estMaxBytes: Long?
)

data class MimeTypeCountRow(
    val mimeType: String?,
    val count: Int
)

@Dao
interface MusicDao {

    // --- Insert Operations ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongsIgnoreConflicts(songs: List<SongEntity>): List<Long>

    @Update
    suspend fun updateSongs(songs: List<SongEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumsIgnoreConflicts(albums: List<AlbumEntity>): List<Long>

    @Update
    suspend fun updateAlbums(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistsIgnoreConflicts(artists: List<ArtistEntity>): List<Long>

    @Update
    suspend fun updateArtists(artists: List<ArtistEntity>)

    @Query("SELECT * FROM artists WHERE id IN (:artistIds)")
    suspend fun getArtistsByIds(artistIds: List<Long>): List<ArtistEntity>

    @Transaction
    suspend fun insertSongs(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        val insertResults = insertSongsIgnoreConflicts(songs)
        val songsToUpdate = mutableListOf<SongEntity>()
        insertResults.forEachIndexed { index, rowId ->
            if (rowId == -1L) songsToUpdate.add(songs[index])
        }
        if (songsToUpdate.isNotEmpty()) {
            updateSongs(songsToUpdate)
        }
    }

    @Transaction
    suspend fun insertAlbums(albums: List<AlbumEntity>) {
        if (albums.isEmpty()) return
        val insertResults = insertAlbumsIgnoreConflicts(albums)
        val albumsToUpdate = mutableListOf<AlbumEntity>()
        insertResults.forEachIndexed { index, rowId ->
            if (rowId == -1L) albumsToUpdate.add(albums[index])
        }
        if (albumsToUpdate.isNotEmpty()) {
            updateAlbums(albumsToUpdate)
        }
    }

    @Transaction
    suspend fun insertArtists(artists: List<ArtistEntity>) {
        if (artists.isEmpty()) return
        val insertResults = insertArtistsIgnoreConflicts(artists)
        val artistsToUpdate = mutableListOf<ArtistEntity>()
        insertResults.forEachIndexed { index, rowId ->
            if (rowId == -1L) artistsToUpdate.add(artists[index])
        }
        if (artistsToUpdate.isNotEmpty()) {
            val existingById = getArtistsByIds(artistsToUpdate.map { it.id }).associateBy { it.id }
            val mergedArtists = artistsToUpdate.map { incoming ->
                val existing = existingById[incoming.id]
                if (existing == null) {
                    incoming
                } else {
                    incoming.copy(
                        imageUrl = incoming.imageUrl ?: existing.imageUrl,
                        customImageUri = incoming.customImageUri ?: existing.customImageUri
                    )
                }
            }
            updateArtists(mergedArtists)
        }
    }



    @Transaction
    suspend fun insertMusicData(songs: List<SongEntity>, albums: List<AlbumEntity>, artists: List<ArtistEntity>) {
        insertArtists(artists)
        insertAlbums(albums)
        insertSongs(songs)
    }

    @Transaction
    suspend fun clearAllMusicData() {
        clearAllSongs()
        clearAllAlbums()
        clearAllArtists()
    }

    // --- Clear Operations ---
    @Query("DELETE FROM songs")
    suspend fun clearAllSongs()

    @Query("DELETE FROM albums")
    suspend fun clearAllAlbums()

    @Query("DELETE FROM artists")
    suspend fun clearAllArtists()

    // --- Incremental Sync Operations ---
    @Query("SELECT id FROM songs")
    suspend fun getAllSongIds(): List<Long>

    @Query("SELECT id FROM songs WHERE source_type = 0")
    suspend fun getAllMediaStoreSongIds(): List<Long>

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIds(songIds: List<Long>)

    @Query("DELETE FROM song_artist_cross_ref WHERE song_id IN (:songIds)")
    suspend fun deleteCrossRefsBySongIds(songIds: List<Long>)

    @Query("DELETE FROM favorites WHERE songId IN (:songIds)")
    suspend fun deleteFavoritesBySongIds(songIds: List<Long>)

    @Query("DELETE FROM lyrics WHERE songId IN (:songIds)")
    suspend fun deleteLyricsBySongIds(songIds: List<Long>)

    @Query("SELECT id FROM songs WHERE source_type = 1")
    suspend fun getAllTelegramSongIds(): List<Long>

    @Query("""
        SELECT id FROM songs
        WHERE source_type = 1
        AND (telegram_chat_id = :chatId
             OR content_uri_string LIKE 'telegram://' || :chatId || '/%')
    """)
    suspend fun getTelegramSongIdsByChatId(chatId: Long): List<Long>

    @Query("""
        SELECT s.id FROM songs s
        INNER JOIN telegram_songs ts
            ON ts.chat_id = s.telegram_chat_id
            AND ('telegram://' || ts.chat_id || '/' || ts.message_id) = s.content_uri_string
        WHERE ts.chat_id = :chatId AND ts.thread_id = :threadId
    """)
    suspend fun getTelegramSongIdsByTopicId(chatId: Long, threadId: Long): List<Long>

    @Query("SELECT id FROM songs WHERE source_type = 2")
    suspend fun getAllNeteaseSongIds(): List<Long>

    @Query("SELECT id FROM songs WHERE source_type = 3")
    suspend fun getAllGDriveSongIds(): List<Long>

    @Query("SELECT id FROM songs WHERE source_type = 4")
    suspend fun getAllQqMusicSongIds(): List<Long>

    @Query("SELECT id FROM songs WHERE source_type = 5")
    suspend fun getAllNavidromeSongIds(): List<Long>

    @Query("SELECT id FROM songs WHERE source_type = 6")
    suspend fun getAllJellyfinSongIds(): List<Long>

    @Transaction
    suspend fun deleteSongsAndRelatedData(songIds: List<Long>) {
        if (songIds.isEmpty()) return
        songIds.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            deleteCrossRefsBySongIds(chunk)
            deleteFavoritesBySongIds(chunk)
            deleteLyricsBySongIds(chunk)
            deleteSongsByIds(chunk)
        }
        deleteOrphanedAlbums()
        deleteOrphanedArtists()
    }

    @Transaction
    suspend fun clearAllNeteaseSongs() {
        val neteaseSongIds = getAllNeteaseSongIds()
        if (neteaseSongIds.isEmpty()) return
        deleteSongsAndRelatedData(neteaseSongIds)
    }

    @Transaction
    suspend fun clearAllGDriveSongs() {
        val gdriveSongIds = getAllGDriveSongIds()
        if (gdriveSongIds.isEmpty()) return
        deleteSongsAndRelatedData(gdriveSongIds)
    }

    @Transaction
    suspend fun clearAllQqMusicSongs() {
        val qqMusicSongIds = getAllQqMusicSongIds()
        if (qqMusicSongIds.isEmpty()) return
        deleteSongsAndRelatedData(qqMusicSongIds)
    }

    @Transaction
    suspend fun clearAllNavidromeSongs() {
        val navidromeSongIds = getAllNavidromeSongIds()
        if (navidromeSongIds.isEmpty()) return
        deleteSongsAndRelatedData(navidromeSongIds)
    }

    @Transaction
    suspend fun clearAllJellyfinSongs() {
        val jellyfinSongIds = getAllJellyfinSongIds()
        if (jellyfinSongIds.isEmpty()) return
        deleteSongsAndRelatedData(jellyfinSongIds)
    }

    @Transaction
    suspend fun clearAllTelegramSongs() {
        val telegramSongIds = getAllTelegramSongIds()
        if (telegramSongIds.isEmpty()) return
        deleteSongsAndRelatedData(telegramSongIds)
    }

    @Transaction
    suspend fun clearTelegramSongsForChat(chatId: Long) {
        val telegramSongIds = getTelegramSongIdsByChatId(chatId)
        if (telegramSongIds.isEmpty()) return
        deleteSongsAndRelatedData(telegramSongIds)
    }

    @Transaction
    suspend fun clearTelegramSongsForTopic(chatId: Long, threadId: Long) {
        val songIds = getTelegramSongIdsByTopicId(chatId, threadId)
        if (songIds.isEmpty()) return
        deleteSongsAndRelatedData(songIds)
    }

    /**
     * Incrementally sync music data: upsert new/modified songs and remove deleted ones.
     * More efficient than clear-and-replace for large libraries with few changes.
     */
    @Transaction
    suspend fun incrementalSyncMusicData(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        crossRefs: List<SongArtistCrossRef>,
        deletedSongIds: List<Long>
    ) {
        // Protect cloud songs from deletion during generic media scan
        // Only allow explicit deletions if the list is non-empty.
        // During general refresh, deletedSongIds strictly contains local MediaStore IDs only.
        if (deletedSongIds.isNotEmpty()) {
            deletedSongIds.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
                deleteCrossRefsBySongIds(chunk)
                deleteFavoritesBySongIds(chunk)
                deleteLyricsBySongIds(chunk)
                deleteSongsByIds(chunk)
            }
        }

        // Upsert artists, albums, and songs.
        insertArtists(artists)
        insertAlbums(albums)

        // Insert songs in chunks to allow concurrent reads
        songs.chunked(SONG_BATCH_SIZE).forEach { chunk ->
            insertSongs(chunk)
        }

        // Delete old cross-refs for updated songs and insert new ones
        val updatedSongIds = songs.map { it.id }
        updatedSongIds.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            deleteCrossRefsBySongIds(chunk)
        }
        crossRefs.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            insertSongArtistCrossRefs(chunk)
        }

        // Clean up orphaned albums and artists
        deleteOrphanedAlbums()
        deleteOrphanedArtists()
    }

    // --- Directory Helper ---
    @Query("SELECT DISTINCT parent_directory_path FROM songs")
    suspend fun getDistinctParentDirectories(): List<String>

    /**
     * Reactive variant of [getDistinctParentDirectories]. Re-emits whenever the songs
     * table changes (e.g. after a sync adds songs in new folders), so cached directory
     * filters stay consistent with the actual library instead of freezing at an early,
     * possibly-empty snapshot taken before the first sync completes.
     */
    @Query("SELECT DISTINCT parent_directory_path FROM songs")
    fun getDistinctParentDirectoriesFlow(): Flow<List<String>>

    // --- Song Queries ---
    // Updated getSongs to include Telegram songs (negative IDs) regardless of directory filter
    @Query("SELECT " + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        ORDER BY title ASC
    """)
    fun getSongs(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("SELECT " + SONG_LIST_PROJECTION + " FROM songs WHERE id IN (:songIds)")
    suspend fun getSongsByIdsListSimple(songIds: List<Long>): List<SongEntity>

    /**
     * Resolves the unified-table song id for a given content URI. Used when the
     * currently-playing song was loaded from a non-unified source (e.g. raw Telegram
     * repository Songs whose ids are "chatId_messageId" strings) and we need the
     * matching negative-Long id to position the song inside the library list.
     */
    @Query("SELECT id FROM songs WHERE content_uri_string = :contentUri LIMIT 1")
    suspend fun getSongIdByContentUri(contentUri: String): Long?

    @Query(
        "SELECT " + SONG_DETAIL_PROJECTION + """
        FROM songs
        LEFT JOIN lyrics AS song_lyrics ON song_lyrics.songId = songs.id
        WHERE songs.id = :songId
        """
    )
    fun getSongById(songId: Long): Flow<SongEntity?>

    @Query(
        "SELECT " + SONG_DETAIL_PROJECTION + """
        FROM songs
        LEFT JOIN lyrics AS song_lyrics ON song_lyrics.songId = songs.id
        WHERE songs.id = :songId
        """
    )
    suspend fun getSongByIdOnce(songId: Long): SongEntity?

    @Query(
        "SELECT " + SONG_DETAIL_PROJECTION + """
        FROM songs
        LEFT JOIN lyrics AS song_lyrics ON song_lyrics.songId = songs.id
        WHERE songs.file_path = :path
        LIMIT 1
        """
    )
    suspend fun getSongByPath(path: String): SongEntity?

    @Query("SELECT " + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE id IN (:songIds)
        AND (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
    """)
    fun getSongsByIds(
        songIds: List<Long>,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE album_id = :albumId ORDER BY disc_number ASC, track_number ASC")
    fun getSongsByAlbumId(albumId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artist_id = :artistId ORDER BY title ASC")
    fun getSongsByArtistId(artistId: Long): Flow<List<SongEntity>>

    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN songs_fts ON songs_fts.rowid = songs.id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND songs_fts MATCH :matchQuery
        ORDER BY songs.title ASC
    """)
    fun searchSongsMatch(
        matchQuery: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (title LIKE '%' || :query || '%' OR artist_name LIKE '%' || :query || '%')
        ORDER BY title ASC
    """)
    fun searchSongsLike(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    fun searchSongs(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>> {
        val ftsFlow = searchSongsMatch(
            matchQuery = buildSongSearchMatchQuery(query),
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter
        )
        val likeFlow = searchSongsLike(
            query = query.trim(),
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter
        )
        return ftsFlow.combine(likeFlow) { ftsResults, likeResults ->
            val seen = LinkedHashMap<Long, SongEntity>(ftsResults.size + likeResults.size)
            ftsResults.forEach { seen.putIfAbsent(it.id, it) }
            likeResults.forEach { seen.putIfAbsent(it.id, it) }
            seen.values.toList()
        }
    }

    @Query("SELECT COUNT(*) FROM songs")
    fun getSongCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs WHERE source_type != 0")
    fun getCloudSongCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCountOnce(): Int

    @Query("""
        SELECT
            file_path AS filePath,
            content_uri_string AS contentUriString,
            mime_type AS mimeType,
            duration,
            bitrate,
            sample_rate AS sampleRate,
            source_type AS sourceType
        FROM songs
    """)
    suspend fun getDeviceCapabilitySongRows(): List<DeviceCapabilitySongRow>

    /**
     * Single-pass audio aggregates for the diagnostic performance report.
     * Hi-res thresholds: > 48 kHz = hi-res, >= 176.4 kHz = ultra-hi-res.
     * Estimated bytes = bitrate(bps) * duration(ms) / 8000.
     */
    @Query("""
        SELECT
            COUNT(*) AS totalCount,
            COALESCE(SUM(CASE WHEN source_type = 0 THEN 1 ELSE 0 END), 0) AS localCount,
            COALESCE(SUM(CASE WHEN source_type != 0 THEN 1 ELSE 0 END), 0) AS cloudCount,
            COALESCE(SUM(CASE WHEN sample_rate > 48000 THEN 1 ELSE 0 END), 0) AS hiResCount,
            COALESCE(SUM(CASE WHEN sample_rate >= 176400 THEN 1 ELSE 0 END), 0) AS ultraHiResCount,
            COALESCE(SUM(CASE
                WHEN sample_rate > 48000
                    OR mime_type LIKE '%flac%'
                    OR mime_type LIKE '%alac%'
                    OR mime_type LIKE '%wav%'
                    OR mime_type LIKE '%aiff%'
                    OR mime_type LIKE '%ape%'
                THEN 1 ELSE 0 END), 0) AS likelyExpensiveCount,
            MAX(bitrate) AS maxBitrate,
            MIN(NULLIF(sample_rate, 0)) AS minSampleRate,
            MAX(sample_rate) AS maxSampleRate,
            MIN(CASE WHEN bitrate > 0 AND duration > 0 THEN bitrate * duration / 8000 END) AS estMinBytes,
            AVG(CASE WHEN bitrate > 0 AND duration > 0 THEN bitrate * duration / 8000 END) AS estAvgBytes,
            MAX(CASE WHEN bitrate > 0 AND duration > 0 THEN bitrate * duration / 8000 END) AS estMaxBytes
        FROM songs
    """)
    suspend fun getLibraryAudioStats(): LibraryAudioStatsRow

    /** Per-MIME song counts for the diagnostic performance report. */
    @Query("SELECT mime_type AS mimeType, COUNT(*) AS count FROM songs GROUP BY mime_type ORDER BY count DESC")
    suspend fun getMimeTypeCounts(): List<MimeTypeCountRow>

    /**
     * Returns random songs for efficient shuffle without loading all songs into memory.
     * Uses SQLite RANDOM() for true randomness.
     */
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    suspend fun getRandomSongs(
        limit: Int,
        allowedParentDirs: List<String> = emptyList(),
        applyDirectoryFilter: Boolean = false
    ): List<SongEntity>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        ORDER BY title COLLATE LOCALIZED ASC, artist_name COLLATE LOCALIZED ASC, id ASC
        LIMIT 1
    """)
    suspend fun getFirstPlayableSong(
        allowedParentDirs: List<String> = emptyList(),
        applyDirectoryFilter: Boolean = false
    ): SongEntity?

    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
    """)
    fun getAllSongs(
        allowedParentDirs: List<String> = emptyList(),
        applyDirectoryFilter: Boolean = false
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE id IN (
            SELECT MIN(id)
            FROM songs
            WHERE album_art_uri_string IS NOT NULL
            AND album_art_uri_string != ''
            AND (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
            GROUP BY album_art_uri_string
        )
        ORDER BY title COLLATE LOCALIZED ASC, artist_name COLLATE LOCALIZED ASC, id ASC
    """)
    fun getDistinctAlbumArtSongs(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        ORDER BY date_added DESC, id DESC
        LIMIT :limit
    """)
    fun getHomeMixPreviewSongs(
        limit: Int,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT id, parent_directory_path, title, album_art_uri_string FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND source_type = 0
            )
            OR (
                :filterMode = 2
                AND source_type != 0
            )
        )
        ORDER BY parent_directory_path ASC, title ASC
    """)
    fun getFolderSongs(
        allowedParentDirs: List<String> = emptyList(),
        applyDirectoryFilter: Boolean = false,
        filterMode: Int
    ): Flow<List<FolderSongRow>>

    @Query("""
        SELECT id FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND source_type = 0
            )
            OR (
                :filterMode = 2
                AND source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'song_default_order' THEN track_number END ASC,
            CASE WHEN :sortOrder = 'song_title_az' THEN title END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'song_title_za' THEN title END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'song_artist' THEN artist_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'song_artist_desc' THEN artist_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'song_album' THEN album_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'song_album_desc' THEN album_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'song_date_added' THEN date_added END DESC,
            CASE WHEN :sortOrder = 'song_date_added_asc' THEN date_added END ASC,
            CASE WHEN :sortOrder = 'song_duration' THEN duration END DESC,
            CASE WHEN :sortOrder = 'song_duration_asc' THEN duration END ASC,
            title COLLATE LOCALIZED ASC,
            id ASC
    """)
    suspend fun getSongIdsSorted(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int
    ): List<Long>

    @Query("""
        SELECT songs.id FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'liked_title_az' THEN songs.title END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'liked_title_za' THEN songs.title END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'liked_artist' THEN songs.artist_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'liked_artist_desc' THEN songs.artist_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'liked_album' THEN songs.album_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'liked_album_desc' THEN songs.album_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'liked_date_liked' THEN favorites.timestamp END DESC,
            CASE WHEN :sortOrder = 'liked_date_liked_asc' THEN favorites.timestamp END ASC,
            songs.title COLLATE LOCALIZED ASC,
            songs.id ASC
    """)
    suspend fun getFavoriteSongIdsSorted(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int
    ): List<Long>

    // --- Paginated Queries for Large Libraries ---
    /**
     * Returns a PagingSource for songs, enabling efficient pagination for large libraries.
     * Room auto-generates the PagingSource implementation.
     */
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND source_type = 0
            )
            OR (
                :filterMode = 2
                AND source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'song_default_order' THEN track_number END ASC,
            CASE WHEN :sortOrder = 'song_title_az' THEN title END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'song_title_za' THEN title END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'song_artist' THEN artist_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'song_artist_desc' THEN artist_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'song_album' THEN album_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'song_album_desc' THEN album_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'song_date_added' THEN date_added END DESC,
            CASE WHEN :sortOrder = 'song_date_added_asc' THEN date_added END ASC,
            CASE WHEN :sortOrder = 'song_duration' THEN duration END DESC,
            CASE WHEN :sortOrder = 'song_duration_asc' THEN duration END ASC,

            -- Secondary sort falls back to title for consistency (case-insensitive)
            title COLLATE LOCALIZED ASC,
            id ASC
    """)
    fun getSongsPaginated(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int
    ): PagingSource<Int, SongEntity>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND source_type = 0
            )
            OR (
                :filterMode = 2
                AND source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'song_default_order' THEN track_number END ASC,
            CASE WHEN :sortOrder = 'song_title_az' THEN title END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'song_title_za' THEN title END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'song_artist' THEN artist_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'song_artist_desc' THEN artist_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'song_album' THEN album_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'song_album_desc' THEN album_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'song_date_added' THEN date_added END DESC,
            CASE WHEN :sortOrder = 'song_date_added_asc' THEN date_added END ASC,
            CASE WHEN :sortOrder = 'song_duration' THEN duration END DESC,
            CASE WHEN :sortOrder = 'song_duration_asc' THEN duration END ASC,
            title COLLATE LOCALIZED ASC,
            id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getSongsPage(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int,
        limit: Int,
        offset: Int
    ): List<SongEntity>

    // --- Paginated Favorites Queries ---
    /**
     * Returns a PagingSource for favorite songs, enabling efficient pagination.
     * Joins songs with favorites table and supports multi-sort.
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'liked_title_az' THEN songs.title END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'liked_title_za' THEN songs.title END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'liked_artist' THEN songs.artist_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'liked_artist_desc' THEN songs.artist_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'liked_album' THEN songs.album_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'liked_album_desc' THEN songs.album_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'liked_date_liked' THEN favorites.timestamp END DESC,
            CASE WHEN :sortOrder = 'liked_date_liked_asc' THEN favorites.timestamp END ASC,
            songs.title COLLATE LOCALIZED ASC,
            songs.id ASC
    """)
    fun getFavoriteSongsPaginated(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int
    ): PagingSource<Int, SongEntity>

    /**
     * Returns all favorite songs as a list (for playback queue when shuffling).
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        ORDER BY songs.title COLLATE LOCALIZED ASC
    """)
    suspend fun getFavoriteSongsList(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int
    ): List<SongEntity>

    @Query("""
        SELECT """ + SONG_LIST_PROJECTION + """
        FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        ORDER BY
            CASE WHEN :sortOrder = 'liked_title_az' THEN songs.title END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'liked_title_za' THEN songs.title END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'liked_artist' THEN songs.artist_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'liked_artist_desc' THEN songs.artist_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'liked_album' THEN songs.album_name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'liked_album_desc' THEN songs.album_name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'liked_date_liked' THEN favorites.timestamp END DESC,
            CASE WHEN :sortOrder = 'liked_date_liked_asc' THEN favorites.timestamp END ASC,
            songs.title COLLATE LOCALIZED ASC,
            songs.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFavoriteSongsPage(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        sortOrder: String,
        filterMode: Int,
        limit: Int,
        offset: Int
    ): List<SongEntity>

    /**
     * Returns the count of favorite songs (reactive).
     */
    @Query("""
        SELECT COUNT(*) FROM songs
        INNER JOIN favorites ON songs.id = favorites.songId AND favorites.isFavorite = 1
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
    """)
    fun getFavoriteSongCount(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int
    ): Flow<Int>

    // --- Paginated Search Query ---
    /**
     * Returns a PagingSource for search results, enabling efficient pagination for large result sets.
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN songs_fts ON songs_fts.rowid = songs.id
        WHERE (:applyDirectoryFilter = 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND songs_fts MATCH :matchQuery
        ORDER BY songs.title ASC
    """)
    fun searchSongsPaginatedMatch(
        matchQuery: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): PagingSource<Int, SongEntity>

    fun searchSongsPaginated(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): PagingSource<Int, SongEntity> = searchSongsPaginatedMatch(
        matchQuery = buildSongSearchMatchQuery(query),
        allowedParentDirs = allowedParentDirs,
        applyDirectoryFilter = applyDirectoryFilter
    )

    /**
     * Search songs with a result limit for non-paginated contexts (FTS).
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN songs_fts ON songs_fts.rowid = songs.id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND songs_fts MATCH :matchQuery
        ORDER BY songs.title ASC
        LIMIT :limit
    """)
    fun searchSongsLimitedMatch(
        matchQuery: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        limit: Int
    ): Flow<List<SongEntity>>

    /**
     * LIKE-based search focusing only on titles.
     */
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND title LIKE '%' || :query || '%'
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun searchSongsLimitedByTitleLike(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        limit: Int
    ): Flow<List<SongEntity>>

    /**
     * LIKE-based fallback search for songs that FTS tokenization may miss.
     */
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (title LIKE '%' || :query || '%' OR artist_name LIKE '%' || :query || '%')
        ORDER BY title ASC
        LIMIT :limit
    """)
    fun searchSongsLimitedLike(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        limit: Int
    ): Flow<List<SongEntity>>

    fun searchSongsLimited(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        limit: Int,
        titleOnly: Boolean = false
    ): Flow<List<SongEntity>> {
        val ftsFlow = searchSongsLimitedMatch(
            matchQuery = if (titleOnly) buildSongTitleSearchMatchQuery(query) else buildSongSearchMatchQuery(query),
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter,
            limit = limit
        )
        val likeFlow = if (titleOnly) {
            searchSongsLimitedByTitleLike(
                query = query.trim(),
                allowedParentDirs = allowedParentDirs,
                applyDirectoryFilter = applyDirectoryFilter,
                limit = limit
            )
        } else {
            searchSongsLimitedLike(
                query = query.trim(),
                allowedParentDirs = allowedParentDirs,
                applyDirectoryFilter = applyDirectoryFilter,
                limit = limit
            )
        }
        return ftsFlow.combine(likeFlow) { ftsResults, likeResults ->
            val seen = LinkedHashMap<Long, SongEntity>(ftsResults.size + likeResults.size)
            ftsResults.forEach { seen.putIfAbsent(it.id, it) }
            likeResults.forEach { seen.putIfAbsent(it.id, it) }
            seen.values.toList().take(limit)
        }
    }

    // --- Paginated Genre Query ---
    /**
     * Returns a PagingSource for songs in a specific genre.
     */
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR parent_directory_path IN (:allowedParentDirs))
        AND genre LIKE :genreName
        ORDER BY title ASC
    """)
    fun getSongsByGenrePaginated(
        genreName: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): PagingSource<Int, SongEntity>

    // --- Album Queries ---
    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY albums.title ASC
    """)
    fun getAlbums(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        minTracks: Int
    ): Flow<List<AlbumEntity>>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY
            CASE WHEN :sortOrder = 'album_title_az' THEN albums.title END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'album_title_za' THEN albums.title END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'album_artist' THEN COALESCE(NULLIF(TRIM(albums.album_artist), ''), albums.artist_name) END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'album_artist_desc' THEN COALESCE(NULLIF(TRIM(albums.album_artist), ''), albums.artist_name) END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'album_release_year' THEN albums.year END DESC,
            CASE WHEN :sortOrder = 'album_release_year_asc' THEN albums.year END ASC,
            CASE WHEN :sortOrder = 'album_date_added' THEN albums.date_added END DESC,
            CASE WHEN :sortOrder = 'album_size_asc' THEN song_count END ASC,
            CASE WHEN :sortOrder = 'album_size_desc' THEN song_count END DESC,
            albums.title COLLATE LOCALIZED ASC,
            albums.artist_name COLLATE LOCALIZED ASC,
            albums.id ASC
    """)
    fun getAlbumsPaginated(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        sortOrder: String,
        minTracks: Int
    ): PagingSource<Int, AlbumEntity>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY
            CASE WHEN :sortOrder = 'album_title_az' THEN albums.title END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'album_title_za' THEN albums.title END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'album_artist' THEN COALESCE(NULLIF(TRIM(albums.album_artist), ''), albums.artist_name) END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'album_artist_desc' THEN COALESCE(NULLIF(TRIM(albums.album_artist), ''), albums.artist_name) END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'album_release_year' THEN albums.year END DESC,
            CASE WHEN :sortOrder = 'album_release_year_asc' THEN albums.year END ASC,
            CASE WHEN :sortOrder = 'album_date_added' THEN albums.date_added END DESC,
            CASE WHEN :sortOrder = 'album_size_asc' THEN song_count END ASC,
            CASE WHEN :sortOrder = 'album_size_desc' THEN song_count END DESC,
            albums.title COLLATE LOCALIZED ASC,
            albums.artist_name COLLATE LOCALIZED ASC,
            albums.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getAlbumsPage(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        sortOrder: String,
        minTracks: Int,
        limit: Int,
        offset: Int
    ): List<AlbumEntity>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
            (
                SELECT COUNT(*)
                FROM songs
                WHERE songs.album_id = albums.id
            ) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM albums
        WHERE albums.id = :albumId
        LIMIT 1
    """)
    fun getAlbumById(albumId: Long): Flow<AlbumEntity?>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
            (
                SELECT COUNT(*)
                FROM songs
                WHERE songs.album_id = albums.id
            ) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM albums
        WHERE albums.title LIKE '%' || :query || '%'
        AND song_count >= :minTracks
        ORDER BY albums.title ASC
    """)
    fun searchAlbums(query: String, minTracks: Int = 1): Flow<List<AlbumEntity>>

    @Query("SELECT COUNT(*) FROM albums")
    fun getAlbumCount(): Flow<Int>

    // Version of getAlbums that returns a List for one-shot reads
    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY albums.title ASC
    """)
    suspend fun getAllAlbumsList(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        minTracks: Int
    ): List<AlbumEntity>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM albums
        LEFT JOIN songs ON albums.id = songs.album_id
        WHERE albums.artist_id = :artistId
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year
        ORDER BY albums.title ASC
    """)
    fun getAlbumsByArtistId(artistId: Long): Flow<List<AlbumEntity>>

    @Query("""
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.artist_name AS artist_name,
            albums.artist_id AS artist_id,
            albums.album_artist AS album_artist,
            albums.album_art_uri_string AS album_art_uri_string,
            COUNT(songs.id) AS song_count,
            albums.date_added AS date_added,
            albums.year AS year
        FROM songs
        INNER JOIN albums ON albums.id = songs.album_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (albums.title LIKE '%' || :query || '%' OR albums.artist_name LIKE '%' || :query || '%')
        GROUP BY
            albums.id,
            albums.title,
            albums.artist_name,
            albums.artist_id,
            albums.album_artist,
            albums.album_art_uri_string,
            albums.date_added,
            albums.year
        HAVING COUNT(songs.id) >= :minTracks
        ORDER BY albums.title ASC
    """)
    fun searchAlbums(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        minTracks: Int
    ): Flow<List<AlbumEntity>>

    // --- Artist Queries ---
    @Query("""
        SELECT DISTINCT artists.* FROM artists
        INNER JOIN songs ON artists.id = songs.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        ORDER BY artists.name ASC
    """)
    fun getArtists(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<ArtistEntity>>

    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT songs.id) AS track_count
        FROM songs
        INNER JOIN song_artist_cross_ref ON song_artist_cross_ref.song_id = songs.id
        INNER JOIN artists ON artists.id = song_artist_cross_ref.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY artists.id
        ORDER BY
            CASE WHEN :sortOrder = 'artist_name_az' THEN artists.name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'artist_name_za' THEN artists.name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_desc' THEN track_count END DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_asc' THEN track_count END ASC,
            artists.name COLLATE LOCALIZED ASC,
            artists.id ASC
    """)
    fun getArtistsPaginated(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        sortOrder: String
    ): PagingSource<Int, ArtistEntity>

    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT songs.id) AS track_count
        FROM songs
        INNER JOIN song_artist_cross_ref ON song_artist_cross_ref.song_id = songs.id
        INNER JOIN artists ON artists.id = song_artist_cross_ref.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY artists.id
        ORDER BY
            CASE WHEN :sortOrder = 'artist_name_az' THEN artists.name END COLLATE LOCALIZED ASC,
            CASE WHEN :sortOrder = 'artist_name_za' THEN artists.name END COLLATE LOCALIZED DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_desc' THEN track_count END DESC,
            CASE WHEN :sortOrder = 'artist_num_songs_asc' THEN track_count END ASC,
            artists.name COLLATE LOCALIZED ASC,
            artists.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getArtistsPage(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int,
        sortOrder: String,
        limit: Int,
        offset: Int
    ): List<ArtistEntity>

    /**
     * Unfiltered list of all artists (including those only reachable via cross-refs).
     */
    @Query("SELECT * FROM artists ORDER BY name ASC")
    fun getAllArtistsRaw(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :artistId")
    fun getArtistById(artistId: Long): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchArtists(query: String): Flow<List<ArtistEntity>>

    @Query("SELECT COUNT(*) FROM artists")
    fun getArtistCount(): Flow<Int>

    // Version of getArtists that returns a List for one-shot reads
    @Query("""
        SELECT DISTINCT artists.* FROM artists
        INNER JOIN songs ON artists.id = songs.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        ORDER BY artists.name ASC
    """)
    suspend fun getAllArtistsList(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): List<ArtistEntity>

    /**
     * Unfiltered list of all artists (one-shot).
     */
    @Query("SELECT * FROM artists ORDER BY name ASC")
    suspend fun getAllArtistsListRaw(): List<ArtistEntity>

    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT songs.id) AS track_count
        FROM songs
        INNER JOIN song_artist_cross_ref ON song_artist_cross_ref.song_id = songs.id
        INNER JOIN artists ON artists.id = song_artist_cross_ref.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND artists.name LIKE '%' || :query || '%'
        GROUP BY artists.id
        ORDER BY artists.name ASC
    """)
    fun searchArtists(
        query: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<ArtistEntity>>

    // --- Artist Image Operations ---
    @Query("SELECT image_url FROM artists WHERE id = :artistId")
    suspend fun getArtistImageUrl(artistId: Long): String?

    @Query("SELECT image_url FROM artists WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun getArtistImageUrlByNormalizedName(name: String): String?

    @Query("UPDATE artists SET image_url = :imageUrl WHERE id = :artistId")
    suspend fun updateArtistImageUrl(artistId: Long, imageUrl: String)

    @Query("SELECT id FROM artists WHERE name = :name LIMIT 1")
    suspend fun getArtistIdByName(name: String): Long?

    @Query("SELECT id FROM artists WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun getArtistIdByNormalizedName(name: String): Long?

    @Query("SELECT MAX(id) FROM artists")
    suspend fun getMaxArtistId(): Long?

    // --- Artist Custom Image Operations ---
    @Query("UPDATE artists SET custom_image_uri = :uri WHERE id = :artistId")
    suspend fun updateArtistCustomImage(artistId: Long, uri: String?)

    @Query("SELECT custom_image_uri FROM artists WHERE id = :artistId")
    suspend fun getArtistCustomImage(artistId: Long): String?

    // --- Genre Queries ---
    // Example: Get all songs for a specific genre
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND genre LIKE :genreName
        ORDER BY title ASC
    """)
    fun getSongsByGenre(
        genreName: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    // Multi-genre aware query: matches songs where the genre column equals the name (case-
    // insensitively, via LIKE), or contains it as part of a comma-separated list.
    // SQLite LIKE is case-insensitive for ASCII letters by default, which is sufficient
    // for genre names. All six arms use LIKE so that "rock" matches "Rock", "Rock,Pop",
    // "Rock, Pop", "Pop,Rock", "Pop, Rock", and "Pop,Rock,Jazz".
    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (
            genre LIKE :genreName
            OR genre LIKE :genrePrefix
            OR genre LIKE :genreSuffixWithSpace
            OR genre LIKE :genreSuffix
            OR genre LIKE :genreMiddleWithSpace
            OR genre LIKE :genreMiddle
        )
        ORDER BY title ASC
    """)
    fun getSongsByGenreContaining(
        genreName: String,
        genrePrefix: String,
        genreSuffixWithSpace: String,
        genreSuffix: String,
        genreMiddleWithSpace: String,
        genreMiddle: String,
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs
        WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        AND (genre IS NULL OR genre = '')
        ORDER BY title ASC
    """)
    fun getSongsWithNullGenre(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<SongEntity>>

    // Example: Get all unique genre names
    @Query("SELECT DISTINCT genre FROM songs WHERE genre IS NOT NULL AND genre != '' ORDER BY genre ASC")
    fun getUniqueGenres(): Flow<List<String>>

    @Query("""
        SELECT DISTINCT genre FROM songs
        WHERE genre IS NOT NULL AND genre != ''
        AND (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
        ORDER BY genre ASC
    """)
    fun getUniqueGenres(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<List<String>>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM songs
            WHERE (:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (:allowedParentDirs))
            AND (genre IS NULL OR genre = '')
        )
    """)
    fun hasUnknownGenre(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean
    ): Flow<Boolean>

    // --- Combined Queries (Potentially useful for more complex scenarios) ---
    // E.g., Get all album art URIs from songs (could be useful for theme preloading from SSoT)
    @Query("SELECT DISTINCT album_art_uri_string FROM songs WHERE album_art_uri_string IS NOT NULL")
    fun getAllUniqueAlbumArtUrisFromSongs(): Flow<List<String>>

    @Query("DELETE FROM albums WHERE NOT EXISTS (SELECT 1 FROM songs WHERE songs.album_id = albums.id)")
    suspend fun deleteOrphanedAlbums()

    @Query("DELETE FROM artists WHERE NOT EXISTS (SELECT 1 FROM song_artist_cross_ref WHERE song_artist_cross_ref.artist_id = artists.id)")
    suspend fun deleteOrphanedArtists()

    // --- Favorite Operations ---
    @Query("UPDATE songs SET is_favorite = :isFavorite WHERE id = :songId")
    suspend fun setFavoriteStatus(songId: Long, isFavorite: Boolean)

    @Query("SELECT is_favorite FROM songs WHERE id = :songId")
    suspend fun getFavoriteStatus(songId: Long): Boolean?

    // Transaction to toggle favorite status
    @Transaction
    suspend fun toggleFavoriteStatus(songId: Long): Boolean {
        val currentStatus = getFavoriteStatus(songId) ?: false // Default to false if not found (should not happen for existing song)
        val newStatus = !currentStatus
        setFavoriteStatus(songId, newStatus)
        return newStatus
    }

    @Query("""
        UPDATE songs
        SET title = :title,
            artist_name = :artist,
            artist_id = :artistId,
            artists_json = :artistsJson,
            album_name = :album,
            album_artist = :albumArtist,
            genre = :genre,
            track_number = :trackNumber,
            disc_number = :discNumber
        WHERE id = :songId
    """)
    suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        artistId: Long,
        artistsJson: String?,
        album: String,
        albumArtist: String?,
        genre: String?,
        trackNumber: Int,
        discNumber: Int?
    )

    @Transaction
    suspend fun updateSongMetadataAndArtistLinks(
        songId: Long,
        title: String,
        artist: String,
        artistId: Long,
        artistsJson: String?,
        album: String,
        albumArtist: String?,
        genre: String?,
        trackNumber: Int,
        discNumber: Int?,
        artistsToEnsure: List<ArtistEntity>,
        crossRefs: List<SongArtistCrossRef>
    ) {
        if (artistsToEnsure.isNotEmpty()) {
            insertArtistsIgnoreConflicts(artistsToEnsure)
        }

        updateSongMetadata(
            songId = songId,
            title = title,
            artist = artist,
            artistId = artistId,
            artistsJson = artistsJson,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            trackNumber = trackNumber,
            discNumber = discNumber
        )

        deleteCrossRefsForSong(songId)
        if (crossRefs.isNotEmpty()) {
            crossRefs.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
                insertSongArtistCrossRefs(chunk)
            }
        }

        deleteOrphanedArtists()
    }

    @Query("UPDATE songs SET album_art_uri_string = :albumArtUri WHERE id = :songId")
    suspend fun updateSongAlbumArt(songId: Long, albumArtUri: String?)

    @Query("UPDATE songs SET lyrics = :lyrics WHERE id = :songId")
    suspend fun updateLyrics(songId: Long, lyrics: String)

    @Query("UPDATE songs SET lyrics = NULL WHERE id = :songId")
    suspend fun resetLyrics(songId: Long)

    @Query("UPDATE songs SET lyrics = NULL")
    suspend fun resetAllLyrics()

    @Query("SELECT " + SONG_LIST_PROJECTION + " FROM songs")
    suspend fun getAllSongsList(): List<SongEntity>

    @Query("SELECT id, title, artist_name, album_name, duration FROM songs WHERE source_type = 0")
    suspend fun getAllLocalSongSummaries(): List<SongSummary>

    @Query("SELECT album_art_uri_string FROM songs WHERE id=:id")
    suspend fun getAlbumArtUriById(id: Long) : String?

    @Query("DELETE FROM songs WHERE id=:id")
    suspend fun deleteById(id: Long)

    @Query("""
    SELECT mime_type AS mimeType,
           bitrate,
           sample_rate AS sampleRate
    FROM songs
    WHERE id = :id
    """)
    suspend fun getAudioMetadataById(id: Long): AudioMeta?

    // ===== Song-Artist Cross Reference (Junction Table) Operations =====

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongArtistCrossRefs(crossRefs: List<SongArtistCrossRef>)

    @Query("SELECT * FROM song_artist_cross_ref")
    fun getAllSongArtistCrossRefs(): Flow<List<SongArtistCrossRef>>

    @Query("SELECT * FROM song_artist_cross_ref")
    suspend fun getAllSongArtistCrossRefsList(): List<SongArtistCrossRef>

    @Query("DELETE FROM song_artist_cross_ref")
    suspend fun clearAllSongArtistCrossRefs()

    @Query("DELETE FROM song_artist_cross_ref WHERE song_id = :songId")
    suspend fun deleteCrossRefsForSong(songId: Long)

    @Query("DELETE FROM song_artist_cross_ref WHERE artist_id = :artistId")
    suspend fun deleteCrossRefsForArtist(artistId: Long)

    /**
     * Get all artists for a specific song using the junction table.
     */
    @Query("""
        SELECT artists.* FROM artists
        INNER JOIN song_artist_cross_ref ON artists.id = song_artist_cross_ref.artist_id
        WHERE song_artist_cross_ref.song_id = :songId
        ORDER BY song_artist_cross_ref.is_primary DESC, artists.name ASC
    """)
    fun getArtistsForSong(songId: Long): Flow<List<ArtistEntity>>

    /**
     * Get all artists for a specific song (one-shot).
     */
    @Query("""
        SELECT artists.* FROM artists
        INNER JOIN song_artist_cross_ref ON artists.id = song_artist_cross_ref.artist_id
        WHERE song_artist_cross_ref.song_id = :songId
        ORDER BY song_artist_cross_ref.is_primary DESC, artists.name ASC
    """)
    suspend fun getArtistsForSongList(songId: Long): List<ArtistEntity>

    /**
     * Get all songs for a specific artist using the junction table.
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN song_artist_cross_ref ON songs.id = song_artist_cross_ref.song_id
        WHERE song_artist_cross_ref.artist_id = :artistId
        ORDER BY songs.title ASC
    """)
    fun getSongsForArtist(artistId: Long): Flow<List<SongEntity>>

    /**
     * Get all songs for a specific artist (one-shot).
     */
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN song_artist_cross_ref ON songs.id = song_artist_cross_ref.song_id
        WHERE song_artist_cross_ref.artist_id = :artistId
        ORDER BY songs.title ASC
    """)
    suspend fun getSongsForArtistList(artistId: Long): List<SongEntity>

    /**
     * Get the cross-references for a specific song.
     */
    @Query("SELECT * FROM song_artist_cross_ref WHERE song_id = :songId")
    suspend fun getCrossRefsForSong(songId: Long): List<SongArtistCrossRef>

    /**
     * Get the primary artist for a song.
     */
    @Query("""
        SELECT artists.id AS artist_id, artists.name FROM artists
        INNER JOIN song_artist_cross_ref ON artists.id = song_artist_cross_ref.artist_id
        WHERE song_artist_cross_ref.song_id = :songId AND song_artist_cross_ref.is_primary = 1
        LIMIT 1
    """)
    suspend fun getPrimaryArtistForSong(songId: Long): PrimaryArtistInfo?

    /**
     * Get song count for an artist from the junction table.
     */
    @Query("SELECT COUNT(*) FROM song_artist_cross_ref WHERE artist_id = :artistId")
    suspend fun getSongCountForArtist(artistId: Long): Int

    /**
     * Get all artists with their song counts computed from the junction table.
     */
    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT song_artist_cross_ref.song_id) AS track_count
        FROM artists
        LEFT JOIN song_artist_cross_ref ON artists.id = song_artist_cross_ref.artist_id
        GROUP BY artists.id
        ORDER BY artists.name ASC
    """)
    fun getArtistsWithSongCounts(): Flow<List<ArtistEntity>>

    /**
     * Get all artists with song counts, filtered by allowed directories.
     */
    @Query("""
        SELECT artists.id, artists.name, artists.image_url, artists.custom_image_uri,
               COUNT(DISTINCT songs.id) AS track_count
        FROM songs
        INNER JOIN song_artist_cross_ref ON song_artist_cross_ref.song_id = songs.id
        INNER JOIN artists ON artists.id = song_artist_cross_ref.artist_id
        WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
        AND (
            :filterMode = 0
            OR (
                :filterMode = 1
                AND songs.source_type = 0
            )
            OR (
                :filterMode = 2
                AND songs.source_type != 0
            )
        )
        GROUP BY artists.id
        ORDER BY artists.name ASC
    """)
    fun getArtistsWithSongCountsFiltered(
        allowedParentDirs: List<String>,
        applyDirectoryFilter: Boolean,
        filterMode: Int
    ): Flow<List<ArtistEntity>>

    /**
     * Clear all music data including cross-references.
     */
    @Transaction
    suspend fun clearAllMusicDataWithCrossRefs() {
        clearAllSongArtistCrossRefs()
        clearAllSongs()
        clearAllAlbums()
        clearAllArtists()
    }

    /**
     * Insert music data with cross-references in a single transaction.
     * Uses chunked inserts for cross-refs to avoid SQLite variable limits.
     */
    @Transaction
    suspend fun insertMusicDataWithCrossRefs(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        crossRefs: List<SongArtistCrossRef>
    ) {
        insertArtists(artists)
        insertAlbums(albums)
        insertSongs(songs)
        // Insert cross-refs in chunks to avoid SQLite variable limit.
        // Each SongArtistCrossRef has 3 fields, so batch size is calculated accordingly.
        crossRefs.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            insertSongArtistCrossRefs(chunk)
        }
    }

    @Transaction
    suspend fun rebuildMusicDataWithCrossRefs(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        crossRefs: List<SongArtistCrossRef>
    ) {
        // Save current cloud songs before clearing to prevent accidental data loss
        // Only clear if we have new songs to insert, or we are explicitly asked to REBUILD everything.
        // We handle this logic at the worker/repository level to be more precise.

        clearAllSongArtistCrossRefs()
        clearAllSongs()
        clearAllAlbums()
        clearAllArtists()

        insertArtists(artists)
        insertAlbums(albums)
        insertSongs(songs)
        crossRefs.chunked(CROSS_REF_BATCH_SIZE).forEach { chunk ->
            insertSongArtistCrossRefs(chunk)
        }
    }

    companion object {
        /**
         * SQLite has a limit on the number of variables per statement (default 999, higher in newer versions).
         * Each SongArtistCrossRef insert uses 3 variables (songId, artistId, isPrimary).
         * The batch size is calculated so that batchSize * 3 <= SQLITE_MAX_VARIABLE_NUMBER.
         */
        private const val SQLITE_MAX_VARIABLE_NUMBER = 999 // Increase if you know your SQLite version supports more
        private const val CROSS_REF_FIELDS_PER_OBJECT = 3
        val CROSS_REF_BATCH_SIZE: Int = SQLITE_MAX_VARIABLE_NUMBER / CROSS_REF_FIELDS_PER_OBJECT

        /**
         * Batch size for song inserts during incremental sync.
         * Allows database reads to interleave with writes for better UX.
         */
        const val SONG_BATCH_SIZE = 500
    }
}
