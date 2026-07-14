package com.theveloper.pixelplay.data.spotify

import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.data.database.SpotifySongEntity
import com.theveloper.pixelplay.data.database.toEntity
import com.theveloper.pixelplay.data.network.spotify.SpotifyApiService
import com.theveloper.pixelplay.data.network.spotify.SpotifyResponseParser
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifySyncManager @Inject constructor(
    private val api: SpotifyApiService
) {

    suspend fun fetchSavedTracks(authorization: String): List<SpotifySongEntity> {
        val songs = mutableListOf<SpotifySongEntity>()
        var offset = 0
        do {
            val page = api.getSavedTracks(authorization = authorization, limit = SAVED_TRACKS_LIMIT, offset = offset)
            songs += page.items.mapNotNull { saved ->
                saved.track?.let {
                    SpotifyResponseParser.parseTrack(it, addedAt = saved.addedAt)
                        ?.toEntity(SpotifySongEntity.LIKED_SONGS_PLAYLIST_ID)
                }
            }
            offset += page.items.size
        } while (page.next != null && page.items.isNotEmpty())
        return songs
    }

    suspend fun fetchPlaylists(authorization: String): List<SpotifyPlaylistEntity> {
        val playlists = mutableListOf<SpotifyPlaylistEntity>()
        var offset = 0
        do {
            val page = api.getCurrentUserPlaylists(authorization = authorization, limit = PLAYLISTS_LIMIT, offset = offset)
            playlists += page.items.map { SpotifyResponseParser.parsePlaylist(it).toEntity() }
            offset += page.items.size
        } while (page.next != null && page.items.isNotEmpty())
        return playlists
    }

    suspend fun fetchPlaylistTracks(authorization: String, playlistId: String): List<SpotifySongEntity> {
        val songs = mutableListOf<SpotifySongEntity>()
        var offset = 0
        do {
            val page = api.getPlaylistTracks(
                authorization = authorization,
                playlistId = playlistId,
                limit = PLAYLIST_TRACKS_LIMIT,
                offset = offset
            )
            songs += page.items.mapNotNull { item ->
                if (item.isLocal) null else item.track?.let {
                    SpotifyResponseParser.parseTrack(it, playlistId = playlistId, addedAt = item.addedAt)
                        ?.toEntity(playlistId)
                }
            }
            offset += page.items.size
        } while (page.next != null && page.items.isNotEmpty())
        return songs
    }

    companion object {
        private const val SAVED_TRACKS_LIMIT = 50
        private const val PLAYLISTS_LIMIT = 50
        private const val PLAYLIST_TRACKS_LIMIT = 100
    }
}

