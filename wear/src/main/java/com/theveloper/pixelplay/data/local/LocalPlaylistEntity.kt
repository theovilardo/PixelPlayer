package com.theveloper.pixelplay.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a playlist snapshot synced from the phone. Song availability is resolved by
 * joining [LocalPlaylistSongCrossRef] with [LocalSongDao] at read time, not stored here — a
 * playlist can exist before all (or any) of its songs have finished transferring.
 */
@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @PrimaryKey val playlistId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)
