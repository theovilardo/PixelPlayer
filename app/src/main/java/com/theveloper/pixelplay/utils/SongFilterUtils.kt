package com.theveloper.pixelplay.utils

import com.theveloper.pixelplay.data.model.Song

/**
 * True when [query] is found in this song's title or artist, case-insensitively.
 * Mirrors the field scope used by the global song search (title + artist).
 * A blank query always matches, so callers can filter unconditionally.
 */
fun Song.matchesTitleOrArtist(query: String): Boolean {
    if (query.isBlank()) return true
    return title.contains(query, ignoreCase = true) || artist.contains(query, ignoreCase = true)
}
