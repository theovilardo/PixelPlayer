package com.theveloper.pixelplay.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages album art cache with LRU eviction policy.
 * 
 * Features:
 * - Configurable max cache size (default 200MB)
 * - LRU eviction based on file lastModified timestamp
 * - Cleanup of orphaned cache files for deleted songs
 * - Thread-safe operations
 */
object AlbumArtCacheManager {
    
    private const val TAG = "AlbumArtCacheManager"
    
    /**
     * Maximum cache size in bytes (200MB default)
     */
    const val DEFAULT_MAX_CACHE_SIZE_BYTES = 200L * 1024 * 1024
    
    /**
     * Prefix for album art cache files
     */
    private const val CACHE_PREFIX = "song_art_"

    // The applied store holds one cover per album plus a pointer per song. Both
    // are artwork this app is keeping on the user's behalf, so both have to be
    // visible here -- but only the covers carry any size worth reporting.
    private const val APPLIED_COVER_PREFIX = "cover_"
    private const val APPLIED_POINTER_EXTENSION = ".ref"
    
    /**
     * Suffix for "no art" marker files
     */
    private const val NO_ART_SUFFIX = "_no.jpg"
    
    /**
     * Percentage of cache to clean when limit is exceeded (25%)
     */
    private const val CLEANUP_PERCENTAGE = 0.25
    
    /**
     * Mutex to prevent concurrent cleanup operations
     */
    private val cleanupMutex = Mutex()
    
    /**
     * Last cleanup timestamp to prevent too frequent cleanups
     */
    @Volatile
    private var lastCleanupTime = 0L

    @Volatile
    var configuredCacheLimitMb: Long = 200L
    
    /**
     * Minimum interval between cleanups (5 minutes)
     */
    private const val MIN_CLEANUP_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * How long an applied cover's song has to stay out of the library before
     * the cover is given up on.
     *
     * Long enough to outlast an unmounted card or a library being reorganised,
     * because the other side of it is permanent loss of the only copy. Waiting
     * costs a few kilobytes per genuinely deleted album.
     */
    internal const val APPLIED_ORPHAN_GRACE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Where the sweep records applied covers that looked orphaned, so a later
     * one can tell a song that has been gone for weeks from a song that is
     * missing from this sync alone.
     *
     * Named outside the artwork prefixes on purpose: it is not artwork, and
     * nothing scanning this directory for covers or pointers should see it.
     */
    private const val ORPHAN_CANDIDATES_FILE_NAME = "orphan_candidates"

    private data class CacheEvictionCandidate(
        val file: File,
        val lastModifiedSnapshot: Long,
        val absolutePathSnapshot: String
    )
    
    /**
     * Cleans the cache if it exceeds the maximum size.
     * Uses LRU policy to remove the oldest 25% of files.
     * 
     * @param context Application context
     * @param maxCacheSizeMb Maximum cache size limit in MB (default: 200MB)
     * @return Number of files deleted, or 0 if no cleanup was needed
     */
    suspend fun cleanCacheIfNeeded(context: Context, maxCacheSizeMb: Long = 200L): Int {
        val maxCacheSizeBytes = maxCacheSizeMb * 1024 * 1024
        return cleanCacheIfNeededInternal(context, maxCacheSizeBytes)
    }

    private suspend fun cleanCacheIfNeededInternal(context: Context, maxCacheSizeBytes: Long): Int = withContext(Dispatchers.IO) {
        // Skip if cleaned recently
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime < MIN_CLEANUP_INTERVAL_MS) {
            return@withContext 0
        }
        
        cleanupMutex.withLock {
            // Double-check after acquiring lock
            if (now - lastCleanupTime < MIN_CLEANUP_INTERVAL_MS) {
                return@withLock 0
            }
            
            val cacheDir = AlbumArtUtils.getAlbumArtDir(context)
            val artFiles = getAlbumArtFiles(cacheDir)
            
            if (artFiles.isEmpty()) {
                return@withLock 0
            }
            
            val currentSize = artFiles.sumOf { it.length() }
            
            if (currentSize <= maxCacheSizeBytes) {
                return@withLock 0
            }
            
            Log.d(TAG, "Cache size ${currentSize / 1024 / 1024}MB exceeds limit, cleaning...")
            
            // Snapshot lastModified before sorting. The timestamp is mutated elsewhere to
            // implement LRU reads, so re-reading it during TimSort can violate comparator
            // transitivity and crash with "Comparison method violates its general contract!".
            val filesToDelete = snapshotFilesForCleanup(
                artFiles = artFiles,
                cleanupPercentage = CLEANUP_PERCENTAGE
            )
            
            var deletedCount = 0
            var freedBytes = 0L
            
            for (file in filesToDelete) {
                val size = file.length()
                if (file.delete()) {
                    deletedCount++
                    freedBytes += size
                }
            }
            
            lastCleanupTime = System.currentTimeMillis()
            
            Log.d(TAG, "Cleaned $deletedCount files, freed ${freedBytes / 1024}KB")
            
            deletedCount
        }
    }
    
    /**
     * Cleans orphaned cache files for songs that no longer exist.
     * Should be called after sync operations.
     *
     * Extracted artwork goes as soon as its song is out of the library; an
     * applied cover has to look orphaned for [APPLIED_ORPHAN_GRACE_MS] of
     * elapsed time before it does. See [sweepAppliedPointers].
     *
     * @param context Application context
     * @param validSongIds Set of song IDs that still exist in the library
     * @param now Exposed so tests do not have to wait out the grace period.
     * @return Number of orphaned files deleted
     */
    suspend fun cleanOrphanedCacheFiles(
        context: Context,
        validSongIds: Set<Long>,
        now: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        // An empty set makes every file here look orphaned, and now costs the
        // applied covers rather than a rebuildable cache. A scan without media
        // permission or before a card mounted produces one, so it is refused.
        if (validSongIds.isEmpty()) {
            Log.w(TAG, "Skipping orphan sweep: no valid song ids were supplied")
            return@withContext 0
        }

        cleanupMutex.withLock {
            var deletedCount = 0

            // Extracted artwork, which is re-read from the audio file the next
            // time anything asks for it, so a song that turns out to still be
            // there costs one extraction.
            for (file in getAllAlbumArtRelatedFiles(AlbumArtUtils.getAlbumArtDir(context))) {
                val songId = extractSongIdFromFilename(file.name)
                if (songId != null && songId !in validSongIds && file.delete()) {
                    deletedCount++
                }
            }

            deletedCount += sweepAppliedPointers(
                appliedDir = AlbumArtUtils.getAppliedArtDir(context),
                validSongIds = validSongIds,
                now = now
            )

            // Songs that left the library take their pointers with them above,
            // which is what can stand a cover down to nothing pointing at it.
            AlbumArtUtils.deleteUnreferencedAppliedCovers(context)

            if (deletedCount > 0) {
                Log.d(TAG, "Cleaned $deletedCount orphaned album art files")
            }

            deletedCount
        }
    }

    /**
     * Drops the pointers of applied covers whose songs have been gone from the
     * library for [APPLIED_ORPHAN_GRACE_MS], and notes the rest as candidates.
     *
     * Absence from one sync is not evidence a song is gone: an unmounted card,
     * a moved folder or a re-index all empty rows that come back. That costs a
     * re-read for the extracted cache, but the cover itself here --
     * [AlbumArtUtils.deleteUnreferencedAppliedCovers] destroys the image once
     * its last pointer goes, and there is no second copy.
     *
     * @return the number of pointers actually deleted.
     */
    private fun sweepAppliedPointers(
        appliedDir: File,
        validSongIds: Set<Long>,
        now: Long
    ): Int {
        val candidates = readOrphanCandidates(appliedDir)
        val stillOrphaned = mutableMapOf<Long, Long>()
        var deletedCount = 0

        for (file in getAllAlbumArtRelatedFiles(appliedDir)) {
            val songId = extractSongIdFromFilename(file.name) ?: continue
            if (songId in validSongIds) continue

            // A timestamp from the future is a clock that was wound back since
            // it was written; read as it stands it would hold the pointer for
            // however long that is.
            val firstSeen = candidates[songId]?.coerceAtMost(now)
            if (firstSeen != null && now - firstSeen >= APPLIED_ORPHAN_GRACE_MS) {
                if (file.delete()) deletedCount++
            } else {
                stillOrphaned[songId] = firstSeen ?: now
            }
        }

        writeOrphanCandidates(appliedDir, stillOrphaned)
        return deletedCount
    }

    /** When each still-present applied cover was first seen without its song. */
    private fun readOrphanCandidates(appliedDir: File): Map<Long, Long> {
        val file = File(appliedDir, ORPHAN_CANDIDATES_FILE_NAME)
        if (!file.exists()) return emptyMap()

        return runCatching {
            file.readLines().mapNotNull { line ->
                val songId = line.substringBefore('=').toLongOrNull() ?: return@mapNotNull null
                val firstSeen = line.substringAfter('=', "").toLongOrNull() ?: return@mapNotNull null
                songId to firstSeen
            }.toMap()
        }.getOrElse {
            // Unreadable means nothing has been observed yet, which starts every
            // candidate's clock again rather than expiring anything early.
            Log.w(TAG, "Could not read applied cover orphan candidates", it)
            emptyMap()
        }
    }

    private fun writeOrphanCandidates(appliedDir: File, candidates: Map<Long, Long>) {
        val file = File(appliedDir, ORPHAN_CANDIDATES_FILE_NAME)
        runCatching {
            if (candidates.isEmpty()) {
                file.delete()
            } else {
                file.writeText(candidates.entries.joinToString("\n") { "${it.key}=${it.value}" })
            }
        }.onFailure {
            // The sweep is still correct without this, only more cautious: every
            // candidate looks new again on the next pass.
            Log.w(TAG, "Could not record applied cover orphan candidates", it)
        }
    }
    
    /**
     * Gets the current cache size in bytes.
     * 
     * @param context Application context
     * @return Total size of album art cache in bytes
     */
    suspend fun getCacheSizeBytes(context: Context): Long = withContext(Dispatchers.IO) {
        artworkDirectories(context)
            .flatMap { getAlbumArtFiles(it) }
            .sumOf { it.length() }
    }
    
    /**
     * Gets the current cache size in a human-readable format.
     * 
     * @param context Application context
     * @return Cache size as "X.X MB" string
     */
    suspend fun getCacheSizeFormatted(context: Context): String {
        val bytes = getCacheSizeBytes(context)
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format("%.1f MB", mb)
    }
    
    /**
     * Gets the number of cached album art files.
     * 
     * @param context Application context
     * @return Number of cached files
     */
    fun getCachedFileCount(context: Context): Int {
        return artworkDirectories(context).sumOf { getAlbumArtFiles(it).size }
    }

    /**
     * Every directory holding artwork the app manages.
     *
     * Covers the user applied live apart from the extracted cache so the LRU
     * cannot evict them, but they are still artwork the app is storing on the
     * user's behalf: leaving them out here would under-report the size and
     * leak them when their songs are gone.
     */
    private fun artworkDirectories(context: Context): List<File> = listOf(
        AlbumArtUtils.getAlbumArtDir(context),
        AlbumArtUtils.getAppliedArtDir(context)
    )

    /**
     * Clears all album art cache files, including covers applied to songs.
     *
     * Unused as it stands. Note before wiring it to anything that the applied
     * covers it deletes are the only copy there is: unlike the extracted cache,
     * nothing can regenerate them from the audio files.
     *
     * @param context Application context
     * @return Number of files deleted
     */
    suspend fun clearAllCache(context: Context): Int = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            val files = artworkDirectories(context).flatMap { getAllAlbumArtRelatedFiles(it) }
            var deletedCount = 0
            
            for (file in files) {
                if (file.delete()) {
                    deletedCount++
                }
            }
            
            Log.d(TAG, "Cleared all album art cache: $deletedCount files")
            deletedCount
        }
    }
    
    /**
     * Gets all album art cache files (excluding "no art" markers).
     */
    private fun getAlbumArtFiles(cacheDir: File): List<File> {
        return cacheDir.listFiles { file ->
            file.isFile &&
            !file.name.endsWith(APPLIED_POINTER_EXTENSION) &&
            (file.name.startsWith(APPLIED_COVER_PREFIX) ||
                (file.name.startsWith(CACHE_PREFIX) && !file.name.contains(NO_ART_SUFFIX)))
        }?.toList() ?: emptyList()
    }

    internal fun snapshotFilesForCleanup(
        artFiles: List<File>,
        cleanupPercentage: Double
    ): List<File> {
        if (artFiles.isEmpty()) return emptyList()

        val deleteCount = (artFiles.size * cleanupPercentage).toInt().coerceAtLeast(1)

        return artFiles.asSequence()
            .map { file ->
                CacheEvictionCandidate(
                    file = file,
                    lastModifiedSnapshot = file.lastModified(),
                    absolutePathSnapshot = file.absolutePath
                )
            }
            .sortedWith(
                compareBy<CacheEvictionCandidate> { it.lastModifiedSnapshot }
                    .thenBy { it.absolutePathSnapshot }
            )
            .take(deleteCount)
            .map(CacheEvictionCandidate::file)
            .toList()
    }
    
    /**
     * Gets all album art related files (including "no art" markers).
     */
    private fun getAllAlbumArtRelatedFiles(cacheDir: File): List<File> {
        return cacheDir.listFiles { file ->
            file.isFile &&
                (file.name.startsWith(CACHE_PREFIX) || file.name.startsWith(APPLIED_COVER_PREFIX))
        }?.toList() ?: emptyList()
    }
    
    /**
     * Extracts song ID from cache filename.
     * Handles formats: "song_art_123.jpg" and "song_art_123_no.jpg"
     * 
     * @param filename The filename to parse
     * @return Song ID or null if parsing fails
     */
    internal fun extractSongIdFromFilename(filename: String): Long? {
        return try {
            // Remove prefix "song_art_"
            val withoutPrefix = filename.removePrefix(CACHE_PREFIX)
            
            // Extract the ID (before any underscore or dot)
            val idPart = withoutPrefix
                .substringBefore("_")
                .substringBefore(".")
            
            idPart.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
