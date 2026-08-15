package com.theveloper.pixelplay.shared

import kotlinx.serialization.Serializable

/**
 * Snapshot of what the watch currently holds, sent in reply to
 * [WearDataPaths.WATCH_LIBRARY_QUERY].
 *
 * [playlistIds] answers a question [songIds] can't: "has this playlist ever been sent to this
 * watch?". Deriving that from songs alone gets it wrong in both directions — two playlists sharing
 * a single song would both look already-sent, and it can't tell a playlist that was sent from one
 * whose songs merely happen to be there. Defaulted so an older watch build, which omits the field,
 * still parses.
 */
@Serializable
data class WearLibraryState(
    val songIds: List<String> = emptyList(),
    val playlistIds: List<String> = emptyList(),
)
