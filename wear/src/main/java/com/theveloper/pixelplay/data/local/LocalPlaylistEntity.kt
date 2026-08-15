package com.theveloper.pixelplay.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a playlist snapshot synced from the phone, so the watch can browse
 * and play it offline. Membership and order live separately in [LocalPlaylistSongCrossRef] —
 * this row only carries the playlist's own identity and timestamps.
 */
@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @PrimaryKey val playlistId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)
