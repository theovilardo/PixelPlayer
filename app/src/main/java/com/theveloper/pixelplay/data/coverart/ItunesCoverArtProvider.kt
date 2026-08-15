package com.theveloper.pixelplay.data.coverart

import com.theveloper.pixelplay.data.network.itunes.ItunesAlbum
import com.theveloper.pixelplay.data.network.itunes.ItunesApiService
import javax.inject.Inject

/**
 * Cover art candidates from the iTunes Search API.
 *
 * iTunes only ever reports a 100x100 artwork URL, but the size is encoded in
 * the path, so a larger rendition is requested by rewriting it. Apple resizes
 * from the master, which is why the size handed back is nominal until the
 * picker measures the real image.
 */
class ItunesCoverArtProvider @Inject constructor(
    private val itunesApiService: ItunesApiService
) : CoverArtProvider {

    override val source: CoverArtSource = CoverArtSource.ITUNES

    override suspend fun search(request: CoverArtSearchRequest): List<CoverArtCandidate> {
        val term = buildTerm(album = request.album, artist = request.artist)
            ?: return emptyList()

        return itunesApiService
            .searchAlbums(term = term, limit = request.limit)
            .results
            .mapNotNull(::toCandidate)
    }

    private fun toCandidate(album: ItunesAlbum): CoverArtCandidate? {
        val artwork = album.artworkUrl100?.takeIf { it.isNotBlank() } ?: return null
        val title = album.collectionName?.takeIf { it.isNotBlank() } ?: return null

        // collectionId is occasionally absent, and two results sharing an id
        // would collide as lazy list keys, so the artwork URL backs the id.
        val id = album.collectionId.takeIf { it != 0L }?.toString()
            ?: candidateIdFor(artwork)

        // Only a URL carrying a size segment can be asked for a larger
        // rendition; the rest serve the 100x100 original. Claiming the requested
        // size regardless put a resolution on the tile the image does not have.
        val isResizable = ARTWORK_SIZE.containsMatchIn(artwork)

        return CoverArtCandidate(
            id = "${CoverArtSource.ITUNES.name}:$id",
            albumTitle = title,
            artistName = album.artistName.orEmpty(),
            thumbnailUrl = resizeArtwork(artwork, THUMBNAIL_SIZE_PX),
            imageUrl = resizeArtwork(artwork, FULL_SIZE_PX),
            source = CoverArtSource.ITUNES,
            size = if (isResizable) {
                CoverArtSize(width = FULL_SIZE_PX, height = FULL_SIZE_PX)
            } else {
                null
            }
        )
    }

    companion object {
        private const val THUMBNAIL_SIZE_PX = 300
        private const val FULL_SIZE_PX = 1200
        private val ARTWORK_SIZE = Regex("/\\d+x\\d+(bb)?\\.(jpg|png)$")

        /**
         * iTunes has no fielded query syntax, so artist and album are simply
         * concatenated the way a person would type them into the store search.
         */
        internal fun buildTerm(album: String, artist: String): String? {
            val term = listOf(artist.trim(), album.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return term.ifEmpty { null }
        }

        /**
         * Rewrites the size segment of an artwork URL, e.g.
         * `.../source/100x100bb.jpg` to `.../source/1200x1200bb.jpg`.
         * URLs that do not carry a size segment are left untouched.
         */
        internal fun resizeArtwork(url: String, sizePx: Int): String {
            val match = ARTWORK_SIZE.find(url) ?: return url
            val suffix = match.groupValues[1]
            val extension = match.groupValues[2]
            return url.replaceRange(match.range, "/${sizePx}x${sizePx}$suffix.$extension")
        }
    }
}
