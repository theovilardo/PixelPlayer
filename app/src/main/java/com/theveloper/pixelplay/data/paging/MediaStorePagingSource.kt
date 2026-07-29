package com.theveloper.pixelplay.data.paging

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.utils.normalizeMetadataText
import com.theveloper.pixelplay.utils.normalizeMetadataTextOrEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * PagingSource that loads songs from MediaStore based on a pre-filtered list of IDs.
 * This ensures complex directory filtering is applied correctly before paging.
 */
class MediaStorePagingSource(
    private val context: Context,
    private val filteredIds: List<Long>,
    private val songIdToGenreMap: Map<Long, String>
) : PagingSource<Int, Song>() {
    private companion object {
        const val TAG = "MediaStorePagingSource"
    }

    override fun getRefreshKey(state: PagingState<Int, Song>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Song> = withContext(Dispatchers.IO) {
        try {
            val pageIndex = params.key ?: 0

            if (filteredIds.isEmpty()) {
                return@withContext LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = null
                )
            }

            val start = pageIndex * params.loadSize
            if (start >= filteredIds.size) {
                 return@withContext LoadResult.Page(
                    data = emptyList(),
                    prevKey = if (pageIndex > 0) pageIndex - 1 else null,
                    nextKey = null
                )
            }

            val end = min(start + params.loadSize, filteredIds.size)
            val idsToLoad = filteredIds.subList(start, end)

            val songs = fetchSongDetails(idsToLoad)

            // Sort songs to match the order of idsToLoad (because "IN" query doesn't guarantee order)
            val songsMap = songs.associateBy { it.id.toLongOrNull() ?: -1L }
            val orderedSongs = idsToLoad.mapNotNull { songsMap[it] }

            val nextKey = if (end < filteredIds.size) pageIndex + 1 else null
            val prevKey = if (pageIndex > 0) pageIndex - 1 else null

            LoadResult.Page(
                data = orderedSongs,
                prevKey = prevKey,
                nextKey = nextKey
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed loading page (pageIndex=${params.key ?: 0}, loadSize=${params.loadSize}, totalIds=${filteredIds.size})",
                e
            )
            LoadResult.Error(e)
        }
    }

    private fun fetchSongDetails(ids: List<Long>): List<Song> {
        val songs = mutableListOf<Song>()
        if (ids.isEmpty()) return songs

        val selection = "${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",")})"
        
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ALBUM_ARTIST
        )

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            null // Order doesn't matter here, we sort in memory
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val albumArtistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val path = cursor.getString(pathCol)
                val albumArtUriString = AlbumArtUtils.getAlbumArtUri(
                    appContext = context,
                    path = path,
                    songId = id,
                    forceRefresh = false
                )

                val song = Song(
                    id = id.toString(),
                    title = cursor.getString(titleCol).normalizeMetadataTextOrEmpty(),
                    artist = cursor.getString(artistCol).normalizeMetadataTextOrEmpty(),
                    artistId = cursor.getLong(artistIdCol),
                    artists = emptyList(),
                    album = cursor.getString(albumCol).normalizeMetadataTextOrEmpty(),
                    albumId = albumId,
                    albumArtist = if (albumArtistCol != -1) cursor.getString(albumArtistCol).normalizeMetadataText() else null,
                    path = path,
                    contentUriString = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    albumArtUriString = albumArtUriString,
                    duration = cursor.getLong(durationCol),
                    genre = songIdToGenreMap[id],
                    lyrics = null,
                    isFavorite = false, // Not critical for paging source display usually, or passed in?
                    trackNumber = cursor.getInt(trackCol) % 1000,
                    year = cursor.getInt(yearCol),
                    dateAdded = cursor.getLong(dateAddedCol),
                    dateModified = cursor.getLong(dateModifiedCol),
                    mimeType = null,
                    bitrate = null,
                    sampleRate = null
                )
                songs.add(song)
            }
        }
        return songs
    }
}
