package com.theveloper.pixelplay.data.network.deezer

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for Deezer API.
 * Used primarily for fetching artist and album artwork.
 *
 * These are public catalog endpoints: they need no API key and no OAuth token,
 * which is why the Retrofit instance in `AppModule` carries no auth interceptor.
 */
interface DeezerApiService {

    /**
     * Search for an artist by name.
     * @param query Artist name to search for
     * @param limit Maximum number of results to return
     * @return Search response containing list of matching artists
     */
    @GET("search/artist")
    suspend fun searchArtist(
        @Query("q") query: String,
        @Query("limit") limit: Int = 1
    ): DeezerSearchResponse

    /**
     * Search for albums, used to offer cover art candidates for a song.
     *
     * The query accepts Deezer's advanced syntax (`artist:"..." album:"..."`)
     * as well as plain free text.
     */
    @GET("search/album")
    suspend fun searchAlbum(
        @Query("q") query: String,
        @Query("limit") limit: Int = 24
    ): DeezerAlbumSearchResponse
}
