package com.theveloper.pixelplay.data.coverart

import com.theveloper.pixelplay.data.network.coverartarchive.CoverArtArchiveApiService
import com.theveloper.pixelplay.data.network.coverartarchive.MusicBrainzApiService
import com.theveloper.pixelplay.data.network.coverartarchive.MusicBrainzRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import javax.inject.Inject

/**
 * Cover art candidates from MusicBrainz releases backed by the Cover Art Archive.
 *
 * Two hops are unavoidable: MusicBrainz knows which releases match the query,
 * the Archive knows which of them actually have artwork. The release lookups run
 * concurrently against the Archive, which is a plain file host, while the single
 * MusicBrainz query respects its one-request-per-second etiquette.
 *
 * Releases with no artwork answer 404, which is a normal outcome here and is
 * dropped rather than surfaced as a failure.
 */
class MusicBrainzCoverArtProvider @Inject constructor(
    private val musicBrainzApiService: MusicBrainzApiService,
    private val coverArtArchiveApiService: CoverArtArchiveApiService
) : CoverArtProvider {

    override val source: CoverArtSource = CoverArtSource.COVER_ART_ARCHIVE

    override suspend fun search(request: CoverArtSearchRequest): List<CoverArtCandidate> {
        val query = buildQuery(album = request.album, artist = request.artist)
            ?: return emptyList()

        val releases = musicBrainzApiService
            .searchReleases(query = query, limit = RELEASE_LOOKUP_LIMIT)
            .releases
            .take(RELEASE_LOOKUP_LIMIT)

        if (releases.isEmpty()) return emptyList()

        val permits = Semaphore(ARCHIVE_CONCURRENCY)
        return coroutineScope {
            releases
                .map { release -> async { permits.withPermit { toCandidate(release) } } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private suspend fun toCandidate(release: MusicBrainzRelease): CoverArtCandidate? {
        val response = try {
            coverArtArchiveApiService.getReleaseCoverArt(release.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Usually a 404, which simply means this release has no artwork --
            // but a timeout or a 5xx lands here too and is not the same thing.
            // The error is logged rather than swallowed so an outage does not
            // read as a library-wide absence of covers.
            Timber.tag(TAG).d(error, "No Cover Art Archive entry for release ${release.id}")
            return null
        }

        val front = response.images.firstOrNull { it.isFront } ?: response.images.firstOrNull()
        val thumbnails = front?.thumbnails
        // Prefer the 1200px rendition, falling back to the original upload.
        val fullSize = thumbnails?.size1200 ?: front?.image ?: return null
        // Anything but the original for the grid, so a multi-megabyte scan is
        // never loaded just to draw a tile.
        val thumbnail = thumbnails?.size250
            ?: thumbnails?.small
            ?: thumbnails?.size500
            ?: thumbnails?.large
            ?: fullSize

        return CoverArtCandidate(
            id = "${CoverArtSource.COVER_ART_ARCHIVE.name}:${release.id}",
            albumTitle = release.title.orEmpty(),
            artistName = release.artistCredit.firstOrNull()?.name.orEmpty(),
            thumbnailUrl = secure(thumbnail),
            imageUrl = secure(fullSize),
            source = CoverArtSource.COVER_ART_ARCHIVE
            // Size is deliberately left unknown: the Archive stores whatever the
            // contributor uploaded, so it is only known once measured.
        )
    }

    companion object {
        private const val TAG = "MusicBrainzCoverArt"
        private const val RELEASE_LOOKUP_LIMIT = 8
        private const val ARCHIVE_CONCURRENCY = 4

        /**
         * The Archive embeds `http://` URLs in its JSON even though every one of
         * them serves fine over TLS. Left as-is they are refused by the
         * downloader, so the scheme is upgraded here.
         */
        internal fun secure(url: String): String =
            if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

        /**
         * Builds a Lucene query for the MusicBrainz release index. Quotes are
         * stripped rather than escaped because a stray quote breaks the parse
         * and returns a 400 for the whole search.
         */
        internal fun buildQuery(album: String, artist: String): String? {
            val cleanAlbum = sanitize(album)
            val cleanArtist = sanitize(artist)

            return when {
                cleanAlbum.isNotEmpty() && cleanArtist.isNotEmpty() ->
                    "release:\"$cleanAlbum\" AND artist:\"$cleanArtist\""

                cleanAlbum.isNotEmpty() -> "release:\"$cleanAlbum\""
                cleanArtist.isNotEmpty() -> "artist:\"$cleanArtist\""
                else -> null
            }
        }

        private fun sanitize(value: String): String =
            value.replace(LUCENE_SPECIALS, " ").trim().replace(WHITESPACE, " ")

        private val LUCENE_SPECIALS = Regex("[\"\\\\+\\-!(){}\\[\\]^~*?:/]")
        private val WHITESPACE = Regex("\\s+")
    }
}
