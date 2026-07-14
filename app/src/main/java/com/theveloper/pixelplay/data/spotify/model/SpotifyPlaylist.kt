package com.theveloper.pixelplay.data.spotify.model

data class SpotifyPlaylist(
    val id: String,
    val uri: String,
    val name: String,
    val description: String?,
    val ownerName: String?,
    val coverUrl: String?,
    val songCount: Int,
    val isPublic: Boolean?,
    val collaborative: Boolean
)

