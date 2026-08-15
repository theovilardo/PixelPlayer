package com.theveloper.pixelplay.data.network.deezer

import com.google.gson.annotations.SerializedName

/**
 * Response from Deezer artist search API.
 */
data class DeezerSearchResponse(
    @SerializedName("data") val data: List<DeezerArtist> = emptyList(),
    @SerializedName("total") val total: Int = 0
)

/**
 * Response from Deezer album search API.
 */
data class DeezerAlbumSearchResponse(
    @SerializedName("data") val data: List<DeezerAlbum> = emptyList(),
    @SerializedName("total") val total: Int = 0
)

/**
 * Album data from Deezer API.
 * Cover URLs follow the same size ladder as artist pictures, with `cover_xl`
 * served at 1000x1000.
 */
data class DeezerAlbum(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("cover_small") val coverSmall: String? = null,
    @SerializedName("cover_medium") val coverMedium: String? = null,
    @SerializedName("cover_big") val coverBig: String? = null,
    @SerializedName("cover_xl") val coverXl: String? = null,
    @SerializedName("nb_tracks") val trackCount: Int = 0,
    @SerializedName("artist") val artist: DeezerAlbumArtist? = null
)

/**
 * Minimal artist payload nested in album search results.
 */
data class DeezerAlbumArtist(
    @SerializedName("id") val id: Long = 0L,
    @SerializedName("name") val name: String = ""
)

/**
 * Artist data from Deezer API.
 * Contains multiple image sizes for different use cases.
 */
data class DeezerArtist(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picture") val picture: String? = null,
    @SerializedName("picture_small") val pictureSmall: String? = null,
    @SerializedName("picture_medium") val pictureMedium: String? = null,
    @SerializedName("picture_big") val pictureBig: String? = null,
    @SerializedName("picture_xl") val pictureXl: String? = null,
    @SerializedName("nb_album") val albumCount: Int = 0,
    @SerializedName("nb_fan") val fanCount: Int = 0
)
