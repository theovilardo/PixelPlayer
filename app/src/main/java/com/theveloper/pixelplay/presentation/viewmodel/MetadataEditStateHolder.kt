package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.coverart.AlbumArtStorage
import com.theveloper.pixelplay.data.coverart.AppArtworkWriter
import com.theveloper.pixelplay.data.database.AlbumArtThemeDao
import com.theveloper.pixelplay.data.media.CoverArtUpdate
import com.theveloper.pixelplay.data.media.ImageCacheManager
import com.theveloper.pixelplay.data.media.MetadataEditError
import com.theveloper.pixelplay.data.media.SongMetadataEditor
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.utils.FileDeletionUtils
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.utils.LocalArtworkUri
import com.theveloper.pixelplay.utils.LyricsUtils
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.theveloper.pixelplay.utils.MediaStorePermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import com.theveloper.pixelplay.data.media.AudioMetadataReader
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val appArtworkWriter: AppArtworkWriter,
    private val userPreferencesRepository: UserPreferencesRepository,
    @ApplicationContext private val context: Context
) {

    // MediaStore write-permission request (needed for metadata editing without MANAGE_EXTERNAL_STORAGE).
    // Owned here because only the metadata-edit cluster emits/consumes it; the ViewModel re-exposes it.
    private val _writePermissionRequest = MutableSharedFlow<IntentSender>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val writePermissionRequest: SharedFlow<IntentSender> = _writePermissionRequest.asSharedFlow()

    /** Re-exposed for the UI; see [AppArtworkWriter.appliedArtworkRevision]. */
    val appliedArtworkRevision: StateFlow<Long> = appArtworkWriter.appliedArtworkRevision

    private val _batchEditInProgress = MutableStateFlow(false)
    private val batchEditsRunning = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * True while a batch of songs is being written, so a screen that started one
     * can say it is happening. Under [AlbumArtStorage.AUDIO_FILES] that is a tag
     * rewrite and a MediaStore rescan per track.
     *
     * Raised around the writing rather than the whole save: on Android 11+ the
     * save parks for MediaStore consent and resumes, and an indicator held
     * across that would sit stranded behind the system dialog.
     */
    val batchEditInProgress: StateFlow<Boolean> = _batchEditInProgress.asStateFlow()

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
        val errorMessage: String? = null,
        /**
         * True when a cover was written to the app's artwork store during this
         * save. Reported even on failure, because the store write does not
         * depend on the tag write succeeding, and a caller that skips its
         * refresh on failure would otherwise leave the queue and the
         * notification drawing a cover the rows no longer name.
         */
        val appliedCoverInApp: Boolean = false
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

    /**
     * @param appStoreCoverOutcome whether a cover was already written to the
     * app's store for this song, or null when this call must write it. Writing
     * it here sees one id at a time, so the writer could never recognise an
     * apply as covering a whole album.
     * @param syncLibraryLyrics whether [newLyrics] is also the library's copy.
     * False when the caller filled the field in from the file merely to have
     * something to write, which would otherwise drop lyrics this app fetched.
     */
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
        cb: MetadataEditCallbacks,
        appStoreCoverOutcome: Boolean? = null,
        syncLibraryLyrics: Boolean = true
    ): MetadataEditResult = withContext(Dispatchers.IO) {

        Log.d("MetadataEditStateHolder", "Starting saveMetadata for: ${song.title}")

        // The setting is about artwork, so it holds mid tag edit too: the tags
        // still land in the file and the file's own artwork is left as it was.
        val keepArtInApp = coverArtUpdate != null &&
            !coverArtUpdate.isDeletion &&
            coverArtUpdate.bytes != null &&
            userPreferencesRepository.albumArtStorageFlow.first() == AlbumArtStorage.APP_ONLY
        val fileCoverArtUpdate = if (keepArtInApp) null else coverArtUpdate

        // CRITICAL FIX: Preserve existing embedded artwork if the user didn't provide a new one.
        // Editing text metadata might strip the artwork if the underlying tagging library
        // overwrites the file structure. Explicitly re-saving the existing artwork prevents this.
        val finalCoverArtUpdate = if (fileCoverArtUpdate == null) {
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
        } else if (fileCoverArtUpdate.isDeletion) {
            Log.d("MetadataEditStateHolder", "Artwork deletion requested, skipping preservation")
            fileCoverArtUpdate
        } else {
            fileCoverArtUpdate
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

        // Storing the cover in the app needs nothing from the file, so a tag
        // write that failed on a read-only file or a refused consent is no
        // reason to throw away the cover the user picked.
        val wantsAppStorage = keepArtInApp && coverArtUpdate?.bytes != null
        val coverStored = when {
            !wantsAppStorage -> false
            // Already written for the whole batch this song is part of.
            appStoreCoverOutcome != null -> appStoreCoverOutcome
            else -> appArtworkWriter.apply(
                bytes = requireNotNull(coverArtUpdate?.bytes),
                songIds = listOf(resolvedSongId),
                albumId = song.albumId
            )
        }
        // Only the cover's own outcome fails here; the rest of the save does not
        // depend on it. A batch reports its single write once, from the caller.
        if (wantsAppStorage && !coverStored && appStoreCoverOutcome == null) {
            cb.sendToast(context.getString(R.string.cover_art_apply_failed))
        }

        // The applied store answers before the file does, so an applied cover
        // from before the user switched to AUDIO_FILES would keep winning over
        // the one just written. fileCoverArtUpdate rather than
        // finalCoverArtUpdate: re-saving the file's existing artwork is not a
        // new choice and should not disturb an applied cover.
        if (result.success &&
            fileCoverArtUpdate != null &&
            !fileCoverArtUpdate.isDeletion &&
            fileCoverArtUpdate.bytes != null
        ) {
            AlbumArtUtils.clearAppliedArtForSong(context, resolvedSongId)
        }
        val artAppliedInApp = coverStored


        if (result.success) {
            val refreshedAlbumArtUri = when {
                coverArtUpdate?.isDeletion == true -> null
                artAppliedInApp -> LocalArtworkUri.buildSongUriWithTimestamp(resolvedSongId)
                else -> result.updatedAlbumArtUri ?: song.albumArtUriString
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

            // Removing a cover means removing the applied one too, or the file
            // would lose its artwork and the app would keep showing the cover
            // the user just asked to be rid of.
            if (coverArtUpdate?.isDeletion == true) {
                AlbumArtUtils.clearAppliedArtForSong(context, resolvedSongId)
            }

            // Force cache invalidation if album art might have changed
            val uriToInvalidate = if (coverArtUpdate?.isDeletion == true) song.albumArtUriString else refreshedAlbumArtUri
            // The writer has already dropped the rendered bitmaps; dropping them
            // again here would only cost a reload.
            if (uriToInvalidate != null && !artAppliedInApp) {
                // Invalidate Coil/Glide caches for the affected URI (old or new)
                imageCacheManager.invalidateCoverArtCaches(uriToInvalidate)
            }
            
            // Force regenerate palette
            themeStateHolder.forceRegenerateColorScheme(refreshedAlbumArtUri)

            MetadataEditResult(
                success = true,
                updatedSong = freshSong,
                updatedAlbumArtUri = freshSong.albumArtUriString,
                parsedLyrics = parsedLyrics,
                appliedCoverInApp = artAppliedInApp
            )
        } else {
            Log.w("MetadataEditStateHolder", "Metadata edit failed: ${result.error} - ${result.errorMessage}")
            MetadataEditResult(
                success = false,
                error = result.error,
                errorMessage = result.errorMessage,
                appliedCoverInApp = artAppliedInApp
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

            // The applied cover is the one being shown, so removal drops that
            // rather than rewriting the file. Unconditional once there is one to
            // take back: gating it on "nothing else changed" left the deletion
            // to the tag write, which strips the file's own artwork and cannot
            // run at all on a file that has gone missing.
            val songId = song.id.toLongOrNull()
            val holdsAppliedCover = coverArtUpdate?.isDeletion == true &&
                songId != null &&
                withContext(Dispatchers.IO) {
                    AlbumArtUtils.getAppliedAlbumArtFile(context, songId) != null
                }
            if (holdsAppliedCover) {
                // The editor sends every field on every save, so unless this is
                // the cover on its own the rest still has to reach the file.
                val coverIsTheOnlyChange = onlyCoverArtChanged(
                    song, newTitle, newArtist, newAlbum, newAlbumArtist, newComposer,
                    newGenre, newLyrics, newTrackNumber, newDiscNumber,
                    newReplayGainTrackGainDb, newReplayGainAlbumGainDb
                )
                val refreshed = removeAppliedCoverArt(
                    songs = listOf(song),
                    cb = cb,
                    notify = coverIsTheOnlyChange
                )
                if (coverIsTheOnlyChange) return@launch

                // Back through the front door with the cover taken out: passing
                // the deletion on would take the file's artwork with it. The
                // refreshed copy, so the write works from what the row holds now.
                editSongMetadata(
                    song = refreshed.firstOrNull() ?: song,
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
                    coverArtUpdate = null,
                    cb = cb
                )
                return@launch
            }

            // A cover kept in the app never touches their files, so it skips the
            // tag rewrite and the consent it would ask for -- before asking.
            if (coverArtUpdate != null &&
                !coverArtUpdate.isDeletion &&
                coverArtUpdate.bytes != null &&
                userPreferencesRepository.albumArtStorageFlow.first() == AlbumArtStorage.APP_ONLY &&
                onlyCoverArtChanged(
                    song, newTitle, newArtist, newAlbum, newAlbumArtist, newComposer,
                    newGenre, newLyrics, newTrackNumber, newDiscNumber,
                    newReplayGainTrackGainDb, newReplayGainAlbumGainDb
                )
            ) {
                applyCoverArtInApp(listOf(song), coverArtUpdate, cb)
                return@launch
            }

            // On Android 11+, request MediaStore write permission for local songs
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

    /**
     * True when the incoming values are the ones the editor was showing, so the
     * only thing this save would change is the cover.
     *
     * The editor sends every field on every save, so unlike [isCoverArtOnly]
     * there are no nulls to read the answer off. The comparison must run against
     * whatever each field was *populated* from -- mostly [Song], where comparing
     * to the file's raw tags would report changes the user never made. Only
     * composer, replay gain and fallback lyrics are read from the file, because
     * that is where the editor reads them from too.
     *
     * Anything unreadable answers false, which writes the file: a needless
     * rewrite is recoverable, a silently dropped edit is not.
     */
    private suspend fun onlyCoverArtChanged(
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
        newReplayGainAlbumGainDb: String?
    ): Boolean {
        fun sameText(incoming: String?, shown: String?): Boolean =
            incoming.orEmpty().trim() == shown.orEmpty().trim()

        val matchesLibrary = sameText(newTitle, song.title) &&
            sameText(newArtist, song.displayArtist) &&
            sameText(newAlbum, song.album) &&
            sameText(newAlbumArtist, song.albumArtist) &&
            sameText(newGenre, song.genre) &&
            newTrackNumber == song.trackNumber &&
            newDiscNumber == song.discNumber
        if (!matchesLibrary) return false

        if (song.path.isBlank()) return false
        val embedded = withContext(Dispatchers.IO) {
            runCatching {
                AudioMetadataReader.read(java.io.File(song.path), readArtwork = false)
            }.getOrNull()
        } ?: return false

        fun sameGain(incoming: String?, shown: Float?): Boolean {
            val parsed = incoming?.trim()?.removeSuffix("dB")?.trim()?.toFloatOrNull()
            return when {
                parsed == null && shown == null -> true
                parsed == null || shown == null -> false
                else -> abs(parsed - shown) < 0.01f
            }
        }

        // The editor shows the library's lyrics when it has any and the file's
        // otherwise, so that is the order the comparison has to follow.
        val shownLyrics = song.lyrics?.takeIf { it.isNotBlank() } ?: embedded.lyrics

        return sameText(newComposer, embedded.composer) &&
            sameText(newLyrics, shownLyrics) &&
            sameGain(newReplayGainTrackGainDb, embedded.replayGainTrackGainDb) &&
            sameGain(newReplayGainAlbumGainDb, embedded.replayGainAlbumGainDb)
    }

    /**
     * True when the only thing being changed is the cover, which is what an
     * apply from the album screen or the cover art picker sends.
     */
    private fun isCoverArtOnly(
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
        coverArtUpdate: CoverArtUpdate?
    ): Boolean = coverArtUpdate != null &&
        !coverArtUpdate.isDeletion &&
        coverArtUpdate.bytes != null &&
        listOf(
            title, artist, album, albumArtist, composer, genre, lyrics,
            trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb
        ).all { it == null }

    /**
     * True when the only thing being changed is that the cover goes away, which
     * is what the batch editor's delete button sends on its own.
     *
     * The counterpart to [isCoverArtOnly] for a removal, and read for the same
     * reason: a save that has nothing to write into the files should not open a
     * tag rewrite, nor ask for the consent one needs.
     */
    private fun isCoverArtDeletionOnly(
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
        coverArtUpdate: CoverArtUpdate?
    ): Boolean = coverArtUpdate?.isDeletion == true &&
        listOf(
            title, artist, album, albumArtist, composer, genre, lyrics,
            trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb
        ).all { it == null }

    /** The songs of [songs] whose cover is one this app is holding. */
    private suspend fun songsHoldingAppliedCover(songs: List<Song>): List<Song> =
        withContext(Dispatchers.IO) {
            songs.filter { song ->
                song.id.toLongOrNull()
                    ?.let { AlbumArtUtils.getAppliedAlbumArtFile(context, it) != null } == true
            }
        }

    /**
     * Takes back a cover applied to [songs], leaving the audio files alone.
     *
     * The undo for an apply, and the reason it is worth having: the cover was
     * put on a whole album in one action, so it has to come off the same way.
     * Everything after the removal is the refresh an apply does, because the
     * same rows, queue entries and notification are showing the old cover.
     */
    fun removeAppliedCoverArt(songs: List<Song>, cb: MetadataEditCallbacks) {
        cb.scope.launch { removeAppliedCoverArt(songs, cb, notify = true) }
    }

    /**
     * The removal itself, for callers already inside a coroutine.
     *
     * @param notify whether to announce the removal. False when the removal is
     * one half of a save that still has tags to write: the selection and the
     * toast belong to that save as a whole, which reports its own outcome once
     * rather than twice.
     * @return the refreshed copies of [songs], so a caller carrying on with the
     * same songs works from what the rows hold now rather than from the applied
     * cover it just took back.
     */
    private suspend fun removeAppliedCoverArt(
        songs: List<Song>,
        cb: MetadataEditCallbacks,
        notify: Boolean
    ): List<Song> {
        val localSongs = songs.filter { (it.id.toLongOrNull() ?: 0L) > 0L }
        val songIds = localSongs.mapNotNull { it.id.toLongOrNull() }
        if (songIds.isEmpty()) return songs

        val albumId = localSongs.map { it.albumId }.distinct().singleOrNull()
        val remainingArt = appArtworkWriter.removeApplied(songIds = songIds, albumId = albumId)

        // What the writer left in each row, not a URI per song: a song whose
        // file carries no art is null there, and a URI resolving to nothing
        // would contradict the row until the next library load.
        val updatedSongs = localSongs.mapNotNull { song ->
            val songId = song.id.toLongOrNull() ?: return@mapNotNull null
            val refreshed = remainingArt[songId]
                ?.let { LocalArtworkUri.buildSongUriWithTimestamp(songId) }
            song.copy(albumArtUriString = refreshed)
        }
        updatedSongs.forEach(libraryStateHolder::updateSong)

        cb.updateUiState { state ->
            var queue = state.currentPlaybackQueue
            updatedSongs.forEach { updated -> queue = queue.replaceSong(updated) }
            if (queue === state.currentPlaybackQueue) state else state.copy(currentPlaybackQueue = queue)
        }

        val playingSong = playbackStateHolder.stablePlayerState.value.currentSong
        updatedSongs.firstOrNull { it.id == playingSong?.id }?.let { updated ->
            playbackStateHolder.updateStablePlayerState { it.copy(currentSong = updated) }
            refreshPlayerArtwork(updated)
        }

        if (notify) {
            multiSelectionStateHolder.clearSelection()
            cb.sendToast(context.getString(R.string.cover_art_removed_in_app))
        }
        return updatedSongs
    }

    /**
     * Applies a cover to the app's own artwork store, leaving the audio files
     * alone, and refreshes the rows and in-memory songs the UI draws from.
     */
    private suspend fun applyCoverArtInApp(
        songs: List<Song>,
        coverArtUpdate: CoverArtUpdate,
        cb: MetadataEditCallbacks
    ) {
        val bytes = coverArtUpdate.bytes ?: return
        val localSongs = songs.filter { (it.id.toLongOrNull() ?: 0L) > 0L }
        if (localSongs.isEmpty()) {
            // Cloud tracks have no local artwork store to write to; saying so
            // beats a button that looks like it did nothing.
            cb.sendToast(context.getString(R.string.cover_art_applied_unsupported))
            return
        }

        // Grouped by album, as the batch save groups it: AppArtworkWriter can
        // only tell whether an apply covers a whole album when handed one
        // album's ids alone, and a call naming two albums lets neither row
        // follow. One album stays a single write.
        val idsByAlbum = localSongs
            .mapNotNull { song ->
                song.id.toLongOrNull()?.takeIf { it > 0 }?.let { song.albumId to it }
            }
            .groupBy({ it.first }, { it.second })

        val storedAlbums = idsByAlbum
            .filter { (albumId, songIds) ->
                appArtworkWriter.apply(bytes = bytes, songIds = songIds, albumId = albumId)
            }
            .keys

        if (storedAlbums.isEmpty()) {
            // Nothing was written, so nothing below it -- the rows, the queue,
            // the notification -- would be describing a cover the store does
            // not actually have.
            cb.sendToast(context.getString(R.string.cover_art_apply_failed))
            return
        }

        // Only what actually landed: a song whose album failed to store is
        // still drawn from the cover it had.
        refreshSongsAfterAppliedCover(localSongs.filter { it.albumId in storedAlbums }, cb)
        multiSelectionStateHolder.clearSelection()
        cb.sendToast(
            context.getString(
                if (storedAlbums.size == idsByAlbum.size) {
                    R.string.cover_art_applied_in_app
                } else {
                    R.string.cover_art_apply_failed
                }
            )
        )
    }

    /**
     * Points the in-memory copies of [songs] at the cover just written to the
     * app's store.
     *
     * This is what the file-writing path does for itself after a cover changes.
     * Without it the notification keeps the old art and the queue holds songs
     * drawn from the previous cover, while the rows and the store hold the new
     * one -- a disagreement nothing resolves until the next library load.
     */
    private fun refreshSongsAfterAppliedCover(songs: List<Song>, cb: MetadataEditCallbacks) {
        val updatedSongs = songs.mapNotNull { song ->
            val songId = song.id.toLongOrNull() ?: return@mapNotNull null
            song.copy(albumArtUriString = LocalArtworkUri.buildSongUriWithTimestamp(songId))
        }
        updatedSongs.forEach(libraryStateHolder::updateSong)

        cb.updateUiState { state ->
            var queue = state.currentPlaybackQueue
            updatedSongs.forEach { updated -> queue = queue.replaceSong(updated) }
            if (queue === state.currentPlaybackQueue) state else state.copy(currentPlaybackQueue = queue)
        }

        val playingSong = playbackStateHolder.stablePlayerState.value.currentSong
        updatedSongs.firstOrNull { it.id == playingSong?.id }?.let { updated ->
            playbackStateHolder.updateStablePlayerState { it.copy(currentSong = updated) }
            refreshPlayerArtwork(updated)
        }
    }

    /**
     * Rebuilds the playing item so the notification picks up the new artwork.
     * Media3 keeps the metadata it was handed, so nothing else refreshes it.
     */
    private fun refreshPlayerArtwork(updatedSong: Song) {
        val controller = playbackStateHolder.mediaController ?: return
        val currentIndex = controller.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex >= controller.mediaItemCount) return

        val currentPosition = controller.currentPosition
        controller.replaceMediaItem(currentIndex, MediaItemBuilder.build(updatedSong))
        // replaceMediaItem may reset the position.
        controller.seekTo(currentIndex, currentPosition)
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
            // A cover the user keeps in the app never touches their files, so it
            // skips the tag rewrite and the write consent it would ask for.
            if (isCoverArtOnly(title, artist, album, albumArtist, composer, genre, lyrics,
                    trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb,
                    coverArtUpdate) &&
                userPreferencesRepository.albumArtStorageFlow.first() == AlbumArtStorage.APP_ONLY
            ) {
                applyCoverArtInApp(songs, requireNotNull(coverArtUpdate), cb)
                return@launch
            }

            // Taking one back never touches the files either, so it skips the
            // rewrite and the consent. Letting the tag write carry the deletion
            // strips the file's own artwork, and cannot run on a missing file.
            if (isCoverArtDeletionOnly(title, artist, album, albumArtist, composer, genre,
                    lyrics, trackNumber, discNumber, replayGainTrackGainDb,
                    replayGainAlbumGainDb, coverArtUpdate)
            ) {
                val holders = songsHoldingAppliedCover(songs)
                // Only when every selected song's cover is one the app holds. A
                // selection mixing the two still has files to rewrite, and
                // performBatchMetadataEdit sorts those out track by track.
                if (holders.isNotEmpty() && holders.size == songs.size) {
                    removeAppliedCoverArt(holders, cb, notify = true)
                    return@launch
                }
            }

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
            coverArtUpdate = coverArtUpdate,
            cb = cb
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
            // The cover is kept even when the tags could not be written, so the
            // rows and the store already hold it; without this the queue and the
            // notification would keep the old art until the next library load.
            if (result.appliedCoverInApp) {
                refreshSongsAfterAppliedCover(listOf(song), cb)
            }
            val errorMessage = result.getUserFriendlyErrorMessage()
            Log.e("PlayerViewModel", "METADATA_EDIT_VM: Failed - ${result.error}: $errorMessage")
            cb.sendToast(errorMessage)
        }
    }

    /**
     * Runs [block] with [batchEditInProgress] raised.
     *
     * The flag is a count in spirit rather than a boolean: two batches can
     * overlap -- a permission-resumed save landing while another is running --
     * and the first to finish must not lower it while the second still writes.
     */
    private suspend fun withBatchEditInProgress(block: suspend () -> Unit) {
        batchEditsRunning.incrementAndGet()
        _batchEditInProgress.value = true
        try {
            block()
        } finally {
            if (batchEditsRunning.decrementAndGet() == 0) {
                _batchEditInProgress.value = false
            }
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
    ) = withBatchEditInProgress {
        var successCount = 0
        var failureCount = 0
        val previousAlbumArts = mutableSetOf<String?>()

        // Written here rather than by the per-song saves: only a whole album's
        // ids at once let the writer recognise the apply as covering that album
        // and point its row at the new cover, and the image is decoded, scaled
        // and re-encoded once per album instead of once per track. Grouped by
        // album so a selection spanning two lets both rows follow.
        val appStoreCoverOutcomes: Map<String, Boolean>? = run {
            val bytes = coverArtUpdate?.takeIf { !it.isDeletion }?.bytes ?: return@run null
            if (userPreferencesRepository.albumArtStorageFlow.first() != AlbumArtStorage.APP_ONLY) {
                return@run null
            }
            // The same ids the per-song saves resolve, so the set written here
            // is exactly the set they each expect to have been written for.
            val writable = songs.mapNotNull { song ->
                resolveSongIdForMetadataEdit(song)?.takeIf { it > 0 }?.let { song to it }
            }
            if (writable.isEmpty()) return@run null

            val outcomeByAlbum = writable
                .groupBy({ it.first.albumId }, { it.second })
                .mapValues { (albumId, songIds) ->
                    appArtworkWriter.apply(bytes = bytes, songIds = songIds, albumId = albumId)
                }
            // Reported once for the save rather than once per track, and only
            // for what actually failed to store.
            if (outcomeByAlbum.values.any { !it }) {
                cb.sendToast(context.getString(R.string.cover_art_apply_failed))
            }

            // Per song rather than per album: the writer refuses a cloud
            // track's negative id, and reading its album's success as its own
            // would point the track at a local artwork URI with no file behind
            // it. Songs nothing was written for are answered false rather than
            // left out, so the per-song save does not try again.
            val writableIds = writable.mapTo(mutableSetOf()) { it.first.id }
            songs.associate { song ->
                song.id to (song.id in writableIds && outcomeByAlbum[song.albumId] == true)
            }
        }

        songs.forEach { song ->
            previousAlbumArts.add(song.albumArtUriString)

            // Reaching here means the selection mixes covers this app holds
            // with covers that really are in the file, so the distinction is
            // drawn per track: handing an applied one to the tag writer would
            // strip the file's own artwork instead of revealing it.
            val appliedSongId = song.id.toLongOrNull()
            val holdsAppliedCover = coverArtUpdate?.isDeletion == true &&
                appliedSongId != null &&
                withContext(Dispatchers.IO) {
                    AlbumArtUtils.getAppliedAlbumArtFile(context, appliedSongId) != null
                }
            val songCoverArtUpdate = if (holdsAppliedCover) null else coverArtUpdate
            // The refreshed copy, so the save below works from what the row
            // holds now rather than from the cover it no longer points at.
            val editedSong = if (holdsAppliedCover) {
                removeAppliedCoverArt(listOf(song), cb, notify = false).firstOrNull() ?: song
            } else {
                song
            }

            // A null field means "not being edited", but the tag write replaces
            // the whole set, so each one still needs a value -- and Song is the
            // library's rendering of the file, not the file. Writing it back
            // turns a multi-artist "A; B" into displayArtist's ", " join, lands
            // a filename-derived title in a file that had its own, and embeds
            // lyrics this app fetched. The file answers for its own tags; Song
            // fills in only what it has nothing to say about.
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
                song = editedSong,
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
                coverArtUpdate = songCoverArtUpdate,
                cb = cb,
                appStoreCoverOutcome = appStoreCoverOutcomes?.get(song.id),
                // The lyrics above are the file's own when this save is not
                // editing them, which is not what the library's copy should
                // be overwritten with.
                syncLibraryLyrics = lyrics != null
            )

            if (result.success && result.updatedSong != null) {
                successCount++
                val updatedSong = result.updatedSong
                val refreshedAlbumArtUri = result.updatedAlbumArtUri

                // Invalidate caches for this song
                song.id.toLongOrNull()?.takeIf { coverArtUpdate?.isDeletion == true }?.let { songId ->
                    // clearAppliedArtForSong blocks on a file delete under a shared lock --
                    // this whole branch otherwise runs on viewModelScope's Main dispatcher,
                    // so only this call is pushed off it rather than wrapping the block that
                    // follows, which touches the MediaController and must stay on Main.
                    withContext(Dispatchers.IO) {
                        AlbumArtUtils.clearAppliedArtForSong(context, songId)
                    }
                }
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
                // As in the single-song path: the cover is kept even when the
                // tags could not be written, so the copies this song is drawn
                // from have to follow the row rather than keep the old art.
                if (result.appliedCoverInApp) {
                    refreshSongsAfterAppliedCover(listOf(song), cb)
                }
            }
        }

        // Handle cover art theme updates if artwork was changed
        if (coverArtUpdate != null) {
            // A cover written into the files leaves the album row pointing at
            // the URI it already held, so nothing tells a header to reload.
            // The app store's own writes announce themselves.
            if (appStoreCoverOutcomes == null) {
                appArtworkWriter.noteExternalArtworkChange()
            }

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

    private suspend fun performBatchEditGenre(
        songs: List<Song>,
        newGenre: String,
        cb: MetadataEditCallbacks
    ) = withBatchEditInProgress {
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
                coverArtUpdate = null,
                cb = cb
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
