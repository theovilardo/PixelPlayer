package com.theveloper.pixelplay.utils

import com.theveloper.pixelplay.data.model.Song

/**
 * True when [query] is found in this song's title or artist, case- and accent-insensitively
 * (e.g. "que gan" matches "Qué ganas..."). Mirrors the field scope used by the global song
 * search (title + artist). A blank query always matches, so callers can filter unconditionally.
 */
fun Song.matchesTitleOrArtist(query: String): Boolean {
    if (query.isBlank()) return true
    val foldedQuery = query.foldDiacritics()
    return title.foldDiacritics().contains(foldedQuery, ignoreCase = true) ||
        artist.foldDiacritics().contains(foldedQuery, ignoreCase = true)
}

/**
 * Filters this list to songs matching [query] by title or artist, preserving order.
 * A blank query returns this list unchanged (same reference), so callers relying on
 * identity for `remember`/diffing keys don't do unnecessary work.
 */
fun List<Song>.filterByQuery(query: String): List<Song> =
    if (query.isBlank()) this else filter { it.matchesTitleOrArtist(query) }
