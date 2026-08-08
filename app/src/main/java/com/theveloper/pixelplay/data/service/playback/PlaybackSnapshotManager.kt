package com.theveloper.pixelplay.data.service.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.model.PlaybackQueueItemSnapshot
import com.theveloper.pixelplay.data.model.PlaybackQueueSnapshot
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.utils.MediaItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages capture, persistence, and debounced scheduling of the playback
 * queue snapshot used to restore playback state across process restarts.
 *
 * Extracted from [MusicService] to isolate snapshot I/O from the service's
 * media-session lifecycle management.
 */
class PlaybackSnapshotManager(
    private val context: Context,
    private val engine: DualPlayerEngine,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val scope: CoroutineScope,
) {

    private var persistJob: Job? = null
    private var unloadWriteJob: Job? = null
    var isRestoring: Boolean = false
        private set
    var isUnloadInProgress: Boolean = false
        private set

    fun schedulePersist(immediate: Boolean = false) {
        if (isUnloadInProgress) return
        persistJob?.cancel()
        persistJob = scope.launch {
            if (!immediate) {
                delay(SNAPSHOT_DEBOUNCE_MS)
            }
            persist()
        }
    }

    suspend fun persist(playWhenReadyOverride: Boolean? = null) {
        if (isRestoring) return
        val snapshot = capture(playWhenReadyOverride)
        runCatching {
            userPreferencesRepository.setPlaybackQueueSnapshot(snapshot)
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "Failed to persist playback snapshot")
        }
    }

    suspend fun capture(playWhenReadyOverride: Boolean? = null): PlaybackQueueSnapshot? =
        withContext(Dispatchers.Main.immediate) {
            captureFromPlayer(playWhenReadyOverride)
        }

    fun captureFromPlayer(
        playWhenReadyOverride: Boolean? = null
    ): PlaybackQueueSnapshot? {
        val player = engine.masterPlayer
        val mediaItemCount = player.mediaItemCount
        if (mediaItemCount <= 0) {
            return null
        }

        val snapshotItems = ArrayList<PlaybackQueueItemSnapshot>(mediaItemCount)
        for (index in 0 until mediaItemCount) {
            val mediaItem = player.getMediaItemAt(index)
            val metadata = mediaItem.mediaMetadata
            val uri = mediaItem.localConfiguration?.uri?.toString()
                ?: metadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)

            if (mediaItem.mediaId.isBlank() || uri.isNullOrBlank()) {
                continue
            }

            val durationMs = metadata.extras
                ?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION)
                ?.takeIf { it > 0L }

            snapshotItems.add(
                PlaybackQueueItemSnapshot(
                    mediaId = mediaItem.mediaId,
                    uri = uri,
                    title = metadata.title?.toString(),
                    artist = metadata.artist?.toString(),
                    albumTitle = metadata.albumTitle?.toString(),
                    artworkUri = resolveStoredArtworkUri(metadata),
                    durationMs = durationMs,
                )
            )
        }

        if (snapshotItems.isEmpty()) {
            return null
        }

        val currentMediaId = player.currentMediaItem?.mediaId
        val indexFromMediaId = currentMediaId
            ?.let { id -> snapshotItems.indexOfFirst { it.mediaId == id } }
            ?.takeIf { it >= 0 }

        val safeCurrentIndex = when {
            indexFromMediaId != null -> indexFromMediaId
            player.currentMediaItemIndex in snapshotItems.indices -> player.currentMediaItemIndex
            else -> 0
        }

        val safeRepeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF,
            Player.REPEAT_MODE_ONE,
            Player.REPEAT_MODE_ALL -> player.repeatMode
            else -> Player.REPEAT_MODE_OFF
        }

        return PlaybackQueueSnapshot(
            items = snapshotItems,
            currentMediaId = currentMediaId,
            currentIndex = safeCurrentIndex,
            currentPositionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = playWhenReadyOverride ?: player.playWhenReady,
            repeatMode = safeRepeatMode,
            shuffleEnabled = isShuffleEnabled(),
        )
    }

    fun persistOnUnload() {
        val snapshot = captureFromPlayer(playWhenReadyOverride = false)
        writeOnUnload(snapshot)
    }

    fun clearOnUnload() {
        writeOnUnload(null)
    }

    private fun writeOnUnload(snapshot: PlaybackQueueSnapshot?) {
        unloadWriteJob?.cancel()
        unloadWriteJob = scope.launch {
            runCatching {
                userPreferencesRepository.setPlaybackQueueSnapshot(snapshot)
            }.onFailure { e ->
                Timber.tag(TAG).w(e, "Failed to persist playback snapshot during unload")
            }
        }
    }

    fun beginRestore() {
        isRestoring = true
    }

    fun endRestore() {
        isRestoring = false
    }

    /**
     * Build a [MediaItem] from a snapshot item for queue restoration.
     * Kept here because it depends on [MediaItemBuilder] which is a utility.
     */
    fun buildMediaItemFromSnapshot(snapshotItem: PlaybackQueueItemSnapshot): android.media3.common.MediaItem? {
        if (snapshotItem.mediaId.isBlank() || snapshotItem.uri.isBlank()) {
            return null
        }

        val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
        snapshotItem.title?.takeIf { it.isNotBlank() }?.let { metadataBuilder.setTitle(it) }
        snapshotItem.artist?.takeIf { it.isNotBlank() }?.let { metadataBuilder.setArtist(it) }
        snapshotItem.albumTitle?.takeIf { it.isNotBlank() }?.let { metadataBuilder.setAlbumTitle(it) }
        MediaItemBuilder.externalControllerArtworkUri(context, snapshotItem.artworkUri)
            ?.let { metadataBuilder.setArtworkUri(it) }

        val extras = Bundle().apply {
            putBoolean(
                MediaItemBuilder.EXTERNAL_EXTRA_FLAG,
                snapshotItem.mediaId.startsWith("external:")
            )
            putString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI, snapshotItem.uri)
            snapshotItem.albumTitle?.takeIf { it.isNotBlank() }?.let {
                putString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM, it)
            }
            snapshotItem.artworkUri?.takeIf { it.isNotBlank() }?.let {
                putString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART, it)
            }
            snapshotItem.durationMs?.takeIf { it > 0L }?.let {
                putLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, it)
            }
        }
        metadataBuilder.setExtras(extras)

        return android.media3.common.MediaItem.Builder()
            .setMediaId(snapshotItem.mediaId)
            .setUri(MediaItemBuilder.playbackUri(snapshotItem.uri))
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /**
     * Placeholder for shuffle state — overridden by MusicService lambda.
     * Set by MusicService via [setShuffleStateProvider].
     */
    private var shuffleStateProvider: () -> Boolean = { false }

    fun setShuffleStateProvider(provider: () -> Boolean) {
        shuffleStateProvider = provider
    }

    private fun isShuffleEnabled(): Boolean = shuffleStateProvider()

    /**
     * Reads the stored artwork URI from media item metadata.
     */
    private fun resolveStoredArtworkUri(metadata: androidx.media3.common.MediaMetadata?): String? {
        metadata ?: return null
        return metadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.artworkUri
                ?.toString()
                ?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "PlaybackSnapshotManager"
        private const val SNAPSHOT_DEBOUNCE_MS = 1500L
    }
}
