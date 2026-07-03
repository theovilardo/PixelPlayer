package com.theveloper.pixelplay.data.service.wear

import com.theveloper.pixelplay.shared.WearTransferProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

@Singleton
class PhoneWatchTransferStateStore @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _transfers = MutableStateFlow<Map<String, PhoneWatchTransferState>>(emptyMap())
    val transfers: StateFlow<Map<String, PhoneWatchTransferState>> = _transfers.asStateFlow()
    private val _reachableWatchNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val reachableWatchNodeIds: StateFlow<Set<String>> = _reachableWatchNodeIds.asStateFlow()
    private val _watchLibrarySyncedNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val watchLibrarySyncedNodeIds: StateFlow<Set<String>> = _watchLibrarySyncedNodeIds.asStateFlow()
    private val _isWatchLibraryResolved = MutableStateFlow(true)
    val isWatchLibraryResolved: StateFlow<Boolean> = _isWatchLibraryResolved.asStateFlow()
    private val watchSongIdsByNodeId = ConcurrentHashMap<String, Set<String>>()
    private val _watchSongIds = MutableStateFlow<Set<String>>(emptySet())
    val watchSongIds: StateFlow<Set<String>> = _watchSongIds.asStateFlow()
    private val freeStorageBytesByNodeId = ConcurrentHashMap<String, Long>()
    private val _watchFreeStorageBytesByNodeId = MutableStateFlow<Map<String, Long>>(emptyMap())
    val watchFreeStorageBytesByNodeId: StateFlow<Map<String, Long>> = _watchFreeStorageBytesByNodeId.asStateFlow()

    private val cleanupJobs = ConcurrentHashMap<String, Job>()

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

    fun updateWatchFreeStorageBytes(nodeId: String, freeStorageBytes: Long) {
        if (nodeId.isBlank()) return
        freeStorageBytesByNodeId[nodeId] = freeStorageBytes
        _watchFreeStorageBytesByNodeId.value = freeStorageBytesByNodeId.toMap()
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
        freeStorageBytesByNodeId.keys.toList().forEach { nodeId ->
            if (nodeId !in nodeIds) {
                freeStorageBytesByNodeId.remove(nodeId)
            }
        }
        _watchFreeStorageBytesByNodeId.value = freeStorageBytesByNodeId.toMap()
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

    private companion object {
        const val TERMINAL_STATE_VISIBILITY_MS = 3500L
    }
}
