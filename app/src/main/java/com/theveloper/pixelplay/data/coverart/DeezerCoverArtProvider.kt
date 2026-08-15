package com.theveloper.pixelplay.data.coverart

import com.theveloper.pixelplay.data.network.deezer.DeezerAlbum
import com.theveloper.pixelplay.data.network.deezer.DeezerApiService
import javax.inject.Inject

/**
 * Cover art candidates from Deezer's public album search.
 *
 * Uses the same unauthenticated catalog API that already backs artist images.
 */
class DeezerCoverArtProvider @Inject constructor(
    private val deezerApiService: DeezerApiService
) : CoverArtProvider {

    override val source: CoverArtSource = CoverArtSource.DEEZER

    override suspend fun search(request: CoverArtSearchRequest): List<CoverArtCandidate> {
        for (query in buildQueries(album = request.album, artist = request.artist)) {
            val candidates = deezerApiService
                .searchAlbum(query = query, limit = request.limit)
                .data
                .mapNotNull(::toCandidate)

            if (candidates.isNotEmpty()) return candidates
        }
        return emptyList()
    }

    private fun toCandidate(album: DeezerAlbum): CoverArtCandidate? {
        val fullSize = album.coverXl ?: album.coverBig ?: album.coverMedium ?: album.cover
        if (fullSize.isNullOrBlank()) return null

        val thumbnail = album.coverMedium ?: album.coverBig ?: fullSize
        // cover_xl is always served at 1000x1000; anything smaller means the XL
        // rendition was missing and the size is then unknown.
        val nominalSize = if (fullSize == album.coverXl) {
            CoverArtSize(width = XL_COVER_SIZE_PX, height = XL_COVER_SIZE_PX)
        } else {
            null
        }

        return CoverArtCandidate(
            id = "${CoverArtSource.DEEZER.name}:${album.id}",
            albumTitle = album.title,
            artistName = album.artist?.name.orEmpty(),
            thumbnailUrl = thumbnail,
            imageUrl = fullSize,
            source = CoverArtSource.DEEZER,
            size = nominalSize
        )
    }

    companion object {
        private const val XL_COVER_SIZE_PX = 1000

        /**
         * Builds the queries to try in order.
         *
         * Deezer's advanced syntax is precise but unforgiving — a single stray
         * edition suffix returns nothing — so a free-text query is kept as a
         * fallback for when the strict one comes back empty.
         */
        internal fun buildQueries(album: String, artist: String): List<String> {
            val cleanAlbum = album.trim()
            val cleanArtist = artist.trim()
            if (cleanAlbum.isEmpty() && cleanArtist.isEmpty()) return emptyList()

            val advanced = buildString {
                if (cleanArtist.isNotEmpty()) append("artist:\"${escape(cleanArtist)}\"")
                if (cleanAlbum.isNotEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append("album:\"${escape(cleanAlbum)}\"")
                }
            }
            val freeText = listOf(cleanArtist, cleanAlbum)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

            return listOf(advanced, freeText).distinct()
        }

        private fun escape(value: String): String = value.replace("\"", " ").trim()
    }
}
