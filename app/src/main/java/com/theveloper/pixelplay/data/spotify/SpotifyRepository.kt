package com.theveloper.pixelplay.data.spotify

import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.data.database.SpotifySongEntity
import com.theveloper.pixelplay.data.network.spotify.SpotifyApiService
import com.theveloper.pixelplay.data.network.spotify.SpotifyResponseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyRepository @Inject constructor(
    private val api: SpotifyApiService,
    private val dao: SpotifyDao,
    private val authManager: SpotifyAuthManager,
    private val syncManager: SpotifySyncManager
) {
    private val isLoggedInState = MutableStateFlow(authManager.loadCredentials() != null)
    private var connectedDisplayName: String? = null
    private var spotifyLastSyncTime: Long = 0L

    init {
        connectedDisplayName = authManager.loadCredentials()?.displayName
    }

    val isLoggedInFlow: Flow<Boolean> = isLoggedInState.asStateFlow()

    fun getPlaylists(): Flow<List<SpotifyPlaylistEntity>> {
        return dao.getPlaylists()
    }

    fun getPlaylistSongs(playlistId: String): Flow<List<SpotifySongEntity>> {
        return dao.getSongsByPlaylist(playlistId)
    }

    val displayName: String?
        get() = connectedDisplayName

    val lastSyncTime: Long
        get() = spotifyLastSyncTime

    suspend fun completeAuthorization(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): Result<String> {
        return if (clientId.isBlank() || code.isBlank() || codeVerifier.isBlank() || redirectUri.isBlank()) {
            Result.failure(IllegalArgumentException("Spotify authorization response is incomplete"))
        } else {
            runCatching {
                var credentials = authManager.exchangeAuthorizationCode(
                    clientId = clientId,
                    code = code,
                    codeVerifier = codeVerifier,
                    redirectUri = redirectUri
                )
                val user = api.getCurrentUser(credentials.authorizationHeader)
                val displayName = SpotifyResponseParser.parseUserDisplayName(user)
                credentials = authManager.withDisplayName(credentials, displayName)
                connectedDisplayName = credentials.displayName ?: displayName
                isLoggedInState.value = true
                connectedDisplayName ?: "Spotify"
            }
        }
    }

    suspend fun syncLibrary(): Result<Int> {
        return runCatching {
            val credentials = requireCredentials()
            val songs = syncManager.fetchSavedTracks(credentials.authorizationHeader)
            dao.deleteSongsByPlaylist(SpotifySongEntity.LIKED_SONGS_PLAYLIST_ID)
            dao.insertSongs(songs)
            spotifyLastSyncTime = System.currentTimeMillis()
            songs.size
        }
    }

    suspend fun syncPlaylists(): Result<Int> {
        return runCatching {
            val credentials = requireCredentials()
            val playlists = syncManager.fetchPlaylists(credentials.authorizationHeader)
            dao.insertPlaylists(playlists)
            spotifyLastSyncTime = System.currentTimeMillis()
            playlists.size
        }
    }

    suspend fun syncPlaylistTracks(playlistId: String): Result<Int> {
        return runCatching {
            val credentials = requireCredentials()
            val songs = syncManager.fetchPlaylistTracks(credentials.authorizationHeader, playlistId)
            dao.deleteSongsByPlaylist(playlistId)
            dao.insertSongs(songs)
            songs.size
        }
    }

    suspend fun deleteCachedPlaylist(playlistId: String) {
        dao.deleteSongsByPlaylist(playlistId)
        dao.deletePlaylist(playlistId)
    }

    suspend fun logout() {
        authManager.clear()
        connectedDisplayName = null
        spotifyLastSyncTime = 0L
        dao.clearSongs()
        dao.clearPlaylists()
        isLoggedInState.value = false
    }

    private suspend fun requireCredentials() =
        authManager.refreshIfNeeded(authManager.loadCredentials())
            ?: error("Spotify is not connected")
    }
