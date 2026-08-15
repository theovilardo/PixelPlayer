package com.theveloper.pixelplay.data

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.theveloper.pixelplay.data.local.LocalSongDao
import com.theveloper.pixelplay.data.local.LocalSongEntity
import com.theveloper.pixelplay.shared.WearLibraryItem
import com.theveloper.pixelplay.shared.WearThemePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local player state mirroring WearPlayerState structure for unified UI.
 */
data class WearLocalPlayerState(
    val songId: String = "",
    val songTitle: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isFavorite: Boolean = false,
    val canToggleFavorite: Boolean = false,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val isEmpty: Boolean get() = songId.isEmpty()
}

data class WearQueueSong(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val uri: Uri,
)

/**
 * Repository driving standalone local playback on the watch.
 * Plays audio files that have been transferred from the phone and stored locally.
 *
 * The actual ExoPlayer + MediaSession live inside [WearPlaybackService] (a foreground
 * MediaSessionService); this repository controls them through a [MediaController]. Hosting playback
 * in a foreground service is what keeps the process alive when the app is backgrounded — and the
 * MediaSession also routes Bluetooth headset media buttons and system transport controls to watch
 * playback.
 */
@Singleton
class WearLocalPlayerRepository @Inject constructor(
    private val application: Application,
    private val localSongDao: LocalSongDao,
    private val playbackStatePersistence: WearPlaybackStatePersistence,
    private val performanceSettings: WearPerformanceSettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private var mediaController: MediaController? = null

    private val _localPlayerState = MutableStateFlow(WearLocalPlayerState())
    val localPlayerState: StateFlow<WearLocalPlayerState> = _localPlayerState.asStateFlow()

    private val _isLocalPlaybackActive = MutableStateFlow(false)
    val isLocalPlaybackActive: StateFlow<Boolean> = _isLocalPlaybackActive.asStateFlow()

    private val _localPaletteSeedArgb = MutableStateFlow<Int?>(null)
    val localPaletteSeedArgb: StateFlow<Int?> = _localPaletteSeedArgb.asStateFlow()

    private val _localThemePalette = MutableStateFlow<WearThemePalette?>(null)
    val localThemePalette: StateFlow<WearThemePalette?> = _localThemePalette.asStateFlow()

    private val _localAlbumArt = MutableStateFlow<Bitmap?>(null)
    val localAlbumArt: StateFlow<Bitmap?> = _localAlbumArt.asStateFlow()

    private val _localQueueState = MutableStateFlow(WearLocalQueueState())
    val localQueueState: StateFlow<WearLocalQueueState> = _localQueueState.asStateFlow()

    private var positionUpdateJob: Job? = null
    private var currentQueueSongIds: List<String> = emptyList()
    private var currentQueueSongsById: Map<String, LocalSongEntity> = emptyMap()
    private var currentQueueItemsById: Map<String, WearQueueSong> = emptyMap()
    private var lastPaletteSongId: String = ""
    private var lastArtworkSongId: String = ""
    private var transientSongIds: Set<String> = emptySet()
    private var transientCleanupPaths: Set<String> = emptySet()

    companion object {
        private const val TAG = "WearLocalPlayer"
        private const val POSITION_UPDATE_INTERVAL_MS = 1000L
        private const val PERSIST_INTERVAL_TICKS = 10
    }

    init {
        scope.launch {
            localSongDao.getAllSongs().collect { songs ->
                if (songs.isNotEmpty()) {
                    val latestSongsById = songs.associateBy { it.songId }
                    if (currentQueueSongsById.isNotEmpty()) {
                        currentQueueSongsById = currentQueueSongsById.mapValues { (songId, song) ->
                            latestSongsById[songId] ?: song
                        }
                    }
                }

                if (!localPlayerState.value.isEmpty) {
                    updateState()
                }
            }
        }

        // React to the performance toggles changing while a song is already loaded — e.g. the
        // user turns "show album art" off from the phone mid-song: drop the bitmap immediately
        // instead of waiting for the next song change, since the whole point is freeing RAM right
        // away. drop(1) skips the initial replay so this doesn't fire redundantly at construction.
        scope.launch {
            performanceSettings.showAlbumArt.drop(1).collect {
                updateArtworkForSong(_localPlayerState.value.songId)
            }
        }
        scope.launch {
            performanceSettings.dynamicColorTheming.drop(1).collect {
                updatePaletteForSong(_localPlayerState.value.songId)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updateState()
            if (playbackState == Player.STATE_ENDED) {
                stopPositionUpdates()
                // Nothing left to resume — clear rather than leave a stale "restore" prompt
                // pointing at a queue that already finished.
                clearPersistedPlaybackState()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState()
            persistCurrentPlaybackState()
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateState()
            persistCurrentPlaybackState()
        }
    }

    /**
     * Connect to (and implicitly start) [WearPlaybackService], returning a [MediaController] that
     * drives its ExoPlayer. Playback lives inside that MediaSessionService so Android keeps it alive
     * as a foreground "mediaPlayback" service while audio plays — otherwise Wear OS reaps the
     * background process after a few minutes and playback dies silently.
     */
    private suspend fun getOrConnectController(): MediaController {
        mediaController?.let { return it }
        return withContext(Dispatchers.Main) {
            mediaController?.let { return@withContext it }
            val token = SessionToken(
                application,
                ComponentName(application, WearPlaybackService::class.java),
            )
            val controller = suspendCancellableCoroutine<MediaController> { continuation ->
                val future = MediaController.Builder(application, token).buildAsync()
                future.addListener(
                    {
                        try {
                            continuation.resume(future.get())
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    },
                    ContextCompat.getMainExecutor(application),
                )
                continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
            }
            mediaController = controller
            controller.addListener(playerListener)
            Timber.tag(TAG).d("MediaController connected to WearPlaybackService")
            controller
        }
    }

    /**
     * Start local playback with the given songs, beginning at [startIndex].
     */
    fun playLocalSongs(
        songs: List<LocalSongEntity>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
    ) {
        scope.launch {
            val playableSongs = songs.filter { song ->
                val file = File(song.localPath)
                file.isFile && file.length() > 0L
            }
            if (playableSongs.isEmpty()) {
                Timber.tag(TAG).w("No playable local files available")
                return@launch
            }

            val queueSongs = playableSongs.map { song ->
                WearQueueSong(
                    songId = song.songId,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    uri = Uri.fromFile(File(song.localPath)),
                )
            }
            startPlayback(
                queueSongs = queueSongs,
                queueSongIdToLocal = playableSongs.associateBy { it.songId },
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
            )
        }
    }

    /**
     * Start local playback from watch MediaStore songs.
     */
    fun playUriSongs(
        songs: List<WearQueueSong>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
    ) {
        scope.launch {
            if (songs.isEmpty()) {
                Timber.tag(TAG).w("No watch library songs available")
                return@launch
            }
            startPlayback(
                queueSongs = songs,
                queueSongIdToLocal = emptyMap(),
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
            )
        }
    }

    fun playTemporarySong(
        song: LocalSongEntity,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        cleanupPaths: Set<String> = emptySet(),
    ) {
        scope.launch {
            val file = File(song.localPath)
            if (!file.isFile || file.length() <= 0L) {
                Timber.tag(TAG).w("Temporary playback file missing for songId=%s", song.songId)
                return@launch
            }

            startPlayback(
                queueSongs = listOf(
                    WearQueueSong(
                        songId = song.songId,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        uri = Uri.fromFile(file),
                    )
                ),
                queueSongIdToLocal = mapOf(song.songId to song),
                startIndex = 0,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
                transientSongIds = setOf(song.songId),
                transientCleanupPaths = cleanupPaths +
                    setOf(song.localPath) +
                    listOfNotNull(song.artworkPath),
            )
        }
    }

    private suspend fun startPlayback(
        queueSongs: List<WearQueueSong>,
        queueSongIdToLocal: Map<String, LocalSongEntity>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        transientSongIds: Set<String> = emptySet(),
        transientCleanupPaths: Set<String> = emptySet(),
    ) {
        withContext(Dispatchers.Main) {
            val player = try {
                getOrConnectController()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to connect to WearPlaybackService")
                return@withContext
            }
            if (this@WearLocalPlayerRepository.transientCleanupPaths.isNotEmpty()) {
                player.stop()
            }
            clearTransientPlaybackArtifacts()
            currentQueueSongIds = queueSongs.map { it.songId }
            val latestSongsById = queueSongIdToLocal.keys.mapNotNull { songId ->
                localSongDao.getSongById(songId)
            }.associateBy { it.songId }
            currentQueueSongsById = queueSongIdToLocal.mapValues { (songId, song) ->
                latestSongsById[songId] ?: song
            }
            currentQueueItemsById = queueSongs.associateBy { it.songId }
            this@WearLocalPlayerRepository.transientSongIds = transientSongIds
            this@WearLocalPlayerRepository.transientCleanupPaths = transientCleanupPaths
                .filter { it.isNotBlank() }
                .toSet()
            lastPaletteSongId = ""
            lastArtworkSongId = ""
            _localThemePalette.value = null
            _localPaletteSeedArgb.value = null
            _localAlbumArt.value = null

            val mediaItems = queueSongs.map { song ->
                MediaItem.Builder()
                    .setMediaId(song.songId)
                    .setUri(song.uri)
                    // A MediaController drops localConfiguration (the URI) when items cross the
                    // binder to the service, so stash it in requestMetadata for the service's
                    // MediaSession.Callback to restore. See WearPlaybackService.
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder()
                            .setMediaUri(song.uri)
                            .build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .build()
                    )
                    .build()
            }
            val startIndexSafe = startIndex.coerceIn(0, mediaItems.lastIndex)
            player.setMediaItems(mediaItems, startIndexSafe, startPositionMs.coerceAtLeast(0L))
            player.prepare()
            player.playWhenReady = autoPlay
            if (autoPlay) {
                player.play()
            } else {
                player.pause()
            }
            _isLocalPlaybackActive.value = true
            updateQueueState(currentIndex = startIndexSafe)
            updateState()
            Timber.tag(TAG).d(
                "Playing locally: ${queueSongs.getOrNull(startIndexSafe)?.title}, queue=${queueSongs.size}, autoPlay=$autoPlay"
            )
        }
    }

    fun play() {
        mediaController?.play()
    }

    fun togglePlayPause() {
        val player = mediaController ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun pause() {
        mediaController?.pause()
    }

    fun next() {
        val player = mediaController ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        }
    }

    fun previous() {
        val player = mediaController ?: return
        if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        scope.launch {
            withContext(Dispatchers.Main) {
                val player = mediaController ?: return@withContext
                player.shuffleModeEnabled = !player.shuffleModeEnabled
                updateState()
            }
        }
    }

    fun cycleRepeat() {
        scope.launch {
            withContext(Dispatchers.Main) {
                val player = mediaController ?: return@withContext
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
                updateState()
            }
        }
    }

    fun playQueueIndex(index: Int) {
        scope.launch {
            withContext(Dispatchers.Main) {
                val player = mediaController ?: return@withContext
                if (index !in 0 until player.mediaItemCount) return@withContext

                player.seekToDefaultPosition(index)
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                player.play()
                updateState()
            }
        }
    }

    suspend fun removeSongFromActiveQueue(songId: String) {
        withContext(Dispatchers.Main) {
            val queueIndex = currentQueueSongIds.indexOf(songId)
            if (queueIndex == -1) return@withContext

            val player = mediaController
            if (player == null || currentQueueSongIds.size <= 1) {
                release()
                return@withContext
            }

            player.removeMediaItem(queueIndex)
            currentQueueSongIds = currentQueueSongIds.toMutableList().apply {
                removeAt(queueIndex)
            }
            currentQueueSongsById = currentQueueSongsById.toMutableMap().apply {
                remove(songId)
            }
            currentQueueItemsById = currentQueueItemsById.toMutableMap().apply {
                remove(songId)
            }
            if (lastPaletteSongId == songId) lastPaletteSongId = ""
            if (lastArtworkSongId == songId) lastArtworkSongId = ""
            updateQueueState()
            updateState()
        }
    }

    /**
     * Stop local playback and release the player.
     */
    fun release() {
        stopPositionUpdates()
        clearPersistedPlaybackState()
        mediaController?.let { controller ->
            controller.removeListener(playerListener)
            runCatching {
                controller.stop()
                controller.clearMediaItems()
            }
            controller.release()
        }
        mediaController = null
        // Tear down the foreground service so its media notification clears immediately.
        runCatching {
            application.stopService(Intent(application, WearPlaybackService::class.java))
        }
        clearTransientPlaybackArtifacts()
        _isLocalPlaybackActive.value = false
        _localPlayerState.value = WearLocalPlayerState()
        _localThemePalette.value = null
        _localPaletteSeedArgb.value = null
        _localAlbumArt.value = null
        _localQueueState.value = WearLocalQueueState()
        currentQueueSongIds = emptyList()
        currentQueueSongsById = emptyMap()
        currentQueueItemsById = emptyMap()
        lastPaletteSongId = ""
        lastArtworkSongId = ""
        Timber.tag(TAG).d("MediaController released, WearPlaybackService stopped")
    }

    private fun updateState() {
        val player = mediaController ?: return
        val currentItem = player.currentMediaItem
        val currentLocalSong = currentItem?.mediaId?.let(currentQueueSongsById::get)
        _localPlayerState.value = WearLocalPlayerState(
            songId = currentItem?.mediaId ?: "",
            songTitle = currentItem?.mediaMetadata?.title?.toString() ?: "",
            artistName = currentItem?.mediaMetadata?.artist?.toString() ?: "",
            albumName = currentItem?.mediaMetadata?.albumTitle?.toString() ?: "",
            isPlaying = player.isPlaying,
            currentPositionMs = player.currentPosition,
            totalDurationMs = player.duration.coerceAtLeast(0L),
            isFavorite = currentLocalSong?.isFavorite == true,
            canToggleFavorite = currentLocalSong != null && currentItem.mediaId !in transientSongIds,
            isShuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
        updateQueueState(currentIndex = player.currentMediaItemIndex)
        updatePaletteForSong(currentItem?.mediaId.orEmpty())
        updateArtworkForSong(currentItem?.mediaId.orEmpty())
    }

    private fun clearTransientPlaybackArtifacts() {
        transientCleanupPaths.forEach { path ->
            runCatching { File(path).delete() }
                .onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to delete transient playback artifact: %s", path)
                }
        }
        transientCleanupPaths = emptySet()
        transientSongIds = emptySet()
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            var ticksSinceLastPersist = 0
            while (isActive) {
                // Skip the StateFlow churn when the user can't see the UI: the
                // ExoPlayer keeps tracking position internally, we just don't
                // need to repaint composables. We still wake every second to
                // notice the screen turning back on quickly, but we don't run
                // the (allocating) updateState() pipeline.
                if (WearLifecycleState.isInteractiveNow) {
                    updateState()
                }
                // Coarser than the 1s UI tick: a DataStore write every second would be real,
                // pointless disk I/O on a device this battery-constrained. Losing up to
                // PERSIST_INTERVAL_TICKS seconds of position on a crash is an acceptable
                // trade — onIsPlayingChanged/onMediaItemTransition already persist immediately
                // on the events that matter most (a pause or a track change right before a
                // crash won't be lost).
                ticksSinceLastPersist++
                if (ticksSinceLastPersist >= PERSIST_INTERVAL_TICKS) {
                    ticksSinceLastPersist = 0
                    persistCurrentPlaybackState()
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun persistCurrentPlaybackState() {
        val player = mediaController ?: return
        if (currentQueueSongIds.isEmpty()) return
        val snapshot = PersistedLocalPlaybackState(
            queueSongIds = currentQueueSongIds,
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            updatedAtMillis = System.currentTimeMillis(),
        )
        scope.launch {
            runCatching { playbackStatePersistence.save(snapshot) }
                .onFailure { error -> Timber.tag(TAG).w(error, "Failed to persist local playback state") }
        }
    }

    private fun clearPersistedPlaybackState() {
        scope.launch {
            runCatching { playbackStatePersistence.clear() }
                .onFailure { error -> Timber.tag(TAG).w(error, "Failed to clear persisted local playback state") }
        }
    }

    /**
     * Restores a persisted queue, paused, if one exists and is still fresh enough
     * ([isPersistedLocalPlaybackStateRestorable]) — the recovery path for a process that died
     * mid-playback (see this class's KDoc). Paused rather than auto-playing: starting audio
     * without a fresh user gesture on app open would be surprising, especially for headphones
     * that may no longer even be in the user's ears.
     *
     * Safe to call unconditionally on startup: a no-op if nothing is local-playback-active
     * to restore, and it never overwrites an already-active queue.
     */
    suspend fun restorePersistedPlaybackIfAvailable(): Boolean {
        if (_isLocalPlaybackActive.value) return false
        val persisted = playbackStatePersistence.read() ?: return false
        if (!isPersistedLocalPlaybackStateRestorable(persisted, System.currentTimeMillis())) {
            playbackStatePersistence.clear()
            return false
        }

        val songsById = persisted.queueSongIds
            .mapNotNull { songId -> localSongDao.getSongById(songId) }
            .associateBy { it.songId }
        // Songs may have been deleted from the watch since the snapshot was taken (storage
        // pressure, the user removing a download) — only resume the ones that are still there,
        // in their original relative order.
        val playableSongs = persisted.queueSongIds.mapNotNull { songsById[it] }
        if (playableSongs.isEmpty()) {
            playbackStatePersistence.clear()
            return false
        }

        val originalIndexSongId = persisted.queueSongIds.getOrNull(persisted.currentIndex)
        val originalIndexInPlayable = playableSongs.indexOfFirst { it.songId == originalIndexSongId }
        val restoredIndex = originalIndexInPlayable.coerceAtLeast(0)

        playLocalSongs(
            songs = playableSongs,
            startIndex = restoredIndex,
            // The saved position belongs to the song that was playing, so it only travels with
            // it: when that song is one of the deleted ones we fall back to the first remaining
            // track, and seeking that to the old position would resume a different song
            // mid-way — or past its end entirely, which just ends playback immediately.
            startPositionMs = if (originalIndexInPlayable >= 0) persisted.positionMs else 0L,
            autoPlay = false,
        )
        return true
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun updateQueueState(currentIndex: Int? = null) {
        val player = mediaController
        val rawCurrentIndex = currentIndex ?: mediaController?.currentMediaItemIndex ?: -1
        val visibleQueueIndices = when {
            player == null -> {
                if (rawCurrentIndex in currentQueueSongIds.indices) {
                    (rawCurrentIndex until currentQueueSongIds.size).toList()
                } else {
                    currentQueueSongIds.indices.toList()
                }
            }

            rawCurrentIndex !in 0 until player.mediaItemCount -> {
                (0 until player.mediaItemCount).toList()
            }

            else -> buildVisibleQueueIndices(player, rawCurrentIndex)
        }

        val queueItems = visibleQueueIndices.mapNotNull { index ->
            val mediaItem = player?.getMediaItemAt(index)
            val songId = mediaItem?.mediaId ?: currentQueueSongIds.getOrNull(index) ?: return@mapNotNull null
            val queueItem = currentQueueItemsById[songId]
            val title = queueItem?.title
                ?: mediaItem?.mediaMetadata?.title?.toString()
                ?: return@mapNotNull null
            val artist = queueItem?.artist ?: mediaItem?.mediaMetadata?.artist?.toString().orEmpty()
            val album = queueItem?.album ?: mediaItem?.mediaMetadata?.albumTitle?.toString().orEmpty()
            val subtitle = when {
                index == rawCurrentIndex -> {
                    val supportingText = artist.ifBlank { album }
                    if (supportingText.isBlank()) {
                        "Playing on watch"
                    } else {
                        "Playing · $supportingText"
                    }
                }

                artist.isNotBlank() -> artist
                else -> album
            }

            WearLibraryItem(
                id = index.toString(),
                title = title,
                subtitle = subtitle,
                type = WearLibraryItem.TYPE_SONG,
            )
        }

        val resolvedCurrentIndex = if (rawCurrentIndex in currentQueueSongIds.indices && queueItems.isNotEmpty()) {
            0
        } else {
            -1
        }

        _localQueueState.value = WearLocalQueueState(
            items = queueItems,
            currentIndex = resolvedCurrentIndex,
        )
    }

    private fun buildVisibleQueueIndices(player: Player, currentIndex: Int): List<Int> {
        if (currentIndex !in 0 until player.mediaItemCount) {
            return (0 until player.mediaItemCount).toList()
        }

        val visibleIndices = mutableListOf(currentIndex)
        val visited = hashSetOf(currentIndex)
        val timeline = player.currentTimeline
        var nextIndex = timeline.getNextWindowIndex(
            currentIndex,
            Player.REPEAT_MODE_OFF,
            player.shuffleModeEnabled,
        )

        while (nextIndex != C.INDEX_UNSET && visited.add(nextIndex)) {
            visibleIndices += nextIndex
            nextIndex = timeline.getNextWindowIndex(
                nextIndex,
                Player.REPEAT_MODE_OFF,
                player.shuffleModeEnabled,
            )
        }

        return visibleIndices
    }

    private fun updatePaletteForSong(songId: String) {
        if (songId.isBlank() || !performanceSettings.dynamicColorTheming.value) {
            // Also reset lastPaletteSongId when the toggle is off (not just for a blank songId):
            // otherwise re-enabling it later without a song change would leave it pointing at a
            // song that's technically "already handled" and skip re-extracting the seed.
            lastPaletteSongId = ""
            _localThemePalette.value = null
            _localPaletteSeedArgb.value = null
            return
        }
        if (songId == lastPaletteSongId) return
        lastPaletteSongId = songId

        val queueSong = currentQueueSongsById[songId]
        val cachedThemePalette = queueSong?.themePaletteJson
            ?.takeIf { it.isNotBlank() }
            ?.let { encodedPalette ->
                runCatching { json.decodeFromString<WearThemePalette>(encodedPalette) }
                    .onFailure { error ->
                        Timber.tag(TAG).w(error, "Failed to decode persisted Wear palette")
                    }
                    .getOrNull()
            }
        val cachedSeed = queueSong?.paletteSeedArgb
        _localThemePalette.value = cachedThemePalette
        if (cachedThemePalette != null || cachedSeed != null) {
            _localPaletteSeedArgb.value = cachedSeed
            return
        }

        _localThemePalette.value = null
        _localPaletteSeedArgb.value = null
        if (queueSong != null) {
            scope.launch(Dispatchers.IO) {
                val extractedSeed = extractSeedFromLocalSong(queueSong)
                if (extractedSeed != null) {
                    runCatching { localSongDao.updatePaletteSeed(queueSong.songId, extractedSeed) }
                        .onFailure { error ->
                            Timber.tag(TAG).w(error, "Failed to persist local palette seed")
                        }
                }

                withContext(Dispatchers.Main) {
                    if (lastPaletteSongId != queueSong.songId) return@withContext
                    if (extractedSeed != null) {
                        currentQueueSongsById = currentQueueSongsById.toMutableMap().apply {
                            put(queueSong.songId, queueSong.copy(paletteSeedArgb = extractedSeed))
                        }
                    }
                    _localPaletteSeedArgb.value = extractedSeed
                }
            }
            return
        }

        val queueItem = currentQueueItemsById[songId] ?: return
        scope.launch(Dispatchers.IO) {
            val extractedSeed = extractSeedFromUri(queueItem.uri, queueItem.songId)
            withContext(Dispatchers.Main) {
                if (lastPaletteSongId != queueItem.songId) return@withContext
                _localThemePalette.value = null
                _localPaletteSeedArgb.value = extractedSeed
            }
        }
    }

    private fun updateArtworkForSong(songId: String) {
        if (songId.isBlank() || !performanceSettings.showAlbumArt.value) {
            // Same reasoning as updatePaletteForSong: reset lastArtworkSongId even when the
            // toggle (not a blank songId) is why we're bailing, so a later re-enable without a
            // song change still triggers a fresh decode instead of being silently skipped.
            lastArtworkSongId = ""
            _localAlbumArt.value = null
            return
        }
        if (songId == lastArtworkSongId) return
        lastArtworkSongId = songId

        val queueSong = currentQueueSongsById[songId]
        if (queueSong != null) {
            scope.launch(Dispatchers.IO) {
                val bitmap = loadLocalAlbumArtBitmap(queueSong)
                withContext(Dispatchers.Main) {
                    if (lastArtworkSongId != queueSong.songId) return@withContext
                    _localAlbumArt.value = bitmap
                }
            }
            return
        }

        val queueItem = currentQueueItemsById[songId]
        if (queueItem == null) {
            _localAlbumArt.value = null
            return
        }
        scope.launch(Dispatchers.IO) {
            val bitmap = loadArtworkBitmapFromUri(queueItem.uri, queueItem.songId)
            withContext(Dispatchers.Main) {
                if (lastArtworkSongId != queueItem.songId) return@withContext
                _localAlbumArt.value = bitmap
            }
        }
    }

    private fun loadLocalAlbumArtBitmap(song: LocalSongEntity): Bitmap? {
        val fromStoredArtwork = song.artworkPath
            ?.takeIf { it.isNotBlank() }
            ?.let { artworkPath ->
                decodeBoundedBitmapFromFile(artworkPath, maxDimension = 1024)
            }
        if (fromStoredArtwork != null) return fromStoredArtwork

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(song.localPath)
            val embedded = retriever.embeddedPicture ?: return null
            decodeBoundedBitmapFromBytes(embedded, maxDimension = 1024)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load local artwork for songId=${song.songId}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeBoundedBitmapFromFile(path: String, maxDimension: Int): Bitmap? {
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        var sampleSize = 1
        while (
            (srcWidth / sampleSize) > maxDimension * 2 ||
            (srcHeight / sampleSize) > maxDimension * 2
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            },
        )
    }

    private fun decodeBoundedBitmapFromBytes(data: ByteArray, maxDimension: Int): Bitmap? {
        if (data.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        var sampleSize = 1
        while (
            (srcWidth / sampleSize) > maxDimension * 2 ||
            (srcHeight / sampleSize) > maxDimension * 2
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeByteArray(
            data,
            0,
            data.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            },
        )
    }

    private fun extractSeedFromLocalSong(song: LocalSongEntity): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(song.localPath)
            val embedded = retriever.embeddedPicture ?: return null
            val bitmap = BitmapFactory.decodeByteArray(
                embedded,
                0,
                embedded.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = 2
                },
            ) ?: return null

            try {
                extractSeedColorArgb(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to extract local artwork seed for songId=${song.songId}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractSeedFromUri(uri: Uri, songId: String): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(application, uri)
            val embedded = retriever.embeddedPicture ?: return null
            val bitmap = BitmapFactory.decodeByteArray(
                embedded,
                0,
                embedded.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = 2
                },
            ) ?: return null

            try {
                extractSeedColorArgb(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to extract artwork seed from URI for songId=$songId")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun loadArtworkBitmapFromUri(uri: Uri, songId: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(application, uri)
            val embedded = retriever.embeddedPicture ?: return null
            decodeBoundedBitmapFromBytes(embedded, maxDimension = 1024)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load artwork from URI for songId=$songId")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractSeedColorArgb(bitmap: Bitmap): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        val step = (minOf(bitmap.width, bitmap.height) / 24).coerceAtLeast(1)
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var count = 0L

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 28) {
                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)
                    if (red + green + blue > 36) {
                        redSum += red
                        greenSum += green
                        blueSum += blue
                        count++
                    }
                }
                x += step
            }
            y += step
        }

        if (count == 0L) return null
        return Color.rgb(
            (redSum / count).toInt(),
            (greenSum / count).toInt(),
            (blueSum / count).toInt(),
        )
    }
}
