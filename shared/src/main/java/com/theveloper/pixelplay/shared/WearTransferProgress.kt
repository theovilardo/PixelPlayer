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
    /** Machine-readable failure reason, so callers can react (retry, prompt for space, ...) without parsing [error]. */
    val errorCode: String? = null,
) {
    companion object {
        /** Phone is re-encoding the source file before it starts streaming; only meaningful for playlist batches. */
        const val STATUS_TRANSCODING = "transcoding"
        const val STATUS_TRANSFERRING = "transferring"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        /**
         * Sent from the watch to the phone right after it stores the [WearTransferMetadata] for a
         * request, before the audio channel opens. Lets the phone confirm the metadata actually
         * arrived instead of racing a blind delay against the audio stream.
         */
        const val STATUS_METADATA_RECEIVED = "metadata_received"
        /**
         * Phone finished sending the bytes but hasn't yet heard the watch's own write-complete ack.
         * Local-only to the phone's in-memory/persisted transfer state — never serialized to the watch.
         */
        const val STATUS_AWAITING_WATCH_ACK = "awaiting_watch_ack"

        const val ERROR_ALREADY_ON_WATCH = "Song is already on watch"
        const val ERROR_CODE_CONNECTION_LOST = "connection_lost"
        const val ERROR_CODE_INSUFFICIENT_STORAGE = "insufficient_storage"
        const val ERROR_CODE_TIMED_OUT = "timed_out"
        const val ERROR_CODE_GENERIC = "generic"
    }
}
