package com.theveloper.pixelplay.utils

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics
import com.theveloper.pixelplay.data.media.AudioMetadataReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object AlbumArtUtils {
    private const val CACHE_VERSION_SUFFIX = "_v4"

    // Cached covers are display-only; the source audio file keeps its original art untouched.
    // Embedded covers can be multi-megabyte full-resolution scans, and re-decoding such a blob
    // on every cold artwork load is the dominant avoidable artwork cost (perf report:
    // artwork_decode up to ~270 ms, max embedded ~6 MB). Bounding the cached copy to 1536 px
    // JPEG is invisible on phone-class displays (the full player tops out at 2048 px) while
    // cutting heavy covers to a few hundred KB — far cheaper to decode and lighter on RAM/IPC.
    private const val MAX_CACHED_ART_DIMENSION_PX = 1536
    private const val CACHED_ART_JPEG_QUALITY = 90
    // Cache entries larger than this are treated as legacy/oversized and shrunk in the
    // background on first access (one-time migration for art cached before bounding existed).
    private const val OVERSIZED_CACHED_ART_BYTES = 900L * 1024

    // WebP at 80 runs roughly a third below JPEG at 90 for the same visible
    // quality on cover art, and the encode is paid once per album rather than
    // per track. The store is new in this release, so nothing needs converting.
    private const val APPLIED_ART_WEBP_QUALITY = 80
    private const val APPLIED_COVER_PREFIX = "cover_"
    private const val APPLIED_POINTER_EXTENSION = ".ref"
    private const val APPLIED_STAGING_EXTENSION = ".tmp"

    // Versioned apart from the extracted cache: bumping that suffix discards a
    // cache the audio files can rebuild, which is why it has been bumped three
    // times. The same bump here would delete the only copy of every cover.
    private const val APPLIED_ART_VERSION_SUFFIX = "_v1"

    // One writer at a time: a cover is written before the pointers that keep it
    // alive, and a sweep landing in that window would delete it.
    private val appliedArtLock = Any()

    // P2-1: Dedicated app-level scope to replace GlobalScope.
    // SupervisorJob ensures child failures don't cancel sibling coroutines.
    // Appropriate for fire-and-forget tasks like cache cleanup that outlive any single component.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Tracks cache files currently being shrunk so rapid repeated loads of the same oversized
    // cover don't read the large blob into memory more than once concurrently.
    private val artworkShrinkInFlight = ConcurrentHashMap.newKeySet<String>()
    private val commonArtworkFileNames = listOf(
        "cover.jpg", "cover.png", "cover.jpeg",
        "folder.jpg", "folder.png", "folder.jpeg",
        "album.jpg", "album.png", "album.jpeg",
        "albumart.jpg", "albumart.png", "albumart.jpeg",
        "artwork.jpg", "artwork.png", "artwork.jpeg",
        "front.jpg", "front.png", "front.jpeg",
        ".folder.jpg", ".albumart.jpg",
        "thumb.jpg", "thumbnail.jpg",
        "scan.jpg", "scanned.jpg"
    )
    private val genericMixedDirectoryNames = setOf(
        "download",
        "downloads",
        "music",
        "songs",
        "audio",
        "telegram audio",
        "studio",
        "gallery",
        "pictures",
        "photos",
        "images",
        "dcim",
        "camera",
        "screenshots"
    )

    /**
     * Main function to get album art for local songs.
     *
     * Local artwork is intentionally embedded-only. Falling back to folder images such as
     * cover.jpg/thumb.jpg can pick unrelated Gallery files when music is stored in mixed
     * directories, and can duplicate the same image across unrelated tracks.
     */
    fun getAlbumArtUri(
        appContext: Context,
        path: String,
        songId: Long,
        forceRefresh: Boolean
    ): String? {
        return if (hasLocalAlbumArt(appContext, path, songId, forceRefresh)) {
            LocalArtworkUri.buildSongUri(songId)
        } else {
            null
        }
    }

    /**
     * Lightweight scan-time artwork resolution.
     *
     * Full embedded-art extraction can allocate large ByteArrays per song. During a library scan
     * we only store the stable lazy URI; the normal image-loading path extracts/caches artwork
     * later for visible rows.
     */
    fun getAlbumArtUriForLibraryScan(
        appContext: Context,
        songId: Long,
        forceRefresh: Boolean
    ): String? {
        val cachedFile = getCachedAlbumArtFile(appContext, songId)
        val noArtFile = noArtMarkerFile(appContext, songId)

        if (forceRefresh) {
            cachedFile.delete()
            noArtFile.delete()
        }

        val hasCachedArtwork = getAppliedAlbumArtFile(appContext, songId) != null ||
            (cachedFile.exists() && cachedFile.length() > 0)
        if (hasCachedArtwork) {
            cachedFile.setLastModified(System.currentTimeMillis())
        }

        return resolveAlbumArtUriForLibraryScan(
            songId = songId,
            hasCachedArtwork = hasCachedArtwork,
            hasNoArtworkMarker = noArtFile.exists(),
            forceRefresh = forceRefresh
        )
    }

    fun getCachedAlbumArtUri(
        appContext: Context,
        songId: Long
    ): Uri? {
        val cachedFile = getCachedAlbumArtFile(appContext, songId)
        if (!cachedFile.exists()) return null

        cachedFile.setLastModified(System.currentTimeMillis())
        return shareableCacheUri(appContext, cachedFile)
    }

    fun hasCachedAlbumArt(
        appContext: Context,
        songId: Long
    ): Boolean {
        return getCachedAlbumArtFile(appContext, songId).exists()
    }

    /**
     * Enhanced album art detection without eagerly persisting the whole library to cache.
     */
    fun getEmbeddedAlbumArtUri(
        appContext: Context,
        filePath: String,
        songId: Long,
        deepScan: Boolean
    ): Uri? {
        ensureAlbumArtCachedFile(appContext, songId, filePath, deepScan)?.let { cachedFile ->
            return shareableCacheUri(appContext, cachedFile)
        }
        return null
    }

    fun ensureAlbumArtCachedFile(
        appContext: Context,
        songId: Long,
        filePath: String? = null,
        forceRefresh: Boolean = false
    ): File? {
        // Applied art answers first and is never evicted, so a cover the user
        // chose survives a cache sweep, a rescan and a re-extract.
        getAppliedAlbumArtFile(appContext, songId)?.let { return it }

        val cachedFile = getCachedAlbumArtFile(appContext, songId)
        val noArtFile = noArtMarkerFile(appContext, songId)

        if (!forceRefresh) {
            if (cachedFile.exists() && cachedFile.length() > 0) {
                cachedFile.setLastModified(System.currentTimeMillis())
                PerformanceMetrics.increment(PerformanceMetrics.Counters.ARTWORK_CACHE_HIT)
                scheduleOversizedArtworkShrink(cachedFile)
                return cachedFile
            }
            if (noArtFile.exists()) {
                return null
            }
            PerformanceMetrics.increment(PerformanceMetrics.Counters.ARTWORK_CACHE_MISS)
        } else {
            cachedFile.delete()
            noArtFile.delete()
        }

        val resolvedPath = filePath ?: resolveSongMediaStoreInfo(appContext, songId)?.path ?: return null
        if (!File(resolvedPath).exists()) {
            return null
        }

        extractEmbeddedAlbumArtBytes(resolvedPath)?.let { bytes ->
            cacheAlbumArtBytes(appContext, bytes, songId)
            return cachedFile.takeIf { it.exists() && it.length() > 0 }
        }

        cachedFile.delete()
        noArtFile.createNewFile()
        return null
    }

    fun openArtworkInputStream(
        appContext: Context,
        uri: Uri
    ): InputStream? {
        val uriString = uri.toString()
        return when {
            LocalArtworkUri.isLocalArtworkUri(uriString) -> {
                val songId = LocalArtworkUri.parseSongId(uriString) ?: return null
                val resolvedPath = resolveSongMediaStoreInfo(appContext, songId)?.path
                ensureAlbumArtCachedFile(
                    appContext = appContext,
                    songId = songId,
                    filePath = resolvedPath
                )?.inputStream()
            }
            uri.scheme.isNullOrBlank() && uri.toString().startsWith("/") -> File(uri.toString()).inputStream()
            else -> appContext.contentResolver.openInputStream(uri)
        }
    }

    private fun hasLocalAlbumArt(
        appContext: Context,
        filePath: String,
        songId: Long,
        deepScan: Boolean
    ): Boolean {
        val audioFile = File(filePath)
        if (!audioFile.exists() || !audioFile.canRead()) {
            return false
        }

        if (getAppliedAlbumArtFile(appContext, songId) != null) {
            return true
        }

        val cachedFile = getCachedAlbumArtFile(appContext, songId)
        val noArtFile = noArtMarkerFile(appContext, songId)

        if (!deepScan) {
            if (noArtFile.exists()) {
                if (cachedFile.exists()) {
                    cachedFile.delete()
                }
                return false
            }

            if (cachedFile.exists() && cachedFile.length() > 0) {
                return true
            }
        } else {
            noArtFile.delete()
        }

        val hasEmbeddedArt = extractEmbeddedAlbumArtBytes(filePath)?.isNotEmpty() == true
        if (hasEmbeddedArt) {
            noArtFile.delete()
            return true
        }

        cachedFile.delete()
        noArtFile.createNewFile()
        return false
    }

    /**
     * Look for external album art files in the same directory.
     *
     * This is kept for explicit, controlled callers only. The default local-song artwork path
     * must remain embedded-only so the app does not pull unrelated personal Gallery files.
     */
    fun getExternalAlbumArtUri(filePath: String): Uri? {
        return runCatching {
            findExternalAlbumArtFile(filePath)?.let(Uri::fromFile)
        }.getOrNull()
    }

    internal fun findExternalAlbumArtFile(filePath: String): File? {
        val audioFile = File(filePath)
        val directory = audioFile.parentFile ?: return null
        if (!directory.exists() || !directory.isDirectory) return null
        if (!shouldTrustDirectoryArtwork(directory.name)) return null

        return commonArtworkFileNames
            .asSequence()
            .map { name -> File(directory, name) }
            .firstOrNull { artFile ->
                artFile.exists() && artFile.isFile && artFile.length() > 1024
            }
    }

    internal fun shouldTrustDirectoryArtwork(directoryName: String): Boolean {
        val normalized = directoryName.trim().lowercase()
        if (normalized.isBlank()) return false
        return normalized !in genericMixedDirectoryNames
    }

    /**
     * MediaStore's album-art cache can alias unrelated local songs when album metadata is weak or
     * collapsed into "Unknown Album". Keep this helper available for controlled callers, but do
     * not use it as an automatic per-song fallback.
     */
    fun getMediaStoreAlbumArtUri(appContext: Context, albumId: Long): Uri? {
        if (albumId <= 0) return null

        val potentialUri = ContentUris.withAppendedId(
            "content://media/external/audio/albumart".toUri(),
            albumId
        )

        return try {
            appContext.contentResolver.openFileDescriptor(potentialUri, "r")?.use {
                potentialUri // only return if open succeeded
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save embedded art to cache with unique naming
     */
    fun saveAlbumArtToCache(appContext: Context, bytes: ByteArray, songId: Long): Uri {
        val file = cacheAlbumArtBytes(appContext, bytes, songId)
        return shareableCacheUri(appContext, file)
    }

    /**
     * Delete both the cached artwork and the "no art" marker for a specific song.
     */
    fun clearCacheForSong(appContext: Context, songId: Long) {
        listOf(
            getCachedAlbumArtFile(appContext, songId),
            noArtMarkerFile(appContext, songId),
            legacyCachedAlbumArtFile(appContext, songId, "_v3"),
            legacyNoArtMarkerFile(appContext, songId, "_v3"),
            legacyCachedAlbumArtFile(appContext, songId, "_v2"),
            legacyNoArtMarkerFile(appContext, songId, "_v2"),
            legacyCachedAlbumArtFile(appContext, songId),
            legacyNoArtMarkerFile(appContext, songId)
        ).forEach { it.delete() }
    }

    /**
     * Drops the cover the user applied to [songId], and the image behind it if
     * no other song was pointing there.
     *
     * Deliberately not part of [clearCacheForSong], which every metadata save
     * reaches and which only deletes what the audio file can rebuild. An
     * applied cover is the only copy, so it goes only when meant to.
     */
    fun clearAppliedArtForSong(appContext: Context, songId: Long) {
        val removed = synchronized(appliedArtLock) {
            appliedPointerFile(appContext, songId).delete()
        }
        // Reading every pointer in the store is too much to do on the caller's
        // thread, and nothing waits on the image being gone.
        if (removed) {
            appScope.launch { deleteUnreferencedAppliedCovers(appContext) }
        }
    }

    // Album art lives in filesDir (persistent) instead of cacheDir, because Android can
    // wipe cacheDir at any time under storage pressure — taking every cached cover with
    // it and leaving the UI blank. The size is bounded by AlbumArtCacheManager's LRU.
    private const val ALBUM_ART_DIR_NAME = "album_art"

    // Apart from the extracted cache, which is safe to evict only because the
    // audio files can rebuild it. An applied cover has no other copy, so this
    // directory is never LRU-swept -- only swept for orphans.
    private const val APPLIED_ART_DIR_NAME = "album_art_applied"

    fun getAlbumArtDir(appContext: Context): File {
        val dir = File(appContext.filesDir, ALBUM_ART_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCachedAlbumArtFile(appContext: Context, songId: Long): File {
        return File(getAlbumArtDir(appContext), "song_art_${songId}${CACHE_VERSION_SUFFIX}.jpg")
    }

    fun getAppliedArtDir(appContext: Context): File {
        val dir = File(appContext.filesDir, APPLIED_ART_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * The cover [songId] was given, or null when it has none.
     *
     * Artwork is addressed by song id everywhere -- the library scan, the shared
     * content provider, the widget and the Coil fetcher all resolve it that way
     * -- so every track needs its own answer. The answer is a pointer rather
     * than a copy: an album's tracks all name the same file, which is why
     * covering a 20-track album costs one image instead of twenty.
     */
    fun getAppliedAlbumArtFile(appContext: Context, songId: Long): File? {
        val pointer = appliedPointerFile(appContext, songId)
        if (!pointer.exists()) return null

        val coverKey = runCatching { pointer.readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return appliedCoverFile(appContext, coverKey).takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Names the pointer after the song the way the extracted cache names its
     * files, so the orphan sweep reads a song id out of it without knowing this
     * directory holds anything unusual.
     */
    private fun appliedPointerFile(appContext: Context, songId: Long): File {
        return File(
            getAppliedArtDir(appContext),
            "song_art_${songId}${APPLIED_ART_VERSION_SUFFIX}$APPLIED_POINTER_EXTENSION"
        )
    }

    internal fun appliedCoverFile(appContext: Context, coverKey: String): File {
        return File(
            getAppliedArtDir(appContext),
            "$APPLIED_COVER_PREFIX$coverKey$APPLIED_ART_VERSION_SUFFIX.webp"
        )
    }

    /**
     * Stores a cover the user applied to [songIds], as the only copy there is.
     *
     * Kept out of the extracted cache so nothing sweeps it, and it wins over
     * both the cache and the file's own tag as the most recent thing the user
     * chose.
     *
     * The image is written once and each song pointed at it, named for a digest
     * of its own bytes -- so the same cover applied by any path lands on one
     * file, and a different cover for one track leaves its album-mates alone.
     * Covers nothing points at are deleted here.
     *
     * @return the cover every song in [songIds] now resolves to, or null when
     * [songIds] is empty.
     */
    fun saveAppliedAlbumArt(
        appContext: Context,
        bytes: ByteArray,
        songIds: List<Long>
    ): File? {
        val ids = songIds.distinct()
        if (ids.isEmpty() || bytes.isEmpty()) return null

        val coverKey = digestOf(bytes)

        return synchronized(appliedArtLock) {
            val cover = appliedCoverFile(appContext, coverKey)

            // Staged and moved into place, so a reader never opens a cover that
            // is half written and an interrupted write leaves nothing behind
            // that a later sweep has to reason about.
            val staging = File(cover.parentFile, cover.name + APPLIED_STAGING_EXTENSION)
            try {
                staging.outputStream().use { it.write(bytes) }
                if (!staging.renameTo(cover)) {
                    staging.copyTo(cover, overwrite = true)
                }
            } finally {
                staging.delete()
            }

            ids.forEach { songId ->
                appliedPointerFile(appContext, songId).writeText(coverKey)
                noArtMarkerFile(appContext, songId).delete()
            }

            deleteUnreferencedAppliedCovers(appContext)
            cover
        }
    }

    /**
     * Deletes applied covers no song points at.
     *
     * Called after a write and after a song's art is dropped, which is every
     * moment a cover can stop being referenced.
     */
    internal fun deleteUnreferencedAppliedCovers(appContext: Context) {
        synchronized(appliedArtLock) {
            val files = getAppliedArtDir(appContext).listFiles() ?: return

            val covers = files
                .filter {
                    it.isFile &&
                        it.name.startsWith(APPLIED_COVER_PREFIX) &&
                        !it.name.endsWith(APPLIED_STAGING_EXTENSION)
                }
                .associateBy { file ->
                    file.name
                        .removePrefix(APPLIED_COVER_PREFIX)
                        .removeSuffix("$APPLIED_ART_VERSION_SUFFIX.webp")
                }

            val referenced = mutableSetOf<String>()
            files.asSequence()
                .filter { it.isFile && it.name.endsWith(APPLIED_POINTER_EXTENSION) }
                .forEach { pointer ->
                    val key = runCatching { pointer.readText().trim() }.getOrNull()
                    // A pointer at a cover that is gone keeps nothing alive, and
                    // would silently adopt the image if that key were ever
                    // written again.
                    if (key.isNullOrEmpty() || key !in covers) {
                        pointer.delete()
                    } else {
                        referenced += key
                    }
                }

            covers.forEach { (key, file) -> if (key !in referenced) file.delete() }

            // Any staging file visible from in here is stale: writes hold this
            // same lock, so an in-flight one cannot be observed.
            files.asSequence()
                .filter { it.isFile && it.name.endsWith(APPLIED_STAGING_EXTENSION) }
                .forEach { it.delete() }
        }
    }

    /**
     * Content digest, used to keep two different covers apart on disk.
     *
     * Long enough that a collision is not worth reasoning about, because the
     * failure would be silent: two covers sharing a name means one album quietly
     * showing another album's artwork, with nothing to notice it.
     */
    private fun digestOf(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Moves any legacy album-art files from cacheDir (old location, wipeable by the OS)
     * into filesDir/album_art/. Idempotent — safe to call on every startup. Runs quickly
     * because it only lists files matching the `song_art_` prefix.
     */
    fun migrateLegacyCacheLocation(appContext: Context) {
        val oldDir = appContext.cacheDir
        val newDir = getAlbumArtDir(appContext)
        val legacyFiles = oldDir.listFiles { f ->
            f.isFile && f.name.startsWith("song_art_")
        } ?: return

        for (file in legacyFiles) {
            val target = File(newDir, file.name)
            if (target.exists()) {
                file.delete()
                continue
            }
            if (!file.renameTo(target)) {
                runCatching {
                    file.copyTo(target, overwrite = false)
                    file.delete()
                }
            }
        }
    }

    private fun cacheAlbumArtBytes(appContext: Context, bytes: ByteArray, songId: Long): File {
        val file = getCachedAlbumArtFile(appContext, songId)

        val boundedBytes = boundArtworkForCache(bytes)
        file.outputStream().use { outputStream ->
            outputStream.write(boundedBytes)
        }
        noArtMarkerFile(appContext, songId).delete()

        // Trigger async cache cleanup if needed
        appScope.launch {
            AlbumArtCacheManager.cleanCacheIfNeeded(appContext, AlbumArtCacheManager.configuredCacheLimitMb)
        }

        return file
    }

    /**
     * Schedules a one-time, background re-compression of an oversized cached cover. The current
     * load still returns the existing file; subsequent loads get the bounded one. De-duplicated
     * per file so rapid repeated loads don't read the same large blob into memory twice.
     */
    private fun scheduleOversizedArtworkShrink(file: File) {
        if (file.length() <= OVERSIZED_CACHED_ART_BYTES) return
        val key = file.absolutePath
        if (!artworkShrinkInFlight.add(key)) return
        appScope.launch {
            try {
                if (file.length() <= OVERSIZED_CACHED_ART_BYTES) return@launch
                val raw = runCatching { file.readBytes() }.getOrNull() ?: return@launch
                val bounded = boundArtworkForCache(raw)
                if (bounded.size >= raw.size) return@launch
                val tmp = File(file.parentFile, "${file.name}.shrink.tmp")
                runCatching {
                    tmp.outputStream().use { it.write(bounded) }
                    if (!tmp.renameTo(file)) {
                        file.outputStream().use { it.write(bounded) }
                        tmp.delete()
                    }
                }
            } finally {
                artworkShrinkInFlight.remove(key)
            }
        }
    }

    /**
     * Bounds and re-encodes a cover for the applied store, once per album.
     *
     * Unlike [boundArtworkForCache] this always re-encodes rather than passing
     * small sources through: the store keeps one image for a whole album and
     * holds it until the user replaces it, so the smaller format is worth the
     * decode every time. Falls back to the original bytes if anything fails,
     * because a slightly larger cover beats no cover.
     */
    fun boundArtworkForStorage(bytes: ByteArray): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val srcWidth = bounds.outWidth
            val srcHeight = bounds.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return bytes

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateArtworkInSampleSize(srcWidth, srcHeight, MAX_CACHED_ART_DIMENSION_PX)
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return bytes
            val scaled = scaleArtworkDownTo(decoded, MAX_CACHED_ART_DIMENSION_PX)
            val encoded = ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, APPLIED_ART_WEBP_QUALITY, stream)
                stream.toByteArray()
            }
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            if (encoded.isNotEmpty()) encoded else bytes
        } catch (e: Throwable) {
            Timber.tag("AlbumArtUtils").w(e, "Failed to bound artwork for the applied store; keeping original bytes")
            bytes
        }
    }

    /**
     * Returns artwork bytes bounded for the display cache: longest edge at most
     * [MAX_CACHED_ART_DIMENSION_PX], re-encoded as JPEG when the source is oversized by
     * dimension or by on-disk size. Returns the original bytes unchanged when they are already
     * small enough or when decoding fails, so artwork is never dropped or needlessly re-encoded.
     */
    private fun boundArtworkForCache(bytes: ByteArray): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val srcWidth = bounds.outWidth
            val srcHeight = bounds.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return bytes

            val oversizedByDimension = maxOf(srcWidth, srcHeight) > MAX_CACHED_ART_DIMENSION_PX
            val oversizedBySize = bytes.size > OVERSIZED_CACHED_ART_BYTES
            if (!oversizedByDimension && !oversizedBySize) return bytes

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateArtworkInSampleSize(srcWidth, srcHeight, MAX_CACHED_ART_DIMENSION_PX)
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return bytes
            val scaled = scaleArtworkDownTo(decoded, MAX_CACHED_ART_DIMENSION_PX)
            val encoded = ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, CACHED_ART_JPEG_QUALITY, stream)
                stream.toByteArray()
            }
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            if (encoded.isNotEmpty() && encoded.size < bytes.size) encoded else bytes
        } catch (e: Throwable) {
            Timber.tag("AlbumArtUtils").w(e, "Failed to bound artwork for cache; keeping original bytes")
            bytes
        }
    }

    /** Largest power-of-two subsample that keeps the decoded longest edge >= [maxDimensionPx]. */
    private fun calculateArtworkInSampleSize(srcWidth: Int, srcHeight: Int, maxDimensionPx: Int): Int {
        var inSampleSize = 1
        val longestEdge = maxOf(srcWidth, srcHeight)
        while (longestEdge / (inSampleSize * 2) >= maxDimensionPx) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /** Scales [src] so its longest edge is [maxDimensionPx]; returns [src] unchanged if already smaller. */
    private fun scaleArtworkDownTo(src: Bitmap, maxDimensionPx: Int): Bitmap {
        val longestEdge = maxOf(src.width, src.height)
        if (longestEdge <= maxDimensionPx) return src
        val scale = maxDimensionPx.toFloat() / longestEdge
        val targetWidth = (src.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (src.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
    }

    private fun noArtMarkerFile(appContext: Context, songId: Long): File {
        return File(getAlbumArtDir(appContext), "song_art_${songId}${CACHE_VERSION_SUFFIX}_no.jpg")
    }

    private fun legacyCachedAlbumArtFile(
        appContext: Context,
        songId: Long,
        versionSuffix: String = ""
    ): File {
        return File(getAlbumArtDir(appContext), "song_art_${songId}${versionSuffix}.jpg")
    }

    private fun legacyNoArtMarkerFile(
        appContext: Context,
        songId: Long,
        versionSuffix: String = ""
    ): File {
        return File(getAlbumArtDir(appContext), "song_art_${songId}${versionSuffix}_no.jpg")
    }

    private data class MediaStoreSongInfo(
        val path: String,
        val albumId: Long?
    )

    private fun resolveSongMediaStoreInfo(
        appContext: Context,
        songId: Long
    ): MediaStoreSongInfo? {
        val selection = "${MediaStore.Audio.Media._ID} = ?"
        val selectionArgs = arrayOf(songId.toString())
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        return runCatching {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                val albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
                MediaStoreSongInfo(
                    path = path,
                    albumId = albumId.takeIf { it > 0L }
                )
            }
        }.getOrNull()
    }

    private fun extractEmbeddedAlbumArtBytes(filePath: String): ByteArray? {
        val startNanos = System.nanoTime()
        val bytes = extractEmbeddedAlbumArtBytesInternal(filePath)
        PerformanceMetrics.recordTiming(
            PerformanceMetrics.Timings.ARTWORK_EXTRACT,
            (System.nanoTime() - startNanos) / 1_000_000
        )
        if (bytes != null) {
            PerformanceMetrics.increment(PerformanceMetrics.Counters.ARTWORK_EXTRACTED_FRESH)
            // Only the byte size is recorded — a free field read. Embedded artwork
            // *dimensions* are intentionally NOT decoded here: a per-file bitmap decode
            // during scan is exactly the kind of overhead this diagnostic must avoid.
            // Decoded dimensions come instead from the real decode path (CoilBitmapLoader).
            PerformanceMetrics.recordEmbeddedArtwork(bytes.size.toLong())
        }
        return bytes
    }

    private fun extractEmbeddedAlbumArtBytesInternal(filePath: String): ByteArray? {
        val retrieverArtwork = MediaMetadataRetrieverPool.withRetriever { retriever ->
            try {
                retriever.setDataSource(filePath)
            } catch (e: IllegalArgumentException) {
                try {
                    FileInputStream(filePath).use { fis ->
                        retriever.setDataSource(fis.fd)
                    }
                } catch (e2: Exception) {
                    return@withRetriever null
                }
            }

            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
        }

        if (retrieverArtwork != null) {
            return retrieverArtwork
        }

        return runCatching {
            AudioMetadataReader.read(File(filePath))?.artwork?.bytes?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun shareableCacheUri(appContext: Context, file: File): Uri {
        return try {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.provider",
                file
            )
        } catch (e: Exception) {
            Uri.fromFile(file)
        }
    }
}

internal fun resolveAlbumArtUriForLibraryScan(
    songId: Long,
    hasCachedArtwork: Boolean,
    hasNoArtworkMarker: Boolean,
    forceRefresh: Boolean
): String? {
    if (hasCachedArtwork) {
        return LocalArtworkUri.buildSongUri(songId)
    }
    if (hasNoArtworkMarker && !forceRefresh) {
        return null
    }
    return LocalArtworkUri.buildSongUri(songId)
}
