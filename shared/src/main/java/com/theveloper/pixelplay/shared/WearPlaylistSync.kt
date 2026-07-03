package com.theveloper.pixelplay.shared

import kotlinx.serialization.Serializable

/**
 * Snapshot of a phone playlist sent to the watch so it can be browsed and played
 * offline. Sent once when the user taps "send to watch", and again (idempotently)
 * when they tap "update" — the watch replaces its local membership/order for
 * [playlistId] with [songIds] on each sync, independent of whether the audio for
 * those songs has already been transferred.
 */
@Serializable
data class WearPlaylistSync(
    val playlistId: String,
    val name: String,
    val songIds: List<String>,
)
