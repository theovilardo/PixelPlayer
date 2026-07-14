package com.theveloper.pixelplay.data.network.spotify

import com.google.gson.JsonObject
import com.theveloper.pixelplay.data.network.spotify.dto.SpotifyPagingDto
import com.theveloper.pixelplay.data.network.spotify.dto.SpotifyPlaylistDto
import com.theveloper.pixelplay.data.network.spotify.dto.SpotifyPlaylistTrackDto
import com.theveloper.pixelplay.data.network.spotify.dto.SpotifySavedTrackDto
import com.theveloper.pixelplay.data.network.spotify.dto.SpotifyUserDto
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface SpotifyApiService {

    @GET("v1/me")
    suspend fun getCurrentUser(
        @Header("Authorization") authorization: String
    ): SpotifyUserDto

    @GET("v1/me/tracks")
    suspend fun getSavedTracks(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null
    ): SpotifyPagingDto<SpotifySavedTrackDto>

    @GET("v1/me/playlists")
    suspend fun getCurrentUserPlaylists(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): SpotifyPagingDto<SpotifyPlaylistDto>

    @GET("v1/playlists/{playlistId}/tracks")
    suspend fun getPlaylistTracks(
        @Header("Authorization") authorization: String,
        @Path("playlistId") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null
    ): SpotifyPagingDto<SpotifyPlaylistTrackDto>

    @PUT("v1/me/player/play")
    suspend fun startPlayback(
        @Header("Authorization") authorization: String,
        @Query("device_id") deviceId: String? = null,
        @Body body: JsonObject
    )

    @PUT("v1/me/player/pause")
    suspend fun pausePlayback(
        @Header("Authorization") authorization: String,
        @Query("device_id") deviceId: String? = null,
        @Body body: RequestBody? = null
    )
}

