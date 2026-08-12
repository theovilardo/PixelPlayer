package com.theveloper.pixelplay.data.service.wear

import com.theveloper.pixelplay.shared.WearPlaylistSyncAck
import com.theveloper.pixelplay.shared.WearTransferProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class PhoneWatchTransferState(
    val requestId: String,
    val songId: String,
    val songTitle: String = "",
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val status: String = WearTransferProgress.STATUS_TRANSFERRING,
    val error: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Aggregate state of a whole-playlist watch transfer, driven by [PlaylistWatchTransferCoordinator].
 * [currentSongProgress] is the 0f..1f progress of whichever song [activeRequestId] refers to
 * (weighted across transcode+transfer phases by the coordinator) — the per-song byte-level detail
 * lives in [PhoneWatchTransferState], keyed by that same requestId.
 */
data class PhoneWatchBatchTransferState(
    val batchId: String,
    val playlistId: String,
    val playlistName: String,
    val totalSongCount: Int,
    val completedSongCount: Int = 0,
    val failedSongCount: Int = 0,
    val status: String = WearTransferProgress.STATUS_TRANSFERRING,
    val activeRequestId: String? = null,
    val currentSongTitle: String = "",
    val currentSongProgress: Float = 0f,
    val errorMessage: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    val processedSongCount: Int get() = completedSongCount + failedSongCount
}

@Singleton
class PhoneWatchTransferStateStore @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _transfers = MutableStateFlow<Map<String, PhoneWatchTransferState>>(emptyMap())
    val transfers: StateFlow<Map<String, PhoneWatchTransferState>> = _transfers.asStateFlow()
    private val _batchTransfers = MutableStateFlow<Map<String, PhoneWatchBatchTransferState>>(emptyMap())
    val batchTransfers: StateFlow<Map<String, PhoneWatchBatchTransferState>> = _batchTransfers.asStateFlow()
    private val _reachableWatchNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val reachableWatchNodeIds: StateFlow<Set<String>> = _reachableWatchNodeIds.asStateFlow()
    private val _watchLibrarySyncedNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val watchLibrarySyncedNodeIds: StateFlow<Set<String>> = _watchLibrarySyncedNodeIds.asStateFlow()
    private val _isWatchLibraryResolved = MutableStateFlow(true)
    val isWatchLibraryResolved: StateFlow<Boolean> = _isWatchLibraryResolved.asStateFlow()
    private val watchSongIdsByNodeId = ConcurrentHashMap<String, Set<String>>()
    private val _watchSongIds = MutableStateFlow<Set<String>>(emptySet())
    val watchSongIds: StateFlow<Set<String>> = _watchSongIds.asStateFlow()

    // Distinct from reachableWatchNodeIds: "ever paired" (CapabilityClient FILTER_ALL) vs
    // "reachable right now" (FILTER_REACHABLE). Defaults to false — safer to hide watch-related
    // UI for someone who's never paired a watch than to flash it on before the first check
    // resolves. See WearPhoneTransferSender.refreshWatchPairingState().
    private val _isAnyWatchPaired = MutableStateFlow(false)
    val isAnyWatchPaired: StateFlow<Boolean> = _isAnyWatchPaired.asStateFlow()

    fun setAnyWatchPaired(paired: Boolean) {
        _isAnyWatchPaired.value = paired
    }

    // Replay a handful rather than 0: the ack can in principle arrive and be emitted before
    // PlaylistWatchTransferCoordinator starts collecting for it (right after messageClient's own
    // send call returns), and a plain event stream with no replay would silently drop it in that
    // case instead of just delivering it a moment "late" to a fresh collector.
    private val _playlistSyncAcks = MutableSharedFlow<WearPlaylistSyncAck>(replay = 8)
    val playlistSyncAcks: SharedFlow<WearPlaylistSyncAck> = _playlistSyncAcks.asSharedFlow()

    private val cleanupJobs = ConcurrentHashMap<String, Job>()

    fun onPlaylistSyncAckReceived(ack: WearPlaylistSyncAck) {
        _playlistSyncAcks.tryEmit(ack)
    }

    fun markRequested(
        requestId: String,
        songId: String,
        songTitle: String = "",
    ) {
        cleanupJobs.remove(requestId)?.cancel()
        _transfers.update { map ->
            val current = map[requestId]
            map + (requestId to (current ?: PhoneWatchTransferState(
                requestId = requestId,
                songId = songId,
                songTitle = songTitle,
                status = WearTransferProgress.STATUS_TRANSFERRING,
                updatedAtMillis = System.currentTimeMillis(),
            )))
        }
    }

    fun markMetadata(
        requestId: String,
        songId: String,
        songTitle: String,
        totalBytes: Long,
    ) {
        cleanupJobs.remove(requestId)?.cancel()
        _transfers.update { map ->
            val current = map[requestId]
            val now = System.currentTimeMillis()
            val updated = if (current != null) {
                current.copy(
                    songId = songId,
                    songTitle = songTitle,
                    totalBytes = maxOf(current.totalBytes, totalBytes),
                    status = WearTransferProgress.STATUS_TRANSFERRING,
                    error = null,
                    updatedAtMillis = now,
                )
            } else {
                PhoneWatchTransferState(
                    requestId = requestId,
                    songId = songId,
                    songTitle = songTitle,
                    totalBytes = totalBytes,
                    status = WearTransferProgress.STATUS_TRANSFERRING,
                    updatedAtMillis = now,
                )
            }
            map + (requestId to updated)
        }
    }

    fun markProgress(
        requestId: String,
        songId: String,
        bytesTransferred: Long,
        totalBytes: Long,
        status: String,
        error: String? = null,
        songTitle: String? = null,
    ) {
        _transfers.update { map ->
            val current = map[requestId]
            val now = System.currentTimeMillis()
            val updated = if (current != null) {
                current.copy(
                    songId = songId,
                    songTitle = songTitle ?: current.songTitle,
                    bytesTransferred = maxOf(current.bytesTransferred, bytesTransferred),
                    totalBytes = maxOf(current.totalBytes, totalBytes),
                    status = status,
                    error = error,
                    updatedAtMillis = now,
                )
            } else {
                PhoneWatchTransferState(
                    requestId = requestId,
                    songId = songId,
                    songTitle = songTitle.orEmpty(),
                    bytesTransferred = bytesTransferred,
                    totalBytes = totalBytes,
                    status = status,
                    error = error,
                    updatedAtMillis = now,
                )
            }
            map + (requestId to updated)
        }

        if (status == WearTransferProgress.STATUS_COMPLETED ||
            status == WearTransferProgress.STATUS_FAILED ||
            status == WearTransferProgress.STATUS_CANCELLED
        ) {
            scheduleTerminalCleanup(requestId)
        } else {
            cleanupJobs.remove(requestId)?.cancel()
        }
    }

    fun updateWatchSongIds(nodeId: String, songIds: Set<String>) {
        if (nodeId.isBlank()) return
        watchSongIdsByNodeId[nodeId] = songIds
        if (nodeId in _reachableWatchNodeIds.value) {
            _watchLibrarySyncedNodeIds.value = _watchLibrarySyncedNodeIds.value + nodeId
        }
        _watchSongIds.value = watchSongIdsByNodeId.values.flatten().toSet()
        updateWatchLibraryResolution()
    }

    fun beginWatchLibraryRefresh(nodeIds: Set<String>) {
        _reachableWatchNodeIds.value = nodeIds
        _watchLibrarySyncedNodeIds.value = emptySet()
        updateWatchLibraryResolution()
    }

    fun markSongPresentOnWatch(nodeId: String, songId: String) {
        if (nodeId.isBlank() || songId.isBlank()) return
        val existingSongIds = watchSongIdsByNodeId[nodeId].orEmpty()
        if (songId in existingSongIds) return
        watchSongIdsByNodeId[nodeId] = existingSongIds + songId
        _watchSongIds.value = watchSongIdsByNodeId.values.flatten().toSet()
    }

    fun markCancelled(requestId: String, error: String? = null) {
        cleanupJobs.remove(requestId)?.cancel()
        _transfers.update { map ->
            val current = map[requestId] ?: return@update map
            map + (requestId to current.copy(
                status = WearTransferProgress.STATUS_CANCELLED,
                error = error ?: current.error,
                updatedAtMillis = System.currentTimeMillis(),
            ))
        }
        scheduleTerminalCleanup(requestId)
    }

    fun retainReachableWatchNodes(nodeIds: Set<String>) {
        _reachableWatchNodeIds.value = nodeIds
        watchSongIdsByNodeId.keys.toList().forEach { nodeId ->
            if (nodeId !in nodeIds) {
                watchSongIdsByNodeId.remove(nodeId)
            }
        }
        _watchLibrarySyncedNodeIds.value = _watchLibrarySyncedNodeIds.value.intersect(nodeIds)
        _watchSongIds.value = watchSongIdsByNodeId.values.flatten().toSet()
        updateWatchLibraryResolution()
    }

    fun isSongSavedOnAllReachableWatches(songId: String): Boolean {
        val reachableNodeIds = _reachableWatchNodeIds.value
        if (reachableNodeIds.isEmpty() || songId.isBlank()) return false

        return reachableNodeIds.all { nodeId ->
            watchSongIdsByNodeId[nodeId]?.contains(songId) == true
        }
    }

    private fun updateWatchLibraryResolution() {
        val reachableNodeIds = _reachableWatchNodeIds.value
        _isWatchLibraryResolved.value = reachableNodeIds.isEmpty() ||
            reachableNodeIds.all { it in _watchLibrarySyncedNodeIds.value }
    }

    private fun scheduleTerminalCleanup(requestId: String) {
        cleanupJobs.remove(requestId)?.cancel()
        cleanupJobs[requestId] = scope.launch {
            delay(TERMINAL_STATE_VISIBILITY_MS)
            _transfers.update { map ->
                val current = map[requestId]
                if (current != null &&
                    (current.status == WearTransferProgress.STATUS_COMPLETED ||
                        current.status == WearTransferProgress.STATUS_FAILED ||
                        current.status == WearTransferProgress.STATUS_CANCELLED)
                ) {
                    map - requestId
                } else {
                    map
                }
            }
            cleanupJobs.remove(requestId)
        }
    }

    // --- Playlist batch transfers, driven by PlaylistWatchTransferCoordinator ---

    private val batchCleanupJobs = ConcurrentHashMap<String, Job>()

    fun markBatchStarted(batchId: String, playlistId: String, playlistName: String, totalSongCount: Int) {
        batchCleanupJobs.remove(batchId)?.cancel()
        _batchTransfers.update { map ->
            map + (batchId to PhoneWatchBatchTransferState(
                batchId = batchId,
                playlistId = playlistId,
                playlistName = playlistName,
                totalSongCount = totalSongCount,
                status = WearTransferProgress.STATUS_TRANSFERRING,
            ))
        }
    }

    fun markBatchSongStarted(
        batchId: String,
        activeRequestId: String,
        songTitle: String,
        startingProgress: Float = 0f,
    ) {
        _batchTransfers.update { map ->
            val current = map[batchId] ?: return@update map
            map + (batchId to current.copy(
                activeRequestId = activeRequestId,
                currentSongTitle = songTitle,
                currentSongProgress = startingProgress.coerceIn(0f, 1f),
                updatedAtMillis = System.currentTimeMillis(),
            ))
        }
    }

    /** [status] is informational only here — [progress] is what actually drives the notification/UI. */
    fun markBatchSongProgress(batchId: String, status: String, progress: Float) {
        _batchTransfers.update { map ->
            val current = map[batchId] ?: return@update map
            map + (batchId to current.copy(
                currentSongProgress = progress.coerceIn(0f, 1f),
                updatedAtMillis = System.currentTimeMillis(),
            ))
        }
    }

    fun markBatchSongCompleted(batchId: String) {
        _batchTransfers.update { map ->
            val current = map[batchId] ?: return@update map
            map + (batchId to current.copy(
                completedSongCount = current.completedSongCount + 1,
                activeRequestId = null,
                currentSongProgress = 0f,
                updatedAtMillis = System.currentTimeMillis(),
            ))
        }
    }

    fun markBatchSongFailed(batchId: String, errorMessage: String? = null) {
        _batchTransfers.update { map ->
            val current = map[batchId] ?: return@update map
            map + (batchId to current.copy(
                failedSongCount = current.failedSongCount + 1,
                activeRequestId = null,
                currentSongProgress = 0f,
                errorMessage = errorMessage ?: current.errorMessage,
                updatedAtMillis = System.currentTimeMillis(),
            ))
        }
    }

    fun markBatchCancelled(batchId: String) {
        _batchTransfers.update { map ->
            val current = map[batchId] ?: return@update map
            map + (batchId to current.copy(
                status = WearTransferProgress.STATUS_CANCELLED,
                activeRequestId = null,
                updatedAtMillis = System.currentTimeMillis(),
            ))
        }
        scheduleBatchTerminalCleanup(batchId)
    }

    fun markBatchFailed(batchId: String, errorMessage: String) {
        _batchTransfers.update { map ->
            val current = map[batchId] ?: return@update map
            map + (batchId to current.copy(
                status = WearTransferProgress.STATUS_FAILED,
                errorMessage = errorMessage,
                activeRequestId = null,
                updatedAtMillis = System.currentTimeMillis(),
            ))
        }
        scheduleBatchTerminalCleanup(batchId)
    }

    fun markBatchCompleted(batchId: String) {
        _batchTransfers.update { map ->
            val current = map[batchId] ?: return@update map
            map + (batchId to current.copy(
                status = WearTransferProgress.STATUS_COMPLETED,
                activeRequestId = null,
                updatedAtMillis = System.currentTimeMillis(),
            ))
        }
        scheduleBatchTerminalCleanup(batchId)
    }

    private fun scheduleBatchTerminalCleanup(batchId: String) {
        batchCleanupJobs.remove(batchId)?.cancel()
        batchCleanupJobs[batchId] = scope.launch {
            delay(TERMINAL_STATE_VISIBILITY_MS)
            _batchTransfers.update { map ->
                val current = map[batchId]
                if (current != null &&
                    (current.status == WearTransferProgress.STATUS_COMPLETED ||
                        current.status == WearTransferProgress.STATUS_FAILED ||
                        current.status == WearTransferProgress.STATUS_CANCELLED)
                ) {
                    map - batchId
                } else {
                    map
                }
            }
            batchCleanupJobs.remove(batchId)
        }
    }

    private companion object {
        const val TERMINAL_STATE_VISIBILITY_MS = 3500L
    }
}
