package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.theveloper.pixelplay.data.spotify.model.SpotifyPlaylist

@Entity(tableName = "spotify_playlists")
data class SpotifyPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "owner_name") val ownerName: String?,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "song_count") val songCount: Int,
    @ColumnInfo(name = "is_public") val isPublic: Boolean?,
    val collaborative: Boolean,
    @ColumnInfo(name = "last_sync_time") val lastSyncTime: Long
)

fun SpotifyPlaylist.toEntity(lastSyncTime: Long = System.currentTimeMillis()): SpotifyPlaylistEntity {
    return SpotifyPlaylistEntity(
        id = id,
        name = name,
        description = description,
        ownerName = ownerName,
        coverUrl = coverUrl,
        songCount = songCount,
        isPublic = isPublic,
        collaborative = collaborative,
        lastSyncTime = lastSyncTime
    )
}
