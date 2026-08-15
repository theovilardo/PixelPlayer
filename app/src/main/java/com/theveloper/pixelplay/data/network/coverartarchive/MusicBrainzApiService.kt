package com.theveloper.pixelplay.data.network.coverartarchive

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the MusicBrainz web service.
 *
 * MusicBrainz is keyless but asks every client to identify itself and to stay
 * under roughly one request per second, which is why searches here are a single
 * query rather than a fan-out.
 */
interface MusicBrainzApiService {

    /**
     * Search releases with Lucene syntax, e.g. `release:"Discovery" AND artist:"Daft Punk"`.
     */
    @GET("ws/2/release")
    suspend fun searchReleases(
        @Query("query") query: String,
        @Query("limit") limit: Int = 8,
        @Query("fmt") format: String = "json"
    ): MusicBrainzReleaseSearchResponse
}

data class MusicBrainzReleaseSearchResponse(
    @SerializedName("releases") val releases: List<MusicBrainzRelease> = emptyList()
)

data class MusicBrainzRelease(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("artist-credit") val artistCredit: List<MusicBrainzArtistCredit> = emptyList()
)

data class MusicBrainzArtistCredit(
    @SerializedName("name") val name: String? = null
)
