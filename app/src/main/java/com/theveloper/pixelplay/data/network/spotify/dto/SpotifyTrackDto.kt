package com.theveloper.pixelplay.data.network.spotify.dto

import com.google.gson.annotations.SerializedName

data class SpotifySavedTrackDto(
    @SerializedName("added_at") val addedAt: String? = null,
    @SerializedName("track") val track: SpotifyTrackDto? = null
)

data class SpotifyPlaylistTrackDto(
    @SerializedName("added_at") val addedAt: String? = null,
    @SerializedName("track") val track: SpotifyTrackDto? = null,
    @SerializedName("is_local") val isLocal: Boolean = false
)

data class SpotifyTrackDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("artists") val artists: List<SpotifyArtistDto> = emptyList(),
    @SerializedName("album") val album: SpotifyAlbumDto? = null,
    @SerializedName("duration_ms") val durationMs: Long = 0L,
    @SerializedName("disc_number") val discNumber: Int? = null,
    @SerializedName("track_number") val trackNumber: Int = 0,
    @SerializedName("explicit") val explicit: Boolean = false,
    @SerializedName("is_playable") val isPlayable: Boolean? = null
)

data class SpotifyArtistDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("name") val name: String? = null
)

data class SpotifyAlbumDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("album_type") val albumType: String? = null,
    @SerializedName("artists") val artists: List<SpotifyArtistDto> = emptyList(),
    @SerializedName("images") val images: List<SpotifyImageDto> = emptyList(),
    @SerializedName("release_date") val releaseDate: String? = null
)

