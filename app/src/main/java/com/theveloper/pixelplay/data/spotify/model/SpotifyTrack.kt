package com.theveloper.pixelplay.data.spotify.model

data class SpotifyTrack(
    val id: String,
    val uri: String,
    val title: String,
    val artists: List<SpotifyArtist>,
    val album: SpotifyAlbum?,
    val durationMs: Long,
    val discNumber: Int?,
    val trackNumber: Int,
    val explicit: Boolean,
    val isPlayable: Boolean,
    val playlistId: String?,
    val addedAt: String?
) {
    val displayArtist: String
        get() = artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" }

    val albumName: String
        get() = album?.name?.ifBlank { "Unknown Album" } ?: "Unknown Album"
}

