package com.theveloper.pixelplay.data.network.coverartarchive

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Retrofit interface for the Cover Art Archive.
 *
 * Keyless, and returns 404 for releases that have no artwork at all, so callers
 * must treat a failed lookup as "no cover" rather than as an outage.
 */
interface CoverArtArchiveApiService {

    @GET("release/{mbid}")
    suspend fun getReleaseCoverArt(@Path("mbid") releaseMbid: String): CoverArtArchiveResponse
}

data class CoverArtArchiveResponse(
    @SerializedName("images") val images: List<CoverArtArchiveImage> = emptyList()
)

data class CoverArtArchiveImage(
    @SerializedName("front") val isFront: Boolean = false,
    @SerializedName("image") val image: String? = null,
    @SerializedName("thumbnails") val thumbnails: CoverArtArchiveThumbnails? = null
)

/**
 * Thumbnail URLs for one image.
 *
 * The Archive answers with two key styles depending on when the item was
 * indexed: numeric keys (`250`, `500`, `1200`) on newer entries and named keys
 * (`small`, `large`) on older ones, where `large` is the 500px rendition. Older
 * entries carry only the named pair, so both have to be read.
 */
data class CoverArtArchiveThumbnails(
    @SerializedName("250") val size250: String? = null,
    @SerializedName("500") val size500: String? = null,
    @SerializedName("1200") val size1200: String? = null,
    @SerializedName("small") val small: String? = null,
    @SerializedName("large") val large: String? = null
)
