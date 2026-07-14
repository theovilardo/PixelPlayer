package com.theveloper.pixelplay.data.network.spotify.dto

import com.google.gson.annotations.SerializedName

data class SpotifyPagingDto<T>(
    @SerializedName("href") val href: String? = null,
    @SerializedName("items") val items: List<T> = emptyList(),
    @SerializedName("limit") val limit: Int = 0,
    @SerializedName("next") val next: String? = null,
    @SerializedName("offset") val offset: Int = 0,
    @SerializedName("previous") val previous: String? = null,
    @SerializedName("total") val total: Int = 0
)

