package com.theveloper.pixelplay.data.repository

import android.net.Uri
import androidx.paging.PagingData
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsSourcePreference
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.Song
import kotlinx.coroutines.flow.Flow
import com.theveloper.pixelplay.data.database.TelegramChannelEntity

interface MusicRepository {
    /**
     * Obtiene la lista de archivos de audio (canciones) filtrada por directorios permitidos.
     * @return Flow que emite una lista completa de objetos Song.
     */
    fun getAudioFiles(): Flow<List<Song>> // Existing Flow for reactive updates

    /**
     * Returns paginated songs for efficient display of large libraries.
     * @return Flow of PagingData<Song> for use with LazyPagingItems.
     */
    fun getPaginatedSongs(sortOption: com.theveloper.pixelplay.data.model.SortOption, storageFilter: com.theveloper.pixelplay.data.model.StorageFilter): Flow<PagingData<Song>>

    /**
     * Returns paginated albums for efficient display in library tabs.
     */
    fun getPaginatedAlbums(
        sortOption: com.theveloper.pixelplay.data.model.SortOption,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL,
        minTracks: Int = 1
    ): Flow<PagingData<Album>>

    /**
     * Returns paginated artists for efficient display in library tabs.
     */
    fun getPaginatedArtists(
        sortOption: com.theveloper.pixelplay.data.model.SortOption,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL
    ): Flow<PagingData<Artist>>

    /**
     * Returns paginated favorite songs for efficient display.
     * @return Flow of PagingData<Song> for use with LazyPagingItems.
     */
    fun getPaginatedFavoriteSongs(
        sortOption: com.theveloper.pixelplay.data.model.SortOption,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL
    ): Flow<PagingData<Song>>

    /**
     * Returns all favorite songs as a list (for playback queue on shuffle).
     */
    suspend fun getFavoriteSongsOnce(
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL
    ): List<Song>

    /**
     * Returns a bounded favorites page without materializing the full favorites list.
     */
    suspend fun getFavoriteSongsPage(
        limit: Int,
        offset: Int,
        sortOption: com.theveloper.pixelplay.data.model.SortOption = com.theveloper.pixelplay.data.model.SortOption.LikedSongTitleAZ,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL
    ): List<Song>

    /**
     * Returns the count of favorite songs (reactive).
     */
    fun getFavoriteSongCountFlow(
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL
    ): Flow<Int>

    /**
     * Returns the count of cloud songs in the library.
     */
    fun getCloudSongCountFlow(): Flow<Int>

    /**
     * Returns a random selection of songs for efficient shuffle.
     * Uses database-level RANDOM() for performance.
     * @param limit Maximum number of songs to return.
     * @return List of randomly selected songs.
     */
    suspend fun getRandomSongs(limit: Int): List<Song>

    /**
     * Returns a bounded song page without materializing the full library.
     */
    suspend fun getSongsPage(
        limit: Int,
        offset: Int,
        sortOption: com.theveloper.pixelplay.data.model.SortOption = com.theveloper.pixelplay.data.model.SortOption.SongDefaultOrder,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL
    ): List<Song>

    /**
     * Returns a bounded album page without materializing the full albums list.
     */
    suspend fun getAlbumsPage(
        limit: Int,
        offset: Int,
        sortOption: com.theveloper.pixelplay.data.model.SortOption = com.theveloper.pixelplay.data.model.SortOption.AlbumTitleAZ,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL,
        minTracks: Int = 1
    ): List<Album>

    /**
     * Returns a bounded artist page without materializing the full artists list.
     */
    suspend fun getArtistsPage(
        limit: Int,
        offset: Int,
        sortOption: com.theveloper.pixelplay.data.model.SortOption = com.theveloper.pixelplay.data.model.SortOption.ArtistNameAZ,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL
    ): List<Artist>

    /**
     * Returns a single playable song without materializing the entire library.
     * Useful for startup and fallback playback paths.
     */
    suspend fun getFirstPlayableSong(): Song?

    /**
     * Obtiene la lista de álbumes filtrada.
     * @return Flow que emite una lista completa de objetos Album.
     */
    fun getAlbums(
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL,
        minTracks: Int = 1
    ): Flow<List<Album>> // Existing Flow for reactive updates

    /**
     * Obtiene la lista de artistas filtrada.
     * @return Flow que emite una lista completa de objetos Artist.
     */
    fun getArtists(
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL
    ): Flow<List<Artist>> // Existing Flow for reactive updates

    /**
     * Obtiene la lista completa de canciones una sola vez.
     * @return Lista de objetos Song.
     */
    suspend fun getAllSongsOnce(): List<Song>

    /**
     * Returns one representative song per unique album art URI for maintenance tools that
     * operate on artwork-derived palettes.
     */
    fun getDistinctAlbumArtSongs(): Flow<List<Song>>

    /**
     * Returns a bounded preview sample for Home without materializing the full library in UI.
     */
    fun getHomeMixPreviewSongs(limit: Int): Flow<List<Song>>

    /**
     * Obtiene la lista completa de álbumes una sola vez.
     * @return Lista de objetos Album.
     */
    suspend fun getAllAlbumsOnce(
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL,
        minTracks: Int = 1
    ): List<Album>

    /**
     * Obtiene la lista completa de artistas una sola vez.
     * @return Lista de objetos Artist.
     */
    suspend fun getAllArtistsOnce(): List<Artist>

    /**
     * Obtiene un álbum específico por su ID.
     * @param id El ID del álbum.
     * @return Flow que emite el objeto Album o null si no se encuentra.
     */
    fun getAlbumById(id: Long): Flow<Album?>

    /**
     * Obtiene la lista de artistas filtrada.
     * @return Flow que emite una lista completa de objetos Artist.
     */
    //fun getArtists(): Flow<List<Artist>>

    /**
     * Obtiene la lista de canciones para un álbum específico (NO paginada para la cola de reproducción).
     * @param albumId El ID del álbum.
     * @return Flow que emite una lista de objetos Song pertenecientes al álbum.
     */
    fun getSongsForAlbum(albumId: Long): Flow<List<Song>>

    /**
     * Obtiene la lista de canciones para un artista específico (NO paginada para la cola de reproducción).
     * @param artistId El ID del artista.
     * @return Flow que emite una lista de objetos Song pertenecientes al artista.
     */
    fun getSongsForArtist(artistId: Long): Flow<List<Song>>

    /**
     * Obtiene una lista de canciones por sus IDs.
     * @param songIds Lista de IDs de canciones.
     * @return Flow que emite una lista de objetos Song correspondientes a los IDs, en el mismo orden.
     */
    fun getSongsByIds(songIds: List<String>): Flow<List<Song>>

    /**
     * Obtiene una canción por su ruta de archivo.
     * @param path Ruta del archivo.
     * @return El objeto Song o null si no se encuentra.
     */
    suspend fun getSongByPath(path: String): Song?

    /**
     * Obtiene todos los directorios únicos que contienen archivos de audio.
     * Esto se usa principalmente para la configuración inicial de directorios.
     * También gestiona el guardado inicial de directorios permitidos si es la primera vez.
     * @return Conjunto de rutas de directorios únicas.
     */
    suspend fun getAllUniqueAudioDirectories(): Set<String>

    fun getAllUniqueAlbumArtUris(): Flow<List<Uri>> // Nuevo para precarga de temas

    suspend fun invalidateCachesDependentOnAllowedDirectories() // Nuevo para precarga de temas

    fun searchSongs(query: String, titleOnly: Boolean = false): Flow<List<Song>>
    fun searchAlbums(query: String, minTracks: Int = 1): Flow<List<Album>>
    fun searchArtists(query: String): Flow<List<Artist>>
    suspend fun searchPlaylists(query: String): List<Playlist> // Mantener suspend, ya que no hay Flow aún
    fun searchAll(query: String, filterType: SearchFilterType): Flow<List<SearchResultItem>>

    // Search History
    suspend fun addSearchHistoryItem(query: String)
    suspend fun getRecentSearchHistory(limit: Int): List<SearchHistoryItem>
    suspend fun deleteSearchHistoryItemByQuery(query: String)
    suspend fun clearSearchHistory()

    /**
     * Obtiene la lista de canciones para un género específico (placeholder implementation).
     * @param genreId El ID del género (e.g., "pop", "rock").
     * @return Flow que emite una lista de objetos Song (simulada para este género).
     */
    fun getMusicByGenre(genreId: String): Flow<List<Song>> // Changed to Flow

    /**
     * Cambia el estado de favorito de una canción.
     * @param songId El ID de la canción.
     * @return El nuevo estado de favorito (true si es favorito, false si no).
     */
    suspend fun toggleFavoriteStatus(songId: String): Boolean

    /**
     * Setea explícitamente el estado favorito de una canción.
     * @param songId El ID de la canción.
     * @param isFavorite Estado objetivo.
     */
    suspend fun setFavoriteStatus(songId: String, isFavorite: Boolean)

    /**
     * Obtiene IDs de canciones favoritas directamente desde Room (tabla favorites).
     */
    suspend fun getFavoriteSongIdsOnce(): Set<String>

    /**
     * Reactive stream of favorite song IDs from Room favorites table.
     */
    fun getFavoriteSongIdsFlow(): Flow<Set<String>>

    /**
     * Obtiene una canción específica por su ID.
     * @param songId El ID de la canción.
     * @return Flow que emite el objeto Song o null si no se encuentra.
     */
    fun getSong(songId: String): Flow<Song?>
    fun getArtistById(artistId: Long): Flow<Artist?>
    suspend fun getArtistIdByName(name: String): Long?
    fun getArtistsForSong(songId: Long): Flow<List<Artist>>

    /**
     * Obtiene la lista de géneros, ya sea mockeados o leídos de los metadatos.
     * @return Flow que emite una lista de objetos Genre.
     */
    fun getGenres(): Flow<List<com.theveloper.pixelplay.data.model.Genre>>

    suspend fun getLyrics(
        song: Song,
        sourcePreference: LyricsSourcePreference = LyricsSourcePreference.EMBEDDED_FIRST,
        forceRefresh: Boolean = false
    ): Lyrics?

    suspend fun getStoredLyrics(song: Song): Pair<Lyrics, String>?

    suspend fun getLyricsFromRemote(song: Song): Result<Pair<Lyrics, String>>

    /**
     * Search for lyrics remotely, less specific than `getLyricsFromRemote` but more lenient
     * @param song The song to search lyrics for
     * @return The search query and the results
     */
    suspend fun searchRemoteLyrics(song: Song): Result<Pair<String, List<LyricsSearchResult>>>

    /**
     * Search for lyrics remotely using query provided, and not use song metadata
     * @param query The query for searching, typically song title and artist name
     * @return The search query and the results
     */
    suspend fun searchRemoteLyricsByQuery(title: String, artist: String? = null): Result<Pair<String, List<LyricsSearchResult>>>

    suspend fun updateLyrics(songId: Long, lyrics: String)

    suspend fun resetLyrics(songId: Long)

    suspend fun resetAllLyrics()

    fun getMusicFolders(
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL
    ): Flow<List<com.theveloper.pixelplay.data.model.MusicFolder>>

    suspend fun deleteById(id: Long)
    suspend fun saveTelegramSongs(songs: List<Song>)

    suspend fun replaceTelegramSongsForChannel(chatId: Long, songs: List<Song>)

    suspend fun clearTelegramData()

    suspend fun saveTelegramChannel(channel: TelegramChannelEntity)
    fun getAllTelegramChannels(): Flow<List<TelegramChannelEntity>>
    suspend fun deleteTelegramChannel(chatId: Long)
    suspend fun saveTelegramTopics(chatId: Long, topics: List<com.theveloper.pixelplay.data.database.TelegramTopicEntity>)
    /** Replaces the full topic list for a channel, deleting any topics that no longer exist. */
    suspend fun replaceTopicsForChannel(chatId: Long, freshTopics: List<com.theveloper.pixelplay.data.database.TelegramTopicEntity>)
    suspend fun getTopicsForChannel(chatId: Long): List<com.theveloper.pixelplay.data.database.TelegramTopicEntity>
    fun getAllTelegramTopics(): Flow<List<com.theveloper.pixelplay.data.database.TelegramTopicEntity>>
    suspend fun replaceTelegramSongsForTopic(chatId: Long, threadId: Long, topicName: String, songs: List<Song>)

    val telegramRepository: com.theveloper.pixelplay.data.telegram.TelegramRepository

    suspend fun getSongIdsSorted(
        sortOption: com.theveloper.pixelplay.data.model.SortOption,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter
    ): List<Long>

    suspend fun getFavoriteSongIdsSorted(
        sortOption: com.theveloper.pixelplay.data.model.SortOption,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter
    ): List<Long>

    /**
     * Resolves the unified-table song id for a content URI. Returns null if no
     * matching row exists. Used by locate-current-song to recover from playback
     * sessions where `Song.id` is a non-numeric source-specific string.
     */
    suspend fun getSongIdByContentUri(contentUri: String): Long?

    /**
     * Enqueues an incremental SyncWorker run with [androidx.work.ExistingWorkPolicy.KEEP].
     * Use from finally blocks of Telegram ingestion flows to guarantee the unified-table
     * sync happens even when an exception bypasses the normal end-of-flow
     * [saveTelegramChannel] call. KEEP avoids cancelling a full/rebuild that may be
     * in progress under the same unique work name.
     */
    fun requestTelegramUnifiedSync()
}
