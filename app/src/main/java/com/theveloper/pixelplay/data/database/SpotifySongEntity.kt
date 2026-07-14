package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.spotify.model.SpotifyTrack

@Entity(
    tableName = "spotify_songs",
    indices = [
        Index(value = ["spotify_id"]),
        Index(value = ["playlist_id"]),
        Index(value = ["date_added"])
    ]
)
data class SpotifySongEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "spotify_id") val spotifyId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "album_art_url") val albumArtUrl: String?,
    @ColumnInfo(name = "track_number") val trackNumber: Int,
    @ColumnInfo(name = "disc_number") val discNumber: Int?,
    val explicit: Boolean,
    @ColumnInfo(name = "date_added") val dateAdded: Long
) {
    companion object {
        const val LIKED_SONGS_PLAYLIST_ID = "__liked_songs__"
    }
}

fun SpotifyTrack.toEntity(playlistId: String = SpotifySongEntity.LIKED_SONGS_PLAYLIST_ID): SpotifySongEntity {
    return SpotifySongEntity(
        id = "${playlistId}_${id}",
        spotifyId = id,
        playlistId = playlistId,
        title = title,
        artist = displayArtist,
        album = albumName,
        albumId = album?.id,
        durationMs = durationMs,
        albumArtUrl = album?.imageUrl,
        trackNumber = trackNumber,
        discNumber = discNumber,
        explicit = explicit,
        dateAdded = System.currentTimeMillis()
    )
}

fun SpotifySongEntity.toSong(): Song {
    return Song(
        id = "spotify_$id",
        title = title,
        artist = artist,
        artistId = -1L,
        album = album,
        albumId = -1L,
        path = "",
        contentUriString = "spotify://track/$spotifyId",
        albumArtUriString = albumArtUrl,
        duration = durationMs,
        mimeType = "audio/spotify",
        bitrate = null,
        sampleRate = null,
        trackNumber = trackNumber,
        discNumber = discNumber,
        dateAdded = dateAdded,
        isFavorite = false,
        spotifyId = spotifyId
    )
}
