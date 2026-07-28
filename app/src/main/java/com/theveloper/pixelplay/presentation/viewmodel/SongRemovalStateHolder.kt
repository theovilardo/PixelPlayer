package com.theveloper.pixelplay.presentation.viewmodel

import android.app.Activity
import android.content.IntentSender
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.utils.MediaStorePermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Callbacks supplied by [PlayerViewModel] so the device-deletion flow can reach
 * ViewModel-owned state (toasts, the media-controller queue, and the full
 * "remove song from library + player" routine) and the ViewModel's
 * [CoroutineScope] without [SongRemovalStateHolder] depending on the ViewModel.
 * Mirrors the lambda-callback pattern already used by [MetadataEditCallbacks].
 */
class SongRemovalCallbacks(
    val scope: CoroutineScope,
    val sendToast: (String) -> Unit,
    val removeFromMediaControllerQueue: (String) -> Unit,
    val removeSong: suspend (Song) -> Unit,
)

@ViewModelScoped
class SongRemovalStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val metadataEditStateHolder: MetadataEditStateHolder,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val libraryStateHolder: LibraryStateHolder,
    private val playbackStateHolder: PlaybackStateHolder,
    private val multiSelectionStateHolder: MultiSelectionStateHolder,
    @param:ApplicationContext private val context: android.content.Context
) {

    // MediaStore delete-permission request (Android 11+ system delete dialog).
    // Owned here because only the deletion cluster emits/consumes it; the ViewModel re-exposes it.
    private val _deletePermissionRequest = MutableSharedFlow<IntentSender>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val deletePermissionRequest: SharedFlow<IntentSender> = _deletePermissionRequest.asSharedFlow()

    // Deletions parked while waiting for the user's MediaStore delete-permission decision.
    private var pendingBatchDeleteSongs: List<Song>? = null
    private var pendingBatchDeleteSkippedCount: Int = 0
    private var pendingBatchDeleteOnComplete: (() -> Unit)? = null
    private var pendingDeleteSong: Song? = null
    private var pendingDeleteCallback: ((Boolean) -> Unit)? = null

    suspend fun showDeleteConfirmation(activity: Activity, song: Song): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    return@withContext false
                }

                val userChoice = CompletableDeferred<Boolean>()
                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.song_removal_dialog_delete_song_title))
                    .setMessage(
                        activity.getString(
                            R.string.song_removal_dialog_delete_song_message,
                            song.title,
                            song.displayArtist
                        )
                    )
                    .setPositiveButton(activity.getString(R.string.common_delete)) { _, _ ->
                        userChoice.complete(true)
                    }
                    .setNegativeButton(activity.getString(R.string.common_cancel)) { _, _ ->
                        userChoice.complete(false)
                    }
                    .setOnCancelListener {
                        userChoice.complete(false)
                    }
                    .setCancelable(true)
                    .create()

                dialog.show()
                userChoice.await()
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun deleteSongFile(song: Song): Boolean {
        return metadataEditStateHolder.deleteSong(song)
    }

    suspend fun removeSongFromLibrary(song: Song) {
        libraryStateHolder.removeSong(song.id)
        song.id.toLongOrNull()?.let { musicRepository.deleteById(it) }
        playlistPreferencesRepository.removeSongFromAllPlaylists(song.id)
    }

    // region Device-deletion cluster (moved from PlayerViewModel)

    /**
     * Deletes all selected songs from device with confirmation.
     * Shows a single confirmation dialog for all songs.
     */
    fun deleteSelectedFromDevice(
        activity: Activity,
        songs: List<Song>,
        onComplete: () -> Unit,
        cb: SongRemovalCallbacks,
    ) {
        cb.scope.launch {
            // Filter out currently playing song
            val currentSongId = playbackStateHolder.stablePlayerState.value.currentSong?.id
            val deletableSongs = songs.filter { it.id != currentSongId }

            if (deletableSongs.isEmpty()) {
                cb.sendToast(context.getString(R.string.song_removal_cannot_delete_currently_playing))
                return@launch
            }

            val skippedCount = songs.size - deletableSongs.size

            // On Android 11+, use system batch delete dialog
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val deleteRequests = withContext(Dispatchers.IO) {
                    deletableSongs.mapNotNull { song ->
                        MediaStorePermissionHelper
                            .resolveDeleteRequestUri(
                                context = activity,
                                songId = song.id.toLongOrNull(),
                                contentUriString = song.contentUriString,
                                filePath = song.path,
                            )?.let { uri -> song to uri }
                    }
                }
                if (deleteRequests.size == deletableSongs.size) {
                    val uris = deleteRequests.map { it.second }.distinctBy { it.toString() }
                    val deleteRequest = try {
                        MediaStorePermissionHelper.createDeleteRequest(activity, uris)
                    } catch (e: Throwable) {
                        null
                    }
                    if (deleteRequest != null) {
                        val acceptedUriStrings = deleteRequest.acceptedUris
                            .mapTo(mutableSetOf()) { it.toString() }
                        val acceptedSongs = deleteRequests
                            .filter { (_, uri) -> uri.toString() in acceptedUriStrings }
                            .map { it.first }
                        val invalidRequestCount = deletableSongs.size - acceptedSongs.size

                        pendingBatchDeleteSongs = acceptedSongs
                        pendingBatchDeleteSkippedCount = skippedCount + invalidRequestCount
                        pendingBatchDeleteOnComplete = onComplete
                        _deletePermissionRequest.emit(deleteRequest.intentSender)
                        return@launch
                    }
                }
            }

            // Fallback for older Android or non-MediaStore songs
            val confirmed = showMultiDeleteConfirmation(activity, deletableSongs.size)
            if (!confirmed) {
                onComplete()
                return@launch
            }

            var successCount = 0
            deletableSongs.forEach { song ->
                val success = deleteSongFile(song)
                if (success) {
                    cb.removeFromMediaControllerQueue(song.id)
                    cb.removeSong(song)
                    successCount++
                }
            }

            when {
                successCount == deletableSongs.size && skippedCount == 0 ->
                    cb.sendToast(
                        context.resources.getQuantityString(R.plurals.song_removal_n_files_deleted, successCount, successCount),
                    )
                successCount == deletableSongs.size && skippedCount > 0 ->
                    cb.sendToast(
                        context.getString(
                            R.string.song_removal_batch_delete_files_deleted_skipped_format,
                            successCount,
                            skippedCount,
                        ),
                    )
                successCount > 0 ->
                    cb.sendToast(
                        context.getString(
                            R.string.song_removal_batch_delete_partial_format,
                            successCount,
                            deletableSongs.size,
                        ),
                    )
                else ->
                    cb.sendToast(context.getString(R.string.song_removal_delete_files_failed))
            }

            multiSelectionStateHolder.clearSelection()
            onComplete()
        }
    }

    private suspend fun showMultiDeleteConfirmation(activity: Activity, count: Int): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    return@withContext false
                }

                val userChoice = CompletableDeferred<Boolean>()

                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(
                        context.resources.getQuantityString(
                            R.plurals.song_removal_delete_songs_confirmation_title,
                            count,
                            count,
                        ),
                    )
                    .setMessage(context.getString(R.string.song_removal_delete_songs_permanent_message))
                    .setPositiveButton(context.getString(R.string.common_delete)) { _, _ ->
                        userChoice.complete(true)
                    }
                    .setNegativeButton(context.getString(R.string.common_cancel)) { _, _ ->
                        userChoice.complete(false)
                    }
                    .setOnCancelListener {
                        userChoice.complete(false)
                    }
                    .setCancelable(true)
                    .create()

                dialog.show()
                userChoice.await()
            } catch (e: Exception) {
                false
            }
        }
    }

    fun deleteFromDevice(
        activity: Activity,
        song: Song,
        onResult: (Boolean) -> Unit = {},
        cb: SongRemovalCallbacks,
    ) {
        cb.scope.launch {
            // Failsafe: Prevent deleting the currently playing song
            if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                cb.sendToast(context.getString(R.string.song_removal_cannot_delete_currently_playing))
                onResult(false)
                return@launch
            }

            // On Android 11+, use the system delete confirmation dialog via MediaStore.createDeleteRequest()
            // which both confirms AND handles deletion in one step (no MANAGE_EXTERNAL_STORAGE needed).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intentSender = withContext(Dispatchers.IO) {
                    try {
                        MediaStorePermissionHelper
                            .resolveDeleteRequestUri(
                                context = activity,
                                songId = song.id.toLongOrNull(),
                                contentUriString = song.contentUriString,
                                filePath = song.path,
                            )?.let { uri ->
                                MediaStorePermissionHelper
                                    .createDeleteRequestIntentSender(activity, listOf(uri))
                            }
                    } catch (e: Throwable) {
                        null
                    }
                }
                if (intentSender != null) {
                    pendingDeleteSong = song
                    pendingDeleteCallback = onResult
                    _deletePermissionRequest.emit(intentSender)
                    return@launch
                }
            }

            // Fallback for older Android or files not in MediaStore
            val userConfirmed = showDeleteConfirmation(activity, song)
            if (!userConfirmed) {
                onResult(false)
                return@launch
            }

            val success = deleteSongFile(song)
            if (success) {
                cb.sendToast(context.getString(R.string.song_removal_file_deleted))
                cb.removeFromMediaControllerQueue(song.id)
                cb.removeSong(song)
                onResult(true)
            } else {
                cb.sendToast(context.getString(R.string.song_removal_delete_file_not_found))
                onResult(false)
            }
        }
    }

    /** Called from the UI after the user approves or denies the MediaStore delete request. */
    fun onDeletePermissionResult(granted: Boolean, cb: SongRemovalCallbacks) {
        // Handle batch delete
        val batchSongs = pendingBatchDeleteSongs
        if (batchSongs != null) {
            val skippedCount = pendingBatchDeleteSkippedCount
            val onComplete = pendingBatchDeleteOnComplete
            pendingBatchDeleteSongs = null
            pendingBatchDeleteSkippedCount = 0
            pendingBatchDeleteOnComplete = null
            cb.scope.launch {
                if (granted) {
                    // System already deleted the files — clean up library
                    batchSongs.forEach { song ->
                        cb.removeFromMediaControllerQueue(song.id)
                        cb.removeSong(song)
                    }
                    val count = batchSongs.size
                    if (skippedCount > 0) {
                        cb.sendToast(
                            context.getString(
                                R.string.song_removal_batch_delete_files_deleted_skipped_format,
                                count,
                                skippedCount,
                            ),
                        )
                    } else {
                        cb.sendToast(
                            context.resources.getQuantityString(R.plurals.song_removal_n_files_deleted, count, count),
                        )
                    }
                } else {
                    cb.sendToast(context.getString(R.string.song_removal_deletion_cancelled))
                }
                multiSelectionStateHolder.clearSelection()
                onComplete?.invoke()
            }
            return
        }

        // Handle single delete
        val song = pendingDeleteSong ?: return
        val callback = pendingDeleteCallback
        pendingDeleteSong = null
        pendingDeleteCallback = null
        cb.scope.launch {
            if (granted) {
                // The system already deleted the file — just clean up the library
                cb.sendToast(context.getString(R.string.song_removal_file_deleted))
                cb.removeFromMediaControllerQueue(song.id)
                cb.removeSong(song)
                callback?.invoke(true)
            } else {
                callback?.invoke(false)
            }
        }
    }

    // endregion
}
