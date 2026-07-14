package com.theveloper.pixelplay.data.network.spotify.dto

import com.google.gson.annotations.SerializedName

data class SpotifyUserDto(
    @SerializedName("id") val id: String,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("images") val images: List<SpotifyImageDto> = emptyList(),
    @SerializedName("product") val product: String? = null
)

data class SpotifyImageDto(
    @SerializedName("url") val url: String,
    @SerializedName("height") val height: Int? = null,
    @SerializedName("width") val width: Int? = null
)

