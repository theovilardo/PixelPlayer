package com.theveloper.pixelplay.data.network.spotify

import com.theveloper.pixelplay.data.network.spotify.dto.SpotifyPlaylistDto
import com.theveloper.pixelplay.data.network.spotify.dto.SpotifyTrackDto
import com.theveloper.pixelplay.data.network.spotify.dto.SpotifyUserDto
import com.theveloper.pixelplay.data.spotify.model.SpotifyAlbum
import com.theveloper.pixelplay.data.spotify.model.SpotifyArtist
import com.theveloper.pixelplay.data.spotify.model.SpotifyPlaylist
import com.theveloper.pixelplay.data.spotify.model.SpotifyTrack

object SpotifyResponseParser {

    fun parseUserDisplayName(user: SpotifyUserDto): String {
        return user.displayName?.takeIf { it.isNotBlank() } ?: user.id
    }

    fun parsePlaylist(dto: SpotifyPlaylistDto): SpotifyPlaylist {
        return SpotifyPlaylist(
            id = dto.id,
            uri = dto.uri ?: "spotify:playlist:${dto.id}",
            name = dto.name?.ifBlank { "Untitled Playlist" } ?: "Untitled Playlist",
            description = dto.description,
            ownerName = dto.owner?.displayName ?: dto.owner?.id,
            coverUrl = dto.images.firstOrNull()?.url,
            songCount = dto.tracks?.total ?: 0,
            isPublic = dto.isPublic,
            collaborative = dto.collaborative
        )
    }

    fun parseTrack(dto: SpotifyTrackDto, playlistId: String? = null, addedAt: String? = null): SpotifyTrack? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        val artists = dto.artists.mapNotNull { artist ->
            val name = artist.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SpotifyArtist(
                id = artist.id,
                uri = artist.uri,
                name = name
            )
        }
        val albumDto = dto.album
        val album = albumDto?.let {
            SpotifyAlbum(
                id = it.id,
                uri = it.uri,
                name = it.name?.ifBlank { "Unknown Album" } ?: "Unknown Album",
                artistName = it.artists.firstOrNull()?.name,
                imageUrl = it.images.firstOrNull()?.url,
                releaseDate = it.releaseDate
            )
        }

        return SpotifyTrack(
            id = id,
            uri = dto.uri ?: "spotify:track:$id",
            title = dto.name?.ifBlank { "Unknown Track" } ?: "Unknown Track",
            artists = artists,
            album = album,
            durationMs = dto.durationMs,
            discNumber = dto.discNumber,
            trackNumber = dto.trackNumber,
            explicit = dto.explicit,
            isPlayable = dto.isPlayable ?: true,
            playlistId = playlistId,
            addedAt = addedAt
        )
    }
}

