package com.theveloper.pixelplay.shared

import kotlinx.serialization.Serializable

/**
 * Progress update for an ongoing song transfer, sent from phone to watch
 * via MessageClient periodically during streaming.
 */
@Serializable
data class WearTransferProgress(
    val requestId: String,
    val songId: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: String,
    val error: String? = null,
    val errorCode: String? = null,
) {
    companion object {
        const val STATUS_TRANSCODING = "transcoding"
        const val STATUS_TRANSFERRING = "transferring"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        // Local-only to PhoneWatchTransferStateStore — a song the phone finished sending but
        // hasn't yet heard the watch's own completion ack for. Never serialized to the watch.
        const val STATUS_AWAITING_WATCH_ACK = "awaiting_watch_ack"
        const val ERROR_ALREADY_ON_WATCH = "Song is already on watch"
        const val ERROR_CODE_CONNECTION_LOST = "connection_lost"
        const val ERROR_CODE_INSUFFICIENT_STORAGE = "insufficient_storage"
        const val ERROR_CODE_TIMED_OUT = "timed_out"
        const val ERROR_CODE_GENERIC = "generic"
    }
}
