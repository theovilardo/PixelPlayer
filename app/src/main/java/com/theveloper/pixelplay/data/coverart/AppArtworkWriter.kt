package com.theveloper.pixelplay.data.coverart

import android.content.Context
import com.theveloper.pixelplay.data.database.AlbumArtThemeDao
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.media.ImageCacheManager
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.utils.LocalArtworkUri
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies a cover without touching the user's audio files.
 *
 * The image goes into the applied-artwork store, which nothing evicts; see
 * [AlbumArtUtils.saveAppliedAlbumArt]. Shared by the unattended pass and by a
 * manual apply under [AlbumArtStorage.APP_ONLY].
 */
@Singleton
class AppArtworkWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val albumArtThemeDao: AlbumArtThemeDao,
    private val imageCacheManager: ImageCacheManager
) {

    private val _appliedArtworkRevision = MutableStateFlow(0L)

    /**
     * Bumped every time a song's artwork changes, so a screen can re-read it.
     *
     * The rows are not a signal on their own: [apply] writes the canonical URI,
     * which is often the string the row already held. Covers writes into the
     * audio files too, via [noteExternalArtworkChange].
     */
    val appliedArtworkRevision: StateFlow<Long> = _appliedArtworkRevision.asStateFlow()

    /**
     * Records artwork changed outside this writer -- a cover written into the
     * audio files themselves.
     *
     * Those writes leave the album row pointing at the URI it already held, so
     * a header drawing from it has nothing else to tell it to reload, and they
     * can supersede an applied cover, which leaves the "remove cover" entry
     * describing a store that no longer holds one.
     */
    fun noteExternalArtworkChange() {
        _appliedArtworkRevision.update { it + 1 }
    }

    /**
     * @param albumId the album these songs belong to, when they share one. Its
     * row follows only if [songIds] covers the whole album -- one track of
     * twenty is not the album getting a new cover.
     * @return false when nothing was stored: an empty [songIds], or a failed
     * write. A believed-but-absent apply would chain the automatic pass into
     * re-fetching the same albums forever.
     */
    suspend fun apply(
        bytes: ByteArray,
        songIds: List<Long>,
        albumId: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        // Cloud tracks have a negative id and no local store to write into, so
        // pointing their rows here would replace a working remote URI with one
        // resolving to nothing. Both callers filter; repeating it makes it the
        // writer's invariant rather than each caller's to remember.
        val songIds = songIds.filter { it > 0 }
        if (songIds.isEmpty()) return@withContext false

        // One decode and re-encode for the album, not one per track: bounding
        // is a full bitmap decode, scale and WebP encode.
        val bounded = AlbumArtUtils.boundArtworkForStorage(bytes)

        // A full disk is worth a log and an unchanged cover, not an exception
        // escaping into a ViewModel's scope. The caller is still told: reporting
        // success chained the automatic pass into re-fetching forever.
        val stored = runCatching { AlbumArtUtils.saveAppliedAlbumArt(context, bounded, songIds) }
            .onFailure { error -> Timber.w(error, "Could not store the applied cover") }
            .getOrNull()
        if (stored == null) return@withContext false

        val artworkUris = songIds.map { songId ->
            val artworkUri = LocalArtworkUri.buildSongUri(songId)
            musicDao.updateSongAlbumArt(songId, artworkUri)
            imageCacheManager.invalidateRenderedCoverArt(artworkUri)
            artworkUri
        }

        if (coversWholeAlbum(albumId, songIds)) {
            musicDao.updateAlbumArt(requireNotNull(albumId), artworkUris.first())
        }

        // The palette is derived from the old cover and keyed by a URI that has
        // not changed, so nothing else would ever recompute it.
        albumArtThemeDao.deleteThemesByUris(artworkUris)
        _appliedArtworkRevision.update { it + 1 }
        true
    }

    /**
     * Takes back a cover applied to [songIds], leaving the audio files alone.
     *
     * Each song shows what it would have shown had the cover never been applied.
     * Returns what each was left pointing at, since not every song keeps art.
     */
    suspend fun removeApplied(
        songIds: List<Long>,
        albumId: Long? = null
    ): Map<Long, String?> = withContext(Dispatchers.IO) {
        // Filtered for the same reason [apply] filters: a cloud track never had
        // an applied cover to take back, and writing one's row here would
        // re-point it at a local file that does not exist.
        val songIds = songIds.filter { it > 0 }
        if (songIds.isEmpty()) return@withContext emptyMap()

        val artworkUris = songIds.map { LocalArtworkUri.buildSongUri(it) }
        var remainingForAlbum: String? = null
        val remaining = mutableMapOf<Long, String?>()

        songIds.forEach { songId ->
            AlbumArtUtils.clearAppliedArtForSong(context, songId)

            // Asking for the artwork again is what re-extracts whatever the file
            // still carries; a song with none is left pointing at nothing rather
            // than at a URI that resolves to a blank.
            val artworkUri = AlbumArtUtils.ensureAlbumArtCachedFile(context, songId)
                ?.let { LocalArtworkUri.buildSongUri(songId) }
            musicDao.updateSongAlbumArt(songId, artworkUri)
            imageCacheManager.invalidateRenderedCoverArt(LocalArtworkUri.buildSongUri(songId))
            remaining[songId] = artworkUri
            if (remainingForAlbum == null) remainingForAlbum = artworkUri
        }

        if (coversWholeAlbum(albumId, songIds)) {
            musicDao.updateAlbumArt(requireNotNull(albumId), remainingForAlbum)
        }

        albumArtThemeDao.deleteThemesByUris(artworkUris)
        _appliedArtworkRevision.update { it + 1 }
        remaining
    }

    /**
     * Whether [songIds] accounts for every track of [albumId] this writer can
     * give a cover to. Cloud tracks are left out of the count as they are left
     * out of the write, or an album holding one is permanently short.
     */
    private suspend fun coversWholeAlbum(albumId: Long?, songIds: List<Long>): Boolean {
        val id = albumId ?: return false
        val albumSongIds = musicDao.getSongsByAlbumIdOnce(id).map { it.id }.filter { it > 0 }
        return albumSongIds.isNotEmpty() && songIds.containsAll(albumSongIds)
    }
}
