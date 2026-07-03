package com.theveloper.pixelplay.data.local

import androidx.room.Entity

/**
 * Membership + order of a song within a local playlist snapshot. [songId] may not (yet) have a
 * matching [LocalSongEntity] row — that's how a pending/not-yet-transferred song is represented.
 */
@Entity(tableName = "local_playlist_songs", primaryKeys = ["playlistId", "songId"])
data class LocalPlaylistSongCrossRef(
    val playlistId: String,
    val songId: String,
    val position: Int,
)
