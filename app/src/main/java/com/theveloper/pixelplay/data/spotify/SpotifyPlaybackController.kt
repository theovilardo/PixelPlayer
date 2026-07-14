package com.theveloper.pixelplay.data.spotify

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.theveloper.pixelplay.data.network.spotify.SpotifyApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyPlaybackController @Inject constructor(
    private val api: SpotifyApiService,
    private val authManager: SpotifyAuthManager
) {

    suspend fun playTrack(spotifyId: String): Result<Unit> = runCatching {
        val credentials = authManager.refreshIfNeeded(authManager.loadCredentials())
            ?: error("Spotify is not connected")
        val body = JsonObject().apply {
            add("uris", JsonArray().apply { add("spotify:track:$spotifyId") })
        }
        api.startPlayback(credentials.authorizationHeader, body = body)
    }

    suspend fun pause(): Result<Unit> = runCatching {
        val credentials = authManager.refreshIfNeeded(authManager.loadCredentials())
            ?: error("Spotify is not connected")
        api.pausePlayback(credentials.authorizationHeader)
    }
}

