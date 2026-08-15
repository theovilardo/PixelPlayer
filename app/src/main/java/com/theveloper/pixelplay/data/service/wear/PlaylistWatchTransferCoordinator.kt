package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.shared.WearCapabilities
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearPlaylistSync
import com.theveloper.pixelplay.shared.WearPlaylistSyncAck
import com.theveloper.pixelplay.shared.WearTransferProgress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Orchestrates sending a whole playlist to the watch: syncs the playlist's membership/order
 * first (so the watch can show it, and start playing it, before every song has arrived), then
 * transfers songs that aren't already on the watch one at a time — never in parallel, to avoid
 * saturating the single Bluetooth channel and spiking CPU/battery on the watch (see
 * [WatchAudioTranscoder]'s doc for why the encode itself is also sequential per song).
 *
 * Reuses the existing single-song pipeline end to end: [WatchAudioTranscoder] decides/produces
 * the audio to send, and [PhoneDirectWatchTransferCoordinator] still owns the actual chunked
 * ChannelClient streaming (via its [PhoneDirectWatchTransferCoordinator.WatchAudioOverride] hook)
 * and per-song cancellation.
 */
@Singleton
class PlaylistWatchTransferCoordinator @Inject constructor(
    private val application: Application,
    private val musicRepository: MusicRepository,
    private val watchAudioTranscoder: WatchAudioTranscoder,
    private val directTransferCoordinator: PhoneDirectWatchTransferCoordinator,
    private val wearPhoneTransferSender: WearPhoneTransferSender,
    private val transferStateStore: PhoneWatchTransferStateStore,
    private val batchPersistence: PlaylistBatchTransferPersistence,
    // Injected directly (unlike most of this package, which resolves these via
    // Wearable.getXClient(application) internally) so this coordinator is constructible with
    // fakes in tests without needing to mock a static Java method.
    private val capabilityClient: CapabilityClient,
    private val messageClient: MessageClient,
    @AppScope private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cancelledBatchIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Deliberately an instance property, not a companion `const`/`var`: tests shrink it on their
     * own coordinator instance, so runs never leak a mutated timeout into unrelated tests the way
     * a shared static field would.
     */
    internal var songTransferAwaitTimeoutMs: Long = DEFAULT_SONG_TRANSFER_AWAIT_TIMEOUT_MS

    /** Returns the generated batchId immediately; the transfer itself runs asynchronously. */
    fun requestPlaylistTransfer(playlistId: String, playlistName: String, songIds: List<String>): String =
        startBatch(
            playlistId = playlistId,
            playlistName = playlistName,
            songIds = songIds,
            requestedAtMillis = System.currentTimeMillis(),
            isResume = false,
        )

    private fun startBatch(
        playlistId: String,
        playlistName: String,
        songIds: List<String>,
        requestedAtMillis: Long,
        isResume: Boolean,
    ): String {
        val batchId = UUID.randomUUID().toString()
        if (songIds.isEmpty()) return batchId

        scope.launch {
            runBatchTransfer(batchId, playlistId, playlistName, songIds, requestedAtMillis, isResume)
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
        scope.launch { batchPersistence.clearInFlightBatch(batchId) }
    }

    /**
     * Called once at process start ([com.theveloper.pixelplay.PixelPlayApplication]). If the
     * process died mid-transfer last time, [PlaylistBatchTransferPersistence] still has that
     * batch's intent — re-running it from scratch is safe and correct: the watch itself rejects
     * a duplicate transfer for a song it already has (`ERROR_ALREADY_ON_WATCH`), and
     * [runBatchTransfer] already skips anything [PhoneWatchTransferStateStore] can confirm is
     * already there. That confirmation is only as good as the watch-library snapshot in memory —
     * empty right after a cold start — so this waits (briefly) for a fresh one before resuming,
     * instead of re-attempting everything and relying solely on the watch's own rejection.
     */
    suspend fun resumePersistedBatchIfNeeded() {
        val persisted = batchPersistence.getInFlightBatch() ?: return
        val age = System.currentTimeMillis() - persisted.requestedAtMillis
        if (age > MAX_RESUME_AGE_MS) {
            Timber.tag(TAG).i(
                "Discarding stale playlist transfer intent: playlistId=%s age=%dms",
                persisted.playlistId,
                age,
            )
            batchPersistence.clearInFlightBatch(persisted.batchId)
            return
        }
        Timber.tag(TAG).i(
            "Resuming playlist transfer interrupted by process death: playlistId=%s (%d songs)",
            persisted.playlistId,
            persisted.songIds.size,
        )
        runCatching { wearPhoneTransferSender.refreshWatchLibraryState() }
        withTimeoutOrNull(WATCH_LIBRARY_RESOLVE_TIMEOUT_MS) {
            transferStateStore.isWatchLibraryResolved.first { it }
        }
        startBatch(
            playlistId = persisted.playlistId,
            playlistName = persisted.playlistName,
            songIds = persisted.songIds,
            // Carries the *original* request's timestamp forward: a resume re-saves the intent
            // under a fresh batchId, and stamping it "now" every time would make the intent
            // immortal, since its age could then never reach MAX_RESUME_AGE_MS.
            requestedAtMillis = persisted.requestedAtMillis,
            isResume = true,
        )
    }

    private suspend fun runBatchTransfer(
        batchId: String,
        playlistId: String,
        playlistName: String,
        songIds: List<String>,
        requestedAtMillis: Long,
        isResume: Boolean,
    ) {
        batchPersistence.saveInFlightBatch(
            PersistedPlaylistBatchIntent(
                batchId = batchId,
                playlistId = playlistId,
                playlistName = playlistName,
                songIds = songIds,
                requestedAtMillis = requestedAtMillis,
            )
        )

        val nodes = resolveReachableNodes()
        transferStateStore.markBatchStarted(batchId, playlistId, playlistName, songIds.size)

        if (nodes.isEmpty()) {
            cancelledBatchIds.remove(batchId)
            transferStateStore.markBatchFailed(batchId, "No reachable watch with PixelPlay")
            // A resumed batch keeps its intent: "no watch in range right now" is exactly the case
            // the persistence exists for — a resume at a cold start routinely lands with the watch
            // off the wrist or out of Bluetooth range, and clearing here would lose the
            // interrupted transfer for good. It ages out via MAX_RESUME_AGE_MS instead.
            // A brand-new request that can't find a watch is just a failed request, and is cleared
            // as before: the user saw it fail and would not expect it to reappear days later.
            if (!isResume) batchPersistence.clearInFlightBatch(batchId)
            return
        }
        transferStateStore.retainReachableWatchNodes(nodes.map { it.id }.toSet())

        WatchTransferForegroundService.start(application)
        val songTitles = resolveSongTitlesInOrder(songIds)
        sendPlaylistSyncToNodes(nodes, playlistId, playlistName, songIds, songTitles)

        val alreadyPresentCount = songIds.count { transferStateStore.isSongSavedOnAllReachableWatches(it) }
        repeat(alreadyPresentCount) { transferStateStore.markBatchSongCompleted(batchId) }

        val pendingSongIds = songIds.filterNot { transferStateStore.isSongSavedOnAllReachableWatches(it) }

        for (songId in pendingSongIds) {
            if (cancelledBatchIds.contains(batchId)) break

            val song = musicRepository.getSongsByIds(listOf(songId)).first().firstOrNull()
            if (song == null) {
                Timber.tag(TAG).w("Song not found for playlist transfer: songId=%s", songId)
                transferStateStore.markBatchSongFailed(batchId)
                continue
            }

            val outcome = transferSongToAllNodesWithRetry(batchId, nodes, song)
            if (outcome.completed) {
                transferStateStore.markBatchSongCompleted(batchId)
            } else {
                transferStateStore.markBatchSongFailed(batchId, outcome.errorCode)
            }
        }

        // Reads the cancellation from cancelledBatchIds rather than from the store's status:
        // cancelPlaylistTransfer() can land before runBatchTransfer() has called markBatchStarted,
        // in which case markBatchCancelled found no entry to update and no-opped — leaving a
        // status that isn't CANCELLED for a batch the user did cancel, reported to them as a
        // successfully completed transfer of zero songs.
        val wasCancelled = cancelledBatchIds.remove(batchId) ||
            transferStateStore.batchTransfers.value[batchId]?.status == WearTransferProgress.STATUS_CANCELLED
        if (wasCancelled) {
            transferStateStore.markBatchCancelled(batchId)
        } else {
            transferStateStore.markBatchCompleted(batchId)
        }
        batchPersistence.clearInFlightBatch(batchId)
    }

    private suspend fun resolveReachableNodes(): List<Node> {
        return try {
            capabilityClient.getCapability(
                WearCapabilities.PIXELPLAY_WEAR_APP,
                CapabilityClient.FILTER_REACHABLE,
            ).await().nodes.toList()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to resolve reachable watches for playlist transfer")
            emptyList()
        }
    }

    /**
     * `MessageClient.sendMessage()` succeeding only means the message was handed off locally, not
     * that the watch received it — real hardware testing showed a sync sent while the watch was
     * mid-reconnect (its Wi-Fi/ADB link drops intermittently under this app's own load) is
     * silently lost: the watch ends up with every song's audio on disk but no playlist row to
     * show them under, because nothing here ever knew the sync didn't land. Each node now gets a
     * fresh [WearPlaylistSync.requestId] and this waits for the matching [WearPlaylistSyncAck]
     * (see [WearTransferRepository][com.theveloper.pixelplay.data.WearTransferRepository]
     * `.onPlaylistSyncReceived` on the watch side), retrying once — same shape as
     * [transferSongToAllNodesWithRetry] — before giving up and logging it. Giving up doesn't fail
     * the batch: songs still transfer either way, and the next explicit re-sync (or "update on
     * watch") is idempotent and gets another chance.
     */
    private suspend fun sendPlaylistSyncToNodes(
        nodes: List<Node>,
        playlistId: String,
        playlistName: String,
        songIds: List<String>,
        songTitles: List<String>,
    ) {
        nodes.forEach { node ->
            sendPlaylistSyncToNodeWithRetry(node, playlistId, playlistName, songIds, songTitles)
        }
    }

    private suspend fun sendPlaylistSyncToNodeWithRetry(
        node: Node,
        playlistId: String,
        playlistName: String,
        songIds: List<String>,
        songTitles: List<String>,
    ) {
        if (sendPlaylistSyncToNodeAndAwaitAck(node, playlistId, playlistName, songIds, songTitles)) return

        Timber.tag(TAG).w(
            "Retrying playlist sync after missing ack: playlistId=%s node=%s",
            playlistId,
            node.id,
        )
        delay(RETRY_BACKOFF_MS)
        val ackedOnRetry = sendPlaylistSyncToNodeAndAwaitAck(node, playlistId, playlistName, songIds, songTitles)
        if (!ackedOnRetry) {
            Timber.tag(TAG).w(
                "Playlist sync unconfirmed after retry: playlistId=%s node=%s — songs will still " +
                    "transfer, but the watch may not show this playlist until the next sync",
                playlistId,
                node.id,
            )
        }
    }

    /** Returns whether [node] acked this attempt within [PLAYLIST_SYNC_ACK_TIMEOUT_MS]. */
    private suspend fun sendPlaylistSyncToNodeAndAwaitAck(
        node: Node,
        playlistId: String,
        playlistName: String,
        songIds: List<String>,
        songTitles: List<String>,
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        val syncPayload = json.encodeToString(
            WearPlaylistSync(playlistId, playlistName, songIds, songTitles, requestId)
        ).toByteArray(Charsets.UTF_8)

        try {
            messageClient.sendMessage(node.id, WearDataPaths.PLAYLIST_SYNC, syncPayload).await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to send playlist sync to node=%s", node.id)
            return false
        }

        val ack = withTimeoutOrNull(PLAYLIST_SYNC_ACK_TIMEOUT_MS) {
            transferStateStore.playlistSyncAcks.first { it.requestId == requestId }
        }
        return ack != null
    }

    /**
     * Titles for [songIds], same order, "" for any id the library doesn't resolve — purely
     * cosmetic (lets the watch show a real name instead of a raw id for a song still awaiting
     * transfer), so a missing title here is never a reason to fail or delay the sync.
     */
    private suspend fun resolveSongTitlesInOrder(songIds: List<String>): List<String> {
        val songsById = musicRepository.getSongsByIds(songIds).first().associateBy { it.id }
        return songIds.map { songId -> songsById[songId]?.title.orEmpty() }
    }

    /**
     * Retries [song] once after a transient failure, with a short backoff. Real hardware
     * testing showed a song can legitimately fail (watch-side idle watchdog closing a live but
     * slow Bluetooth stream — see WearTransferRepository) while a retry moments later succeeds
     * cleanly: the watch's Bluetooth radio is shared with any connected BT headset, and a
     * transfer can genuinely stall for a while under that contention without anything actually
     * being broken. Doesn't retry past a cancellation, and re-transcodes on the retry rather
     * than caching the first attempt's output — simpler and safe (transcoding on a modern phone
     * is a few seconds, not the bottleneck), at the cost of redoing work that likely already
     * succeeded once.
     */
    private suspend fun transferSongToAllNodesWithRetry(
        batchId: String,
        nodes: List<Node>,
        song: Song,
    ): SongTransferResult {
        val firstAttempt = transferSongToAllNodes(batchId, nodes, song)
        if (firstAttempt.completed || cancelledBatchIds.contains(batchId)) return firstAttempt

        Timber.tag(TAG).w(
            "Retrying transfer after failure: songId=%s errorCode=%s",
            song.id,
            firstAttempt.errorCode,
        )
        delay(RETRY_BACKOFF_MS)
        if (cancelledBatchIds.contains(batchId)) return firstAttempt
        return transferSongToAllNodes(batchId, nodes, song)
    }

    /** Transcodes [song] once (if needed) and streams it to every reachable [nodes] in turn. */
    private suspend fun transferSongToAllNodes(
        batchId: String,
        nodes: List<Node>,
        song: Song,
    ): SongTransferResult {
        if (cancelledBatchIds.contains(batchId)) return SongTransferResult(completed = false)

        val transcodeRequestId = UUID.randomUUID().toString()
        transferStateStore.markBatchSongStarted(batchId, transcodeRequestId, song.title)

        val transcodeResult = watchAudioTranscoder.transcodeIfNeeded(
            song = song,
            requestId = transcodeRequestId,
            onProgress = { fraction ->
                transferStateStore.markBatchSongProgress(
                    batchId,
                    WearTransferProgress.STATUS_TRANSCODING,
                    fraction.coerceIn(0f, 1f) * TRANSCODE_PHASE_WEIGHT,
                )
            },
        )
        if (transcodeResult is WatchAudioTranscoder.TranscodeResult.Failed) {
            Timber.tag(TAG).w(transcodeResult.error, "Transcode failed for songId=%s, skipping", song.id)
            return SongTransferResult(completed = false, errorCode = WearTransferProgress.ERROR_CODE_GENERIC)
        }
        if (cancelledBatchIds.contains(batchId)) {
            watchAudioTranscoder.cleanup(transcodeResult)
            return SongTransferResult(completed = false)
        }

        val audioOverride = (transcodeResult as? WatchAudioTranscoder.TranscodeResult.Transcoded)?.let { transcoded ->
            PhoneDirectWatchTransferCoordinator.WatchAudioOverride(
                file = transcoded.outputFile,
                mimeType = WatchAudioTranscoder.TRANSCODED_OUTPUT_MIME_TYPE,
                bitrateBps = WatchAudioTranscoder.TARGET_BITRATE_BPS,
            )
        }
        val wasTranscoded = audioOverride != null

        // Send to every reachable node (not just the first) — with multiple paired watches this
        // song should land on all of them. Present on at least one counts as done overall; if
        // every node failed, report whichever node failed last (good enough for the UI's
        // single-line failure summary).
        var succeededOnAnyNode = false
        var lastFailureErrorCode: String? = null
        for (node in nodes) {
            if (cancelledBatchIds.contains(batchId)) break
            val nodeOutcome = transferSongToNode(batchId, node, song, audioOverride, wasTranscoded)
            if (nodeOutcome.completed) {
                succeededOnAnyNode = true
            } else {
                lastFailureErrorCode = nodeOutcome.errorCode
            }
        }

        watchAudioTranscoder.cleanup(transcodeResult)
        return SongTransferResult(
            completed = succeededOnAnyNode,
            errorCode = if (succeededOnAnyNode) null else lastFailureErrorCode,
        )
    }

    private suspend fun transferSongToNode(
        batchId: String,
        node: Node,
        song: Song,
        audioOverride: PhoneDirectWatchTransferCoordinator.WatchAudioOverride?,
        wasTranscoded: Boolean,
    ): SongTransferResult {
        val requestId = UUID.randomUUID().toString()
        // Re-targets activeRequestId to this node's request without resetting the visible
        // progress: if the song was transcoded, it's already sitting at TRANSCODE_PHASE_WEIGHT.
        val startingProgress = if (wasTranscoded) TRANSCODE_PHASE_WEIGHT else 0f
        transferStateStore.markBatchSongStarted(batchId, requestId, song.title, startingProgress)

        val progressWatcherJob: Job = scope.launch {
            transferStateStore.transfers
                .mapNotNull { it[requestId] }
                .collect { state ->
                    if (state.status == WearTransferProgress.STATUS_TRANSFERRING) {
                        // Transferring is the second phase for a transcoded song: continue from
                        // TRANSCODE_PHASE_WEIGHT up to 1.0 instead of restarting at 0.
                        val overallProgress = if (wasTranscoded) {
                            TRANSCODE_PHASE_WEIGHT + state.progress * (1f - TRANSCODE_PHASE_WEIGHT)
                        } else {
                            state.progress
                        }
                        transferStateStore.markBatchSongProgress(batchId, state.status, overallProgress)
                    }
                }
        }

        directTransferCoordinator.startTransferToWatch(
            nodeId = node.id,
            requestId = requestId,
            songId = song.id,
            audioOverride = audioOverride,
        )

        val finalState = withTimeoutOrNull(songTransferAwaitTimeoutMs) {
            transferStateStore.transfers
                .mapNotNull { it[requestId] }
                .first { it.status in TERMINAL_STATUSES }
        }
        progressWatcherJob.cancel()

        if (finalState == null) {
            Timber.tag(TAG).w(
                "Timed out awaiting watch confirmation: songId=%s requestId=%s",
                song.id,
                requestId,
            )
            transferStateStore.markProgress(
                requestId = requestId,
                songId = song.id,
                bytesTransferred = 0L,
                totalBytes = 0L,
                status = WearTransferProgress.STATUS_FAILED,
                error = "Timed out waiting for watch confirmation",
            )
            return SongTransferResult(completed = false, errorCode = WearTransferProgress.ERROR_CODE_TIMED_OUT)
        }

        // The watch rejecting a song it already has is a success for this batch's purposes, not a
        // failure: treating it as one made transferSongToAllNodesWithRetry re-transcode and
        // re-offer the very same song, get rejected again, and then report it to the user as
        // failed. It also teaches the state store the song really is on this node — the outcome
        // handler only learns that from a COMPLETED — so the rest of the batch (and the next one)
        // can skip it up front.
        val alreadyOnWatch = finalState.status == WearTransferProgress.STATUS_FAILED &&
            finalState.error == WearTransferProgress.ERROR_ALREADY_ON_WATCH
        if (alreadyOnWatch) {
            transferStateStore.markSongPresentOnWatch(nodeId = node.id, songId = song.id)
            return SongTransferResult(completed = true)
        }

        return SongTransferResult(
            completed = finalState.status == WearTransferProgress.STATUS_COMPLETED,
            errorCode = if (finalState.status == WearTransferProgress.STATUS_FAILED) {
                WearTransferProgress.ERROR_CODE_GENERIC
            } else {
                null
            },
        )
    }

    private data class SongTransferResult(val completed: Boolean, val errorCode: String? = null)

    internal companion object {
        private const val TAG = "PlaylistWatchTransfer"

        // Transcoding and transferring both report 0f..1f progress for the same song; weighting
        // them into one continuous 0..1 scale (instead of each resetting to 0) avoids the visible
        // jump-then-reset when a song moves from one phase to the other.
        private const val TRANSCODE_PHASE_WEIGHT = 0.3f

        // Deliberately generous relative to the watch's own idle watchdog: leaves room for slow
        // transcoding plus a slow Bluetooth link on large files. Better to wait too long than to
        // mark a legitimately-slow transfer as failed.
        private const val DEFAULT_SONG_TRANSFER_AWAIT_TIMEOUT_MS = 300_000L

        // Short on purpose: a retry exists for transient stalls (radio contention with a
        // connected BT headset, momentary Bluetooth hiccups), not to wait out a genuinely dead
        // link — a longer backoff would just make a real failure take longer to report.
        private const val RETRY_BACKOFF_MS = 3_000L

        // How long resumePersistedBatchIfNeeded() waits for a fresh watch-library snapshot before
        // giving up and resuming anyway. Short: this only avoids some wasted duplicate-rejected
        // round-trips, it's not load-bearing for correctness (the watch rejects duplicates itself).
        private const val WATCH_LIBRARY_RESOLVE_TIMEOUT_MS = 10_000L

        // How stale a persisted intent may be before resumePersistedBatchIfNeeded() drops it
        // instead of resuming. A transfer interrupted by process death is worth retrying across
        // the next few launches (the watch may simply have been out of range each time), but a
        // playlist the user asked for a week ago and has since forgotten about isn't.
        private const val MAX_RESUME_AGE_MS = 7L * 24 * 60 * 60 * 1000

        // How long to wait for the watch's playlist-sync ack before retrying. Generous relative to
        // a normal round-trip (which is near-instant) to tolerate a brief Wi-Fi/ADB reconnect blip
        // without firing a spurious retry.
        private const val PLAYLIST_SYNC_ACK_TIMEOUT_MS = 10_000L

        private val TERMINAL_STATUSES = setOf(
            WearTransferProgress.STATUS_COMPLETED,
            WearTransferProgress.STATUS_FAILED,
            WearTransferProgress.STATUS_CANCELLED,
        )
    }
}
