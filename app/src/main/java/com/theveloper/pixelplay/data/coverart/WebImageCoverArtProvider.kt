package com.theveloper.pixelplay.data.coverart

import com.theveloper.pixelplay.data.network.webimage.SerperImageSearchApi
import com.theveloper.pixelplay.data.network.webimage.SerperImageSearchRequest
import com.theveloper.pixelplay.data.network.webimage.WebImageSearchEngine
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Cover art candidates from a web image search.
 *
 * Music catalogs answer with structured releases, which is why they are the
 * default and why their results can be scored against the album's tags. A web
 * search has none of that structure -- it returns whatever pictures a page
 * carried -- so it is disabled unless the user configures it, it runs only when
 * asked for by hand, and the measured resolution shown on each tile is what
 * makes the results judgeable.
 *
 * It earns its place on releases no catalog carries at all: Bandcamp-only
 * records, private pressings, bootlegs.
 *
 * The engine requires an account, so the key is the user's own. None is
 * shipped, and nothing is queried until one is entered.
 */
class WebImageCoverArtProvider @Inject constructor(
    private val serperImageSearchApi: SerperImageSearchApi,
    private val userPreferencesRepository: UserPreferencesRepository
) : CoverArtProvider {

    override val source: CoverArtSource = CoverArtSource.WEB_IMAGE_SEARCH

    override suspend fun isAvailable(): Boolean = apiKey() != null

    override suspend fun search(request: CoverArtSearchRequest): List<CoverArtCandidate> {
        val apiKey = apiKey() ?: return emptyList()
        val query = buildQuery(album = request.album, artist = request.artist) ?: return emptyList()

        return serperImageSearchApi
            .searchImages(
                url = WebImageSearchEngine.IMAGES_URL,
                apiKey = apiKey,
                request = SerperImageSearchRequest(query = query, count = request.limit)
            )
            .images
            .mapNotNull { result ->
                val imageUrl = result.imageUrl?.takeIf { it.startsWith("https://") }
                    ?: return@mapNotNull null
                candidate(
                    imageUrl = imageUrl,
                    thumbnailUrl = result.thumbnailUrl ?: imageUrl,
                    title = result.title.orEmpty(),
                    width = result.imageWidth,
                    height = result.imageHeight
                )
            }
    }

    private suspend fun apiKey(): String? =
        userPreferencesRepository.webImageSearchApiKeyFlow.first().takeIf { it.isNotBlank() }

    private fun candidate(
        imageUrl: String,
        thumbnailUrl: String,
        title: String,
        width: Int?,
        height: Int?
    ) = CoverArtCandidate(
        id = "${CoverArtSource.WEB_IMAGE_SEARCH.name}:${candidateIdFor(imageUrl)}",
        // A web result has no artist field; its page title is all there is.
        albumTitle = title,
        artistName = "",
        thumbnailUrl = thumbnailUrl,
        imageUrl = imageUrl,
        source = CoverArtSource.WEB_IMAGE_SEARCH,
        size = if (width != null && height != null && width > 0 && height > 0) {
            CoverArtSize(width = width, height = height)
        } else {
            null
        }
    )

    companion object {
        /**
         * The artist and album, plus the words that bias an engine towards
         * artwork.
         *
         * Dropping the suffix sounds right -- an image search is already
         * looking at pictures -- and measures worse: without it the engine
         * ranks photographs of the artist and unrelated art above the cover.
         */
        internal fun buildQuery(album: String, artist: String): String? {
            val terms = listOf(artist.trim(), album.trim()).filter { it.isNotEmpty() }
            if (terms.isEmpty()) return null
            return (terms + "album cover").joinToString(" ")
        }
    }
}
