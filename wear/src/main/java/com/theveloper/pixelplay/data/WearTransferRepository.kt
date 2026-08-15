package com.theveloper.pixelplay.data

import android.app.Application
import android.os.SystemClock
import android.webkit.MimeTypeMap
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.theveloper.pixelplay.data.local.LocalPlaylistDao
import com.theveloper.pixelplay.data.local.LocalPlaylistEntity
import com.theveloper.pixelplay.data.local.LocalPlaylistSongCrossRef
import com.theveloper.pixelplay.data.local.LocalSongDao
import com.theveloper.pixelplay.data.local.LocalSongEntity
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearLibraryState
import com.theveloper.pixelplay.shared.WearPlaylistSync
import com.theveloper.pixelplay.shared.WearPlaylistSyncAck
import com.theveloper.pixelplay.shared.WearTransferMetadata
import com.theveloper.pixelplay.shared.WearTransferProgress
import com.theveloper.pixelplay.shared.WearTransferRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents the current state of an active transfer.
 */
data class TransferState(
    val requestId: String,
    val songId: String,
    val songTitle: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: String,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
}

/**
 * Repository managing song transfers from phone to watch.
 *
 * Transfer flow:
 * 1. [requestTransfer] sends a WearTransferRequest to the phone
 * 2. Phone validates and sends WearTransferMetadata back ([onMetadataReceived])
 * 3. Phone opens a ChannelClient stream and sends audio data
 * 4. Watch receives via [onChannelOpened], writes to disk, inserts into Room
 * 5. Progress updates arrive via [onProgressReceived] during streaming
 */
@Singleton
class WearTransferRepository @Inject constructor(
    private val application: Application,
    private val localSongDao: LocalSongDao,
    private val localPlaylistDao: LocalPlaylistDao,
    private val channelClient: ChannelClient,
    private val messageClient: MessageClient,
    private val nodeClient: NodeClient,
    private val localPlayerRepository: WearLocalPlayerRepository,
    private val stateRepository: WearStateRepository,
    private val playbackController: WearPlaybackController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** Observable map of active transfers: requestId -> TransferState */
    private val _activeTransfers = MutableStateFlow<Map<String, TransferState>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, TransferState>> = _activeTransfers.asStateFlow()

    /**
     * All locally stored songs that still have a valid file on disk.
     * Any stale DB rows (missing/empty files) are cleaned up automatically.
     */
    val localSongs: Flow<List<LocalSongEntity>> = localSongDao.getAllSongs()
        .transform { songs ->
            val (validSongs, staleSongs) = songs.partition { it.hasPlayableLocalFile() }
            if (staleSongs.isNotEmpty()) {
                staleSongs.forEach { stale ->
                    stale.artworkPath?.let { artworkPath ->
                        runCatching { File(artworkPath).delete() }
                    }
                    localSongDao.deleteById(stale.songId)
                }
                Timber.tag(TAG).w("Removed ${staleSongs.size} stale local song entries from DB")
            }
            emit(validSongs)
        }

    /** Set of song IDs that are already downloaded and still valid on disk */
    val downloadedSongIds: Flow<Set<String>> = localSongs
        .map { songs -> songs.map { it.songId }.toSet() }

    /** Pending metadata awaiting channel stream: requestId -> metadata */
    private val pendingMetadata = ConcurrentHashMap<String, WearTransferMetadata>()

    /**
     * requestId -> the node this request came from, recorded as soon as metadata arrives so
     * later stages (audio-channel success/failure, the idle watchdog) can report the real
     * outcome back to the phone without having to thread a nodeId through every call site.
     */
    private val requestIdToSourceNode = ConcurrentHashMap<String, String>()

    /** Mapping from songId -> requestId for tracking which song is being transferred */
    private val songToRequestId = ConcurrentHashMap<String, String>()

    /** Artwork bytes received before/while audio transfer: requestId -> bytes */
    private val pendingArtworkByRequestId = ConcurrentHashMap<String, ByteArray>()
    /** Temporary handoff requests waiting to become local watch playback. */
    private val pendingTemporaryPlaybackRequests = ConcurrentHashMap<String, PendingTemporaryPlaybackRequest>()

    /** Failsafe timeout per transfer to avoid hanging states at 0%. */
    private val transferWatchdogs = ConcurrentHashMap<String, Job>()
    /** The live audio InputStream for a request, while onAudioChannelOpened is reading it —
     *  lets armTransferWatchdog actually interrupt a stuck read instead of just updating
     *  bookkeeping while the real transfer keeps running unaware. */
    private val openAudioStreams = ConcurrentHashMap<String, InputStream>()
    /** Request IDs whose audio stream was closed by the watchdog, so onAudioChannelOpened's
     *  catch block can report "Transfer timed out" instead of a generic stream-closed message. */
    private val watchdogTimedOutRequestIds = ConcurrentHashMap.newKeySet<String>()
    /** Request IDs currently receiving bytes through ChannelClient. */
    private val activeChannelRequestIds = ConcurrentHashMap.newKeySet<String>()
    /** Cancelled request IDs retained briefly so late metadata/progress/channel events are ignored safely. */
    private val cancelledRequestIds = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val TAG = "WearTransferRepo"
        private const val ARTWORK_FILE_EXTENSION = "jpg"
        private const val TRANSFER_IDLE_TIMEOUT_MS = 120_000L
        private const val METADATA_WAIT_TIMEOUT_MS = 8_000L
        private const val METADATA_POLL_INTERVAL_MS = 120L
        private const val WATCHDOG_TOUCH_INTERVAL_MS = 1_500L
        private const val LOCAL_PROGRESS_UPDATE_INTERVAL_BYTES = 65_536L
        private const val CANCELLED_REQUEST_RETENTION_MS = 300_000L
    }

    private data class PendingTemporaryPlaybackRequest(
        val songId: String,
        val startPositionMs: Long,
        val autoPlay: Boolean,
        val pausePhoneAfterStart: Boolean,
        val requestedAtElapsedMs: Long,
    )

    init {
        scope.launch {
            downloadedSongIds
                .distinctUntilChanged()
                .collect { songIds ->
                    publishLibraryState(songIds = songIds)
                }
        }
    }

    /**
     * Request transfer of a song from the phone.
     * Sends a WearTransferRequest via MessageClient.
     */
    fun requestTransfer(
        songId: String,
        requestId: String = UUID.randomUUID().toString(),
        targetNodeId: String? = null,
        transferMode: String = WearTransferRequest.MODE_SAVE_TO_LIBRARY,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = false,
    ) {
        // Don't request if already transferring this song
        if (songToRequestId.containsKey(songId)) {
            Timber.tag(TAG).d("Transfer already in progress for songId=$songId")
            return
        }

        scope.launch {
            val existingSong = localSongDao.getSongById(songId)
            if (existingSong?.hasPlayableLocalFile() == true) {
                notifyPhoneTransferFailure(
                    targetNodeId = targetNodeId,
                    requestId = requestId,
                    songId = songId,
                    message = WearTransferProgress.ERROR_ALREADY_ON_WATCH,
                )
                return@launch
            }

            clearStaleTransfersForSong(songId)
            songToRequestId[songId] = requestId
            if (
                transferMode == WearTransferRequest.MODE_TEMPORARY_PLAYBACK &&
                !pendingTemporaryPlaybackRequests.containsKey(requestId)
            ) {
                pendingTemporaryPlaybackRequests[requestId] = PendingTemporaryPlaybackRequest(
                    songId = songId,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                    pausePhoneAfterStart = false,
                    requestedAtElapsedMs = SystemClock.elapsedRealtime(),
                )
            }

            _activeTransfers.update { map ->
                map + (requestId to TransferState(
                    requestId = requestId,
                    songId = songId,
                    songTitle = "",
                    bytesTransferred = 0,
                    totalBytes = 0,
                    status = WearTransferProgress.STATUS_TRANSFERRING,
                ))
            }
            armTransferWatchdog(requestId, songId)

            try {
                val request = WearTransferRequest(
                    requestId = requestId,
                    songId = songId,
                    transferMode = transferMode,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                )
                val requestBytes = json.encodeToString(request).toByteArray(Charsets.UTF_8)

                if (targetNodeId != null) {
                    messageClient.sendMessage(
                        targetNodeId,
                        WearDataPaths.TRANSFER_REQUEST,
                        requestBytes,
                    ).await()
                } else {
                    val nodes = nodeClient.connectedNodes.await()
                    if (nodes.isEmpty()) {
                        handleTransferError(requestId, songId, "Phone not connected")
                        return@launch
                    }

                    nodes.forEach { node ->
                        messageClient.sendMessage(
                            node.id,
                            WearDataPaths.TRANSFER_REQUEST,
                            requestBytes,
                        ).await()
                    }
                }
                Timber.tag(TAG).d("Transfer requested: songId=$songId, requestId=$requestId")
            } catch (e: Exception) {
                handleTransferError(requestId, songId, e.message ?: "Failed to send request")
            }
        }
    }

    fun requestTemporaryPlayback(
        songId: String,
        startPositionMs: Long,
        autoPlay: Boolean,
        pausePhoneAfterStart: Boolean,
    ) {
        if (songToRequestId.containsKey(songId)) {
            Timber.tag(TAG).d("Temporary watch playback already requested for songId=%s", songId)
            return
        }
        val requestId = UUID.randomUUID().toString()
        pendingTemporaryPlaybackRequests[requestId] = PendingTemporaryPlaybackRequest(
            songId = songId,
            startPositionMs = startPositionMs,
            autoPlay = autoPlay,
            pausePhoneAfterStart = pausePhoneAfterStart,
            requestedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        requestTransfer(
            songId = songId,
            requestId = requestId,
            transferMode = WearTransferRequest.MODE_TEMPORARY_PLAYBACK,
            startPositionMs = startPositionMs,
            autoPlay = autoPlay,
        )
    }

    private suspend fun notifyPhoneTransferFailure(
        targetNodeId: String?,
        requestId: String,
        songId: String,
        message: String,
    ) {
        Timber.tag(TAG).d(
            "Rejecting transfer requestId=%s songId=%s: %s",
            requestId,
            songId,
            message,
        )
        notifyPhoneTransferOutcome(
            targetNodeId = targetNodeId,
            requestId = requestId,
            songId = songId,
            status = WearTransferProgress.STATUS_FAILED,
            error = message,
        )
    }

    /**
     * Tells the phone what actually happened to a transfer, over the same
     * [WearDataPaths.TRANSFER_PROGRESS] path the phone uses to push its own send-side progress —
     * the phone side ([com.theveloper.pixelplay.data.service.wear.WearCommandReceiver], app
     * module) has a matching handler for messages arriving *from* the watch on this path. This is
     * the only source of truth the phone trusts for whether a save-to-library transfer really
     * landed: the phone no longer marks a song "present on watch" purely because it finished
     * writing bytes to the channel — see `PhoneDirectWatchTransferCoordinator.streamFileToWatch`.
     * Best-effort: if this message itself gets lost, the phone's own await timeout in
     * `PlaylistWatchTransferCoordinator.transferSongToNode` still fails the request instead of
     * hanging forever, and the coordinator's existing retry-once logic picks it up from there.
     */
    private suspend fun notifyPhoneTransferOutcome(
        targetNodeId: String?,
        requestId: String,
        songId: String,
        status: String,
        error: String? = null,
    ) {
        if (targetNodeId == null) return

        runCatching {
            val progress = WearTransferProgress(
                requestId = requestId,
                songId = songId,
                bytesTransferred = 0L,
                totalBytes = 0L,
                status = status,
                error = error,
            )
            messageClient.sendMessage(
                targetNodeId,
                WearDataPaths.TRANSFER_PROGRESS,
                json.encodeToString(progress).toByteArray(Charsets.UTF_8),
            ).await()
        }.onFailure { sendError ->
            Timber.tag(TAG).w(sendError, "Failed to report transfer outcome (%s) to phone", status)
        }
    }

    /**
     * Called when metadata arrives from the phone (before the audio channel opens).
     */
    suspend fun onMetadataReceived(
        metadata: WearTransferMetadata,
        sourceNodeId: String? = null,
    ) {
        if (sourceNodeId != null) {
            requestIdToSourceNode[metadata.requestId] = sourceNodeId
        }
        if (isTransferCancelled(metadata.requestId)) {
            cleanupCancelledTransfer(metadata.requestId, metadata.songId)
            return
        }
        val errorMsg = metadata.error
        if (errorMsg != null) {
            Timber.tag(TAG).w("Transfer rejected by phone: $errorMsg")
            handleTransferError(metadata.requestId, metadata.songId, errorMsg)
            return
        }
        val existingSong = localSongDao.getSongById(metadata.songId)
        if (
            metadata.transferMode == WearTransferRequest.MODE_SAVE_TO_LIBRARY &&
            existingSong?.hasPlayableLocalFile() == true
        ) {
            // handleTransferError() below reports this outcome to the phone itself now, using
            // requestIdToSourceNode (populated a few lines up) — no separate notify call needed.
            handleTransferError(
                requestId = metadata.requestId,
                songId = metadata.songId,
                message = WearTransferProgress.ERROR_ALREADY_ON_WATCH,
            )
            return
        }

        pendingMetadata[metadata.requestId] = metadata
        armTransferWatchdog(metadata.requestId, metadata.songId)
        if (sourceNodeId != null) {
            notifyPhoneTransferOutcome(
                targetNodeId = sourceNodeId,
                requestId = metadata.requestId,
                songId = metadata.songId,
                status = WearTransferProgress.STATUS_METADATA_RECEIVED,
            )
        }
        _activeTransfers.update { map ->
            val current = map[metadata.requestId] ?: TransferState(
                requestId = metadata.requestId,
                songId = metadata.songId,
                songTitle = metadata.title,
                bytesTransferred = 0L,
                totalBytes = metadata.fileSize,
                status = WearTransferProgress.STATUS_TRANSFERRING,
            )
            map + (metadata.requestId to current.copy(
                songTitle = metadata.title,
                totalBytes = metadata.fileSize,
                status = WearTransferProgress.STATUS_TRANSFERRING,
            ))
        }
        Timber.tag(TAG).d(
            "Metadata received: ${metadata.title} (${metadata.fileSize} bytes)"
        )
    }

    /**
     * Called when progress updates arrive from the phone during streaming.
     */
    fun onProgressReceived(progress: WearTransferProgress) {
        if (progress.status == WearTransferProgress.STATUS_CANCELLED) {
            rememberCancelledRequest(progress.requestId)
            cleanupCancelledTransfer(progress.requestId, progress.songId)
            return
        }
        if (isTransferCancelled(progress.requestId)) {
            return
        }
        val normalizedStatus = if (
            progress.status == WearTransferProgress.STATUS_COMPLETED &&
            activeChannelRequestIds.contains(progress.requestId)
        ) {
            WearTransferProgress.STATUS_TRANSFERRING
        } else {
            progress.status
        }

        _activeTransfers.update { map ->
            val current = map[progress.requestId] ?: TransferState(
                requestId = progress.requestId,
                songId = progress.songId,
                songTitle = pendingMetadata[progress.requestId]?.title.orEmpty(),
                bytesTransferred = 0L,
                totalBytes = 0L,
                status = normalizedStatus,
            )
            map + (progress.requestId to current.copy(
                bytesTransferred = maxOf(current.bytesTransferred, progress.bytesTransferred),
                totalBytes = maxOf(current.totalBytes, progress.totalBytes),
                status = normalizedStatus,
                error = progress.error,
            ))
        }

        if (normalizedStatus == WearTransferProgress.STATUS_FAILED) {
            val songId = progress.songId
            val error = progress.error ?: "Transfer failed"
            scope.launch { handleTransferError(progress.requestId, songId, error) }
        } else if (
            normalizedStatus == WearTransferProgress.STATUS_COMPLETED ||
            normalizedStatus == WearTransferProgress.STATUS_CANCELLED
        ) {
            clearTransferWatchdog(progress.requestId)
        } else {
            armTransferWatchdog(progress.requestId, progress.songId)
        }
    }

    fun receiveAudioChannel(channel: ChannelClient.Channel) {
        scope.launch {
            runCatching {
                channelClient.getInputStream(channel).await().use { inputStream ->
                    val requestId = readLengthPrefixedString(inputStream, "requestId")
                    Timber.tag(TAG).d("Audio transfer channel: requestId=%s", requestId)
                    onAudioChannelOpened(requestId, inputStream)
                }
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "Failed to receive audio transfer channel")
            }
            runCatching { channelClient.close(channel).await() }
                .onFailure { error -> Timber.tag(TAG).w(error, "Failed to close audio transfer channel") }
        }
    }

    fun receiveArtworkChannel(channel: ChannelClient.Channel) {
        scope.launch {
            runCatching {
                channelClient.getInputStream(channel).await().use { stream ->
                    val requestId = readLengthPrefixedString(stream, "requestId")
                    val songId = readLengthPrefixedString(stream, "songId")
                    val artworkBytes = stream.readBytesSafely()
                    if (artworkBytes.isNotEmpty()) {
                        onArtworkReceived(
                            requestId = requestId,
                            songId = songId,
                            artworkBytes = artworkBytes,
                        )
                        Timber.tag(TAG).d(
                            "Artwork received for requestId=%s, bytes=%d",
                            requestId,
                            artworkBytes.size,
                        )
                    } else {
                        Timber.tag(TAG).d("Artwork stream empty for requestId=%s", requestId)
                    }
                }
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "Failed to receive artwork transfer channel")
            }
            runCatching { channelClient.close(channel).await() }
                .onFailure { error -> Timber.tag(TAG).w(error, "Failed to close artwork transfer channel") }
        }
    }

    /**
     * Called when a ChannelClient channel is opened by the phone.
     * Reads the audio stream, writes it to local storage, and inserts into Room.
     */
    private suspend fun onAudioChannelOpened(requestId: String, inputStream: InputStream) {
        activeChannelRequestIds.add(requestId)
        if (isTransferCancelled(requestId)) {
            inputStream.close()
            cleanupCancelledTransfer(
                requestId = requestId,
                songId = _activeTransfers.value[requestId]?.songId,
            )
            activeChannelRequestIds.remove(requestId)
            return
        }

        val musicDir = File(application.filesDir, "music")
        if (!musicDir.exists()) musicDir.mkdirs()
        val tempFile = File(musicDir, "$requestId.part")
        var metadata: WearTransferMetadata? = pendingMetadata[requestId]
        openAudioStreams[requestId] = inputStream

        try {
            if (isTransferCancelled(requestId)) {
                inputStream.close()
                cleanupCancelledTransfer(
                    requestId = requestId,
                    songId = metadata?.songId ?: _activeTransfers.value[requestId]?.songId,
                )
                return
            }

            metadata?.let { availableMetadata ->
                _activeTransfers.update { map ->
                    val current = map[requestId] ?: TransferState(
                        requestId = requestId,
                        songId = availableMetadata.songId,
                        songTitle = availableMetadata.title,
                        bytesTransferred = 0L,
                        totalBytes = availableMetadata.fileSize,
                        status = WearTransferProgress.STATUS_TRANSFERRING,
                    )
                    map + (requestId to current.copy(
                        songId = availableMetadata.songId,
                        songTitle = availableMetadata.title,
                        totalBytes = maxOf(current.totalBytes, availableMetadata.fileSize),
                        status = WearTransferProgress.STATUS_TRANSFERRING,
                    ))
                }
            }

            var totalReceived = 0L
            var lastProgressUpdateAtBytes = 0L
            var lastWatchdogTouchAt = SystemClock.elapsedRealtime()
            var cancelledDuringStream = false
            armTransferWatchdog(
                requestId = requestId,
                songId = metadata?.songId ?: _activeTransfers.value[requestId]?.songId.orEmpty(),
            )

            tempFile.outputStream().use { fileOut ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isTransferCancelled(requestId)) {
                        cancelledDuringStream = true
                        break
                    }
                    fileOut.write(buffer, 0, bytesRead)
                    totalReceived += bytesRead

                    if (metadata == null) {
                        pendingMetadata[requestId]?.let { availableMetadata ->
                            metadata = availableMetadata
                            _activeTransfers.update { map ->
                                val current = map[requestId] ?: return@update map
                                map + (requestId to current.copy(
                                    songId = availableMetadata.songId,
                                    songTitle = availableMetadata.title,
                                    totalBytes = maxOf(current.totalBytes, availableMetadata.fileSize),
                                    status = WearTransferProgress.STATUS_TRANSFERRING,
                                ))
                            }
                        }
                    }

                    if (totalReceived - lastProgressUpdateAtBytes >= LOCAL_PROGRESS_UPDATE_INTERVAL_BYTES) {
                        _activeTransfers.update { map ->
                            val current = map[requestId] ?: return@update map
                            map + (requestId to current.copy(
                                bytesTransferred = maxOf(current.bytesTransferred, totalReceived),
                                totalBytes = maxOf(current.totalBytes, metadata?.fileSize ?: current.totalBytes),
                                status = WearTransferProgress.STATUS_TRANSFERRING,
                            ))
                        }
                        lastProgressUpdateAtBytes = totalReceived
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastWatchdogTouchAt >= WATCHDOG_TOUCH_INTERVAL_MS) {
                        armTransferWatchdog(
                            requestId = requestId,
                            songId = metadata?.songId ?: _activeTransfers.value[requestId]?.songId.orEmpty(),
                        )
                        lastWatchdogTouchAt = now
                    }
                }
            }
            inputStream.close()

            if (cancelledDuringStream || isTransferCancelled(requestId)) {
                if (tempFile.exists() && !tempFile.delete()) {
                    Timber.tag(TAG).w(
                        "Failed to delete partial cancelled transfer for requestId=%s",
                        requestId,
                    )
                }
                cleanupCancelledTransfer(
                    requestId = requestId,
                    songId = metadata?.songId ?: _activeTransfers.value[requestId]?.songId,
                )
                return
            }

            val actualSize = tempFile.length()
            if (actualSize == 0L) {
                tempFile.delete()
                handleTransferError(
                    requestId = requestId,
                    songId = metadata?.songId ?: _activeTransfers.value[requestId]?.songId.orEmpty(),
                    message = "Empty file received",
                )
                return
            }

            val resolvedMetadata = metadata?.also { pendingMetadata.remove(requestId) } ?: awaitMetadata(requestId)
            if (resolvedMetadata == null) {
                tempFile.delete()
                val songId = _activeTransfers.value[requestId]?.songId
                if (isTransferCancelled(requestId)) {
                    cleanupCancelledTransfer(requestId, songId)
                } else if (!songId.isNullOrBlank()) {
                    handleTransferError(requestId, songId, "Transfer metadata missing")
                } else {
                    Timber.tag(TAG).w("No pending metadata for requestId=%s after draining audio", requestId)
                    _activeTransfers.update { it - requestId }
                    clearTransferWatchdog(requestId)
                }
                return
            }

            // The read loop exiting cleanly is not proof the whole file arrived: armTransferWatchdog
            // interrupts a stuck transfer by closing this very stream, and a closed channel stream's
            // blocking read() can return -1 instead of throwing — which walks straight out of the
            // loop and down this path with a partial .part file. Committing that would rename a
            // truncated song into the music dir, insert it into Room as playable, and report
            // STATUS_COMPLETED to the phone, so the phone would mark it present on the watch and
            // never retry it.
            // Only short files are rejected, never long ones: fileSize comes from whatever source
            // the phone opened and is 0 (unknown) for a content uri that won't report a length,
            // so treating "more bytes than announced" as corruption would fail transfers that are
            // actually fine. A file that stops short of its announced size is unambiguous.
            val expectedSize = resolvedMetadata.fileSize
            val isTruncated = if (expectedSize > 0L) {
                actualSize < expectedSize
            } else {
                watchdogTimedOutRequestIds.contains(requestId)
            }
            if (isTruncated) {
                tempFile.delete()
                Timber.tag(TAG).w(
                    "Incomplete transfer discarded: requestId=%s received=%d expected=%d",
                    requestId,
                    actualSize,
                    expectedSize,
                )
                handleTransferError(
                    requestId = requestId,
                    songId = resolvedMetadata.songId,
                    message = if (watchdogTimedOutRequestIds.contains(requestId)) {
                        "Transfer timed out"
                    } else {
                        "Incomplete file received"
                    },
                )
                return
            }

            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(resolvedMetadata.mimeType) ?: "mp3"
            if (resolvedMetadata.transferMode == WearTransferRequest.MODE_TEMPORARY_PLAYBACK) {
                val playbackDir = File(application.cacheDir, "temporary_playback")
                if (!playbackDir.exists()) playbackDir.mkdirs()
                val playbackFile = File(playbackDir, "$requestId.$extension")
                if (playbackFile.exists() && !playbackFile.delete()) {
                    tempFile.delete()
                    handleTransferError(requestId, resolvedMetadata.songId, "Couldn't replace temporary playback file")
                    return
                }

                if (playbackFile.absolutePath != tempFile.absolutePath) {
                    val renamed = tempFile.renameTo(playbackFile)
                    if (!renamed) {
                        runCatching {
                            tempFile.inputStream().use { input ->
                                playbackFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }.onFailure { error ->
                            tempFile.delete()
                            playbackFile.delete()
                            throw error
                        }
                        if (!tempFile.delete()) {
                            Timber.tag(TAG).w("Failed to delete temp playback staging file for requestId=%s", requestId)
                        }
                    }
                }

                val artworkPath = consumeAndPersistPendingArtwork(
                    requestId = requestId,
                    artworkKey = "temp_$requestId",
                )
                val pendingPlayback = pendingTemporaryPlaybackRequests.remove(requestId)
                val resolvedStartPositionMs = resolveTemporaryPlaybackStartPosition(
                    requestId = requestId,
                    metadata = resolvedMetadata,
                    pendingPlayback = pendingPlayback,
                )
                localPlayerRepository.playTemporarySong(
                    song = LocalSongEntity(
                        songId = resolvedMetadata.songId,
                        title = resolvedMetadata.title,
                        artist = resolvedMetadata.artist,
                        album = resolvedMetadata.album,
                        albumId = resolvedMetadata.albumId,
                        duration = resolvedMetadata.duration,
                        mimeType = resolvedMetadata.mimeType,
                        fileSize = actualSize,
                        bitrate = resolvedMetadata.bitrate,
                        sampleRate = resolvedMetadata.sampleRate,
                        isFavorite = resolvedMetadata.isFavorite,
                        favoriteSyncPending = false,
                        paletteSeedArgb = resolvedMetadata.paletteSeedArgb,
                        themePaletteJson = resolvedMetadata.themePalette?.let { json.encodeToString(it) },
                        artworkPath = artworkPath,
                        localPath = playbackFile.absolutePath,
                        transferredAt = System.currentTimeMillis(),
                    ),
                    startPositionMs = resolvedStartPositionMs,
                    autoPlay = pendingPlayback?.autoPlay ?: resolvedMetadata.autoPlay,
                )
                stateRepository.setOutputTarget(WearOutputTarget.WATCH)
                if (pendingPlayback?.pausePhoneAfterStart == true) {
                    playbackController.pause()
                }
                _activeTransfers.update { it - requestId }
                songToRequestId.remove(resolvedMetadata.songId)
                clearTransferWatchdog(requestId)
                // Temporary playback doesn't participate in the phone's "is this song saved on
                // watch" bookkeeping, so it skips the outcome round-trip — just drop the
                // now-unneeded node mapping.
                requestIdToSourceNode.remove(requestId)
                Timber.tag(TAG).d(
                    "Temporary playback ready: %s (%d bytes) → %s",
                    resolvedMetadata.title,
                    actualSize,
                    playbackFile.absolutePath,
                )
                return
            }

            val localFile = File(musicDir, "${resolvedMetadata.songId}.$extension")
            val previousSong = localSongDao.getSongById(resolvedMetadata.songId)

            if (localFile.exists() && localFile.absolutePath != tempFile.absolutePath && !localFile.delete()) {
                tempFile.delete()
                handleTransferError(requestId, resolvedMetadata.songId, "Couldn't replace existing local file")
                return
            }

            if (localFile.absolutePath != tempFile.absolutePath) {
                val renamed = tempFile.renameTo(localFile)
                if (!renamed) {
                    runCatching {
                        tempFile.inputStream().use { input ->
                            localFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }.onFailure { error ->
                        tempFile.delete()
                        localFile.delete()
                        throw error
                    }
                    if (!tempFile.delete()) {
                        Timber.tag(TAG).w("Failed to delete temp transfer file for requestId=%s", requestId)
                    }
                }
            }

            val artworkPath = consumeAndPersistPendingArtwork(
                requestId = requestId,
                artworkKey = resolvedMetadata.songId,
            )

            localSongDao.insert(
                LocalSongEntity(
                    songId = resolvedMetadata.songId,
                    title = resolvedMetadata.title,
                    artist = resolvedMetadata.artist,
                    album = resolvedMetadata.album,
                    albumId = resolvedMetadata.albumId,
                    duration = resolvedMetadata.duration,
                    mimeType = resolvedMetadata.mimeType,
                    fileSize = actualSize,
                    bitrate = resolvedMetadata.bitrate,
                    sampleRate = resolvedMetadata.sampleRate,
                    isFavorite = resolvedMetadata.isFavorite,
                    favoriteSyncPending = false,
                    paletteSeedArgb = resolvedMetadata.paletteSeedArgb,
                    themePaletteJson = resolvedMetadata.themePalette?.let { json.encodeToString(it) },
                    artworkPath = artworkPath,
                    localPath = localFile.absolutePath,
                    transferredAt = System.currentTimeMillis(),
                )
            )

            cleanupReplacedSongFiles(
                previousSong = previousSong,
                currentAudioPath = localFile.absolutePath,
                currentArtworkPath = artworkPath,
            )

            _activeTransfers.update { it - requestId }
            songToRequestId.remove(resolvedMetadata.songId)
            clearTransferWatchdog(requestId)

            // Only now — file written and inserted into Room, genuinely playable — does the
            // phone get told this landed. It's what unblocks PlaylistWatchTransferCoordinator's
            // await and is the only trigger for markSongPresentOnWatch() on the phone side.
            requestIdToSourceNode.remove(requestId)?.let { nodeId ->
                notifyPhoneTransferOutcome(
                    targetNodeId = nodeId,
                    requestId = requestId,
                    songId = resolvedMetadata.songId,
                    status = WearTransferProgress.STATUS_COMPLETED,
                )
            }

            Timber.tag(TAG).d(
                "Transfer complete: ${resolvedMetadata.title} ($actualSize bytes) → ${localFile.absolutePath}"
            )
        } catch (e: Exception) {
            val timedOut = watchdogTimedOutRequestIds.remove(requestId)
            Timber.tag(TAG).e(e, "Failed to write transferred file")
            tempFile.delete()
            handleTransferError(
                requestId = requestId,
                songId = metadata?.songId ?: _activeTransfers.value[requestId]?.songId.orEmpty(),
                message = if (timedOut) "Transfer timed out" else (e.message ?: "Write failed"),
            )
        } finally {
            activeChannelRequestIds.remove(requestId)
            openAudioStreams.remove(requestId)
            watchdogTimedOutRequestIds.remove(requestId)
        }
    }

    /**
     * Delete a locally stored song (file + Room entry).
     */
    suspend fun deleteSong(songId: String): Result<Unit> {
        val song = localSongDao.getSongById(songId)
            ?: return Result.failure(IllegalArgumentException("Song not found on watch"))
        val file = File(song.localPath)
        if (file.exists() && !file.delete()) {
            return Result.failure(IllegalStateException("Couldn't remove the song file from watch"))
        }
        song.artworkPath?.let { artwork ->
            val artworkFile = File(artwork)
            if (artworkFile.exists() && !artworkFile.delete()) {
                Timber.tag(TAG).w("Artwork cleanup failed for songId=%s path=%s", songId, artwork)
            }
        }
        localSongDao.deleteById(songId)
        Timber.tag(TAG).d("Deleted local song: ${song.title}")
        return Result.success(Unit)
    }

    /**
     * Get total storage used by transferred songs.
     */
    suspend fun getStorageUsed(): Long {
        return localSongDao.getTotalStorageUsed() ?: 0L
    }

    /**
     * Cancel an in-progress transfer.
     */
    fun cancelTransfer(requestId: String, notifyPhone: Boolean = true) {
        scope.launch {
            val state = _activeTransfers.value[requestId]
            val songId = state?.songId ?: pendingMetadata[requestId]?.songId
            rememberCancelledRequest(requestId)
            try {
                if (notifyPhone) {
                    val cancelRequest = WearTransferRequest(
                        requestId = requestId,
                        songId = songId.orEmpty(),
                    )
                    val cancelBytes = json.encodeToString(cancelRequest).toByteArray(Charsets.UTF_8)
                    val nodes = nodeClient.connectedNodes.await()
                    nodes.forEach { node ->
                        messageClient.sendMessage(
                            node.id,
                            WearDataPaths.TRANSFER_CANCEL,
                            cancelBytes,
                        ).await()
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to send cancel request")
            }
            cleanupCancelledTransfer(requestId, songId)
        }
    }

    /**
     * Clears a finished (failed/cancelled) transfer from [activeTransfers] so its chip stops
     * showing in the Downloads screen's "issues" section. Purely a local UI dismissal — doesn't
     * touch the phone's own bookkeeping of what's actually saved, which already reflects reality
     * independently (see [notifyPhoneTransferOutcome]). Safe to call for any requestId; a no-op
     * if it's already gone or still in progress (nothing to dismiss either way).
     */
    fun dismissTransfer(requestId: String) {
        _activeTransfers.update { map ->
            val current = map[requestId] ?: return@update map
            if (current.status == WearTransferProgress.STATUS_TRANSFERRING) return@update map
            map - requestId
        }
    }

    suspend fun publishLibraryState(
        targetNodeId: String? = null,
        songIds: Set<String>? = null,
    ) {
        val snapshotSongIds = songIds ?: localSongDao.getAllSongIds().first().toSet()
        val payload = json.encodeToString(
            WearLibraryState(songIds = snapshotSongIds.sorted())
        ).toByteArray(Charsets.UTF_8)

        runCatching {
            if (targetNodeId != null) {
                messageClient.sendMessage(
                    targetNodeId,
                    WearDataPaths.WATCH_LIBRARY_STATE,
                    payload,
                ).await()
            } else {
                val nodes = nodeClient.connectedNodes.await()
                nodes.forEach { node ->
                    messageClient.sendMessage(
                        node.id,
                        WearDataPaths.WATCH_LIBRARY_STATE,
                        payload,
                    ).await()
                }
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to publish watch library state")
        }
    }

    /**
     * Called when a playlist sync arrives from the phone — sent once up front, before any of its
     * songs' audio has necessarily finished transferring, so the watch can show the playlist and
     * start playing whatever's already local right away. Idempotent: re-syncing the same
     * [WearPlaylistSync.playlistId] (e.g. after the user edits the playlist on the phone) replaces
     * membership/order in one transaction rather than merging with the stale cross-refs.
     *
     * [sourceNodeId] is where the ack goes back to. Acking is best-effort and never blocks or
     * fails this function — if [WearPlaylistSync.requestId] is empty (an old phone build) there's
     * nothing to correlate an ack to, so none is sent.
     */
    suspend fun onPlaylistSyncReceived(sync: WearPlaylistSync, sourceNodeId: String) {
        val now = System.currentTimeMillis()
        val existing = localPlaylistDao.getPlaylistById(sync.playlistId)
        val entity = LocalPlaylistEntity(
            playlistId = sync.playlistId,
            name = sync.name,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        val crossRefs = sync.songIds.mapIndexed { index, songId ->
            LocalPlaylistSongCrossRef(
                playlistId = sync.playlistId,
                songId = songId,
                position = index,
                // songTitles is a parallel list to songIds; an older phone build omits it
                // entirely (defaults to emptyList()), so this falls back to "" per song rather
                // than crashing on an index that isn't there.
                pendingTitle = sync.songTitles.getOrElse(index) { "" },
            )
        }
        localPlaylistDao.upsertPlaylist(entity, crossRefs)
        Timber.tag(TAG).d(
            "Playlist synced: %s (%d songs)",
            sync.name,
            sync.songIds.size,
        )

        if (sync.requestId.isNotEmpty()) {
            sendPlaylistSyncAck(sourceNodeId, sync.playlistId, sync.requestId)
        }
    }

    private suspend fun sendPlaylistSyncAck(nodeId: String, playlistId: String, requestId: String) {
        val ack = WearPlaylistSyncAck(playlistId = playlistId, requestId = requestId)
        try {
            val ackBytes = json.encodeToString(ack).toByteArray(Charsets.UTF_8)
            messageClient.sendMessage(nodeId, WearDataPaths.PLAYLIST_SYNC_ACK, ackBytes).await()
        } catch (e: Exception) {
            // Not retried here: if this is lost too, the phone's own await-ack timeout fires and
            // it resends the whole sync, which is idempotent — so the watch just gets another shot
            // at acking rather than needing its own retry logic for the ack itself.
            Timber.tag(TAG).w(e, "Failed to send playlist sync ack: playlistId=%s", playlistId)
        }
    }

    /**
     * Called when artwork bytes arrive over the dedicated artwork channel.
     * If song row exists, artwork is persisted immediately; otherwise cached until audio finishes.
     */
    suspend fun onArtworkReceived(requestId: String, songId: String, artworkBytes: ByteArray) {
        if (artworkBytes.isEmpty()) return
        armTransferWatchdog(requestId, songId)

        val existing = localSongDao.getSongById(songId)
        if (existing != null) {
            val artworkPath = persistArtwork(songId, artworkBytes)
            if (artworkPath != null) {
                if (existing.artworkPath != null && existing.artworkPath != artworkPath) {
                    runCatching { File(existing.artworkPath).delete() }
                }
                localSongDao.updateArtworkPath(songId, artworkPath)
                Timber.tag(TAG).d("Artwork updated for existing local song: songId=$songId")
            }
            return
        }

        pendingArtworkByRequestId[requestId] = artworkBytes
        Timber.tag(TAG).d("Artwork cached for pending transfer: requestId=$requestId")
    }

    private suspend fun awaitMetadata(requestId: String): WearTransferMetadata? {
        pendingMetadata.remove(requestId)?.let { return it }
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < METADATA_WAIT_TIMEOUT_MS) {
            delay(METADATA_POLL_INTERVAL_MS)
            pendingMetadata.remove(requestId)?.let { return it }
        }
        return null
    }

    private fun clearStaleTransfersForSong(songId: String) {
        val staleRequestIds = _activeTransfers.value.values
            .filter { it.songId == songId }
            .map { it.requestId }
        if (staleRequestIds.isEmpty()) return

        _activeTransfers.update { map ->
            map.filterValues { it.songId != songId }
        }
        staleRequestIds.forEach { staleRequestId ->
            pendingTemporaryPlaybackRequests.remove(staleRequestId)
            pendingMetadata.remove(staleRequestId)
            pendingArtworkByRequestId.remove(staleRequestId)
            clearTransferWatchdog(staleRequestId)
            activeChannelRequestIds.remove(staleRequestId)
        }
    }

    private fun isTransferCancelled(requestId: String): Boolean {
        return cancelledRequestIds.contains(requestId)
    }

    private fun rememberCancelledRequest(requestId: String) {
        cancelledRequestIds.add(requestId)
        scope.launch {
            delay(CANCELLED_REQUEST_RETENTION_MS)
            cancelledRequestIds.remove(requestId)
        }
    }

    private fun cleanupCancelledTransfer(requestId: String, songId: String?) {
        _activeTransfers.update { it - requestId }
        songId?.takeIf { it.isNotBlank() }?.let { safeSongId ->
            songToRequestId.remove(safeSongId)
        }
        pendingTemporaryPlaybackRequests.remove(requestId)
        pendingMetadata.remove(requestId)
        pendingArtworkByRequestId.remove(requestId)
        clearTransferWatchdog(requestId)
        activeChannelRequestIds.remove(requestId)
        openAudioStreams.remove(requestId)
        watchdogTimedOutRequestIds.remove(requestId)
    }

    private suspend fun handleTransferError(requestId: String, songId: String, message: String) {
        Timber.tag(TAG).e("Transfer error: $message (requestId=$requestId, songId=$songId)")
        _activeTransfers.update { map ->
            val current = map[requestId]
            if (current != null) {
                map + (requestId to current.copy(
                    status = WearTransferProgress.STATUS_FAILED,
                    error = message,
                ))
            } else {
                map
            }
        }
        songToRequestId.remove(songId)
        pendingTemporaryPlaybackRequests.remove(requestId)
        pendingMetadata.remove(requestId)
        pendingArtworkByRequestId.remove(requestId)
        clearTransferWatchdog(requestId)
        activeChannelRequestIds.remove(requestId)
        openAudioStreams.remove(requestId)
        watchdogTimedOutRequestIds.remove(requestId)
        // Single funnel for every failure reason (empty file, metadata never arrived, write
        // errors, duplicate rejection, idle timeout, ...) — the phone needs the real outcome so
        // it stops believing a song landed just because it finished sending bytes. See
        // notifyPhoneTransferOutcome's doc for why this is the source of truth now.
        requestIdToSourceNode.remove(requestId)?.let { nodeId ->
            notifyPhoneTransferOutcome(
                targetNodeId = nodeId,
                requestId = requestId,
                songId = songId,
                status = WearTransferProgress.STATUS_FAILED,
                error = message,
            )
        }
    }

    private fun resolveTemporaryPlaybackStartPosition(
        requestId: String,
        metadata: WearTransferMetadata,
        pendingPlayback: PendingTemporaryPlaybackRequest?,
    ): Long {
        val initialPositionMs = pendingPlayback?.startPositionMs ?: metadata.startPositionMs
        if (!(pendingPlayback?.autoPlay ?: metadata.autoPlay)) {
            return initialPositionMs.coerceAtLeast(0L)
        }

        val requestedAtElapsedMs = pendingPlayback?.requestedAtElapsedMs
            ?: return initialPositionMs.coerceAtLeast(0L)
        val elapsedMs = (SystemClock.elapsedRealtime() - requestedAtElapsedMs).coerceAtLeast(0L)
        val adjustedPositionMs = initialPositionMs.coerceAtLeast(0L) + elapsedMs
        val clampedPositionMs = if (metadata.duration > 0L) {
            adjustedPositionMs.coerceAtMost(metadata.duration)
        } else {
            adjustedPositionMs
        }
        Timber.tag(TAG).d(
            "Resolved temporary playback start for requestId=%s: base=%d elapsed=%d final=%d",
            requestId,
            initialPositionMs,
            elapsedMs,
            clampedPositionMs,
        )
        return clampedPositionMs
    }

    private fun LocalSongEntity.hasPlayableLocalFile(): Boolean {
        val file = File(localPath)
        return file.isFile && file.length() > 0L
    }

    private fun consumeAndPersistPendingArtwork(requestId: String, artworkKey: String): String? {
        val artworkBytes = pendingArtworkByRequestId.remove(requestId) ?: return null
        return persistArtwork(artworkKey, artworkBytes)
    }

    private fun persistArtwork(songId: String, artworkBytes: ByteArray): String? {
        return try {
            val artworkDir = File(application.filesDir, "artwork")
            if (!artworkDir.exists()) artworkDir.mkdirs()
            val artworkFile = File(artworkDir, "$songId.$ARTWORK_FILE_EXTENSION")
            artworkFile.outputStream().use { output ->
                output.write(artworkBytes)
                output.flush()
            }
            if (artworkFile.length() <= 0L) {
                artworkFile.delete()
                null
            } else {
                artworkFile.absolutePath
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to persist artwork for songId=$songId")
            null
        }
    }

    private fun cleanupReplacedSongFiles(
        previousSong: LocalSongEntity?,
        currentAudioPath: String,
        currentArtworkPath: String?,
    ) {
        if (previousSong == null) return
        if (previousSong.localPath != currentAudioPath) {
            runCatching { File(previousSong.localPath).delete() }
        }
        val oldArtwork = previousSong.artworkPath
        if (oldArtwork != null && oldArtwork != currentArtworkPath) {
            runCatching { File(oldArtwork).delete() }
        }
    }

    private fun armTransferWatchdog(requestId: String, songId: String) {
        clearTransferWatchdog(requestId)
        transferWatchdogs[requestId] = scope.launch {
            delay(TRANSFER_IDLE_TIMEOUT_MS)
            if (_activeTransfers.value.containsKey(requestId)) {
                val stream = openAudioStreams[requestId]
                if (stream != null) {
                    // A live audio stream is genuinely stuck: close it so the blocking
                    // read() in onAudioChannelOpened unblocks with an IOException and routes
                    // through that function's own catch block for cleanup — a single,
                    // consistent path instead of this watchdog declaring failure on its own
                    // while the read loop keeps running in the background, unaware anything
                    // happened. That's what let a "failed" transfer keep going and finish
                    // seconds later anyway, or worse, strip pendingMetadata out from under
                    // the still-running loop and turn a slow-but-fine transfer into a real
                    // failure ("Transfer metadata missing" from the loop's own metadata
                    // resolution not finding what this watchdog had just cleared).
                    watchdogTimedOutRequestIds.add(requestId)
                    runCatching { stream.close() }
                } else {
                    // No audio stream open yet (still waiting on metadata/channel) — nothing
                    // to interrupt, so this is still the right place to declare failure.
                    handleTransferError(requestId, songId, "Transfer timed out")
                }
            }
        }
    }

    private fun clearTransferWatchdog(requestId: String) {
        transferWatchdogs.remove(requestId)?.cancel()
    }

    private fun readLengthPrefixedString(inputStream: InputStream, label: String): String {
        val lengthBytes = ByteArray(4)
        var totalRead = 0
        while (totalRead < 4) {
            val read = inputStream.read(lengthBytes, totalRead, 4 - totalRead)
            if (read == -1) throw Exception("Stream ended before $label length")
            totalRead += read
        }

        val length = java.nio.ByteBuffer.wrap(lengthBytes).int
        if (length < 0 || length > 1024 * 1024) {
            throw Exception("Invalid $label length: $length")
        }

        val data = ByteArray(length)
        totalRead = 0
        while (totalRead < length) {
            val read = inputStream.read(data, totalRead, length - totalRead)
            if (read == -1) throw Exception("Stream ended before $label data")
            totalRead += read
        }
        return String(data, Charsets.UTF_8)
    }

    private fun InputStream.readBytesSafely(): ByteArray {
        val buffer = ByteArray(8192)
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
