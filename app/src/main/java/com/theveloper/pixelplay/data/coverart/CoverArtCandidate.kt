package com.theveloper.pixelplay.data.coverart

import java.security.MessageDigest

/**
 * Catalog a cover art candidate was found in.
 *
 * [label] is a proper noun shown verbatim in the picker, so it is intentionally
 * not a translatable resource.
 */
enum class CoverArtSource(val label: String) {
    DEEZER("Deezer"),
    ITUNES("iTunes"),
    COVER_ART_ARCHIVE("Cover Art Archive"),
    WEB_IMAGE_SEARCH("Web search");

    /**
     * Catalogs return structured releases that can be scored against the
     * album's tags. A web search returns pictures with a page title, which
     * cannot be scored the same way.
     */
    val isCatalog: Boolean get() = this != WEB_IMAGE_SEARCH

    /**
     * True for catalogs that answer a search in one request.
     *
     * The Cover Art Archive is reached through MusicBrainz -- a release query
     * plus a lookup per release, rate-limited to about one a second -- so it
     * costs seconds where Deezer and iTunes cost hundreds of milliseconds.
     */
    val isDirectLookup: Boolean get() = this == DEEZER || this == ITUNES
}

/**
 * Pixel dimensions of a candidate, plus its weight in bytes once known.
 *
 * Providers publish a nominal size for the image they hand out (Deezer states
 * one for its largest cover, iTunes resizes to whatever size is requested),
 * which is shown immediately. [measured] marks the sizes that were read back from the
 * image header instead of assumed.
 */
data class CoverArtSize(
    val width: Int,
    val height: Int,
    val byteCount: Long? = null,
    val measured: Boolean = false
)

/**
 * A single cover art result offered to the user.
 *
 * @property id Stable key for lazy lists, unique across sources.
 * @property artistName As reported by the source, and blank for a web result.
 * @property thumbnailUrl Small image used for the results grid.
 * @property imageUrl Largest available image, downloaded once the user picks it.
 * @property score Match confidence in `0f..1f`, assigned by [CoverArtQuery.rank].
 * @property size Nominal size from the provider, replaced by the measured size
 * once the image header has been probed. Null when the provider cannot say.
 */
data class CoverArtCandidate(
    val id: String,
    val albumTitle: String,
    val artistName: String,
    val thumbnailUrl: String,
    val imageUrl: String,
    val source: CoverArtSource,
    val score: Float = 0f,
    val size: CoverArtSize? = null
)

/**
 * What the user is looking for. Values are already trimmed by the repository.
 */
data class CoverArtSearchRequest(
    val album: String,
    val artist: String,
    val limit: Int
)

/**
 * A catalog that can be asked for cover art candidates.
 *
 * Implementations are expected to be stateless and to throw on transport
 * failures; the repository decides how to run them, how to retry and how to
 * merge what they return.
 */
interface CoverArtProvider {
    val source: CoverArtSource

    /**
     * False for a provider the user has not configured, so it is left out of a
     * search entirely rather than reported as a catalog that found nothing.
     */
    suspend fun isAvailable(): Boolean = true

    suspend fun search(request: CoverArtSearchRequest): List<CoverArtCandidate>
}

/**
 * How one catalog is doing within a search.
 *
 * @property resultCount Results it contributed, meaningful once it has answered.
 */
data class CoverArtProviderStatus(
    val source: CoverArtSource,
    val isSearching: Boolean,
    val resultCount: Int = 0,
    val failed: Boolean = false
)

/**
 * One snapshot of a streaming search: everything found so far, ranked.
 *
 * @property statuses Per catalog progress, in provider order, so the UI can show
 * which ones have answered while the rest are still running.
 * @property isComplete True once every catalog has answered.
 * @property failure Set only when the search finished without a single result
 * and at least one catalog failed, so the UI can tell "nothing matched" apart
 * from "nothing answered".
 */
data class CoverArtSearchUpdate(
    val candidates: List<CoverArtCandidate>,
    val statuses: List<CoverArtProviderStatus>,
    val isComplete: Boolean,
    val failure: Throwable? = null
)

/**
 * What a completed one-shot search turned up, ranked, and whether every catalog
 * it asked actually answered.
 *
 * A caller acting without a person watching has to tell "no cover exists" from
 * "the question never got asked".
 *
 * @property failure The first failure among the catalogs, set whenever any of
 * them failed -- including when others answered. What arrived is still worth
 * using; the failure says the absence of *more* means nothing.
 */
data class CoverArtSearchOutcome(
    val candidates: List<CoverArtCandidate>,
    val failure: Throwable? = null
)

/**
 * A stable id for a candidate a catalog gave no id of its own.
 *
 * Derived from the image URL with a real digest rather than [String.hashCode]:
 * ids key the results grid and the measured sizes folded into it, so two
 * candidates colliding would show one cover's resolution under another.
 */
internal fun candidateIdFor(imageUrl: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(imageUrl.toByteArray())
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte) }
