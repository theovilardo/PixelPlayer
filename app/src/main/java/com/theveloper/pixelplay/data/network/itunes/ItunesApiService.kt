package com.theveloper.pixelplay.data.network.itunes

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the iTunes Search API.
 *
 * Public and unauthenticated like the Deezer catalog endpoints, rate limited at
 * roughly 20 requests per minute.
 */
interface ItunesApiService {

    /**
     * Search the music catalog for albums.
     *
     * @param term Free text query, typically "artist album".
     */
    @GET("search")
    suspend fun searchAlbums(
        @Query("term") term: String,
        @Query("limit") limit: Int = 24,
        @Query("entity") entity: String = "album",
        @Query("media") media: String = "music"
    ): ItunesSearchResponse
}

data class ItunesSearchResponse(
    @SerializedName("resultCount") val resultCount: Int = 0,
    @SerializedName("results") val results: List<ItunesAlbum> = emptyList()
)

data class ItunesAlbum(
    @SerializedName("collectionId") val collectionId: Long = 0L,
    @SerializedName("collectionName") val collectionName: String? = null,
    @SerializedName("artistName") val artistName: String? = null,
    /** Always a 100x100 URL; the size is part of the path and can be raised. */
    @SerializedName("artworkUrl100") val artworkUrl100: String? = null,
    @SerializedName("trackCount") val trackCount: Int = 0
)
