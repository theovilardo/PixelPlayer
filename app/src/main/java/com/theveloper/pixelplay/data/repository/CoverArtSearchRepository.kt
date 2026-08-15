package com.theveloper.pixelplay.data.repository

import android.content.Context
import android.net.Uri
import com.theveloper.pixelplay.data.coverart.CoverArtCandidate
import com.theveloper.pixelplay.data.coverart.CoverArtImageHeader
import com.theveloper.pixelplay.data.coverart.CoverArtProvider
import com.theveloper.pixelplay.data.coverart.CoverArtProviderStatus
import com.theveloper.pixelplay.data.coverart.CoverArtQuery
import com.theveloper.pixelplay.data.coverart.CoverArtSearchOutcome
import com.theveloper.pixelplay.data.coverart.CoverArtSearchRequest
import com.theveloper.pixelplay.data.coverart.CoverArtSearchUpdate
import com.theveloper.pixelplay.data.coverart.CoverArtSize
import com.theveloper.pixelplay.di.CoverArtImageClient
import com.theveloper.pixelplay.utils.NetworkRetryUtils
import com.theveloper.pixelplay.utils.isRetryableNetworkError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks up cover art for an album across online catalogs and downloads the
 * picked image into the cache so the existing cropper can consume it.
 *
 * Catalog searches run from the album screen and from the automatic pass over
 * albums missing artwork. A web image search is metered against the user's own
 * key, so it has its own entry point and runs only when asked for by hand.
 */
@Singleton
class CoverArtSearchRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providers: List<@JvmSuppressWildcards CoverArtProvider>,
    @CoverArtImageClient private val okHttpClient: OkHttpClient
) {

    /**
     * Queries the catalogs concurrently and returns the merged, ranked
     * candidates along with whether any of them failed to answer.
     *
     * Run together, so the search costs the slowest catalog rather than the sum
     * of all of them. A failing provider is skipped rather than failing the
     * search, but is reported: an empty result and an incomplete one read the
     * same to a caller that only looks at the candidates.
     *
     * @param confidentMatchScore when set, the slow catalogs are consulted only
     * if the direct ones did not already answer this well. Null shows a person
     * everything.
     */
    suspend fun search(
        album: String,
        artist: String,
        confidentMatchScore: Float? = null
    ): CoverArtSearchOutcome =
        withContext(Dispatchers.IO) {
            val trimmedAlbum = album.trim()
            val trimmedArtist = artist.trim()
            if (trimmedAlbum.isEmpty() && trimmedArtist.isEmpty()) {
                return@withContext CoverArtSearchOutcome(emptyList(), null)
            }

            val request = CoverArtSearchRequest(
                album = trimmedAlbum,
                artist = trimmedArtist,
                limit = PROVIDER_RESULT_LIMIT
            )

            val catalogs = catalogProviders()
            val (direct, deferred) = when (confidentMatchScore) {
                null -> catalogs to emptyList()
                else -> catalogs.partition { it.source.isDirectLookup }
            }

            val first = query(direct, request)
            val firstRanked = rank(first.candidates, trimmedAlbum, trimmedArtist)
            val settled = confidentMatchScore != null &&
                firstRanked.firstOrNull()?.let { it.score >= confidentMatchScore } == true
            if (settled || deferred.isEmpty()) {
                return@withContext CoverArtSearchOutcome(firstRanked, first.failure)
            }

            val second = query(deferred, request)
            val collected = first.candidates + second.candidates

            CoverArtSearchOutcome(
                candidates = rank(collected, trimmedAlbum, trimmedArtist),
                failure = first.failure ?: second.failure
            )
        }

    /** What one wave of providers turned up, and the first failure among them. */
    private data class ProviderOutcomes(
        val candidates: List<CoverArtCandidate>,
        val failure: Throwable?
    )

    private suspend fun query(
        providers: List<CoverArtProvider>,
        request: CoverArtSearchRequest
    ): ProviderOutcomes {
        if (providers.isEmpty()) return ProviderOutcomes(emptyList(), null)

        val outcomes = coroutineScope {
            providers
                .map { provider -> async { provider.searchOrFailure(request) } }
                .awaitAll()
        }
        return ProviderOutcomes(
            candidates = outcomes.flatMap { it.getOrDefault(emptyList()) },
            failure = outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }
        )
    }

    /**
     * Same search, but emitting a merged snapshot every time a catalog answers.
     *
     * Latency between catalogs is wide -- iTunes usually answers in a few
     * hundred milliseconds while MusicBrainz needs a second hop to the Cover Art
     * Archive and can take seconds -- so waiting for all of them before drawing
     * anything wastes the fast answers. Each emission is the full ranked list so
     * far, which keeps the grid ordered by match quality rather than by arrival.
     */
    fun searchStreaming(album: String, artist: String): Flow<CoverArtSearchUpdate> = channelFlow {
        val trimmedAlbum = album.trim()
        val trimmedArtist = artist.trim()
        if (trimmedAlbum.isEmpty() && trimmedArtist.isEmpty()) {
            send(
                CoverArtSearchUpdate(
                    candidates = emptyList(),
                    statuses = emptyList(),
                    isComplete = true
                )
            )
            return@channelFlow
        }

        val request = CoverArtSearchRequest(
            album = trimmedAlbum,
            artist = trimmedArtist,
            limit = PROVIDER_RESULT_LIMIT
        )

        val available = catalogProviders()
        if (available.isEmpty()) {
            // Nothing to wait for. Without this the flow ends on a snapshot that
            // says "still searching" and the picker spins for good.
            send(
                CoverArtSearchUpdate(
                    candidates = emptyList(),
                    statuses = emptyList(),
                    isComplete = true
                )
            )
            return@channelFlow
        }

        val mutex = Mutex()
        val collected = mutableListOf<CoverArtCandidate>()
        // Indexed by provider so the reported failure does not depend on which
        // catalog happened to fail first.
        val failures = arrayOfNulls<Throwable>(available.size)
        val statuses = available.map { CoverArtProviderStatus(it.source, isSearching = true) }
            .toMutableList()
        var pending = available.size

        // Announce every catalog as pending before any of them answers, so the
        // user can see what is being queried from the first frame.
        send(
            CoverArtSearchUpdate(
                candidates = emptyList(),
                statuses = statuses.toList(),
                isComplete = false
            )
        )

        available.forEachIndexed { index, provider ->
            launch {
                val outcome = provider.searchOrFailure(request)
                mutex.withLock {
                    outcome
                        .onSuccess { found ->
                            collected += found
                            statuses[index] = statuses[index].copy(
                                isSearching = false,
                                resultCount = found.size
                            )
                        }
                        .onFailure { error ->
                            failures[index] = error
                            statuses[index] = statuses[index].copy(
                                isSearching = false,
                                failed = true
                            )
                        }
                    pending--

                    val isComplete = pending == 0
                    send(
                        CoverArtSearchUpdate(
                            candidates = rank(collected, trimmedAlbum, trimmedArtist),
                            statuses = statuses.toList(),
                            isComplete = isComplete,
                            failure = if (isComplete && collected.isEmpty()) {
                                failures.firstNotNullOfOrNull { it }
                            } else {
                                null
                            }
                        )
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun isWebImageSearchAvailable(): Boolean =
        providers.any { !it.source.isCatalog && it.isAvailable() }

    /**
     * Searches the user's configured image engine, and nothing else.
     *
     * Kept off every other path on purpose. The engines meter by request against
     * a monthly allowance the user pays for or caps, and a web result cannot be
     * scored against the album's tags, so spending a request on an album the
     * catalogs already matched buys nothing. It runs when the user asks for it,
     * on the album that needs it.
     */
    suspend fun searchWebImages(album: String, artist: String): Result<List<CoverArtCandidate>> =
        withContext(Dispatchers.IO) {
            val trimmedAlbum = album.trim()
            val trimmedArtist = artist.trim()
            if (trimmedAlbum.isEmpty() && trimmedArtist.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val engine = providers.firstOrNull { !it.source.isCatalog && it.isAvailable() }
                ?: return@withContext Result.success(emptyList())

            engine.searchOrFailure(
                CoverArtSearchRequest(
                    album = trimmedAlbum,
                    artist = trimmedArtist,
                    limit = WEB_RESULT_LIMIT
                )
            ).map { found -> found.distinctBy { it.imageUrl }.take(WEB_RESULT_LIMIT) }
        }

    private suspend fun catalogProviders(): List<CoverArtProvider> =
        providers.filter { it.source.isCatalog && it.isAvailable() }

    /**
     * Orders catalog results by how well they match the album's tags.
     *
     * Only catalogs reach here. Web search results carry no artist to score
     * against, so the scorer would discard them wholesale; they keep the
     * engine's own relevance order and the caller appends them after these.
     */
    private fun rank(
        candidates: List<CoverArtCandidate>,
        album: String,
        artist: String
    ): List<CoverArtCandidate> =
        CoverArtQuery.rank(
            candidates = candidates.distinctBy { it.imageUrl },
            queryAlbum = album,
            queryArtist = artist
        ).take(MAX_RESULTS)

    private suspend fun CoverArtProvider.searchOrFailure(
        request: CoverArtSearchRequest
    ): Result<List<CoverArtCandidate>> = try {
        Result.success(
            NetworkRetryUtils.withNetworkRetry(
                operationName = "cover_art_search:${source.name}",
                maxAttempts = NETWORK_RETRY_ATTEMPTS,
                initialDelayMs = NETWORK_RETRY_INITIAL_DELAY_MS,
                shouldRetry = { throwable -> throwable.isRetryableNetworkError() }
            ) {
                search(request)
            }
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Timber.tag(TAG).w(error, "Cover art provider ${source.name} failed")
        Result.failure(error)
    }

    /**
     * Reads the real dimensions and weight of a candidate without downloading it.
     *
     * Only the first [CoverArtImageHeader.PROBE_BYTES] are requested, via a
     * range request where the host honors one, and the total size comes from the
     * response headers. Returns null when the host refuses or the prefix cannot
     * be parsed, which leaves the provider's nominal size in place.
     */
    suspend fun probeSize(candidate: CoverArtCandidate): CoverArtSize? =
        withContext(Dispatchers.IO) {
            if (!candidate.imageUrl.startsWith("https://")) return@withContext null

            try {
                val request = Request.Builder()
                    .url(candidate.imageUrl)
                    .header("Range", "bytes=0-${CoverArtImageHeader.PROBE_BYTES - 1}")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null

                    // Not readBounded, which refuses a body past its limit: a
                    // host ignoring Range sends the whole file, and the header
                    // wanted here is in its first bytes.
                    val prefix = response.body.byteStream()
                        .readAtMost(CoverArtImageHeader.PROBE_BYTES)
                    val dimensions = CoverArtImageHeader.readDimensions(prefix)
                        ?: return@withContext null

                    CoverArtSize(
                        width = dimensions.first,
                        height = dimensions.second,
                        byteCount = response.totalByteCount(),
                        measured = true
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).d("Could not probe cover art ${candidate.imageUrl}: ${error.message}")
                null
            }
        }

    /**
     * Total size of the image, taken from `Content-Range` for a partial response
     * and from `Content-Length` when the host ignored the range request.
     */
    private fun Response.totalByteCount(): Long? {
        header("Content-Range")
            ?.substringAfter('/', "")
            ?.toLongOrNull()
            ?.let { return it }

        if (code == 200) {
            body.contentLength().takeIf { it >= 0 }?.let { return it }
        }
        return null
    }

    /**
     * Downloads a candidate into the cache directory and returns a `file://`
     * URI for it, ready to be handed to the cover art cropper.
     */
    suspend fun downloadCandidate(candidate: CoverArtCandidate): Result<Uri> =
        withContext(Dispatchers.IO) {
            if (!candidate.imageUrl.startsWith("https://")) {
                return@withContext Result.failure(
                    IOException("Refusing to download cover art over a non-HTTPS URL")
                )
            }

            try {
                val bytes = NetworkRetryUtils.withNetworkRetry(
                    operationName = "cover_art_download:${candidate.id}",
                    maxAttempts = NETWORK_RETRY_ATTEMPTS,
                    initialDelayMs = NETWORK_RETRY_INITIAL_DELAY_MS,
                    // A rejected payload is rejected for good; retrying spends
                    // up to three downloads of up to 8 MB on a verdict that
                    // cannot change.
                    shouldRetry = { throwable ->
                        throwable !is UnusableCoverArtException && throwable.isRetryableNetworkError()
                    }
                ) {
                    fetchImageBytes(candidate.imageUrl)
                }

                val directory = cacheDirectory()
                // Named after the image URL rather than the candidate id, so
                // the same image picked twice reuses one file and two results
                // that differ only by image never share one.
                val file = File(directory, "${cacheFileName(candidate.imageUrl)}.img")
                file.writeBytes(bytes)
                prune(directory)

                Result.success(Uri.fromFile(file))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Failed to download cover art ${candidate.imageUrl}")
                Result.failure(error)
            }
        }

    /**
     * A response that will never become usable however many times it is asked
     * for: too large, not an image, or refused with a status that says so.
     * Kept apart from transport failures so the retry above does not spend
     * three downloads reaching the same verdict.
     */
    private class UnusableCoverArtException(message: String) : IOException(message)

    private fun fetchImageBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = "Cover art download failed with HTTP ${response.code}"
                // A plain IOException is retried three times, which for a dead
                // rendition URL spends a second and a half re-reading the same
                // 404 -- and one of the unattended pass's five allowed failures.
                // UnusableCoverArtException marks the codes worth asking once.
                throw if (response.code in RETRYABLE_STATUS_CODES || response.code >= 500) {
                    IOException(message)
                } else {
                    UnusableCoverArtException(message)
                }
            }

            val body = response.body
            val contentType = body.contentType()
            if (contentType != null && contentType.type != "image") {
                throw UnusableCoverArtException("Cover art download returned $contentType")
            }
            if (body.contentLength() > MAX_IMAGE_BYTES) {
                throw UnusableCoverArtException(
                    "Cover art is larger than the ${MAX_IMAGE_BYTES} byte limit"
                )
            }

            val bytes = body.byteStream().readBounded(MAX_IMAGE_BYTES)
                ?: throw UnusableCoverArtException(
                    "Cover art is larger than the ${MAX_IMAGE_BYTES} byte limit"
                )
            if (!bytes.looksLikeImage()) {
                throw UnusableCoverArtException("Cover art download did not return image data")
            }
            return bytes
        }
    }

    private fun cacheDirectory(): File =
        File(context.cacheDir, CACHE_DIRECTORY_NAME).apply { mkdirs() }

    /**
     * Keeps the cache bounded. These files only exist between picking a result
     * and confirming the crop, but each one may be up to [MAX_IMAGE_BYTES], so
     * the ceiling is a count rather than a size and the worst case is that many
     * times that limit.
     */
    private fun prune(directory: File) {
        val files = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED_FILES).forEach { stale ->
            if (!stale.delete()) {
                Timber.tag(TAG).d("Could not delete stale cover art cache file ${stale.name}")
            }
        }
    }

    /** Reads up to [limit] bytes, returning whatever arrived before that. */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(DOWNLOAD_CHUNK_BYTES)
        while (output.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - output.size()))
            if (read == -1) break
            output.write(chunk, 0, read)
        }
        return output.toByteArray()
    }

    private fun InputStream.readBounded(limit: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(DOWNLOAD_CHUNK_BYTES)
        var total = 0L

        while (true) {
            val read = read(chunk)
            if (read == -1) break
            total += read
            if (total > limit) return null
            output.write(chunk, 0, read)
        }
        return output.toByteArray()
    }

    /**
     * Cheap magic-byte check so an error page served with an image content type
     * never lands in the cache as a cover.
     */
    private fun ByteArray.looksLikeImage(): Boolean {
        if (size < 12) return false
        val isJpeg = this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()
        val isPng = this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() &&
            this[2] == 'N'.code.toByte() && this[3] == 'G'.code.toByte()
        val isGif = this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte()
        val isWebp = this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
            this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
            this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
        return isJpeg || isPng || isGif || isWebp
    }

    companion object {
        private const val TAG = "CoverArtSearchRepository"
        private const val CACHE_DIRECTORY_NAME = "cover_art_search"
        private const val PROVIDER_RESULT_LIMIT = 24
        private const val MAX_RESULTS = 24

        /**
         * Web results kept, well past the catalogs' cap.
         *
         * One request is billed whether it answers with twenty covers or
         * eighty, and the right one for an obscure release sits far down the
         * page -- truncating to a catalog's worth throws away what was already
         * paid for.
         */
        private const val WEB_RESULT_LIMIT = 60
        private const val MAX_CACHED_FILES = 20
        private const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
        private const val DOWNLOAD_CHUNK_BYTES = 16 * 1024
        private const val NETWORK_RETRY_ATTEMPTS = 3
        private const val NETWORK_RETRY_INITIAL_DELAY_MS = 500L

        /**
         * The 4xx answers that can come back differently next time: the request
         * took too long to arrive (408), the server declined to risk replaying
         * it (425), or it is asking for a slower pace (429). Every other 4xx is
         * a verdict on the request itself and will not change however often it
         * is asked. 5xx is handled alongside these, by range.
         */
        private val RETRYABLE_STATUS_CODES = setOf(408, 425, 429)

        /**
         * Content-addressed name so one cached file always means one image.
         *
         * A 32-bit String.hashCode is trivially collidable, and a collision
         * here hands the cropper somebody else's cover.
         */
        internal fun cacheFileName(imageUrl: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(imageUrl.toByteArray())
                .take(16)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
