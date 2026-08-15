package com.theveloper.pixelplay.data.media

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.memory.MemoryCache
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.utils.LocalArtworkUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Drops every cached form of the given artwork, including the extracted
     * artwork file, so the next load re-reads the cover from the audio file.
     *
     * Callers that just wrote new art *into the file* want this. Callers whose
     * new cover exists only as a file the app saved want
     * [invalidateRenderedCoverArt] instead, or they delete the very image they
     * just saved.
     */
    fun invalidateCoverArtCaches(vararg uriStrings: String?) =
        invalidate(uriStrings, dropExtractedArtwork = true)

    /**
     * Drops the rendered bitmaps while keeping the extracted artwork files, for
     * covers whose only copy is the cached file itself.
     */
    fun invalidateRenderedCoverArt(vararg uriStrings: String?) =
        invalidate(uriStrings, dropExtractedArtwork = false)

    @OptIn(ExperimentalCoilApi::class)
    private fun invalidate(uriStrings: Array<out String?>, dropExtractedArtwork: Boolean) {
        val imageLoader = context.imageLoader
        val memoryCache = imageLoader.memoryCache
        val diskCache = imageLoader.diskCache
        if (memoryCache == null && diskCache == null) return

        // Known Coil size request keys/transformations often append params.
        // This is a best-effort invalidation for common sizes.
        val knownSizeSuffixes = listOf(null, "128x128", "150x150", "168x168", "256x256", "300x300", "512x512", "600x600", "800x800")

        // Album and artist rows point at the query-less form of a song artwork
        // URI, while song rows carry a cache busting "?t=" token. Invalidating
        // only what was passed in leaves the album grid and album header showing
        // the previous cover, so the canonical form is always included.
        val expandedUris = uriStrings
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .flatMap { uri ->
                val canonical = LocalArtworkUri.parseSongId(uri)?.let(LocalArtworkUri::buildSongUri)
                listOfNotNull(uri, canonical)
            }
            .distinct()

        expandedUris.forEach { baseUri ->
            if (dropExtractedArtwork && LocalArtworkUri.isLocalArtworkUri(baseUri)) {
                LocalArtworkUri.parseSongId(baseUri)?.let { songId ->
                    AlbumArtUtils.clearCacheForSong(context, songId)
                }
            }

            knownSizeSuffixes.forEach { suffix ->
                val cacheKey = suffix?.let { "${baseUri}_${it}" } ?: baseUri
                memoryCache?.remove(MemoryCache.Key(cacheKey))
                diskCache?.remove(cacheKey)
            }
        }
    }
}
