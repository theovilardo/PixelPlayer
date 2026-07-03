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
) {
    companion object {
        const val STATUS_TRANSCODING = "transcoding"
        const val STATUS_TRANSFERRING = "transferring"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        const val ERROR_ALREADY_ON_WATCH = "Song is already on watch"
    }
}
