package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotifyDao {

    @Query("SELECT * FROM spotify_playlists ORDER BY name ASC")
    fun getPlaylists(): Flow<List<SpotifyPlaylistEntity>>

    @Query("SELECT * FROM spotify_playlists ORDER BY name ASC")
    suspend fun getPlaylistsList(): List<SpotifyPlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<SpotifyPlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: SpotifyPlaylistEntity)

    @Query("DELETE FROM spotify_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("SELECT * FROM spotify_songs WHERE playlist_id = :playlistId ORDER BY date_added DESC")
    fun getSongsByPlaylist(playlistId: String): Flow<List<SpotifySongEntity>>

    @Query("SELECT * FROM spotify_songs WHERE playlist_id = :playlistId ORDER BY date_added DESC")
    suspend fun getSongsByPlaylistList(playlistId: String): List<SpotifySongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SpotifySongEntity>)

    @Query("DELETE FROM spotify_songs WHERE playlist_id = :playlistId")
    suspend fun deleteSongsByPlaylist(playlistId: String)

    @Query("DELETE FROM spotify_songs")
    suspend fun clearSongs()

    @Query("DELETE FROM spotify_playlists")
    suspend fun clearPlaylists()
}
