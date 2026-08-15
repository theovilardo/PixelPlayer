package com.theveloper.pixelplay.shared

import kotlinx.serialization.Serializable

/**
 * Sent by the watch back to the phone once a [WearPlaylistSync] has been durably applied to the
 * local playlist table. `MessageClient.sendMessage()` returning success on the phone only means
 * the message was handed off locally, not that the watch received it — real hardware testing
 * showed a sync sent while the watch was mid-reconnect (Wi-Fi/ADB drops intermittently under this
 * app's own load) is silently lost, leaving the watch with every song's audio but no playlist row
 * to show them under. The phone waits for this ack (see `PlaylistWatchTransferCoordinator`) and
 * resends if it doesn't arrive in time.
 */
@Serializable
data class WearPlaylistSyncAck(
    val playlistId: String,
    val requestId: String,
)
