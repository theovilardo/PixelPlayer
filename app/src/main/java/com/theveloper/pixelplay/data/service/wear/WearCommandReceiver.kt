package com.theveloper.pixelplay.data.service.wear

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.core.content.ContextCompat
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.MusicService
import com.theveloper.pixelplay.data.service.MusicNotificationProvider
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.shared.WearBrowseRequest
import com.theveloper.pixelplay.shared.WearBrowseResponse
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearLibraryItem
import com.theveloper.pixelplay.shared.WearLibraryState
import com.theveloper.pixelplay.shared.WearPlaybackCommand
import com.theveloper.pixelplay.shared.WearTransferMetadata
import com.theveloper.pixelplay.shared.WearTransferProgress
import com.theveloper.pixelplay.shared.WearTransferRequest
import com.theveloper.pixelplay.shared.WearVolumeCommand
import com.theveloper.pixelplay.utils.MediaItemBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * WearableListenerService that receives commands from the Wear OS watch app.
 * Handles playback commands (play, pause, next, prev, etc.), volume commands,
 * and library browse requests.
 *
 * Commands are received via the Wear Data Layer MessageClient and forwarded
 * to the MusicService via MediaController or processed directly.
 */
@AndroidEntryPoint
class WearCommandReceiver : WearableListenerService() {

    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var playlistPreferencesRepository: PlaylistPreferencesRepository
    @Inject lateinit var dualPlayerEngine: DualPlayerEngine
    @Inject lateinit var directTransferCoordinator: PhoneDirectWatchTransferCoordinator
    @Inject lateinit var transferCancellationStore: PhoneWatchTransferCancellationStore
    @Inject lateinit var transferStateStore: PhoneWatchTransferStateStore

    private val json = Json { ignoreUnknownKeys = true }
    private var mediaController: MediaController? = null
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set of requestIds that have been cancelled by the watch */
    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val TAG = "WearCommandReceiver"
        private const val MAX_SONGS = 500
        private const val MAX_ALBUMS = 200
        private const val MAX_ARTISTS = 200
        private const val MAX_QUEUE_ITEMS = 20
        private const val TRANSFER_CHUNK_SIZE = 8192
        private const val PROGRESS_UPDATE_INTERVAL_BYTES = 65536L
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Timber.tag(TAG).d("Received message on path: ${messageEvent.path}")

        when (messageEvent.path) {
            WearDataPaths.PLAYBACK_COMMAND -> handlePlaybackCommand(messageEvent)
            WearDataPaths.VOLUME_COMMAND -> handleVolumeCommand(messageEvent)
            WearDataPaths.BROWSE_REQUEST -> handleBrowseRequest(messageEvent)
            WearDataPaths.TRANSFER_REQUEST -> handleTransferRequest(messageEvent)
            WearDataPaths.TRANSFER_CANCEL -> handleTransferCancel(messageEvent)
            WearDataPaths.TRANSFER_PROGRESS -> handleTransferProgressAck(messageEvent)
            WearDataPaths.WATCH_LIBRARY_STATE -> handleWatchLibraryState(messageEvent)
            else -> Timber.tag(TAG).w("Unknown message path: ${messageEvent.path}")
        }
    }

    /**
     * The watch reports the real, validated outcome of a transfer over this same path (reusing
     * the schema the phone uses for its own outgoing progress updates). This is the only source
     * of truth for "is this song actually present on the watch" — see
     * PhoneDirectWatchTransferCoordinator.sendTransferProgress, which deliberately no longer
     * marks a song present just because the phone finished sending it.
     */
    private fun handleTransferProgressAck(messageEvent: MessageEvent) {
        val progress = try {
            json.decodeFromString<WearTransferProgress>(String(messageEvent.data, Charsets.UTF_8))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse transfer progress ack")
            return
        }
        when (progress.status) {
            WearTransferProgress.STATUS_COMPLETED -> {
                transferStateStore.markProgress(
                    requestId = progress.requestId,
                    songId = progress.songId,
                    bytesTransferred = progress.totalBytes,
                    totalBytes = progress.totalBytes,
                    status = WearTransferProgress.STATUS_COMPLETED,
                )
                transferStateStore.markSongPresentOnWatch(
                    nodeId = messageEvent.sourceNodeId,
                    songId = progress.songId,
                )
            }
            WearTransferProgress.STATUS_FAILED -> {
                transferStateStore.markProgress(
                    requestId = progress.requestId,
                    songId = progress.songId,
                    bytesTransferred = progress.bytesTransferred,
                    totalBytes = progress.totalBytes,
                    status = WearTransferProgress.STATUS_FAILED,
                    error = progress.error,
                    errorCode = progress.errorCode,
                )
                // A rejection because it's already on the watch means it IS genuinely present —
                // don't downgrade that case, only a real failure should clear presence.
                if (progress.error != WearTransferProgress.ERROR_ALREADY_ON_WATCH) {
                    transferStateStore.clearSongPresentOnWatch(
                        nodeId = messageEvent.sourceNodeId,
                        songId = progress.songId,
                    )
                }
            }
            else -> Timber.tag(TAG).d("Ignoring non-terminal transfer ack: %s", progress.status)
        }
    }

    private fun handlePlaybackCommand(messageEvent: MessageEvent) {
        val commandJson = String(messageEvent.data, Charsets.UTF_8)
        val command = try {
            json.decodeFromString<WearPlaybackCommand>(commandJson)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse playback command")
            return
        }

        Timber.tag(TAG).d("Playback command: ${command.action}")

        when (command.action) {
            WearPlaybackCommand.PLAY_FROM_CONTEXT -> {
                handlePlayFromContext(command)
            }
            else -> {
                getOrBuildMediaController { controller ->
                    when (command.action) {
                        WearPlaybackCommand.PLAY -> controller.play()
                        WearPlaybackCommand.PAUSE -> controller.pause()
                        WearPlaybackCommand.TOGGLE_PLAY_PAUSE -> {
                            if (controller.isPlaying) controller.pause() else controller.play()
                        }
                        WearPlaybackCommand.NEXT -> controller.seekToNext()
                        WearPlaybackCommand.PREVIOUS -> controller.seekToPrevious()
                        WearPlaybackCommand.PLAY_QUEUE_INDEX -> {
                            val queueIndex = command.queueIndex
                            if (queueIndex == null) {
                                Timber.tag(TAG).w("PLAY_QUEUE_INDEX missing queueIndex")
                            } else if (queueIndex !in 0 until controller.mediaItemCount) {
                                Timber.tag(TAG).w(
                                    "PLAY_QUEUE_INDEX out of bounds: %d (size=%d)",
                                    queueIndex,
                                    controller.mediaItemCount
                                )
                            } else {
                                controller.seekToDefaultPosition(queueIndex)
                                controller.play()
                            }
                        }
                        WearPlaybackCommand.TOGGLE_SHUFFLE -> {
                            controller.sendCustomCommand(
                                SessionCommand(
                                    MusicNotificationProvider.CUSTOM_COMMAND_TOGGLE_SHUFFLE,
                                    Bundle.EMPTY
                                ),
                                Bundle.EMPTY
                            )
                        }
                        WearPlaybackCommand.CYCLE_REPEAT -> {
                            controller.sendCustomCommand(
                                SessionCommand(
                                    MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE,
                                    Bundle.EMPTY
                                ),
                                Bundle.EMPTY
                            )
                        }
                        WearPlaybackCommand.TOGGLE_FAVORITE -> {
                            val targetEnabled = command.targetEnabled
                            if (targetEnabled == null) {
                                val sessionCommand = SessionCommand(
                                    MusicNotificationProvider.CUSTOM_COMMAND_LIKE,
                                    Bundle.EMPTY
                                )
                                controller.sendCustomCommand(sessionCommand, Bundle.EMPTY)
                            } else {
                                val args = Bundle().apply {
                                    putBoolean(MusicNotificationProvider.EXTRA_FAVORITE_ENABLED, targetEnabled)
                                }
                                val sessionCommand = SessionCommand(
                                    MusicNotificationProvider.CUSTOM_COMMAND_SET_FAVORITE_STATE,
                                    Bundle.EMPTY
                                )
                                controller.sendCustomCommand(sessionCommand, args)
                            }
                        }
                        else -> Timber.tag(TAG).w("Unknown playback action: ${command.action}")
                    }
                }
            }
        }
    }

    /**
     * Handle PLAY_FROM_CONTEXT: load songs for the given context, build a queue,
     * find the start index, and start playback.
     */
    private fun handlePlayFromContext(command: WearPlaybackCommand) {
        val songId = command.songId
        val contextType = command.contextType
        if (songId == null || contextType == null) {
            Timber.tag(TAG).w("PLAY_FROM_CONTEXT missing songId or contextType")
            return
        }

        scope.launch {
            try {
                val songs = getSongsForContext(contextType, command.contextId)
                if (songs.isEmpty()) {
                    Timber.tag(TAG).w("No songs found for context: $contextType / ${command.contextId}")
                    return@launch
                }

                val mediaItems = songs.map { MediaItemBuilder.build(it) }.toMutableList()
                val startIndex = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
                mediaItems[startIndex] = dualPlayerEngine.resolveMediaItem(mediaItems[startIndex])

                getOrBuildMediaController { controller ->
                    controller.setMediaItems(mediaItems, startIndex, 0L)
                    controller.prepare()
                    controller.play()
                    Timber.tag(TAG).d(
                        "Playing from context: $contextType, song=${songs[startIndex].title}, " +
                            "queue size=${songs.size}"
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to handle PLAY_FROM_CONTEXT")
            }
        }
    }

    /**
     * Get songs for a given context type and optional context ID.
     */
    private suspend fun getSongsForContext(contextType: String, contextId: String?): List<Song> {
        return when (contextType) {
            "album" -> {
                val albumId = contextId?.toLongOrNull() ?: return emptyList()
                musicRepository.getSongsForAlbum(albumId).first()
            }
            "artist" -> {
                val artistId = contextId?.toLongOrNull() ?: return emptyList()
                musicRepository.getSongsForArtist(artistId).first()
            }
            "playlist" -> {
                val playlistId = contextId ?: return emptyList()
                val playlist = playlistPreferencesRepository.userPlaylistsFlow.first()
                    .find { it.id == playlistId } ?: return emptyList()
                val songs = musicRepository.getSongsByIds(playlist.songIds).first()
                // Maintain playlist order
                val songsById = songs.associateBy { it.id }
                playlist.songIds.mapNotNull { id -> songsById[id] }
            }
            "favorites" -> {
                musicRepository.getFavoriteSongsPage(limit = MAX_SONGS, offset = 0)
            }
            "all_songs" -> {
                musicRepository.getSongsPage(limit = MAX_SONGS, offset = 0)
            }
            else -> {
                Timber.tag(TAG).w("Unknown context type: $contextType")
                emptyList()
            }
        }
    }

    // ---- Browse request handling ----

    private fun handleBrowseRequest(messageEvent: MessageEvent) {
        val requestJson = String(messageEvent.data, Charsets.UTF_8)
        val request = try {
            json.decodeFromString<WearBrowseRequest>(requestJson)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse browse request")
            return
        }

        Timber.tag(TAG).d("Browse request: type=${request.browseType}, contextId=${request.contextId}")

        scope.launch {
            val response = try {
                val items = getBrowseItems(request.browseType, request.contextId)
                WearBrowseResponse(requestId = request.requestId, items = items)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to process browse request")
                WearBrowseResponse(
                    requestId = request.requestId,
                    error = e.message ?: "Unknown error"
                )
            }

            // Send response back to the watch
            try {
                val responseBytes = json.encodeToString(response).toByteArray(Charsets.UTF_8)
                val messageClient = Wearable.getMessageClient(this@WearCommandReceiver)
                messageClient.sendMessage(
                    messageEvent.sourceNodeId,
                    WearDataPaths.BROWSE_RESPONSE,
                    responseBytes
                ).await()
                Timber.tag(TAG).d(
                    "Sent browse response: ${response.items.size} items for ${request.browseType}"
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to send browse response")
            }
        }
    }

    /**
     * Get browse items based on the browse type, following the same pattern
     * as AutoMediaBrowseTree.
     */
    private suspend fun getBrowseItems(browseType: String, contextId: String?): List<WearLibraryItem> {
        return when (browseType) {
            WearBrowseRequest.ROOT -> listOf(
                WearLibraryItem("favorites", "Favorites", "", WearLibraryItem.TYPE_CATEGORY),
                WearLibraryItem("playlists", "Playlists", "", WearLibraryItem.TYPE_CATEGORY),
                WearLibraryItem("albums", "Albums", "", WearLibraryItem.TYPE_CATEGORY),
                WearLibraryItem("artists", "Artists", "", WearLibraryItem.TYPE_CATEGORY),
                WearLibraryItem("all_songs", "All Songs", "", WearLibraryItem.TYPE_CATEGORY),
            )

            WearBrowseRequest.ALBUMS -> {
                musicRepository.getAlbumsPage(limit = MAX_ALBUMS, offset = 0, minTracks = 1)
                    .map { album ->
                        WearLibraryItem(
                            id = album.id.toString(),
                            title = album.title,
                            subtitle = "${album.artist} · ${album.songCount} songs",
                            type = WearLibraryItem.TYPE_ALBUM,
                        )
                    }
            }

            WearBrowseRequest.ARTISTS -> {
                musicRepository.getArtistsPage(limit = MAX_ARTISTS, offset = 0)
                    .map { artist ->
                        WearLibraryItem(
                            id = artist.id.toString(),
                            title = artist.name,
                            subtitle = "${artist.songCount} songs",
                            type = WearLibraryItem.TYPE_ARTIST,
                        )
                    }
            }

            WearBrowseRequest.PLAYLISTS -> {
                playlistPreferencesRepository.userPlaylistsFlow.first()
                    .map { playlist ->
                        WearLibraryItem(
                            id = playlist.id,
                            title = playlist.name,
                            subtitle = "${playlist.songIds.size} songs",
                            type = WearLibraryItem.TYPE_PLAYLIST,
                        )
                    }
            }

            WearBrowseRequest.FAVORITES -> {
                musicRepository.getFavoriteSongsPage(limit = MAX_SONGS, offset = 0)
                    .map { song -> song.toWearLibraryItem() }
            }

            WearBrowseRequest.ALL_SONGS -> {
                musicRepository.getSongsPage(limit = MAX_SONGS, offset = 0)
                    .map { song -> song.toWearLibraryItem() }
            }

            WearBrowseRequest.ALBUM_SONGS -> {
                val albumId = contextId?.toLongOrNull()
                    ?: throw IllegalArgumentException("Missing albumId for ALBUM_SONGS")
                musicRepository.getSongsForAlbum(albumId).first()
                    .map { song -> song.toWearLibraryItem() }
            }

            WearBrowseRequest.ARTIST_SONGS -> {
                val artistId = contextId?.toLongOrNull()
                    ?: throw IllegalArgumentException("Missing artistId for ARTIST_SONGS")
                musicRepository.getSongsForArtist(artistId).first()
                    .map { song -> song.toWearLibraryItem() }
            }

            WearBrowseRequest.PLAYLIST_SONGS -> {
                val playlistId = contextId
                    ?: throw IllegalArgumentException("Missing playlistId for PLAYLIST_SONGS")
                val playlist = playlistPreferencesRepository.userPlaylistsFlow.first()
                    .find { it.id == playlistId }
                    ?: throw IllegalArgumentException("Playlist not found: $playlistId")
                val songs = musicRepository.getSongsByIds(playlist.songIds).first()
                // Maintain playlist order
                val songsById = songs.associateBy { it.id }
                playlist.songIds
                    .mapNotNull { id -> songsById[id] }
                    .map { song -> song.toWearLibraryItem() }
            }

            WearBrowseRequest.QUEUE -> {
                getCurrentQueueItems()
            }

            else -> {
                Timber.tag(TAG).w("Unknown browse type: $browseType")
                emptyList()
            }
        }
    }

    private fun Song.toWearLibraryItem(): WearLibraryItem {
        return WearLibraryItem(
            id = id,
            title = title,
            subtitle = displayArtist,
            type = WearLibraryItem.TYPE_SONG,
        )
    }

    private suspend fun getCurrentQueueItems(): List<WearLibraryItem> {
        val deferred = CompletableDeferred<List<WearLibraryItem>>()
        getOrBuildMediaController { controller ->
            runCatching {
                buildCurrentQueueItems(controller)
            }.onSuccess(deferred::complete)
                .onFailure(deferred::completeExceptionally)
        }
        return withTimeout(5_000L) {
            deferred.await()
        }
    }

    private fun buildCurrentQueueItems(controller: MediaController): List<WearLibraryItem> {
        val queueSize = controller.mediaItemCount
        if (queueSize <= 0) return emptyList()

        val currentIndex = controller.currentMediaItemIndex
            .takeIf { it in 0 until queueSize }
            ?: 0

        val queueIndices = buildList(queueSize) {
            for (index in currentIndex until queueSize) {
                add(index)
            }
            for (index in 0 until currentIndex) {
                add(index)
            }
        }

        return queueIndices
            .asSequence()
            .take(MAX_QUEUE_ITEMS)
            .map { actualIndex ->
            val mediaItem = controller.getMediaItemAt(actualIndex)
            val metadata = mediaItem.mediaMetadata
            val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: "Track ${actualIndex + 1}"
            val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }.orEmpty()
            val isCurrentItem = actualIndex == currentIndex
            WearLibraryItem(
                id = actualIndex.toString(),
                title = title,
                subtitle = when {
                    isCurrentItem && artist.isNotBlank() -> "Playing · $artist"
                    isCurrentItem -> "Playing"
                    artist.isNotBlank() -> artist
                    else -> ""
                },
                type = WearLibraryItem.TYPE_SONG,
            )
        }
            .toList()
    }

    // ---- Volume handling ----

    private fun handleVolumeCommand(messageEvent: MessageEvent) {
        val commandJson = String(messageEvent.data, Charsets.UTF_8)
        val command = try {
            json.decodeFromString<WearVolumeCommand>(commandJson)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse volume command")
            return
        }

        Timber.tag(TAG).d("Volume command: direction=${command.direction}, value=${command.value}")

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val absoluteValue = command.value
        if (absoluteValue != null) {
            // Set absolute volume (scaled from 0-100 to device range)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (absoluteValue * maxVolume / 100).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } else {
            when (command.direction) {
                WearVolumeCommand.UP -> audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    0
                )
                WearVolumeCommand.DOWN -> audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    0
                )
            }
        }
    }

    // ---- Transfer handling ----

    private fun handleTransferRequest(messageEvent: MessageEvent) {
        val requestJson = String(messageEvent.data, Charsets.UTF_8)
        val request = try {
            json.decodeFromString<WearTransferRequest>(requestJson)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse transfer request")
            return
        }

        Timber.tag(TAG).d("Transfer request: songId=${request.songId}, requestId=${request.requestId}")
        directTransferCoordinator.startTransferToWatch(
            nodeId = messageEvent.sourceNodeId,
            requestId = request.requestId,
            songId = request.songId,
            transferMode = request.transferMode,
            startPositionMs = request.startPositionMs,
            autoPlay = request.autoPlay,
        )
    }

    private fun handleTransferCancel(messageEvent: MessageEvent) {
        val requestJson = String(messageEvent.data, Charsets.UTF_8)
        val request = try {
            json.decodeFromString<WearTransferRequest>(requestJson)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse transfer cancel")
            return
        }
        cancelledTransfers.add(request.requestId)
        transferCancellationStore.markCancelled(request.requestId)
        transferStateStore.markCancelled(request.requestId)
        Timber.tag(TAG).d("Transfer cancelled: requestId=${request.requestId}")
    }

    private fun handleWatchLibraryState(messageEvent: MessageEvent) {
        val stateJson = String(messageEvent.data, Charsets.UTF_8)
        val state = try {
            json.decodeFromString<WearLibraryState>(stateJson)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse watch library state")
            return
        }
        val nodeId = messageEvent.sourceNodeId
        transferStateStore.updateWatchSongIds(nodeId, state.songIds.toSet())
        transferStateStore.updateWatchFreeStorageBytes(nodeId, state.freeStorageBytes)
    }

    /**
     * Open a song's audio file, trying direct File access first,
     * then falling back to ContentResolver.
     */
    private fun openSongFile(song: Song): InputStream? {
        return try {
            val file = File(song.path)
            if (file.exists() && file.canRead()) {
                file.inputStream()
            } else {
                contentResolver.openInputStream(song.contentUriString.toUri())
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open song file: ${song.path}")
            try {
                contentResolver.openInputStream(song.contentUriString.toUri())
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "ContentResolver fallback also failed")
                null
            }
        }
    }

    /**
     * Get the file size of a song, trying File.length() first,
     * then ContentResolver.
     */
    private fun getSongFileSize(song: Song): Long {
        val file = File(song.path)
        if (file.exists()) return file.length()

        // Fallback: query ContentResolver for size
        return try {
            contentResolver.openAssetFileDescriptor(song.contentUriString.toUri(), "r")?.use {
                it.length
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Stream an audio file to the watch via ChannelClient.
     * The stream format is: [4 bytes requestId length][requestId bytes][audio data]
     */
    private suspend fun streamFileToWatch(
        nodeId: String,
        requestId: String,
        songId: String,
        inputStream: InputStream,
        fileSize: Long,
    ) {
        val channelClient = Wearable.getChannelClient(this@WearCommandReceiver)
        val channel = channelClient.openChannel(nodeId, WearDataPaths.TRANSFER_CHANNEL).await()

        try {
            val outputStream = channelClient.getOutputStream(channel).await()

            // Write header: requestId length (4 bytes big-endian) + requestId bytes
            val requestIdBytes = requestId.toByteArray(Charsets.UTF_8)
            val lengthBytes = ByteBuffer.allocate(4).putInt(requestIdBytes.size).array()
            outputStream.write(lengthBytes)
            outputStream.write(requestIdBytes)

            // Stream audio data in chunks
            val buffer = ByteArray(TRANSFER_CHUNK_SIZE)
            var totalSent = 0L
            var lastProgressUpdate = 0L

            inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    // Check for cancellation
                    if (cancelledTransfers.remove(requestId)) {
                        Timber.tag(TAG).d("Transfer cancelled during streaming: $requestId")
                        sendTransferProgress(
                            nodeId, requestId, songId, totalSent, fileSize,
                            WearTransferProgress.STATUS_CANCELLED,
                        )
                        return
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    totalSent += bytesRead

                    // Send progress updates periodically
                    if (totalSent - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_BYTES) {
                        sendTransferProgress(
                            nodeId, requestId, songId, totalSent, fileSize,
                            WearTransferProgress.STATUS_TRANSFERRING,
                        )
                        lastProgressUpdate = totalSent
                    }
                }
            }

            outputStream.flush()
            outputStream.close()

            // Send completion status
            sendTransferProgress(
                nodeId, requestId, songId, fileSize, fileSize,
                WearTransferProgress.STATUS_COMPLETED,
            )
            Timber.tag(TAG).d("Transfer complete: songId=$songId, $totalSent bytes sent")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to stream file to watch")
            sendTransferProgress(
                nodeId, requestId, songId, 0, fileSize,
                WearTransferProgress.STATUS_FAILED, e.message,
            )
        } finally {
            try {
                channelClient.close(channel).await()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to close channel")
            }
        }
    }

    private suspend fun sendTransferMetadataError(
        nodeId: String,
        requestId: String,
        songId: String,
        errorMessage: String,
    ) {
        val metadata = WearTransferMetadata(
            requestId = requestId,
            songId = songId,
            title = "",
            artist = "",
            album = "",
            albumId = 0L,
            duration = 0L,
            mimeType = "",
            fileSize = 0L,
            bitrate = 0,
            sampleRate = 0,
            error = errorMessage,
        )
        try {
            val metadataBytes = json.encodeToString(metadata).toByteArray(Charsets.UTF_8)
            val msgClient = Wearable.getMessageClient(this@WearCommandReceiver)
            msgClient.sendMessage(nodeId, WearDataPaths.TRANSFER_METADATA, metadataBytes).await()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to send transfer error metadata")
        }
    }

    private suspend fun sendTransferProgress(
        nodeId: String,
        requestId: String,
        songId: String,
        bytesTransferred: Long,
        totalBytes: Long,
        status: String,
        error: String? = null,
    ) {
        val progress = WearTransferProgress(
            requestId = requestId,
            songId = songId,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            status = status,
            error = error,
        )
        try {
            val progressBytes = json.encodeToString(progress).toByteArray(Charsets.UTF_8)
            val msgClient = Wearable.getMessageClient(this@WearCommandReceiver)
            msgClient.sendMessage(nodeId, WearDataPaths.TRANSFER_PROGRESS, progressBytes).await()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to send transfer progress")
        }
    }

    // ---- MediaController management ----

    /**
     * Get existing MediaController or build a new one, then execute the action.
     */
    private fun getOrBuildMediaController(action: (MediaController) -> Unit) {
        runOnMainThread {
            val existing = mediaController
            if (existing != null && existing.isConnected) {
                action(existing)
                return@runOnMainThread
            }

            val inFlight = mediaControllerFuture
            if (inFlight != null && !inFlight.isDone) {
                inFlight.addListener(
                    {
                        try {
                            action(inFlight.get())
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to reuse pending MediaController")
                        }
                    },
                    ContextCompat.getMainExecutor(this)
                )
                return@runOnMainThread
            }

            val sessionToken = SessionToken(
                this,
                ComponentName(this, MusicService::class.java)
            )
            val future = MediaController.Builder(this, sessionToken)
                .setApplicationLooper(Looper.getMainLooper())
                .buildAsync()
            mediaControllerFuture = future
            future.addListener(
                {
                    try {
                        val controller = future.get()
                        mediaController = controller
                        action(controller)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to build MediaController")
                    }
                },
                ContextCompat.getMainExecutor(this)
            )
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun releaseController() {
        val controller = mediaController
        mediaController = null
        mediaControllerFuture = null
        if (controller != null) {
            try {
                controller.release()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to release MediaController")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runOnMainThread { releaseController() }
        super.onDestroy()
    }
}
