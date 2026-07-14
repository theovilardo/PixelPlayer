package com.theveloper.pixelplay.data.network.spotify.dto

import com.google.gson.annotations.SerializedName

data class SpotifyPlaylistDto(
    @SerializedName("id") val id: String,
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("images") val images: List<SpotifyImageDto> = emptyList(),
    @SerializedName("owner") val owner: SpotifyPlaylistOwnerDto? = null,
    @SerializedName("tracks") val tracks: SpotifyPlaylistTracksSummaryDto? = null,
    @SerializedName("public") val isPublic: Boolean? = null,
    @SerializedName("collaborative") val collaborative: Boolean = false
)

data class SpotifyPlaylistOwnerDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("display_name") val displayName: String? = null
)

data class SpotifyPlaylistTracksSummaryDto(
    @SerializedName("href") val href: String? = null,
    @SerializedName("total") val total: Int = 0
)

