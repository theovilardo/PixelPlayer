package com.theveloper.pixelplay.shared

import kotlinx.serialization.Serializable

/**
 * Snapshot of a phone playlist sent to the watch so it can be browsed and played offline.
 *
 * Sent once when the user taps "send to watch", and again (idempotently) when they tap
 * "update" — the watch replaces its local membership/order for [playlistId] with [songIds]
 * on each sync, independent of whether the audio for those songs has already arrived. This
 * lets the watch show the full playlist and its intended order immediately, while individual
 * songs keep streaming in afterward.
 *
 * [songTitles] is a parallel list to [songIds] (same index = same song) rather than a list of
 * pairs, so an older watch build ignores it (via `ignoreUnknownKeys`) and an older phone build
 * omitting it still deserializes cleanly on a newer watch — it's purely cosmetic (lets a song
 * still awaiting transfer show its real name instead of its raw ID) and never load-bearing for
 * the transfer itself.
 *
 * [requestId] identifies this specific send attempt so the watch's [WearPlaylistSyncAck] can be
 * correlated back to it — `MessageClient.sendMessage()` doesn't guarantee delivery, so the phone
 * resends (a new [requestId] each time) until it sees a matching ack. Defaults to "" for the same
 * backward-compatibility reason as [songTitles]: an old phone build omitting it just means the
 * watch never acks, and the phone falls back to its old fire-and-forget behavior for that sync.
 */
@Serializable
data class WearPlaylistSync(
    val playlistId: String,
    val name: String,
    val songIds: List<String>,
    val songTitles: List<String> = emptyList(),
    val requestId: String = "",
)
