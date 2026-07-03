package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.shared.WearCapabilities
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearPlaylistSync
import com.theveloper.pixelplay.shared.WearTransferProgress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Orchestrates sending a whole playlist to the watch: syncs the playlist's membership/order
 * first (so the watch can show it even before every song has arrived), then transfers songs
 * that aren't already on the watch one at a time — never in parallel, to avoid saturating the
 * single Bluetooth channel and spiking CPU/battery on the watch (see [WatchAudioTranscoder]).
 *
 * Reuses the existing single-song pipeline end to end: [WatchAudioTranscoder] decides/produces
 * the audio to send, and [PhoneDirectWatchTransferCoordinator] still owns the actual chunked
 * ChannelClient streaming (via its `overrideAudioFile` hook) and per-song cancellation.
 */
@Singleton
class PlaylistWatchTransferCoordinator @Inject constructor(
    private val application: Application,
    private val musicRepository: MusicRepository,
    private val watchAudioTranscoder: WatchAudioTranscoder,
    private val directTransferCoordinator: PhoneDirectWatchTransferCoordinator,
    private val wearPhoneTransferSender: WearPhoneTransferSender,
    private val transferStateStore: PhoneWatchTransferStateStore,
) {
    private val capabilityClient by lazy { Wearable.getCapabilityClient(application) }
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(application) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val cancelledBatchIds = ConcurrentHashMap.newKeySet<String>()

    /** Returns the generated batchId immediately; the transfer itself runs asynchronously. */
    fun requestPlaylistTransfer(playlistId: String, playlistName: String, songIds: List<String>): String {
        val batchId = UUID.randomUUID().toString()
        if (songIds.isEmpty()) return batchId

        scope.launch {
            runBatchTransfer(batchId, playlistId, playlistName, songIds)
        }
        return batchId
    }

    fun cancelPlaylistTransfer(batchId: String) {
        cancelledBatchIds.add(batchId)
        val activeRequestId = transferStateStore.batchTransfers.value[batchId]?.activeRequestId
        if (activeRequestId != null) {
            scope.launch { wearPhoneTransferSender.cancelTransfer(activeRequestId) }
        }
        transferStateStore.markBatchCancelled(batchId)
    }

    private suspend fun runBatchTransfer(
        batchId: String,
        playlistId: String,
        playlistName: String,
        songIds: List<String>,
    ) {
        val nodes = runCatching {
            capabilityClient.getCapability(
                WearCapabilities.PIXELPLAY_WEAR_APP,
                CapabilityClient.FILTER_REACHABLE,
            ).await().nodes.toList()
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Failed to resolve reachable watches for playlist transfer")
            emptyList()
        }

        transferStateStore.markBatchStarted(batchId, playlistId, playlistName, songIds.size)

        if (nodes.isEmpty()) {
            transferStateStore.markBatchFailed(batchId, "No reachable watch with PixelPlay")
            return
        }
        transferStateStore.retainReachableWatchNodes(nodes.map { it.id }.toSet())

        val syncPayload = json.encodeToString(WearPlaylistSync(playlistId, playlistName, songIds))
            .toByteArray(Charsets.UTF_8)
        nodes.forEach { node ->
            runCatching {
                messageClient.sendMessage(node.id, WearDataPaths.PLAYLIST_SYNC, syncPayload).await()
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to send playlist sync to node=%s", node.id)
            }
        }

        val alreadyPresentCount = songIds.count { transferStateStore.isSongSavedOnAllReachableWatches(it) }
        repeat(alreadyPresentCount) { transferStateStore.markBatchSongCompleted(batchId) }

        val pendingSongIds = songIds.filterNot { transferStateStore.isSongSavedOnAllReachableWatches(it) }

        for (songId in pendingSongIds) {
            if (cancelledBatchIds.contains(batchId)) break

            val song = musicRepository.getSongsByIds(listOf(songId)).first().firstOrNull()
            if (song == null) {
                Timber.tag(TAG).w("Song not found for playlist transfer: songId=%s", songId)
                continue
            }

            if (transferSongToAllNodes(batchId, nodes, song)) {
                transferStateStore.markBatchSongCompleted(batchId)
            }
        }

        cancelledBatchIds.remove(batchId)
        if (transferStateStore.batchTransfers.value[batchId]?.status != WearTransferProgress.STATUS_CANCELLED) {
            transferStateStore.markBatchCompleted(batchId)
        }
    }

    /** Transcodes [song] once (if needed) and streams it to every reachable [nodes] in turn. */
    private suspend fun transferSongToAllNodes(
        batchId: String,
        nodes: List<Node>,
        song: Song,
    ): Boolean {
        if (cancelledBatchIds.contains(batchId)) return false

        val transcodeRequestId = UUID.randomUUID().toString()
        transferStateStore.markBatchSongStarted(batchId, transcodeRequestId, song.title)

        val transcodeResult = watchAudioTranscoder.transcodeIfNeeded(
            song = song,
            requestId = transcodeRequestId,
            onProgress = { fraction ->
                transferStateStore.markBatchSongProgress(batchId, WearTransferProgress.STATUS_TRANSCODING, fraction)
            },
        )
        if (transcodeResult is WatchAudioTranscoder.TranscodeResult.Failed) {
            Timber.tag(TAG).w(transcodeResult.error, "Transcode failed for songId=%s, skipping", song.id)
            return false
        }
        if (cancelledBatchIds.contains(batchId)) {
            watchAudioTranscoder.cleanup(transcodeResult)
            return false
        }

        val overrideAudioFile = (transcodeResult as? WatchAudioTranscoder.TranscodeResult.Transcoded)?.outputFile

        var succeededOnAnyNode = false
        for (node in nodes) {
            if (cancelledBatchIds.contains(batchId)) break
            succeededOnAnyNode = transferSongToNode(batchId, node, song, overrideAudioFile) || succeededOnAnyNode
        }

        watchAudioTranscoder.cleanup(transcodeResult)
        return succeededOnAnyNode
    }

    private suspend fun transferSongToNode(
        batchId: String,
        node: Node,
        song: Song,
        overrideAudioFile: java.io.File?,
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        transferStateStore.markBatchSongStarted(batchId, requestId, song.title)

        val progressWatcherJob: Job = scope.launch {
            transferStateStore.transfers
                .mapNotNull { it[requestId] }
                .collect { state ->
                    if (state.status == WearTransferProgress.STATUS_TRANSFERRING) {
                        transferStateStore.markBatchSongProgress(batchId, state.status, state.progress)
                    }
                }
        }

        directTransferCoordinator.startTransferToWatch(
            nodeId = node.id,
            requestId = requestId,
            songId = song.id,
            overrideAudioFile = overrideAudioFile,
        )

        val finalState = transferStateStore.transfers
            .mapNotNull { it[requestId] }
            .first { it.status in TERMINAL_STATUSES }
        progressWatcherJob.cancel()

        return finalState.status == WearTransferProgress.STATUS_COMPLETED
    }

    private companion object {
        const val TAG = "PlaylistWatchTransfer"
        val TERMINAL_STATUSES = setOf(
            WearTransferProgress.STATUS_COMPLETED,
            WearTransferProgress.STATUS_FAILED,
            WearTransferProgress.STATUS_CANCELLED,
        )
    }
}
