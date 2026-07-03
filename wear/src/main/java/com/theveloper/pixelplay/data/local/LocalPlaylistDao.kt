package com.theveloper.pixelplay.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO for locally stored playlist snapshots on the watch.
 */
@Dao
interface LocalPlaylistDao {

    /**
     * Replaces the playlist row and its full song membership/order in one transaction, so a
     * re-sync (e.g. after adding a song on the phone) always reflects the latest order rather
     * than merging with stale cross-refs.
     */
    @Transaction
    suspend fun upsertPlaylist(entity: LocalPlaylistEntity, songCrossRefs: List<LocalPlaylistSongCrossRef>) {
        insertPlaylist(entity)
        deleteSongsForPlaylist(entity.playlistId)
        insertSongCrossRefs(songCrossRefs)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(entity: LocalPlaylistEntity)

    @Query("SELECT * FROM local_playlists WHERE playlistId = :playlistId")
    suspend fun getPlaylistById(playlistId: String): LocalPlaylistEntity?

    @Query("DELETE FROM local_playlist_songs WHERE playlistId = :playlistId")
    suspend fun deleteSongsForPlaylist(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongCrossRefs(crossRefs: List<LocalPlaylistSongCrossRef>)

    @Query("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<LocalPlaylistEntity>>

    @Query("SELECT * FROM local_playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observePlaylistSongs(playlistId: String): Flow<List<LocalPlaylistSongCrossRef>>
}
