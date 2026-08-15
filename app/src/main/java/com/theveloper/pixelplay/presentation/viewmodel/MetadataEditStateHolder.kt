package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.database.AlbumArtThemeDao
import com.theveloper.pixelplay.data.media.CoverArtUpdate
import com.theveloper.pixelplay.data.media.ImageCacheManager
import com.theveloper.pixelplay.data.media.MetadataEditError
import com.theveloper.pixelplay.data.media.SongMetadataEditor
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.utils.FileDeletionUtils
import com.theveloper.pixelplay.utils.LyricsUtils
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.theveloper.pixelplay.utils.MediaStorePermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import com.theveloper.pixelplay.data.media.AudioMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Callbacks supplied by [PlayerViewModel] so the metadata-edit cluster can read and mutate
 * ViewModel-owned state (player UI state, the "song info" selection, toasts) and the
 * ViewModel's [CoroutineScope] without [MetadataEditStateHolder] depending on the ViewModel.
 * Mirrors the lambda-callback pattern already used by FolderNavigationStateHolder.
 */
class MetadataEditCallbacks(
    val scope: CoroutineScope,
    val getUiState: () -> PlayerUiState,
    val updateUiState: ((PlayerUiState) -> PlayerUiState) -> Unit,
    val getSelectedSongForInfo: () -> Song?,
    val setSelectedSongForInfo: (Song) -> Unit,
    val sendToast: (String) -> Unit,
    val reloadLyricsForCurrentSong: () -> Unit,
)

private data class PendingMetadataEdit(
    val song: Song,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val composer: String,
    val genre: String,
    val lyrics: String,
    val trackNumber: Int,
    val discNumber: Int?,
    val replayGainTrackGainDb: String?,
    val replayGainAlbumGainDb: String?,
    val coverArtUpdate: CoverArtUpdate?
)

private data class PendingBatchMetadataEdit(
    val songs: List<Song>,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val composer: String?,
    val genre: String?,
    val lyrics: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val replayGainTrackGainDb: String?,
    val replayGainAlbumGainDb: String?,
    val coverArtUpdate: CoverArtUpdate?
)

private data class PendingLyricsSave(
    val song: Song,
    val lyrics: Lyrics,
    val preferSynced: Boolean
)

class MetadataEditStateHolder @Inject constructor(
    private val songMetadataEditor: SongMetadataEditor,
    private val musicRepository: MusicRepository,
    private val imageCacheManager: ImageCacheManager,
    private val themeStateHolder: ThemeStateHolder,
    private val playbackStateHolder: PlaybackStateHolder,
    private val libraryStateHolder: LibraryStateHolder,
    private val multiSelectionStateHolder: MultiSelectionStateHolder,
    private val albumArtThemeDao: AlbumArtThemeDao,
    @ApplicationContext private val context: Context
) {

    // MediaStore write-permission request (needed for metadata editing without MANAGE_EXTERNAL_STORAGE).
    // Owned here because only the metadata-edit cluster emits/consumes it; the ViewModel re-exposes it.
    private val _writePermissionRequest = MutableSharedFlow<IntentSender>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val writePermissionRequest: SharedFlow<IntentSender> = _writePermissionRequest.asSharedFlow()

    // Edits parked while waiting for the user's MediaStore write-permission decision.
    private var pendingMetadataEdit: PendingMetadataEdit? = null
    private var pendingBatchMetadataEdit: PendingBatchMetadataEdit? = null
    private var pendingLyricsSave: PendingLyricsSave? = null
    private var pendingBatchGenreEdit: Pair<List<Song>, String>? = null

    data class MetadataEditResult(
        val success: Boolean,
        val updatedSong: Song? = null,
        val updatedAlbumArtUri: String? = null,
        val parsedLyrics: Lyrics? = null,
        val error: MetadataEditError? = null,
        val errorMessage: String? = null
    ) {
        /**
         * Returns a user-friendly error message based on the error type
         */
        fun getUserFriendlyErrorMessage(): String {
            return when (error) {
                MetadataEditError.FILE_NOT_FOUND -> "The song file could not be found. It may have been moved or deleted."
                MetadataEditError.NO_WRITE_PERMISSION -> "Cannot edit this file. You may need to grant additional permissions or the file is on read-only storage."
                MetadataEditError.INVALID_INPUT -> errorMessage ?: "Invalid input provided."
                MetadataEditError.UNSUPPORTED_FORMAT -> "This file format is not supported for editing."
                MetadataEditError.TAGLIB_ERROR -> "Failed to write metadata to the file. The file may be corrupted."
                MetadataEditError.TIMEOUT -> "The operation took too long and was cancelled."
                MetadataEditError.FILE_CORRUPTED -> "The file appears to be corrupted or in an unsupported format."
                MetadataEditError.IO_ERROR -> "An error occurred while accessing the file. Please try again."
                MetadataEditError.UNKNOWN, null -> errorMessage ?: "An unknown error occurred while editing metadata."
            }
        }
    }

    suspend fun saveMetadata(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String,
        newComposer: String,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        newReplayGainTrackGainDb: String? = null,
        newReplayGainAlbumGainDb: String? = null,
        coverArtUpdate: CoverArtUpdate?,
        /**
         * Whether [newLyrics] is also the library's copy of them. False when the
         * caller filled the field in from the file merely to have something to
         * write there -- the batch path does that for every field a save is not
         * editing -- because storing that back would drop lyrics this app
         * fetched into its own database for a song whose file carries none.
         */
        syncLibraryLyrics: Boolean = true
    ): MetadataEditResult = withContext(Dispatchers.IO) {
        
        Log.d("MetadataEditStateHolder", "Starting saveMetadata for: ${song.title}")

        // CRITICAL FIX: Preserve existing embedded artwork if the user didn't provide a new one.
        // Editing text metadata might strip the artwork if the underlying tagging library
        // overwrites the file structure. Explicitly re-saving the existing artwork prevents this.
        val finalCoverArtUpdate = if (coverArtUpdate == null) {
            val existingMetadata = try {
                 com.theveloper.pixelplay.data.media.AudioMetadataReader.read(java.io.File(song.path))
            } catch (e: Exception) {
                null
            }
            if (existingMetadata?.artwork != null) {
                Log.d("MetadataEditStateHolder", "Preserving existing embedded artwork")
                CoverArtUpdate(existingMetadata.artwork.bytes, existingMetadata.artwork.mimeType ?: "image/jpeg")
            } else {
                null
            }
        } else if (coverArtUpdate.isDeletion) {
            Log.d("MetadataEditStateHolder", "Artwork deletion requested, skipping preservation")
            coverArtUpdate
        } else {
            coverArtUpdate
        }

        val trimmedLyrics = newLyrics.trim()
        val normalizedLyrics = trimmedLyrics.takeIf { it.isNotBlank() }
        // What the library should hold afterwards, which is not always what is
        // being written to the file: see [syncLibraryLyrics].
        val libraryLyrics = if (syncLibraryLyrics) {
            normalizedLyrics
        } else {
            song.lyrics?.takeIf { it.isNotBlank() }
        }
        // We parse lyrics here just to ensure they are valid or to have them ready,
        // essentially mirroring logic in ViewModel
        val parsedLyrics = libraryLyrics?.let { LyricsUtils.parseLyrics(it) }
        val resolvedSongId = resolveSongIdForMetadataEdit(song)

        if (resolvedSongId == null) {
            Log.w("MetadataEditStateHolder", "Cannot edit metadata for non-numeric song id: ${song.id}")
            return@withContext MetadataEditResult(
                success = false,
                error = MetadataEditError.INVALID_INPUT,
                errorMessage = "This song source does not support metadata editing."
            )
        }

        val result = songMetadataEditor.editSongMetadata(
            newTitle = newTitle,
            newArtist = newArtist,
            newAlbum = newAlbum,
            newAlbumArtist = newAlbumArtist.trim().takeIf { it.isNotBlank() },
            newComposer = newComposer.trim().takeIf { it.isNotBlank() },
            newGenre = newGenre,
            newLyrics = trimmedLyrics,
            newTrackNumber = newTrackNumber,
            newDiscNumber = newDiscNumber,
            newReplayGainTrackGainDb = newReplayGainTrackGainDb,
            newReplayGainAlbumGainDb = newReplayGainAlbumGainDb,
            coverArtUpdate = finalCoverArtUpdate,
            songId = resolvedSongId,
        )

        Log.d("MetadataEditStateHolder", "Editor result: success=${result.success}, error=${result.error}")

        if (result.success) {
            val refreshedAlbumArtUri = if (coverArtUpdate?.isDeletion == true) {
                null
            } else {
                result.updatedAlbumArtUri ?: song.albumArtUriString
            }
            
            // Update Repository (Lyrics)
            if (syncLibraryLyrics) {
                if (normalizedLyrics != null) {
                    musicRepository.updateLyrics(resolvedSongId, normalizedLyrics)
                } else {
                    musicRepository.resetLyrics(resolvedSongId)
                }
            }

            val updatedSong = song.copy(
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                albumArtist = newAlbumArtist.trim().takeIf { it.isNotBlank() },
                genre = newGenre,
                lyrics = libraryLyrics,
                trackNumber = newTrackNumber,
                discNumber = newDiscNumber,
                albumArtUriString = refreshedAlbumArtUri,
            )

            // CRITICAL: Fetch the authoritative song object from the repository (MediaStore/DB).
            // When metadata changes (especially album/artist), MediaStore might re-index the song
            // and assign it a NEW album ID, resulting in a NEW albumArtUri.
            // Using the 'updatedSong' copy above might retain a STALE albumArtUri.
            val freshSongFromRepo = try {
                musicRepository.getSong(song.id).first() ?: updatedSong
            } catch (e: Exception) {
                updatedSong
            }

            // Ensure we use the refreshed artwork URI we just generated/cleared.
            // The repository emission may be stale for a split second.
            val freshSong = freshSongFromRepo.copy(
                albumArtUriString = refreshedAlbumArtUri
            )

            // Force cache invalidation if album art might have changed
            val uriToInvalidate = if (coverArtUpdate?.isDeletion == true) song.albumArtUriString else refreshedAlbumArtUri
            if (uriToInvalidate != null) {
                // Invalidate Coil/Glide caches for the affected URI (old or new)
                imageCacheManager.invalidateCoverArtCaches(uriToInvalidate)
            }
            
            // Force regenerate palette
            themeStateHolder.forceRegenerateColorScheme(refreshedAlbumArtUri)

            MetadataEditResult(
                success = true,
                updatedSong = freshSong,
                updatedAlbumArtUri = freshSong.albumArtUriString,
                parsedLyrics = parsedLyrics
            )
        } else {
            Log.w("MetadataEditStateHolder", "Metadata edit failed: ${result.error} - ${result.errorMessage}")
            MetadataEditResult(
                success = false,
                error = result.error,
                errorMessage = result.errorMessage
            )
        }
    }

    suspend fun deleteSong(song: Song): Boolean = withContext(Dispatchers.IO) {
        val fileInfo = FileDeletionUtils.getFileInfo(song.path)
        if (!fileInfo.exists) {
            true
        } else if (fileInfo.canWrite) {
            val success = FileDeletionUtils.deleteFile(context, song.path)
            if (success) {
                // Remove from DB happens in ViewModel call logic or should happen here?
                // VM's deleteFromDevice calls removeSong -> toggleFavorite(false) -> updates lists.
                // It does NOT explicitly call repository.deleteSong() because MediaStore/FileObserver handles it?
                // Or maybe explicit deletion IS needed but VM logic (Line 3687) says "removeSong(song)".
                // removeSong(3698) toggles favorites and updates _masterAllSongs. It implies memory update.
                // FileDeletionUtils deletes the physical file. The MediaScanner should eventually pick it up, 
                // but for immediate UI responsiveness, manual update is good.
                // Also, MusicRepository.deleteById(id) exists.
                // ViewModel did NOT call musicRepository.deleteById(). It relied on "removeSong" which is UI state only? 
                // Wait, removeSong updates UI state. Does it update DB?
                // Line 3698: toggleFavoriteSpecificSong(song, true)?? Wait.
                
                // Let's stick to returning success and letting ViewModel handle UI updates for now, 
                // or if we want to be thorough, we call repository delete.
                // But if ViewModel wasn't doing it, I won't add it to change behavior.
                true
            } else {
                false
            }
        } else {
            false
        }
    }

    private fun resolveSongIdForMetadataEdit(song: Song): Long? {
        song.id.toLongOrNull()?.let { return it }

        val uriCandidates = buildList {
            if (song.contentUriString.isNotBlank()) add(song.contentUriString)
            if (song.id.startsWith("external:")) add(song.id.removePrefix("external:"))
        }

        for (rawUri in uriCandidates) {
            val parsedUri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: continue
            if (parsedUri.scheme != "content") continue

            parsedUri.lastPathSegment?.toLongOrNull()?.let { return it }
        }

        return null
    }

    // region Metadata-edit cluster (moved from PlayerViewModel)

    fun editSongMetadata(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String,
        newComposer: String,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        newReplayGainTrackGainDb: String? = null,
        newReplayGainAlbumGainDb: String? = null,
        coverArtUpdate: CoverArtUpdate?,
        cb: MetadataEditCallbacks,
    ) {
        cb.scope.launch {
            Log.e("PlayerViewModel", "METADATA_EDIT_VM: Starting editSongMetadata via Holder")

            // On Android 11+, request MediaStore write permission for local songs
            val songId = song.id.toLongOrNull()
            if (songId != null && songId > 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intentSender = MediaStorePermissionHelper.createWriteRequestForSong(context, songId)
                if (intentSender != null) {
                    // Store pending edit and request permission from the UI
                    pendingMetadataEdit = PendingMetadataEdit(
                        song = song,
                        title = newTitle,
                        artist = newArtist,
                        album = newAlbum,
                        albumArtist = newAlbumArtist,
                        composer = newComposer,
                        genre = newGenre,
                        lyrics = newLyrics,
                        trackNumber = newTrackNumber,
                        discNumber = newDiscNumber,
                        replayGainTrackGainDb = newReplayGainTrackGainDb,
                        replayGainAlbumGainDb = newReplayGainAlbumGainDb,
                        coverArtUpdate = coverArtUpdate
                    )
                    _writePermissionRequest.emit(intentSender)
                    return@launch
                }
            }

            performMetadataEdit(song, newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics,
                newTrackNumber, newDiscNumber, newReplayGainTrackGainDb, newReplayGainAlbumGainDb, coverArtUpdate, cb)
        }
    }

    fun saveBatchMetadata(
        songs: List<Song>,
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        composer: String?,
        genre: String?,
        lyrics: String?,
        trackNumber: Int?,
        discNumber: Int?,
        replayGainTrackGainDb: String?,
        replayGainAlbumGainDb: String?,
        coverArtUpdate: CoverArtUpdate?,
        cb: MetadataEditCallbacks,
    ) {
        cb.scope.launch {
            // Check if we need MediaStore permission (Android 11+)
            val localSongsNeedingPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                songs.mapNotNull { song ->
                    song.id.toLongOrNull()?.takeIf { it > 0 }?.let { song to it }
                }
            } else {
                emptyList()
            }

            // If we have local songs on Android 11+, request permission for batch edit
            if (localSongsNeedingPermission.isNotEmpty()) {
                val uris = localSongsNeedingPermission.mapNotNull { (_, songId) ->
                    android.provider.MediaStore.Audio.Media.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        songId
                    )
                }

                if (uris.isNotEmpty()) {
                    val intentSender = MediaStorePermissionHelper.createWriteRequestIntentSender(context, uris)

                    if (intentSender != null) {
                        // Store pending batch edit
                        pendingBatchMetadataEdit = PendingBatchMetadataEdit(
                            songs = songs,
                            title = title,
                            artist = artist,
                            album = album,
                            albumArtist = albumArtist,
                            composer = composer,
                            genre = genre,
                            lyrics = lyrics,
                            trackNumber = trackNumber,
                            discNumber = discNumber,
                            replayGainTrackGainDb = replayGainTrackGainDb,
                            replayGainAlbumGainDb = replayGainAlbumGainDb,
                            coverArtUpdate = coverArtUpdate
                        )
                        _writePermissionRequest.emit(intentSender)
                        return@launch
                    }
                }
            }

            performBatchMetadataEdit(
                songs, title, artist, album, albumArtist, composer, genre, lyrics,
                trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate, cb
            )
        }
    }

    fun batchEditGenre(songs: List<Song>, newGenre: String, cb: MetadataEditCallbacks) {
        if (songs.isEmpty()) return

        cb.scope.launch {
            // On Android 11+, request write permission for all local songs upfront
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val uris = songs.mapNotNull { song ->
                    song.id.toLongOrNull()?.takeIf { it > 0 }?.let { id ->
                        MediaStorePermissionHelper.getMediaStoreUri(context, id)
                    }
                }
                if (uris.isNotEmpty()) {
                    val intentSender = MediaStorePermissionHelper.createWriteRequestIntentSender(context, uris)
                    if (intentSender != null) {
                        pendingBatchGenreEdit = songs to newGenre
                        _writePermissionRequest.emit(intentSender)
                        return@launch
                    }
                }
            }

            performBatchEditGenre(songs, newGenre, cb)
        }
    }

    fun saveLyricsToFile(song: Song, lyrics: Lyrics, preferSynced: Boolean, cb: MetadataEditCallbacks) {
        val lrcContent = LyricsUtils.toLrcString(lyrics, preferSynced)
        if (lrcContent.isEmpty()) {
            cb.sendToast(context.getString(R.string.metadata_edit_lyrics_none_to_save))
            return
        }

        val songFile = java.io.File(song.path)
        val lrcFile = java.io.File(songFile.parentFile, "${songFile.nameWithoutExtension}.lrc")

        // Android 11+ check: if file exists and we might not have permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && lrcFile.exists() && !lrcFile.canWrite()) {
            val uri = MediaStorePermissionHelper.getMediaStoreUri(context, lrcFile.absolutePath)
            if (uri != null) {
                val intentSender = MediaStorePermissionHelper.createWriteRequestIntentSender(context, listOf(uri))
                if (intentSender != null) {
                    pendingLyricsSave = PendingLyricsSave(song, lyrics, preferSynced)
                    cb.scope.launch { _writePermissionRequest.emit(intentSender) }
                    return
                }
            }
        }

        performLyricsSave(song, lyrics, preferSynced, cb)
    }

    /** Called from the UI after the user approves or denies the MediaStore write permission. */
    fun onWritePermissionResult(granted: Boolean, cb: MetadataEditCallbacks) {
        // Handle batch metadata edit
        val batchMetadata = pendingBatchMetadataEdit
        if (batchMetadata != null) {
            pendingBatchMetadataEdit = null
            if (!granted) {
                cb.sendToast(context.getString(R.string.metadata_edit_permission_denied_edit_files))
                return
            }
            cb.scope.launch {
                performBatchMetadataEdit(
                    batchMetadata.songs,
                    batchMetadata.title,
                    batchMetadata.artist,
                    batchMetadata.album,
                    batchMetadata.albumArtist,
                    batchMetadata.composer,
                    batchMetadata.genre,
                    batchMetadata.lyrics,
                    batchMetadata.trackNumber,
                    batchMetadata.discNumber,
                    batchMetadata.replayGainTrackGainDb,
                    batchMetadata.replayGainAlbumGainDb,
                    batchMetadata.coverArtUpdate,
                    cb
                )
            }
            return
        }

        // Handle batch genre edit
        val batchGenre = pendingBatchGenreEdit
        if (batchGenre != null) {
            pendingBatchGenreEdit = null
            if (!granted) {
                cb.sendToast(context.getString(R.string.metadata_edit_permission_denied_edit_files))
                return
            }
            cb.scope.launch { performBatchEditGenre(batchGenre.first, batchGenre.second, cb) }
            return
        }

        // Handle lyrics save retry
        val pendingLyrics = pendingLyricsSave
        if (pendingLyrics != null) {
            pendingLyricsSave = null
            if (!granted) {
                cb.sendToast(context.getString(R.string.metadata_edit_permission_denied_save_lyrics))
                return
            }
            performLyricsSave(pendingLyrics.song, pendingLyrics.lyrics, pendingLyrics.preferSynced, cb)
            return
        }

        // Handle single metadata edit
        val pending = pendingMetadataEdit ?: return
        pendingMetadataEdit = null
        if (!granted) {
            cb.sendToast(context.getString(R.string.metadata_edit_permission_denied_edit_this_file))
            return
        }
        cb.scope.launch {
            performMetadataEdit(
                pending.song, pending.title, pending.artist, pending.album,
                pending.albumArtist, pending.composer, pending.genre, pending.lyrics,
                pending.trackNumber, pending.discNumber,
                pending.replayGainTrackGainDb, pending.replayGainAlbumGainDb, pending.coverArtUpdate,
                cb
            )
        }
    }

    private fun performLyricsSave(song: Song, lyrics: Lyrics, preferSynced: Boolean, cb: MetadataEditCallbacks) {
        cb.scope.launch(Dispatchers.IO) {
            try {
                val songFile = java.io.File(song.path)
                val lrcFile = java.io.File(songFile.parentFile, "${songFile.nameWithoutExtension}.lrc")
                val lrcContent = LyricsUtils.toLrcString(lyrics, preferSynced)

                lrcFile.writeText(lrcContent, Charsets.UTF_8)
                cb.sendToast(context.getString(R.string.metadata_edit_lyrics_saved_successfully))

                // If it was the current song, refresh the lyrics in state if it migrated from remote to local
                if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                    cb.reloadLyricsForCurrentSong()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save lyrics to file")
                cb.sendToast(context.getString(R.string.metadata_edit_lyrics_save_failed))
            }
        }
    }

    private suspend fun performMetadataEdit(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String,
        newComposer: String,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        newReplayGainTrackGainDb: String?,
        newReplayGainAlbumGainDb: String?,
        coverArtUpdate: CoverArtUpdate?,
        cb: MetadataEditCallbacks,
    ) {
        val previousAlbumArt = song.albumArtUriString

        val result = saveMetadata(
            song = song,
            newTitle = newTitle,
            newArtist = newArtist,
            newAlbum = newAlbum,
            newAlbumArtist = newAlbumArtist,
            newComposer = newComposer,
            newGenre = newGenre,
            newLyrics = newLyrics,
            newTrackNumber = newTrackNumber,
            newDiscNumber = newDiscNumber,
            newReplayGainTrackGainDb = newReplayGainTrackGainDb,
            newReplayGainAlbumGainDb = newReplayGainAlbumGainDb,
            coverArtUpdate = coverArtUpdate
        )

        Log.e("PlayerViewModel", "METADATA_EDIT_VM: Result success=${result.success}")

        if (result.success && result.updatedSong != null) {
            val updatedSong = result.updatedSong
            val refreshedAlbumArtUri = result.updatedAlbumArtUri

            invalidateCoverArtCaches(previousAlbumArt, refreshedAlbumArtUri)

            cb.updateUiState { state ->
                val updatedQueue = state.currentPlaybackQueue.replaceSong(updatedSong)
                if (updatedQueue === state.currentPlaybackQueue) {
                    state
                } else {
                    state.copy(currentPlaybackQueue = updatedQueue)
                }
            }

            // Update the LibraryStateHolder which drives the UI (handles the SSOT update)
            libraryStateHolder.updateSong(updatedSong)

            if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        currentSong = updatedSong,
                        lyrics = result.parsedLyrics
                    )
                }

                // Update the player's current MediaItem to refresh notification artwork
                // This is efficient: only replaces metadata, not the media stream
                val controller = playbackStateHolder.mediaController
                if (controller != null) {
                    val currentIndex = controller.currentMediaItemIndex
                    if (currentIndex >= 0 && currentIndex < controller.mediaItemCount) {
                        val currentPosition = controller.currentPosition
                        val newMediaItem = MediaItemBuilder.build(updatedSong)
                        controller.replaceMediaItem(currentIndex, newMediaItem)
                        // Restore position since replaceMediaItem may reset it
                        controller.seekTo(currentIndex, currentPosition)
                    }
                }
            }

            if (cb.getSelectedSongForInfo()?.id == song.id) {
                cb.setSelectedSongForInfo(updatedSong)
            }

            if (coverArtUpdate != null) {
                purgeAlbumArtThemes(previousAlbumArt, updatedSong.albumArtUriString)
                val paletteTargetUri = updatedSong.albumArtUriString
                if (paletteTargetUri != null) {
                    themeStateHolder.getAlbumColorSchemeFlow(paletteTargetUri)
                    val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                    themeStateHolder.extractAndGenerateColorScheme(paletteTargetUri.toUri(), currentUri, isPreload = false)
                } else {
                    val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                    themeStateHolder.extractAndGenerateColorScheme(null, currentUri, isPreload = false)
                }
            }

            // No need for full library sync - file, MediaStore, and local DB are already updated
            cb.sendToast(context.getString(R.string.metadata_edit_updated_successfully))
        } else {
            val errorMessage = result.getUserFriendlyErrorMessage()
            Log.e("PlayerViewModel", "METADATA_EDIT_VM: Failed - ${result.error}: $errorMessage")
            cb.sendToast(errorMessage)
        }
    }

    private suspend fun performBatchMetadataEdit(
        songs: List<Song>,
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        composer: String?,
        genre: String?,
        lyrics: String?,
        trackNumber: Int?,
        discNumber: Int?,
        replayGainTrackGainDb: String?,
        replayGainAlbumGainDb: String?,
        coverArtUpdate: CoverArtUpdate?,
        cb: MetadataEditCallbacks,
    ) {
        var successCount = 0
        var failureCount = 0
        val previousAlbumArts = mutableSetOf<String?>()

        songs.forEach { song ->
            previousAlbumArts.add(song.albumArtUriString)

            // A null field here means "not being edited", but the tag write
            // replaces the whole set, so every one of them still has to be
            // given a value -- and the value it has to be given is the one the
            // file already carries.
            //
            // Song is not that value. It is the library's rendering of the
            // file, and writing it back changes tags nobody asked to change: a
            // multi-artist "A; B" comes back as the ", " join
            // Song.displayArtist shows, a title MediaStore derived from the
            // filename lands in the file that had none, lyrics this app fetched
            // into its own database get embedded. Composer is worse still --
            // Song carries none at all, so every batch edit was sending "" for
            // it, and the editor reads a blank as "delete this tag".
            //
            // So the file answers for its own tags, and Song only fills in what
            // the file has nothing to say about.
            val readsFromFile = title == null || artist == null || album == null ||
                albumArtist == null || composer == null || genre == null || lyrics == null ||
                trackNumber == null || discNumber == null
            val embedded = if (readsFromFile) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        AudioMetadataReader.read(java.io.File(song.path), readArtwork = false)
                    }.getOrNull()
                }
            } else {
                null
            }

            val result = saveMetadata(
                song = song,
                newTitle = title ?: embedded?.title ?: song.title,
                newArtist = artist ?: embedded?.artist ?: song.displayArtist,
                newAlbum = album ?: embedded?.album ?: song.album,
                newAlbumArtist = albumArtist
                    ?: embedded?.albumArtist
                    ?: song.albumArtist?.takeIf { it.isNotBlank() }
                    ?: "",
                newComposer = composer ?: embedded?.composer ?: "",
                newGenre = genre ?: embedded?.genre ?: song.genre?.takeIf { it.isNotBlank() } ?: "",
                newLyrics = lyrics ?: embedded?.lyrics ?: song.lyrics?.takeIf { it.isNotBlank() } ?: "",
                newTrackNumber = trackNumber ?: embedded?.trackNumber ?: song.trackNumber,
                newDiscNumber = discNumber ?: embedded?.discNumber ?: song.discNumber,
                newReplayGainTrackGainDb = replayGainTrackGainDb,
                newReplayGainAlbumGainDb = replayGainAlbumGainDb,
                coverArtUpdate = coverArtUpdate,
                // The lyrics above are the file's own whenever this save is not
                // editing them, and the library's copy -- which a file often
                // does not carry at all -- is not something to overwrite with
                // them.
                syncLibraryLyrics = lyrics != null
            )

            if (result.success && result.updatedSong != null) {
                successCount++
                val updatedSong = result.updatedSong
                val refreshedAlbumArtUri = result.updatedAlbumArtUri

                // Invalidate caches for this song
                invalidateCoverArtCaches(song.albumArtUriString, refreshedAlbumArtUri)

                // Update queue if this song is in it
                cb.updateUiState { state ->
                    val updatedQueue = state.currentPlaybackQueue.replaceSong(updatedSong)
                    if (updatedQueue === state.currentPlaybackQueue) {
                        state
                    } else {
                        state.copy(currentPlaybackQueue = updatedQueue)
                    }
                }

                // Update library state
                libraryStateHolder.updateSong(updatedSong)

                // If this is the current playing song, update it
                if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                    playbackStateHolder.updateStablePlayerState {
                        it.copy(
                            currentSong = updatedSong,
                            lyrics = result.parsedLyrics
                        )
                    }

                    // Update MediaItem for notification
                    val controller = playbackStateHolder.mediaController
                    if (controller != null) {
                        val currentIndex = controller.currentMediaItemIndex
                        if (currentIndex >= 0 && currentIndex < controller.mediaItemCount) {
                            val currentPosition = controller.currentPosition
                            val newMediaItem = MediaItemBuilder.build(updatedSong)
                            controller.replaceMediaItem(currentIndex, newMediaItem)
                            controller.seekTo(currentIndex, currentPosition)
                        }
                    }
                }

                // Update selected song for info sheet if needed
                if (cb.getSelectedSongForInfo()?.id == song.id) {
                    cb.setSelectedSongForInfo(updatedSong)
                }
            } else {
                failureCount++
            }
        }

        // Handle cover art theme updates if artwork was changed
        if (coverArtUpdate != null) {
            previousAlbumArts.forEach { previousArt ->
                purgeAlbumArtThemes(previousArt, null)
            }

            // Regenerate theme for current song if it was edited
            val currentSongId = playbackStateHolder.stablePlayerState.value.currentSong?.id
            if (currentSongId != null && songs.any { it.id == currentSongId }) {
                val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
                val paletteTargetUri = currentSong?.albumArtUriString
                if (paletteTargetUri != null) {
                    themeStateHolder.getAlbumColorSchemeFlow(paletteTargetUri)
                    themeStateHolder.extractAndGenerateColorScheme(
                        paletteTargetUri.toUri(),
                        paletteTargetUri,
                        isPreload = false
                    )
                } else {
                    themeStateHolder.extractAndGenerateColorScheme(null, null, isPreload = false)
                }
            }
        }

        // Clear multi-selection
        multiSelectionStateHolder.clearSelection()

        // Show result toast
        val message = when {
            failureCount == 0 -> context.getString(R.string.batch_edit_success, successCount)
            successCount == 0 -> context.getString(R.string.batch_edit_failed)
            else -> context.getString(R.string.batch_edit_partial_success, successCount, songs.size)
        }
        cb.sendToast(message)
    }

    private suspend fun performBatchEditGenre(songs: List<Song>, newGenre: String, cb: MetadataEditCallbacks) {
        Log.d("PlayerViewModel", "Starting batch genre update for ${songs.size} songs to '$newGenre'")
        cb.sendToast(context.getString(R.string.metadata_edit_updating_n_songs, songs.size))

        var successCount = 0
        var failCount = 0

        songs.forEach { song ->
            val sourceSong = if (song.lyrics != null) {
                song
            } else {
                withContext(Dispatchers.IO) {
                    musicRepository.getSong(song.id).first()
                } ?: song
            }

            val result = saveMetadata(
                song = sourceSong,
                newTitle = sourceSong.title,
                newArtist = sourceSong.artist,
                newAlbum = sourceSong.album,
                newAlbumArtist = sourceSong.albumArtist ?: "",
                newComposer = "",
                newGenre = newGenre,
                newLyrics = sourceSong.lyrics ?: "",
                newTrackNumber = sourceSong.trackNumber,
                newDiscNumber = sourceSong.discNumber,
                coverArtUpdate = null
            )

            if (result.success && result.updatedSong != null) {
                successCount++
                val updatedSong = result.updatedSong

                // Optimistic update of UI flows (libraryStateHolder.updateSong handles the SSOT update)
                libraryStateHolder.updateSong(updatedSong)

                if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                    playbackStateHolder.updateStablePlayerState { it.copy(currentSong = updatedSong) }
                    val controller = playbackStateHolder.mediaController
                    if (controller != null) {
                        val idx = controller.currentMediaItemIndex
                        if (idx != C.INDEX_UNSET) {
                            controller.replaceMediaItem(idx, MediaItemBuilder.build(updatedSong))
                        }
                    }
                }
            } else {
                failCount++
            }
        }

        if (failCount == 0) {
            cb.sendToast(context.getString(R.string.metadata_edit_batch_genre_updated_all, successCount))
        } else {
            cb.sendToast(context.getString(R.string.metadata_edit_batch_genre_updated_partial, successCount, failCount))
        }
    }

    private fun invalidateCoverArtCaches(vararg uriStrings: String?) {
        imageCacheManager.invalidateCoverArtCaches(*uriStrings)
    }

    private suspend fun purgeAlbumArtThemes(vararg uriStrings: String?) {
        val uris = uriStrings.mapNotNull { it?.takeIf(String::isNotBlank) }.distinct()
        if (uris.isEmpty()) return

        withContext(Dispatchers.IO) {
            albumArtThemeDao.deleteThemesByUris(uris)
        }
    }

    // endregion
}
