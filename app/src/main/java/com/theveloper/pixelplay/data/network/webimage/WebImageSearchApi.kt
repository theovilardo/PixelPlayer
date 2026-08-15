package com.theveloper.pixelplay.data.network.webimage

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * The image search engine covers can be looked up in, when no catalog carries
 * the release.
 *
 * It runs on Google's index, which is what makes it worth the request: the
 * releases that reach this point are the ones living on a single label or
 * Bandcamp page, and engines crawling their own index tend not to have them.
 *
 * An account is required, so the key is the user's own and nothing is shipped
 * with the app.
 */
object WebImageSearchEngine {
    const val LABEL = "Serper"
    const val CONSOLE_URL = "https://serper.dev/api-key"
    const val IMAGES_URL = "https://google.serper.dev/images"
}

/**
 * Serper's image endpoint, which reports image dimensions directly, so those
 * results carry a size before anything is measured.
 */
interface SerperImageSearchApi {

    @POST
    suspend fun searchImages(
        @Url url: String,
        @Header("X-API-KEY") apiKey: String,
        @Body request: SerperImageSearchRequest
    ): SerperImageSearchResponse
}

data class SerperImageSearchRequest(
    @SerializedName("q") val query: String,
    @SerializedName("num") val count: Int = 20
)

data class SerperImageSearchResponse(
    @SerializedName("images") val images: List<SerperImageResult> = emptyList()
)

data class SerperImageResult(
    @SerializedName("title") val title: String? = null,
    @SerializedName("imageUrl") val imageUrl: String? = null,
    @SerializedName("imageWidth") val imageWidth: Int? = null,
    @SerializedName("imageHeight") val imageHeight: Int? = null,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("source") val source: String? = null
)
