package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.service.cast.CastRemotePlaybackState
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.worker.SyncManager
import com.theveloper.pixelplay.utils.AppShortcutManager
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.theveloper.pixelplay.utils.QueueUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import timber.log.Timber

private const val CAST_LOG_TAG = "PlayerCastTransfer"
private const val SONG_ID_QUERY_CHUNK_SIZE = 900
private val LOCAL_PLAYBACK_SCHEMES = setOf("content", "file", "android.resource")

/**
 * Callbacks supplied by [PlayerViewModel] so the playback dispatch core can reach
 * ViewModel-owned state (the media controller, the UI state, the player sheet,
 * toasts/dialog events, the crossfade transition job, listening stats, and
 * predictive back) without [PlaybackDispatchStateHolder] depending on the
 * ViewModel. Stored once via [PlaybackDispatchStateHolder.initialize], mirroring
 * the pattern used by SleepTimerStateHolder/AiStateHolder/CastTransferStateHolder.
 */
class PlaybackDispatchCallbacks(
    val scope: CoroutineScope,
    val getController: () -> MediaController?,
    val getUiState: () -> PlayerUiState,
    val updateUiState: ((PlayerUiState) -> PlayerUiState) -> Unit,
    val showSheet: () -> Unit,
    val collapseSheetState: () -> Unit,
    val showPlayer: () -> Unit,
    val sendToast: (String) -> Unit,
    val emitToast: suspend (String) -> Unit,
    val showNoInternetDialog: () -> Unit,
    val ensureTelegramObservers: () -> Unit,
    val cancelTransitionScheduler: () -> Unit,
    val incrementSongScore: (Song) -> Unit,
    val resetPredictiveBackState: () -> Unit,
)

private data class PreparedPlaybackQueueSegments(
    val beforeCurrent: List<MediaItem>,
    val afterCurrent: List<MediaItem>,
    val currentIndex: Int
)

/**
 * Owns the deep playback dispatch core extracted from [PlayerViewModel]: turning a
 * song selection into a controller/cast queue and starting playback. Covers the
 * full-queue (library/favorites) and direct request token machinery, queue-context
 * reuse, hydration, queue-segment batching, external-URI playback, the shuffle-all
 * tile entry point, and the "preparing playback" pill state.
 */
@OptIn(UnstableApi::class)
@ViewModelScoped
class PlaybackDispatchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val dualPlayerEngine: DualPlayerEngine,
    private val appShortcutManager: AppShortcutManager,
    private val syncManager: SyncManager,
    private val externalMediaStateHolder: ExternalMediaStateHolder,
    private val playbackStateHolder: PlaybackStateHolder,
    private val queueStateHolder: QueueStateHolder,
    private val libraryStateHolder: LibraryStateHolder,
    private val castStateHolder: CastStateHolder,
    private val castTransferStateHolder: CastTransferStateHolder,
    private val connectivityStateHolder: ConnectivityStateHolder,
    private val themeStateHolder: ThemeStateHolder,
    @param:ApplicationContext private val context: Context,
) {

    private lateinit var cb: PlaybackDispatchCallbacks

    fun initialize(callbacks: PlaybackDispatchCallbacks) {
        cb = callbacks
    }

    // Token + job machinery guarding the two kinds of playback requests so that a
    // newer request always wins over an in-flight older one.
    private var fullQueuePlaybackJob: Job? = null
    private var fullQueuePlaybackToken: Long = 0L
    private var directPlaybackJob: Job? = null
    private var directPlaybackToken: Long = 0L
    private var pendingQueueSegmentsJob: Job? = null
    private var remoteQueueLoadJob: Job? = null

    // Playback action parked until the MediaController finishes connecting.
    private var pendingPlaybackAction: (() -> Unit)? = null

    /** Invoked by the ViewModel when the MediaController connects. */
    fun flushPendingPlaybackAction() {
        pendingPlaybackAction?.invoke()
        pendingPlaybackAction = null
    }

    fun onCleared() {
        remoteQueueLoadJob?.cancel()
    }

    fun showAndPlaySongFromLibrary(
        song: Song,
        queueName: String = "Library",
        isVoluntaryPlay: Boolean = true
    ) {
        launchLatestFullQueuePlayback(
            song = song,
            queueName = queueName,
            isVoluntaryPlay = isVoluntaryPlay,
            failureMessage = "Failed to build full library queue for songId=%s"
        ) {
            val sortOption = cb.getUiState().currentSongSortOption
            val storageFilter = cb.getUiState().currentStorageFilter
            musicRepository.getSongIdsSorted(sortOption, storageFilter)
        }
    }

    fun showAndPlaySongFromFavorites(
        song: Song,
        queueName: String = "Liked Songs",
        isVoluntaryPlay: Boolean = true
    ) {
        launchLatestFullQueuePlayback(
            song = song,
            queueName = queueName,
            isVoluntaryPlay = isVoluntaryPlay,
            failureMessage = "Failed to build favorites queue for songId=%s"
        ) {
            val sortOption = cb.getUiState().currentFavoriteSortOption
            val storageFilter = cb.getUiState().currentStorageFilter
            musicRepository.getFavoriteSongIdsSorted(sortOption, storageFilter)
        }
    }

    suspend fun getSongsForCurrentLibrarySelection(): List<Song> {
        val sortOption = cb.getUiState().currentSongSortOption
        val storageFilter = cb.getUiState().currentStorageFilter
        val sortedIds = musicRepository.getSongIdsSorted(sortOption, storageFilter)
        return resolvePlaybackQueueFromSortedIds(sortedIds)
    }

    suspend fun getSongsForCurrentFavoriteSelection(): List<Song> {
        val sortOption = cb.getUiState().currentFavoriteSortOption
        val storageFilter = cb.getUiState().currentStorageFilter
        val sortedIds = musicRepository.getFavoriteSongIdsSorted(sortOption, storageFilter)
        return resolvePlaybackQueueFromSortedIds(sortedIds)
    }

    private fun launchLatestFullQueuePlayback(
        song: Song,
        queueName: String,
        isVoluntaryPlay: Boolean,
        failureMessage: String,
        sortedIdsProvider: suspend () -> List<Long>
    ) {
        cancelPendingFullQueuePlayback()
        cancelPendingDirectPlayback()
        val requestToken = fullQueuePlaybackToken

        fullQueuePlaybackJob = cb.scope.launch {
            try {
                val sortedIds = sortedIdsProvider()
                throwIfFullQueuePlaybackRequestIsStale(requestToken)

                val fullQueue = resolvePlaybackQueueFromSortedIds(sortedIds)
                throwIfFullQueuePlaybackRequestIsStale(requestToken)

                showAndPlaySong(
                    song = song,
                    contextSongs = fullQueue.ifEmpty { listOf(song) },
                    queueName = queueName,
                    isVoluntaryPlay = isVoluntaryPlay,
                    cancelPendingQueueBuild = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestToken != fullQueuePlaybackToken) {
                    return@launch
                }

                Timber.e(error, failureMessage, song.id)
                val fallbackQueue = libraryStateHolder.allSongs.value.takeIf { songs ->
                    songs.isNotEmpty() && songs.any { it.id == song.id }
                } ?: listOf(song)
                showAndPlaySong(
                    song = song,
                    contextSongs = fallbackQueue,
                    queueName = queueName,
                    isVoluntaryPlay = isVoluntaryPlay,
                    cancelPendingQueueBuild = false
                )
            }
        }
    }

    fun cancelPendingFullQueuePlayback() {
        fullQueuePlaybackToken += 1L
        fullQueuePlaybackJob?.cancel()
        fullQueuePlaybackJob = null
    }

    private fun throwIfFullQueuePlaybackRequestIsStale(requestToken: Long) {
        if (requestToken != fullQueuePlaybackToken) {
            throw CancellationException("Stale full-queue playback request")
        }
    }

    private fun beginDirectPlaybackRequest(): Long {
        directPlaybackToken += 1L
        directPlaybackJob?.cancel()
        directPlaybackJob = null
        pendingQueueSegmentsJob?.cancel()
        pendingQueueSegmentsJob = null
        return directPlaybackToken
    }

    private fun cancelPendingDirectPlayback() {
        cancelPendingDirectPlaybackBuild()
        pendingQueueSegmentsJob?.cancel()
        pendingQueueSegmentsJob = null
    }

    private fun cancelPendingDirectPlaybackBuild() {
        directPlaybackToken += 1L
        directPlaybackJob?.cancel()
        directPlaybackJob = null
    }

    private fun throwIfDirectPlaybackRequestIsStale(requestToken: Long) {
        if (requestToken != directPlaybackToken) {
            throw CancellationException("Stale direct playback request")
        }
    }

    private suspend fun resolvePlaybackQueueFromSortedIds(sortedIds: List<Long>): List<Song> {
        if (sortedIds.isEmpty()) return emptyList()

        val orderedIds = sortedIds.map(Long::toString)
        val cachedSongsById = libraryStateHolder.allSongsById.value
        val missingIds = ArrayList<String>()
        val cachedQueue = ArrayList<Song>(orderedIds.size)

        withContext(Dispatchers.Default) {
            orderedIds.forEach { songId ->
                val cachedSong = cachedSongsById[songId]
                if (cachedSong != null) {
                    cachedQueue.add(cachedSong)
                } else {
                    missingIds.add(songId)
                }
            }
        }

        if (missingIds.isEmpty()) {
            return cachedQueue
        }

        val missingSongsById = getSongsByIdsChunked(missingIds).associateBy { it.id }
        return withContext(Dispatchers.Default) {
            val finalQueue = ArrayList<Song>(orderedIds.size)
            orderedIds.forEach { songId ->
                val resolvedSong = cachedSongsById[songId] ?: missingSongsById[songId]
                if (resolvedSong != null) {
                    finalQueue.add(resolvedSong)
                }
            }
            finalQueue
        }
    }

    private suspend fun getSongsByIdsChunked(songIds: List<String>): List<Song> {
        if (songIds.isEmpty()) return emptyList()
        if (songIds.size <= SONG_ID_QUERY_CHUNK_SIZE) {
            return musicRepository.getSongsByIds(songIds).first()
        }

        return withContext(Dispatchers.IO) {
            buildList(songIds.size) {
                songIds.chunked(SONG_ID_QUERY_CHUNK_SIZE).forEach { chunk ->
                    addAll(musicRepository.getSongsByIds(chunk).first())
                }
            }
        }
    }

    fun showAndPlaySong(
        song: Song,
        contextSongs: List<Song>,
        queueName: String = "Current Context",
        isVoluntaryPlay: Boolean = true,
        cancelPendingQueueBuild: Boolean = true,
        playlistId: String? = null,
        indexInQueue: Int? = null
    ) {
        if (cancelPendingQueueBuild) {
            cancelPendingFullQueuePlayback()
        }
        val playbackContext =
            if (contextSongs.any { it.id == song.id }) contextSongs else listOf(song)
        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            val remoteMediaClient = castSession.remoteMediaClient!!
            val mediaStatus = remoteMediaClient.mediaStatus
            val desiredQueue = playbackContext
            val lastRemoteQueue = castTransferStateHolder.lastRemoteQueue
            val contextMatchesRemoteSnapshot = lastRemoteQueue.matchesSongOrder(desiredQueue)
            val targetIndexInDesiredQueue = desiredQueue.indexOfFirst { it.id == song.id }

            val currentRemoteId = mediaStatus
                ?.let { status ->
                    status.getQueueItemById(status.getCurrentItemId())
                        ?.customData?.optString("songId")
                        ?.takeIf { it.isNotBlank() }
                } ?: castTransferStateHolder.lastRemoteSongId

            val itemIdFromStatus = mediaStatus
                ?.queueItems
                ?.firstOrNull { it.customData?.optString("songId") == song.id }
                ?.itemId

            val targetItemId = itemIdFromStatus?.takeIf { it > 0 }
            val canJumpInCurrentRemoteQueue = contextMatchesRemoteSnapshot && targetIndexInDesiredQueue >= 0 && targetItemId != null

            when {
                canJumpInCurrentRemoteQueue -> {
                    // Same queue context: jump directly for immediate, deterministic song changes.
                    remoteQueueLoadJob?.cancel()
                    castTransferStateHolder.markPendingRemoteSong(song)
                    val itemId = requireNotNull(targetItemId)
                    castStateHolder.castPlayer?.jumpToItem(itemId, 0L)
                }
                contextMatchesRemoteSnapshot && currentRemoteId == song.id -> {
                    // Already on target.
                    remoteQueueLoadJob?.cancel()
                    castTransferStateHolder.markPendingRemoteSong(song)
                }
                else -> {
                    // Queue context changed: perform a single remote queue load.
                    remoteQueueLoadJob?.cancel()
                    remoteQueueLoadJob = cb.scope.launch {
                        val hydratedQueue = hydrateSongsIfNeeded(desiredQueue)
                        if (hydratedQueue.isEmpty()) return@launch
                        val hydratedStartSong =
                            hydratedQueue.firstOrNull { it.id == song.id } ?: hydratedQueue.first()
                        val loaded = castTransferStateHolder.playRemoteQueue(
                            songsToPlay = hydratedQueue,
                            startSong = hydratedStartSong,
                            isShuffleEnabled = playbackStateHolder.stablePlayerState.value.isShuffleEnabled
                        )
                        if (!loaded) {
                            Timber.tag(CAST_LOG_TAG).w(
                                "Failed to load requested remote queue (songId=%s size=%d).",
                                song.id,
                                desiredQueue.size
                            )
                        }
                    }
                }
            }

            if (isVoluntaryPlay) {
                cb.incrementSongScore(song)
                if (playlistId != null && queueName != "None") {
                    appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
                }
            }
            return
        }    // Local playback logic
        val controller = cb.getController()
        val currentQueue = cb.getUiState().currentPlaybackQueue
        val songIndexInQueue = indexInQueue ?: currentQueue.indexOfFirst { it.id == song.id }
        val queueMatchesContext = currentQueue.matchesSongOrder(playbackContext)
        val reusableTargetIndex = if (
            controller != null &&
            controller.isConnected &&
            !dualPlayerEngine.isTransitionRunning() &&
            songIndexInQueue != -1 &&
            queueMatchesContext
        ) {
            controller.resolveReusablePlaybackTargetIndex(
                songIndexInQueue = songIndexInQueue,
                songId = song.id,
                isExplicitQueueTarget = indexInQueue != null
            )
        } else {
            null
        }

        if (controller != null && reusableTargetIndex != null) {
            cancelPendingDirectPlaybackBuild()
            playLoadedControllerItem(controller, reusableTargetIndex)
            if (isVoluntaryPlay) {
                cb.incrementSongScore(song)
                if (playlistId != null && queueName != "None") {
                    appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
                }
            }
        } else {
            if (isVoluntaryPlay) cb.incrementSongScore(song)
            playSongs(playbackContext, song, queueName, playlistId)
        }
        cb.resetPredictiveBackState()
    }

    fun showAndPlaySong(song: Song) {
        Timber.tag("ShuffleDebug").d("showAndPlaySong (single song overload) called for '${song.title}'")
        val castSession = castStateHolder.castSession.value
        val contextSongs = if (castSession != null && castSession.remoteMediaClient != null) {
            libraryStateHolder.allSongs.value.takeIf { songs ->
                songs.isNotEmpty() && songs.any { it.id == song.id }
            } ?: listOf(song)
        } else {
            listOf(song)
        }
        showAndPlaySong(song, contextSongs, "Library")
    }

    private fun List<Song>.matchesSongOrder(contextSongs: List<Song>): Boolean {
        if (size != contextSongs.size) return false
        return indices.all { this[it].id == contextSongs[it].id }
    }

    private fun MediaController.resolveReusablePlaybackTargetIndex(
        songIndexInQueue: Int,
        songId: String,
        isExplicitQueueTarget: Boolean = false
    ): Int? {
        if (!isExplicitQueueTarget) {
            currentMediaItem?.takeIf { it.mediaId == songId }?.let {
                return currentMediaItemIndex.takeIf { index -> index != C.INDEX_UNSET } ?: 0
            }
        }

        if (songIndexInQueue !in 0 until mediaItemCount) return null

        val mediaIdAtTarget = runCatching { getMediaItemAt(songIndexInQueue).mediaId }.getOrNull()
        return songIndexInQueue.takeIf { mediaIdAtTarget == songId }
    }

    private fun playLoadedControllerItem(controller: MediaController, targetIndex: Int) {
        val shouldSeekToStart =
            controller.currentMediaItemIndex != targetIndex ||
                controller.playbackState == Player.STATE_ENDED

        if (shouldSeekToStart) {
            controller.seekTo(targetIndex, 0L)
        }
        if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
            controller.prepare()
        }
        controller.play()
    }

    fun songRequiresHydration(song: Song): Boolean = song.requiresHydration()

    private fun Song.requiresHydration(): Boolean {
        return contentUriString.isBlank()
    }

    suspend fun hydrateSongsIfNeeded(songs: List<Song>): List<Song> {
        if (songs.isEmpty() || songs.none { it.requiresHydration() }) return songs
        val hydratedSongs = getSongsByIdsChunked(songs.map { it.id })
        if (hydratedSongs.isEmpty()) return songs
        val hydratedById = hydratedSongs.associateBy { it.id }
        return songs.mapNotNull { original ->
            hydratedById[original.id] ?: original.takeIf { !original.requiresHydration() }
        }
    }

    fun playSongs(songsToPlay: List<Song>, startSong: Song, queueName: String = "None", playlistId: String? = null) {
        cancelPendingFullQueuePlayback()
        val requestToken = beginDirectPlaybackRequest()
        directPlaybackJob = cb.scope.launch {
            cb.cancelTransitionScheduler()

            val validSongs = hydrateSongsIfNeeded(songsToPlay)
            throwIfDirectPlaybackRequestIsStale(requestToken)

            if (validSongs.isEmpty()) {
                cb.emitToast(context.getString(R.string.player_view_model_no_valid_songs))
                return@launch
            }

            // Adjust startSong if it was filtered out
            val validStartSong =
                validSongs.firstOrNull { it.id == startSong.id } ?: validSongs.first()

            // Offline check for the starting song if it is a Telegram song
            if (validStartSong.contentUriString.startsWith("telegram:")) {
                cb.ensureTelegramObservers()
                val isOnline = connectivityStateHolder.isOnline.value
                val fileId = validStartSong.telegramFileId

                Timber.d("Offline Check: fileId=$fileId, contentUri=${validStartSong.contentUriString}, isOnline=$isOnline")

                if (!isOnline) {
                     if (fileId != null) {
                         val isCached = musicRepository.telegramRepository.isFileCached(fileId)
                         Timber.d("Offline Check: isCached=$isCached")
                         throwIfDirectPlaybackRequestIsStale(requestToken)
                         if (!isCached) {
                             Timber.w("Blocked playback: Offline and not cached.")
                             cb.showNoInternetDialog()
                             return@launch
                         }
                     }
                }
            }

            // Store the original order so we can "unshuffle" later if the user turns shuffle off
            queueStateHolder.setOriginalQueueOrder(validSongs)
            queueStateHolder.saveOriginalQueueState(validSongs, queueName)

            // Check if the user wants shuffle to be persistent across different albums
            val isPersistent = userPreferencesRepository.persistentShuffleEnabledFlow.first()
            throwIfDirectPlaybackRequestIsStale(requestToken)
            // Check if shuffle is currently active in the player
            val isShuffleOn = playbackStateHolder.stablePlayerState.value.isShuffleEnabled

            // If Persistent Shuffle is OFF, we reset shuffle to "false" every time a new album starts
            if (!isPersistent) {
                playbackStateHolder.updateStablePlayerState { it.copy(isShuffleEnabled = false) }
            }

            // If shuffle is persistent and currently ON, we shuffle the new songs immediately
            val finalSongsToPlay = if (isPersistent && isShuffleOn) {
                // Shuffle the list but make sure the song you clicked stays at its current index or starts first
                withContext(Dispatchers.Default) {
                    QueueUtils.buildAnchoredShuffleQueueSuspending(
                        validSongs,
                        validSongs.indexOfFirst { it.id == validStartSong.id }.coerceAtLeast(0)
                    )
                }
            } else {
                // Otherwise, just use the normal sequential order
                validSongs
            }
            throwIfDirectPlaybackRequestIsStale(requestToken)

            // Send the final list (shuffled or not) to the player engine
            internalPlaySongs(finalSongsToPlay, validStartSong, queueName, playlistId)
            if (requestToken == directPlaybackToken) {
                directPlaybackJob = null
            }
        }
    }

    // Start playback with shuffle enabled in one coroutine to avoid racing queue updates
    fun playSongsShuffled(
        songsToPlay: List<Song>,
        queueName: String = "None",
        playlistId: String? = null,
        startAtZero: Boolean = false
    ) {
        cancelPendingFullQueuePlayback()
        val requestToken = beginDirectPlaybackRequest()
        directPlaybackJob = cb.scope.launch {
            val result = queueStateHolder.prepareShuffledQueueSuspending(songsToPlay, queueName, startAtZero)
            throwIfDirectPlaybackRequestIsStale(requestToken)
            if (result == null) {
                cb.sendToast(context.getString(R.string.player_view_model_no_songs_to_shuffle))
                return@launch
            }

            val (shuffledQueue, startSong) = result
            cb.cancelTransitionScheduler()

            // Optimistically update shuffle state
            playbackStateHolder.updateStablePlayerState { it.copy(isShuffleEnabled = true) }
            launch { userPreferencesRepository.setShuffleOn(true) }

            internalPlaySongs(shuffledQueue, startSong, queueName, playlistId)
            if (requestToken == directPlaybackToken) {
                directPlaybackJob = null
            }
        }
    }

    fun playExternalUri(uri: Uri) {
        cb.scope.launch {
            val externalResult = externalMediaStateHolder.buildExternalSongFromUri(uri)
            if (externalResult == null) {
                cb.sendToast(context.getString(R.string.external_playback_error))
                return@launch
            }

            cb.cancelTransitionScheduler()

            val queueSongs = externalMediaStateHolder.buildExternalQueue(externalResult, uri)
            val immutableQueue = queueSongs.toPlaybackQueue()

            cb.updateUiState { state ->
                state.copy(
                    currentPlaybackQueue = immutableQueue,
                    currentQueueSourceName = context.getString(R.string.external_queue_label),
                    showDismissUndoBar = false,
                    dismissedSong = null,
                    dismissedQueue = persistentListOf(),
                    dismissedQueueName = "",
                    dismissedPosition = 0L
                )
            }
            playbackStateHolder.setCurrentPosition(0L)

            playbackStateHolder.updateStablePlayerState { state ->
                state.copy(
                    currentSong = externalResult.song,
                    isPlaying = true,
                    playWhenReady = true,
                    totalDuration = externalResult.song.duration,
                    lyrics = null,
                    isLoadingLyrics = false
                )
            }

            cb.collapseSheetState()
            cb.showSheet()

            internalPlaySongs(queueSongs, externalResult.song, context.getString(R.string.external_queue_label), null)
            cb.showPlayer()
        }
    }

    fun triggerShuffleAllFromTile() {
        Timber.d("[TileDebug] triggerShuffleAllFromTile called. mediaController=${cb.getController() != null}")
        val action: () -> Unit = {
            Timber.d("[TileDebug] action() invoked")
            cb.scope.launch {
                var songs = musicRepository.getRandomSongs(limit = 10000)
                Timber.d("[TileDebug] Repository returned ${songs.size} random songs immediately")

                if (songs.isEmpty()) {
                    // Cold start or stale DB state: trigger a sync and retry the bounded query.
                    Timber.d("[TileDebug] No songs available yet, triggering sync and retrying repository sample")
                    syncManager.sync()
                    songs = withTimeoutOrNull(30_000L) {
                        var refreshedSongs = emptyList<Song>()
                        while (refreshedSongs.isEmpty()) {
                            refreshedSongs = musicRepository.getRandomSongs(limit = 10000)
                            if (refreshedSongs.isEmpty()) {
                                delay(500L)
                            }
                        }
                        refreshedSongs
                    }
                        ?: emptyList()
                    Timber.d("[TileDebug] After retry, repository returned ${songs.size} songs")
                }

                if (songs.isNotEmpty()) {
                    Timber.d("[TileDebug] Calling playSongsShuffled with ${songs.size} songs")
                    playSongsShuffled(songs, "All Songs (Shuffled)", startAtZero = true)
                } else {
                    Timber.w("[TileDebug] No songs found even after sync - library may be empty")
                    cb.sendToast(context.getString(R.string.player_view_model_no_songs_in_library_toast))
                }
            }
        }

        if (cb.getController() == null) {
            Timber.d("[TileDebug] mediaController null, queuing as pendingPlaybackAction")
            pendingPlaybackAction = action
        } else {
            Timber.d("[TileDebug] mediaController ready, calling action immediately")
            action()
        }
    }

    private fun setPreparingSong(songId: String?) {
        cb.updateUiState { state ->
            if (state.preparingSongId == songId) state else state.copy(preparingSongId = songId)
        }
    }

    private fun beginPreparingSong(song: Song) {
        // Skip the "Preparing playback…" pill for local files: they reach STATE_READY
        // in milliseconds, and transient STATE_BUFFERING from audio HAL/offload init
        // (or a re-tap of an already-loaded song) can otherwise leave the pill stuck.
        // Always write the new value (null for local, song.id for remote) so a stale
        // preparingSongId from a previous remote song cannot outlive a local track switch.
        if (!isLocalPlaybackSong(song)) {
            setPreparingSong(song.id)
        } else {
            setPreparingSong(null)
        }
        cb.scope.launch(Dispatchers.IO) {
            val albumArtUri = song.albumArtUriString
            if (albumArtUri.isNullOrBlank()) {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = null,
                    currentSongUriString = null,
                    isPreload = false
                )
            } else {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = albumArtUri.toUri(),
                    currentSongUriString = albumArtUri,
                    isPreload = false
                )
            }
        }
    }

    private fun isLocalPlaybackSong(song: Song): Boolean {
        val scheme = MediaItemBuilder.playbackUri(song).scheme?.lowercase()
        return scheme == null || scheme in LOCAL_PLAYBACK_SCHEMES
    }

    fun clearPreparingSongIfMatching(mediaId: String? = null) {
        val preparingSongId = cb.getUiState().preparingSongId ?: return
        if (mediaId == null || preparingSongId == mediaId) {
            setPreparingSong(null)
        }
    }

    private suspend fun preparePlaybackQueueSegments(
        songsToPlay: List<Song>,
        startSongId: String,
        playlistId: String?
    ): PreparedPlaybackQueueSegments = withContext(Dispatchers.Default) {
        val currentIndex = songsToPlay
            .indexOfFirst { it.id == startSongId }
            .takeIf { it >= 0 }
            ?: 0

        val beforeCurrent = List(currentIndex) { index ->
            buildPlaybackMediaItem(songsToPlay[index], playlistId)
        }
        val afterStartIndex = currentIndex + 1
        val afterCurrent = List((songsToPlay.size - afterStartIndex).coerceAtLeast(0)) { offset ->
            buildPlaybackMediaItem(songsToPlay[afterStartIndex + offset], playlistId)
        }

        PreparedPlaybackQueueSegments(
            beforeCurrent = beforeCurrent,
            afterCurrent = afterCurrent,
            currentIndex = currentIndex
        )
    }

    private suspend fun attachPreparedQueueSegmentsIfCurrent(
        player: Player,
        startSongId: String,
        preparedSegments: PreparedPlaybackQueueSegments
    ) {
        if (player.currentMediaItem?.mediaId != startSongId) return
        if (player.mediaItemCount != 1) return
        if (player.getMediaItemAt(0).mediaId != startSongId) return

        val batchSize = 200

        if (preparedSegments.beforeCurrent.isNotEmpty()) {
            var insertedCount = 0
            while (insertedCount < preparedSegments.beforeCurrent.size) {
                val end = (insertedCount + batchSize).coerceAtMost(preparedSegments.beforeCurrent.size)
                val batch = preparedSegments.beforeCurrent.subList(insertedCount, end)
                player.addMediaItems(insertedCount, batch)
                insertedCount = end
                yield()
            }
        }

        if (preparedSegments.afterCurrent.isNotEmpty()) {
            var insertedCount = 0
            while (insertedCount < preparedSegments.afterCurrent.size) {
                val end = (insertedCount + batchSize).coerceAtMost(preparedSegments.afterCurrent.size)
                val batch = preparedSegments.afterCurrent.subList(insertedCount, end)
                player.addMediaItems(preparedSegments.beforeCurrent.size + 1 + insertedCount, batch)
                insertedCount = end
                yield()
            }
        }

        playbackStateHolder.updateStablePlayerState {
            it.copy(currentMediaItemIndex = preparedSegments.currentIndex)
        }
    }

    suspend fun internalPlaySongs(songsToPlay: List<Song>, startSong: Song, queueName: String = "None", playlistId: String? = null) {
        if (songsToPlay.isEmpty()) {
            clearPreparingSongIfMatching()
            return
        }
        val effectiveStartSong = songsToPlay.firstOrNull { it.id == startSong.id } ?: songsToPlay.first()

        // Update dynamic shortcut for last played playlist
        if (playlistId != null && queueName != "None") {
            appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
        }

        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            clearPreparingSongIfMatching()
            val remoteLoaded = castTransferStateHolder.playRemoteQueue(
                songsToPlay = songsToPlay,
                startSong = effectiveStartSong,
                isShuffleEnabled = playbackStateHolder.stablePlayerState.value.isShuffleEnabled
            )

            if (!remoteLoaded) {
                Timber.tag(CAST_LOG_TAG).w(
                    "Remote queue load failed in internalPlaySongs (songId=%s queueSize=%d).",
                    effectiveStartSong.id,
                    songsToPlay.size
                )
                castSession.remoteMediaClient?.requestStatus()
                return
            }

            cb.updateUiState { it.copy(currentPlaybackQueue = songsToPlay.toPlaybackQueue(), currentQueueSourceName = queueName) }
            playbackStateHolder.updateStablePlayerState {
                it.copy(
                    currentSong = effectiveStartSong,
                    currentMediaItemIndex = 0,
                    isPlaying = true,
                    playWhenReady = true,
                    totalDuration = effectiveStartSong.duration.coerceAtLeast(0L)
                )
            }
        } else {
            beginPreparingSong(effectiveStartSong)
            cb.updateUiState {
                it.copy(
                    currentPlaybackQueue = songsToPlay.toPlaybackQueue(),
                    currentQueueSourceName = queueName
                )
            }
            playbackStateHolder.updateStablePlayerState {
                it.copy(
                    currentSong = effectiveStartSong,
                    currentMediaItemIndex = 0,
                    isPlaying = true,
                    playWhenReady = true,
                    totalDuration = effectiveStartSong.duration.coerceAtLeast(0L)
                )
            }
            cb.showSheet()

            val startMediaItem = buildResolvedPlaybackMediaItem(effectiveStartSong)

            val playSongsAction = {
                // Use Direct Engine Access to avoid TransactionTooLargeException on Binder
                dualPlayerEngine.cancelNext()
                val enginePlayer = dualPlayerEngine.masterPlayer

                enginePlayer.setMediaItem(startMediaItem, 0L)
                enginePlayer.prepare()
                enginePlayer.play()
                cb.updateUiState { it.copy(isLoadingInitialSongs = false) }

                if (songsToPlay.size > 1) {
                    pendingQueueSegmentsJob?.cancel()
                    pendingQueueSegmentsJob = cb.scope.launch {
                        val preparedSegments = preparePlaybackQueueSegments(
                            songsToPlay = songsToPlay,
                            startSongId = effectiveStartSong.id,
                            playlistId = playlistId
                        )
                        withContext(Dispatchers.Main.immediate) {
                            attachPreparedQueueSegmentsIfCurrent(
                                player = dualPlayerEngine.masterPlayer,
                                startSongId = effectiveStartSong.id,
                                preparedSegments = preparedSegments
                            )
                        }
                    }
                }
            }

            // We still check for mediaController to ensure the Service is bound and active
            // even though we aren't using it for the heavy lifting anymore.
            if (cb.getController() == null) {
                Timber.w("MediaController not available. Queuing playback action.")
                pendingPlaybackAction = playSongsAction
            } else {
                playSongsAction()
            }
        }
    }

    suspend fun buildResolvedPlaybackMediaItem(song: Song): MediaItem {
        val mediaItem = MediaItemBuilder.build(song)
        val originalUri = mediaItem.localConfiguration?.uri ?: return mediaItem
        val scheme = originalUri.scheme
        if (
            scheme != "telegram" &&
            scheme != "netease" &&
            scheme != "qqmusic" &&
            scheme != "navidrome" &&
            scheme != "jellyfin" &&
            scheme != "gdrive"
        ) {
            return mediaItem
        }

        if (scheme == "telegram") {
            cb.ensureTelegramObservers()
        }

        val resolvedUri = dualPlayerEngine.resolveCloudUri(originalUri)
        return if (resolvedUri == originalUri) {
            mediaItem
        } else {
            mediaItem.buildUpon().setUri(resolvedUri).build()
        }
    }

    fun loadAndPlaySong(song: Song) {
        cancelPendingFullQueuePlayback()
        beginPreparingSong(song)
        playbackStateHolder.updateStablePlayerState {
            it.copy(
                currentSong = song,
                isPlaying = true,
                playWhenReady = true
            )
        }
        cb.showSheet()

        val controller = cb.getController()
        if (controller == null) {
            pendingPlaybackAction = {
                loadAndPlaySong(song)
            }
            return
        }

        cb.scope.launch {
            val mediaItem = buildResolvedPlaybackMediaItem(song)
            if (controller.currentMediaItem?.mediaId == song.id) {
                if (!controller.isPlaying) controller.play()
            } else {
                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.play()
            }
        }
    }

    fun addSongToQueue(song: Song) {
        cb.getController()?.let { controller ->
            val mediaItem = buildPlaybackMediaItem(song)
            controller.addMediaItem(mediaItem)
            // Queue UI is synced via onTimelineChanged listener
        }
    }

    fun addSongNextToQueue(song: Song) {
        cb.getController()?.let { controller ->
            val mediaItem = buildPlaybackMediaItem(song)

            val insertionIndex = if (controller.currentMediaItemIndex != C.INDEX_UNSET) {
                (controller.currentMediaItemIndex + 1).coerceAtMost(controller.mediaItemCount)
            } else {
                controller.mediaItemCount
            }

            controller.addMediaItem(insertionIndex, mediaItem)
            // Queue UI is synced via onTimelineChanged listener
        }
    }

    private fun buildPlaybackMediaItem(song: Song, playlistId: String? = null): MediaItem {
        val baseItem = MediaItemBuilder.build(song)
        if (playlistId == null) {
            return baseItem
        }

        val mergedExtras = Bundle(baseItem.mediaMetadata.extras ?: Bundle()).apply {
            putString("playlistId", playlistId)
        }

        return baseItem.buildUpon()
            .setMediaMetadata(
                baseItem.mediaMetadata.buildUpon()
                    .setExtras(mergedExtras)
                    .build()
            )
            .build()
    }

    fun playPause() {
        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            val remoteMediaClient = castSession.remoteMediaClient!!
            val remotePlayback = remoteMediaClient.mediaStatus?.let { mediaStatus ->
                CastRemotePlaybackState.project(
                    mediaStatus = mediaStatus,
                    previousPlayIntent = playbackStateHolder.stablePlayerState.value.playWhenReady
                )
            }
            if (remoteMediaClient.isPlaying || remotePlayback?.playWhenReady == true) {
                castStateHolder.castPlayer?.pause()
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        isPlaying = false,
                        playWhenReady = false,
                        isBuffering = false
                    )
                }
            } else {
                val localQueue = cb.getUiState().currentPlaybackQueue.toList()
                val startSong = playbackStateHolder.stablePlayerState.value.currentSong ?: localQueue.firstOrNull()
                val remoteHasQueue = hasRemoteQueueItems(remoteMediaClient)
                val remoteQueueAligned = remoteQueueMatchesLocalQueue(remoteMediaClient, localQueue, startSong)
                val shouldResumeRemoteQueue = remoteHasQueue && (localQueue.isEmpty() || remoteQueueAligned)

                if (shouldResumeRemoteQueue) {
                    castStateHolder.castPlayer?.play()
                    playbackStateHolder.updateStablePlayerState {
                        it.copy(
                            isPlaying = true,
                            playWhenReady = true
                        )
                    }
                } else if (localQueue.isNotEmpty() && startSong != null) {
                    Timber.tag(CAST_LOG_TAG).i(
                        "Remote queue out of sync. Reloading remote queue (local=%d status=%d snapshot=%d).",
                        localQueue.size,
                        remoteMediaClient.mediaStatus?.queueItems?.size ?: 0,
                        castTransferStateHolder.lastRemoteQueue.size
                    )
                    cb.scope.launch {
                        internalPlaySongs(localQueue, startSong, cb.getUiState().currentQueueSourceName)
                    }
                } else if (remoteHasQueue) {
                    // No local queue available to reconcile; fallback to resuming remote queue.
                    castStateHolder.castPlayer?.play()
                    playbackStateHolder.updateStablePlayerState {
                        it.copy(
                            isPlaying = true,
                            playWhenReady = true
                        )
                    }
                } else {
                    Timber.tag(CAST_LOG_TAG).w("Cannot resume Cast playback: both local and remote queues are empty.")
                }
            }
        } else {
            val controller = cb.getController()
            if (controller == null || !controller.isConnected) {
                playbackStateHolder.playPause()
                return
            }

            if (controller.isPlaying) {
                controller.pause()
            } else {
                if (controller.currentMediaItem == null) {
                    val currentQueue = cb.getUiState().currentPlaybackQueue
                    val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
                    when {
                        currentQueue.isNotEmpty() && currentSong != null -> {
                            cb.scope.launch {
                                cb.cancelTransitionScheduler()
                                internalPlaySongs(
                                    currentQueue.toList(),
                                    currentSong,
                                    cb.getUiState().currentQueueSourceName
                                )
                            }
                        }
                        currentSong != null -> {
                            loadAndPlaySong(currentSong)
                        }
                        else -> {
                            cb.scope.launch {
                                val fallbackSong = musicRepository.getFirstPlayableSong()
                                if (fallbackSong != null) {
                                    loadAndPlaySong(fallbackSong)
                                } else {
                                    controller.play()
                                }
                            }
                        }
                    }
                } else {
                    if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
                        controller.prepare()
                    }
                    controller.play()
                }
            }
        }
    }

    private fun hasRemoteQueueItems(remoteMediaClient: RemoteMediaClient): Boolean {
        val mediaQueueCount = remoteMediaClient.mediaQueue.itemCount
        val statusQueueCount = remoteMediaClient.mediaStatus?.queueItems?.size ?: 0
        val snapshotQueueCount = castTransferStateHolder.lastRemoteQueue.size
        return mediaQueueCount > 0 || statusQueueCount > 0 || snapshotQueueCount > 0
    }

    private fun remoteQueueMatchesLocalQueue(
        remoteMediaClient: RemoteMediaClient,
        localQueue: List<Song>,
        localStartSong: Song?
    ): Boolean {
        if (localQueue.isEmpty()) return true

        val localQueueIds = localQueue.map { it.id }
        val status = remoteMediaClient.mediaStatus
        val remoteQueueIdsFromStatus = status
            ?.queueItems
            ?.mapNotNull { item ->
                item.customData
                    ?.optString("songId")
                    ?.takeIf { it.isNotBlank() }
            }
            .orEmpty()
        val remoteQueueIdsFromSnapshot = castTransferStateHolder.lastRemoteQueue.map { it.id }

        val queueMatches = when {
            remoteQueueIdsFromStatus.size == localQueueIds.size ->
                remoteQueueIdsFromStatus == localQueueIds
            remoteQueueIdsFromSnapshot.size == localQueueIds.size ->
                remoteQueueIdsFromSnapshot == localQueueIds
            remoteQueueIdsFromStatus.isNotEmpty() -> false
            remoteQueueIdsFromSnapshot.isNotEmpty() -> false
            else -> false
        }

        if (!queueMatches) return false

        val expectedSongId = localStartSong?.id ?: return true
        val remoteCurrentSongId = status
            ?.let { mediaStatus ->
                mediaStatus.getQueueItemById(mediaStatus.getCurrentItemId())
                    ?.customData
                    ?.optString("songId")
                    ?.takeIf { it.isNotBlank() }
            }
            ?: castTransferStateHolder.lastRemoteSongId

        return remoteCurrentSongId == null || remoteCurrentSongId == expectedSongId
    }
}
