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
    @OptIn(ExperimentalCoilApi::class)
    fun invalidateCoverArtCaches(vararg uriStrings: String?) {
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
            if (LocalArtworkUri.isLocalArtworkUri(baseUri)) {
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
