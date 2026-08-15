package com.theveloper.pixelplay.presentation.viewmodel

import android.net.Uri
import android.util.Log
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.model.SmartPlaylistRule
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.playlist.M3uManager
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.wear.PhoneWatchBatchTransferState
import com.theveloper.pixelplay.data.service.wear.PhoneWatchTransferStateStore
import com.theveloper.pixelplay.data.service.wear.PlaylistWatchTransferCoordinator
import com.theveloper.pixelplay.data.service.wear.WatchAudioTranscoder
import com.theveloper.pixelplay.data.service.wear.WatchPlaylistTransferEstimate
import com.theveloper.pixelplay.data.service.wear.WatchPlaylistTransferEstimator
import com.theveloper.pixelplay.data.service.wear.WearPhoneTransferSender
import com.theveloper.pixelplay.shared.WearTransferProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.OutputStreamWriter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import com.theveloper.pixelplay.data.preferences.TelegramTopicDisplayMode
import com.theveloper.pixelplay.data.ai.AiPlaylistGenerator
import com.theveloper.pixelplay.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class PlaylistUiState(
    val playlists: List<Playlist> = emptyList(),
    val showTelegramCloudPlaylists: Boolean = true,
    val telegramTopicDisplayMode: TelegramTopicDisplayMode = TelegramTopicDisplayMode.CHANNELS_AND_TOPICS,
    val currentPlaylistSongs: List<Song> = emptyList(),
    val currentPlaylistDetails: Playlist? = null,
    val isLoading: Boolean = false,
    val playlistNotFound: Boolean = false,

    //Sort option
    val currentPlaylistSortOption: SortOption = SortOption.PlaylistNameAZ,
    val currentPlaylistSongsSortOption: SortOption = SortOption.SongTitleAZ,
    val playlistSongsOrderMode: PlaylistSongsOrderMode = PlaylistSongsOrderMode.Sorted(SortOption.SongTitleAZ),
    val playlistOrderModes: Map<String, PlaylistSongsOrderMode> = emptyMap(),

    // AI Generation State
    val isAiGenerating: Boolean = false,
    val aiGenerationError: String? = null
)

sealed class PlaylistSongsOrderMode {
    object Manual : PlaylistSongsOrderMode()
    data class Sorted(val option: SortOption) : PlaylistSongsOrderMode()
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val dailyMixManager: DailyMixManager,
    private val aiPlaylistGenerator: AiPlaylistGenerator,
    private val m3uManager: M3uManager,
    private val playlistWatchTransferCoordinator: PlaylistWatchTransferCoordinator,
    private val watchTransferStateStore: PhoneWatchTransferStateStore,
    private val wearPhoneTransferSender: WearPhoneTransferSender,
    private val watchAudioTranscoder: WatchAudioTranscoder,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    private val _playlistCreationEvent = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val playlistCreationEvent: SharedFlow<Boolean> = _playlistCreationEvent.asSharedFlow()

    private val _isPixelPlayWatchAvailable = MutableStateFlow(false)
    val isPixelPlayWatchAvailable: StateFlow<Boolean> = _isPixelPlayWatchAvailable.asStateFlow()
    private val _isRefreshingWatchAvailability = MutableStateFlow(false)
    val watchSongIds: StateFlow<Set<String>> = watchTransferStateStore.watchSongIds

    /** Playlists the watch itself reports having, which is what "send" vs "update" hinges on. */
    val watchPlaylistIds: StateFlow<Set<String>> = watchTransferStateStore.watchPlaylistIds

    /** Whether any watch has ever been paired with PixelPlay installed — as opposed to
     *  [isPixelPlayWatchAvailable], which is "reachable right now". Gates whether watch-related
     *  actions show at all, vs. showing disabled for a paired-but-out-of-range watch. */
    val isAnyWatchPaired: StateFlow<Boolean> = watchTransferStateStore.isAnyWatchPaired

    /**
     * Whichever playlist batch transfer is currently active, regardless of which screen/ViewModel
     * instance started it — queried off the shared [PhoneWatchTransferStateStore] instead of
     * remembering "the last batchId this instance kicked off", so re-entering the detail screen
     * for the same playlist still sees a batch already in flight.
     */
    val activePlaylistBatchTransfer: StateFlow<PhoneWatchBatchTransferState?> = watchTransferStateStore.batchTransfers
        .map { batches -> batches.values.firstOrNull { it.status !in TERMINAL_BATCH_STATUSES } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null,
        )

    companion object {
        const val FOLDER_PLAYLIST_PREFIX = "folder_playlist:"
        private const val MANUAL_ORDER_MODE = "manual"
        private const val SMART_PLAYLIST_MAX_ITEMS = 100
        private val TERMINAL_BATCH_STATUSES = setOf(
            WearTransferProgress.STATUS_COMPLETED,
            WearTransferProgress.STATUS_FAILED,
            WearTransferProgress.STATUS_CANCELLED,
        )

        fun sanitizeFileName(name: String): String {
            val sanitized = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_')
            return if (sanitized.isEmpty()) "Playlist" else sanitized
        }
    }

    // Helper function to resolve stored playlist sort keys
    private fun resolvePlaylistSortOption(optionKey: String?): SortOption {
        return SortOption.fromStorageKey(
            optionKey,
            SortOption.PLAYLISTS,
            SortOption.PlaylistNameAZ
        )
    }

    init {
        loadPlaylistsAndInitialSortOption()
        observeTelegramCloudPlaylistVisibility()
        observeTelegramTopicDisplayMode()
        observePlaylistOrderModes()
    }

    private fun observePlaylistOrderModes() {
        viewModelScope.launch {
            playlistPreferencesRepository.playlistSongOrderModesFlow.collect { storedModes ->
                val resolvedModes = storedModes.mapValues { (_, value) ->
                    decodeOrderMode(value)
                }
                _uiState.update { it.copy(playlistOrderModes = resolvedModes) }
            }
        }
    }

    private fun loadPlaylistsAndInitialSortOption() {
        viewModelScope.launch {
            // First, get the initial sort option
            val initialSortOptionName = playlistPreferencesRepository.playlistsSortOptionFlow.first()
            val initialSortOption = resolvePlaylistSortOption(initialSortOptionName)
            _uiState.update { it.copy(currentPlaylistSortOption = initialSortOption) }

            // Then, collect playlists and apply the sort option
            playlistPreferencesRepository.userPlaylistsFlow.collect { playlists ->
                val currentSortOption =
                    _uiState.value.currentPlaylistSortOption // Use the most up-to-date sort option
                val sortedPlaylists = sortPlaylistsList(playlists, currentSortOption)
                _uiState.update { it.copy(playlists = sortedPlaylists) }
            }
        }
        // Collect subsequent changes to sort option from preferences
        viewModelScope.launch {
            playlistPreferencesRepository.playlistsSortOptionFlow.collect { optionName ->
                val newSortOption = resolvePlaylistSortOption(optionName)
                if (_uiState.value.currentPlaylistSortOption != newSortOption) {
                    // If the option from preferences is different, re-sort the current list
                    sortPlaylists(newSortOption)
                }
            }
        }
    }

    private fun observeTelegramCloudPlaylistVisibility() {
        viewModelScope.launch {
            playlistPreferencesRepository.showTelegramCloudPlaylistsFlow.collect { show ->
                _uiState.update { it.copy(showTelegramCloudPlaylists = show) }
            }
        }
    }

    private fun observeTelegramTopicDisplayMode() {
        viewModelScope.launch {
            playlistPreferencesRepository.telegramTopicDisplayModeFlow.collect { mode ->
                _uiState.update { it.copy(telegramTopicDisplayMode = mode) }
            }
        }
    }

    fun setTelegramTopicDisplayMode(mode: TelegramTopicDisplayMode) { // Simplified
        _uiState.update { it.copy(telegramTopicDisplayMode = mode) }
        viewModelScope.launch {
            playlistPreferencesRepository.setTelegramTopicDisplayMode(mode)
        }
    }

    fun loadPlaylistDetails(playlistId: String) {
        viewModelScope.launch {
            val shouldKeepExisting = _uiState.value.currentPlaylistDetails?.id == playlistId
            _uiState.update {
                it.copy(
                    isLoading = true,
                    playlistNotFound = false,
                    currentPlaylistDetails = if (shouldKeepExisting) it.currentPlaylistDetails else null,
                    currentPlaylistSongs = if (shouldKeepExisting) it.currentPlaylistSongs else emptyList()
                )
            } // Resetear detalles y canciones
            try {
                if (isFolderPlaylistId(playlistId)) {
                    val folderPath = Uri.decode(playlistId.removePrefix(FOLDER_PLAYLIST_PREFIX))
                    val folders = musicRepository.getMusicFolders().first()
                    val folder = findFolder(folderPath, folders)

                    if (folder != null) {
                        val songsList = withContext(Dispatchers.IO) {
                            val rawSongs = folder.collectAllSongs()
                            if (rawSongs.any { it.contentUriString.isBlank() }) {
                                musicRepository.getSongsByIds(rawSongs.map { it.id }).first()
                            } else {
                                rawSongs
                            }
                        }
                        val pseudoPlaylist = Playlist(
                            id = playlistId,
                            name = folder.name,
                            songIds = songsList.map { it.id }
                        )

                        _uiState.update {
                            it.copy(
                                currentPlaylistDetails = pseudoPlaylist,
                                currentPlaylistSongs = applySortToSongs(songsList, it.currentPlaylistSongsSortOption),
                                playlistSongsOrderMode = PlaylistSongsOrderMode.Sorted(it.currentPlaylistSongsSortOption),
                                isLoading = false,
                                playlistNotFound = false
                            )
                        }
                    } else {
                        Log.w("PlaylistVM", "Folder playlist with path $folderPath not found.")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                playlistNotFound = true,
                                currentPlaylistDetails = null,
                                currentPlaylistSongs = emptyList()
                            )
                        }
                    }
                } else {
                    // Obtener la playlist de las preferencias del usuario
                    val playlist = playlistPreferencesRepository.userPlaylistsFlow.first()
                        .find { it.id == playlistId }

                    if (playlist != null) {
                        val orderMode = _uiState.value.playlistOrderModes[playlistId]
                            ?: PlaylistSongsOrderMode.Manual

                        // Colectar la lista de canciones del Flow devuelto por el repositorio en un hilo de IO
                        val songsList: List<Song> = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            musicRepository.getSongsByIds(playlist.songIds).first()
                        }

                        val orderedSongs = when (orderMode) {
                            is PlaylistSongsOrderMode.Sorted -> applySortToSongs(songsList, orderMode.option)
                            PlaylistSongsOrderMode.Manual -> songsList
                        }

                        // La actualización del UI se hace en el hilo principal
                        _uiState.update {
                            it.copy(
                                currentPlaylistDetails = playlist,
                                currentPlaylistSongs = orderedSongs,
                                currentPlaylistSongsSortOption = (orderMode as? PlaylistSongsOrderMode.Sorted)?.option
                                    ?: it.currentPlaylistSongsSortOption,
                                playlistSongsOrderMode = orderMode,
                                playlistOrderModes = it.playlistOrderModes + (playlistId to orderMode),
                                isLoading = false,
                                playlistNotFound = false
                            )
                        }
                    } else {
                        Log.w("PlaylistVM", "Playlist with id $playlistId not found.")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                playlistNotFound = true,
                                currentPlaylistDetails = null,
                                currentPlaylistSongs = emptyList()
                            )
                        } // Mantener isLoading en false
                        // Opcional: podrías establecer un error o un estado específico de "no encontrado"
                    }
                }
            } catch (e: Exception) {
                Log.e("PlaylistVM", "Error loading playlist details for id $playlistId", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        playlistNotFound = true,
                        currentPlaylistDetails = null,
                        currentPlaylistSongs = emptyList()
                    )
                }
            }
        }
    }

    fun createPlaylist(
        name: String,
        coverImageUri: String? = null,
        coverColor: Int? = null,
        coverIcon: String? = null,
        songIds: List<String> = emptyList(), // Added songIds parameter
        cropScale: Float = 1f,
        cropPanX: Float = 0f,
        cropPanY: Float = 0f,
        isAiGenerated: Boolean = false,
        isQueueGenerated: Boolean = false,
        coverShapeType: String? = null,
        coverShapeDetail1: Float? = null,
        coverShapeDetail2: Float? = null,
        coverShapeDetail3: Float? = null,
        coverShapeDetail4: Float? = null,
        source: String = "LOCAL", // Mark source
        smartRuleKey: String? = null
    ) {
        viewModelScope.launch {
            var savedCoverPath: String? = null

            if (coverImageUri != null) {
                // Generate a unique ID for the image file since we don't have the playlist ID yet
                val imageId = UUID.randomUUID().toString()
                savedCoverPath = saveCoverImageToInternalStorage(
                    Uri.parse(coverImageUri),
                    imageId,
                    cropScale,
                    cropPanX,
                    cropPanY
                )
            }

            val resolvedSmartRule = SmartPlaylistRule.fromStorageKey(smartRuleKey)
            val resolvedSongIds = if (resolvedSmartRule != null) {
                buildSmartPlaylistSongIds(
                    rule = resolvedSmartRule,
                    limit = SMART_PLAYLIST_MAX_ITEMS
                )
            } else {
                songIds
            }
            val resolvedSource = when {
                resolvedSmartRule != null && source == "LOCAL" -> "SMART"
                else -> source
            }

            playlistPreferencesRepository.createPlaylist(
                name = name,
                songIds = resolvedSongIds,
                isAiGenerated = isAiGenerated,
                isQueueGenerated = isQueueGenerated,
                coverImageUri = savedCoverPath,
                coverColorArgb = coverColor,
                coverIconName = coverIcon,
                coverShapeType = coverShapeType,
                coverShapeDetail1 = coverShapeDetail1,
                coverShapeDetail2 = coverShapeDetail2,
                coverShapeDetail3 = coverShapeDetail3,
                coverShapeDetail4 = coverShapeDetail4,
                source = resolvedSource
            )
            _playlistCreationEvent.emit(true)
        }
    }

    private suspend fun buildSmartPlaylistSongIds(
        rule: SmartPlaylistRule,
        limit: Int
    ): List<String> {
        val allSongs = musicRepository.getAllSongsOnce()
        if (allSongs.isEmpty()) return emptyList()

        val engagements = dailyMixManager.getAllEngagementStats()
        val now = System.currentTimeMillis()
        val songById = allSongs.associateBy { it.id }
        val favoriteIds = musicRepository.getFavoriteSongIdsOnce()
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(allSongs.size)

        val pickedSongs = when (rule) {
            SmartPlaylistRule.TOP_PLAYED -> {
                engagements.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, DailyMixManager.SongEngagementStats>> { it.value.playCount }
                            .thenByDescending { it.value.totalPlayDurationMs }
                            .thenByDescending { it.value.lastPlayedTimestamp }
                    )
                    .mapNotNull { (songId, _) -> songById[songId] }
                    .take(safeLimit)
            }

            SmartPlaylistRule.RECENTLY_PLAYED -> {
                engagements.entries
                    .filter { it.value.lastPlayedTimestamp > 0L }
                    .sortedByDescending { it.value.lastPlayedTimestamp }
                    .mapNotNull { (songId, _) -> songById[songId] }
                    .take(safeLimit)
            }

            SmartPlaylistRule.FORGOTTEN_FAVORITES -> {
                val staleThreshold = now - TimeUnit.DAYS.toMillis(30)
                allSongs
                    .asSequence()
                    .filter { favoriteIds.contains(it.id) }
                    .sortedWith(
                        compareBy<Song> { engagements[it.id]?.lastPlayedTimestamp ?: 0L }
                            .thenBy { it.title.lowercase() }
                    )
                    .filter { song ->
                        (engagements[song.id]?.lastPlayedTimestamp ?: 0L) < staleThreshold
                    }
                    .take(safeLimit)
                    .toList()
            }

            SmartPlaylistRule.NEW_GEMS -> {
                allSongs
                    .asSequence()
                    .sortedWith(
                        compareByDescending<Song> { it.dateAdded }
                            .thenBy { engagements[it.id]?.playCount ?: 0 }
                    )
                    .filter { song -> (engagements[song.id]?.playCount ?: 0) <= 2 }
                    .take(safeLimit)
                    .toList()
            }
        }

        if (pickedSongs.isNotEmpty()) {
            return pickedSongs.map { it.id }.distinct()
        }

        return allSongs
            .sortedByDescending { it.dateAdded }
            .take(safeLimit)
            .map { it.id }
    }


    suspend fun saveCoverImageToInternalStorage(
        uri: Uri,
        uniqueId: String,
        cropScale: Float,
        cropPanX: Float,
        cropPanY: Float
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Robust bitmap loading (Content URI or Local File)
                val originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = when {
                        uri.scheme == "content" -> ImageDecoder.createSource(context.contentResolver, uri)
                        uri.scheme == "file" || uri.path?.startsWith("/") == true -> {
                            ImageDecoder.createSource(File(uri.path ?: ""))
                        }
                        else -> ImageDecoder.createSource(context.contentResolver, uri)
                    }
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (uri.scheme == "content") {
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    } else {
                        android.graphics.BitmapFactory.decodeFile(uri.path)
                    }
                }

                if (originalBitmap == null) return@withContext null

                // Target dimensions (Square)
                val targetSize = 1024

                // create target bitmap
                val targetBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(targetBitmap)

                // Calculate base dimensions (fitting smallest dimension to target)
                // Logic must match ImageCropView
                val bitmapWidth = originalBitmap.width.toFloat()
                val bitmapHeight = originalBitmap.height.toFloat()
                val bitmapRatio = bitmapWidth / bitmapHeight

                val (baseWidth, baseHeight) = if (bitmapRatio > 1f) {
                    // Wide: Height matches target
                    targetSize * bitmapRatio to targetSize.toFloat()
                } else {
                    // Tall: Width matches target
                    targetSize.toFloat() to targetSize / bitmapRatio
                }

                // Calculate transformations
                // Scaled Dimensions
                val scaledWidth = baseWidth * cropScale
                val scaledHeight = baseHeight * cropScale

                // Center + Pan
                // Center of target is targetSize/2
                // We want to center the Scaled Image at (Center + Pan)
                // TopLeft = CenterX - ScaledW/2 + PanX

                // Pan is normalized relative to Viewport (TargetSize)
                val panPxX = cropPanX * targetSize
                val panPxY = cropPanY * targetSize

                val dx = (targetSize - scaledWidth) / 2f + panPxX
                val dy = (targetSize - scaledHeight) / 2f + panPxY

                // Draw
                // We draw the original bitmap scaled to (scaledWidth, scaledHeight) at (dx, dy)
                val matrix = android.graphics.Matrix()
                matrix.postScale(scaledWidth / bitmapWidth, scaledHeight / bitmapHeight)
                matrix.postTranslate(dx, dy)

                canvas.drawBitmap(originalBitmap, matrix, null)

                // Save
                val fileName = "playlist_cover_$uniqueId.jpg"
                val file = File(context.filesDir, fileName)
                FileOutputStream(file).use { out ->
                    targetBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                // Recycle
                if (originalBitmap != targetBitmap) originalBitmap.recycle()
                // Target bitmap is not recycled here, let GC handle?
                // Or recycle explicitly if immediate memory pressure concern.

                file.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        if (isFolderPlaylistId(playlistId)) return
        viewModelScope.launch {
            playlistPreferencesRepository.deletePlaylist(playlistId)
        }
    }

    fun importM3u(uri: Uri) {
        viewModelScope.launch {
            try {
                val (name, songIds) = m3uManager.parseM3u(uri)
                if (songIds.isNotEmpty()) {
                    playlistPreferencesRepository.createPlaylist(name, songIds)
                }
            } catch (e: Exception) {
                Log.e("PlaylistViewModel", "Error importing M3U", e)
            }
        }
    }

    fun exportM3u(playlist: Playlist, uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            try {
                val songs = musicRepository.getSongsByIds(playlist.songIds).first()
                val m3uContent = m3uManager.generateM3u(playlist, songs)
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write(m3uContent)
                    }
                }
            } catch (e: Exception) {
                Log.e("PlaylistViewModel", "Error exporting M3U", e)
            }
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        if (isFolderPlaylistId(playlistId)) return
        viewModelScope.launch {
            playlistPreferencesRepository.renamePlaylist(playlistId, newName)
            if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
                _uiState.update {
                    it.copy(
                        currentPlaylistDetails = it.currentPlaylistDetails?.copy(
                            name = newName
                        )
                    )
                }
            }
        }
    }

    fun updatePlaylistParameters(
        playlistId: String,
        name: String,
        coverImageUri: String?,
        coverColor: Int?,
        coverIcon: String?,
        cropScale: Float,
        cropPanX: Float,
        cropPanY: Float,
        coverShapeType: String?,
        coverShapeDetail1: Float?,
        coverShapeDetail2: Float?,
        coverShapeDetail3: Float?,
        coverShapeDetail4: Float?
    ) {
        if (isFolderPlaylistId(playlistId)) return
        val currentPlaylist = _uiState.value.currentPlaylistDetails ?: return
        if (currentPlaylist.id != playlistId) return

        viewModelScope.launch {
            var savedCoverPath: String? = currentPlaylist.coverImageUri

            val isNewImage = coverImageUri != null && coverImageUri != currentPlaylist.coverImageUri
            val isAdjusted = cropScale != 1f || cropPanX != 0f || cropPanY != 0f

            if (coverImageUri != null && (isNewImage || isAdjusted)) {
                // Save new image or re-crop existing one
                val imageId = UUID.randomUUID().toString()
                val newPath = saveCoverImageToInternalStorage(
                    Uri.parse(coverImageUri),
                    imageId,
                    cropScale,
                    cropPanX,
                    cropPanY
                )
                if (newPath != null) {
                    // Optional: Delete old file if it was a local file managed by us
                    currentPlaylist.coverImageUri?.let { oldPath ->
                        if (oldPath.contains("playlist_cover_")) {
                            try { File(oldPath).delete() } catch (e: Exception) {}
                        }
                    }
                    savedCoverPath = newPath
                }
            } else if (coverImageUri == null) {
                // Explicitly removed
                currentPlaylist.coverImageUri?.let { oldPath ->
                    if (oldPath.contains("playlist_cover_")) {
                        try { File(oldPath).delete() } catch (e: Exception) {}
                    }
                }
                savedCoverPath = null
            }


            val updatedPlaylist = currentPlaylist.copy(
                name = name,
                coverImageUri = savedCoverPath,
                coverColorArgb = coverColor,
                coverIconName = coverIcon,
                coverShapeType = coverShapeType,
                coverShapeDetail1 = coverShapeDetail1,
                coverShapeDetail2 = coverShapeDetail2,
                coverShapeDetail3 = coverShapeDetail3,
                coverShapeDetail4 = coverShapeDetail4
            )

            // Optimistic update
            _uiState.update {
                it.copy(currentPlaylistDetails = updatedPlaylist)
            }

            playlistPreferencesRepository.updatePlaylist(updatedPlaylist)
        }
    }

    fun addSongsToPlaylist(playlistId: String, songIdsToAdd: List<String>) {
        if (isFolderPlaylistId(playlistId)) return
        viewModelScope.launch {
            playlistPreferencesRepository.addSongsToPlaylist(playlistId, songIdsToAdd)
            if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
                loadPlaylistDetails(playlistId)
            }
        }
    }

    /**
     * @param playlistIds Ids of playlists to add the song to
     * */
    fun addOrRemoveSongFromPlaylists(
        songId: String,
        playlistIds: List<String>,
        currentPlaylistId: String?
    ) {
        viewModelScope.launch {
            val removedFromPlaylists =
                playlistPreferencesRepository.addOrRemoveSongFromPlaylists(songId, playlistIds)
            if (currentPlaylistId != null && removedFromPlaylists.contains (currentPlaylistId)) {
                removeSongFromPlaylist(currentPlaylistId, songId)
            }
        }
    }

    fun addSongsToPlaylists(songIds: List<String>, playlistIds: List<String>) {
        viewModelScope.launch {
            playlistIds.forEach { playlistId ->
                playlistPreferencesRepository.addSongsToPlaylist(playlistId, songIds)
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: String, songIdToRemove: String) {
        if (isFolderPlaylistId(playlistId)) return
        viewModelScope.launch {
            playlistPreferencesRepository.removeSongFromPlaylist(playlistId, songIdToRemove)
            if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
                _uiState.update {
                    it.copy(currentPlaylistSongs = it.currentPlaylistSongs.filterNot { s -> s.id == songIdToRemove })
                }
            }
        }
    }

    fun reorderSongsInPlaylist(playlistId: String, fromIndex: Int, toIndex: Int) {
        if (isFolderPlaylistId(playlistId)) return
        viewModelScope.launch {
            val currentSongs = _uiState.value.currentPlaylistSongs.toMutableList()
            if (fromIndex in currentSongs.indices && toIndex in currentSongs.indices) {
                val item = currentSongs.removeAt(fromIndex)
                currentSongs.add(toIndex, item)
                val newSongOrderIds = currentSongs.map { it.id }
                playlistPreferencesRepository.reorderSongsInPlaylist(playlistId, newSongOrderIds)
                playlistPreferencesRepository.setPlaylistSongOrderMode(
                    playlistId,
                    MANUAL_ORDER_MODE
                )
                _uiState.update {
                    val updatedModes = it.playlistOrderModes + (playlistId to PlaylistSongsOrderMode.Manual)
                    it.copy(
                        currentPlaylistSongs = currentSongs,
                        playlistSongsOrderMode = PlaylistSongsOrderMode.Manual,
                        playlistOrderModes = updatedModes
                    )
                }
            }
        }
    }

    //Sort funs
    fun sortPlaylists(sortOption: SortOption) {
        if (_uiState.value.currentPlaylistSortOption.storageKey == sortOption.storageKey) {
            return
        }

        _uiState.update { it.copy(currentPlaylistSortOption = sortOption) }

        val currentPlaylists = _uiState.value.playlists
        val sortedPlaylists = sortPlaylistsList(currentPlaylists, sortOption)

        _uiState.update { it.copy(playlists = sortedPlaylists) }

        viewModelScope.launch {
            playlistPreferencesRepository.setPlaylistsSortOption(sortOption.storageKey)
        }
    }

    fun setShowTelegramCloudPlaylists(show: Boolean) {
        if (_uiState.value.showTelegramCloudPlaylists == show) return

        _uiState.update { it.copy(showTelegramCloudPlaylists = show) }
        viewModelScope.launch {
            playlistPreferencesRepository.setShowTelegramCloudPlaylists(show)
        }
    }

    fun sortPlaylistSongs(sortOption: SortOption) {
        val playlistId = _uiState.value.currentPlaylistDetails?.id

        // If SongDefaultOrder is selected, reload the playlist to get original order
        if (sortOption == SortOption.SongDefaultOrder) {
            if (playlistId != null) {
                viewModelScope.launch {
                    // Set order mode to Manual (which preserves original order)
                    playlistPreferencesRepository.setPlaylistSongOrderMode(
                        playlistId,
                        MANUAL_ORDER_MODE
                    )
                    // Reload the playlist to get original song order
                    loadPlaylistDetails(playlistId)
                }
            }
            return
        }

        val currentSongs = _uiState.value.currentPlaylistSongs
        val sortedSongs = sortSongsList(currentSongs, sortOption)

        _uiState.update {
            val updatedModes = if (playlistId != null) {
                it.playlistOrderModes + (playlistId to PlaylistSongsOrderMode.Sorted(sortOption))
            } else {
                it.playlistOrderModes
            }
            it.copy(
                currentPlaylistSongs = sortedSongs,
                currentPlaylistSongsSortOption = sortOption,
                playlistSongsOrderMode = PlaylistSongsOrderMode.Sorted(sortOption),
                playlistOrderModes = updatedModes
            )
        }

        if (playlistId != null) {
            viewModelScope.launch {
                playlistPreferencesRepository.setPlaylistSongOrderMode(
                    playlistId,
                    sortOption.storageKey
                )
            }
        }

        // Persist local sort preference if needed (optional, not requested but good UX)
        // For now, we keep it in memory as per request focus.
    }

    private fun isFolderPlaylistId(playlistId: String): Boolean =
        playlistId.startsWith(FOLDER_PLAYLIST_PREFIX)

    private fun findFolder(
        targetPath: String,
        folders: List<com.theveloper.pixelplay.data.model.MusicFolder>
    ): com.theveloper.pixelplay.data.model.MusicFolder? {
        val queue: ArrayDeque<com.theveloper.pixelplay.data.model.MusicFolder> = ArrayDeque(folders)
        while (queue.isNotEmpty()) {
            val folder = queue.removeFirst()
            if (folder.path == targetPath) {
                return folder
            }
            folder.subFolders.forEach { queue.addLast(it) }
        }
        return null
    }

    private fun com.theveloper.pixelplay.data.model.MusicFolder.collectAllSongs(): List<Song> {
        return songs + subFolders.flatMap { it.collectAllSongs() }
    }

    private fun applySortToSongs(songs: List<Song>, sortOption: SortOption): List<Song> {
        return sortSongsList(songs, sortOption)
    }

    private fun sortPlaylistsList(
        playlists: List<com.theveloper.pixelplay.data.model.Playlist>,
        sortOption: SortOption
    ): List<com.theveloper.pixelplay.data.model.Playlist> {
        return when (sortOption) {
            SortOption.PlaylistNameAZ -> playlists.sortedWith(
                compareBy<com.theveloper.pixelplay.data.model.Playlist> { it.name.lowercase() }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.id }
            )
            SortOption.PlaylistNameZA -> playlists.sortedWith(
                compareByDescending<com.theveloper.pixelplay.data.model.Playlist> { it.name.lowercase() }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.id }
            )
            SortOption.PlaylistDateCreated -> playlists.sortedWith(
                compareByDescending<com.theveloper.pixelplay.data.model.Playlist> { it.lastModified }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.PlaylistDateCreatedAsc -> playlists.sortedWith(
                compareBy<com.theveloper.pixelplay.data.model.Playlist> { it.lastModified }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id }
            )
            else -> playlists.sortedWith(
                compareBy<com.theveloper.pixelplay.data.model.Playlist> { it.name.lowercase() }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.id }
            )
        }
    }

    private fun sortSongsList(
        songs: List<Song>,
        sortOption: SortOption
    ): List<Song> {
        return when (sortOption) {
            SortOption.SongTitleAZ -> songs.sortedWith(
                compareBy<Song> { it.title.lowercase() }
                    .thenBy { it.artist.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongTitleZA -> songs.sortedWith(
                compareByDescending<Song> { it.title.lowercase() }
                    .thenBy { it.artist.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongArtist -> songs.sortedWith(
                compareBy<Song> { it.artist.lowercase() }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongArtistDesc -> songs.sortedWith(
                compareByDescending<Song> { it.artist.lowercase() }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongAlbum -> songs.sortedWith(
                compareBy<Song> { it.album.lowercase() }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongAlbumDesc -> songs.sortedWith(
                compareByDescending<Song> { it.album.lowercase() }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongDuration -> songs.sortedWith(
                compareByDescending<Song> { it.duration }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongDurationAsc -> songs.sortedWith(
                compareBy<Song> { it.duration }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongDateAdded -> songs.sortedWith(
                compareByDescending<Song> { it.dateAdded }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongDateAddedAsc -> songs.sortedWith(
                compareBy<Song> { it.dateAdded }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            else -> songs
        }
    }

    private fun decodeOrderMode(value: String): PlaylistSongsOrderMode {
        return if (value == MANUAL_ORDER_MODE) {
            PlaylistSongsOrderMode.Manual
        } else {
            val option = SortOption.fromStorageKey(value, SortOption.SONGS, SortOption.SongTitleAZ)
            PlaylistSongsOrderMode.Sorted(option)
        }
    }

    fun generateAiPlaylist(prompt: String, minLength: Int = 10, maxLength: Int = 50) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAiGenerating = true, aiGenerationError = null) }

            try {
                val allSongs = withContext(Dispatchers.IO) {
                    musicRepository.getAllSongsOnce()
                }

                // Call AiPlaylistGenerator
                val result = aiPlaylistGenerator.generate(
                    userPrompt = prompt,
                    allSongs = allSongs,
                    minLength = minLength,
                    maxLength = maxLength
                )

                result.onSuccess { selectedSongs ->
                    // Create Playlist
                    val playlistName = "AI: $prompt".take(50)

                    playlistPreferencesRepository.createPlaylist(
                        name = playlistName,
                        songIds = selectedSongs.map { it.id },
                        isAiGenerated = true,
                        source = "AI" // Mark as AI source
                    )

                    _uiState.update { it.copy(isAiGenerating = false) }
                    _playlistCreationEvent.emit(true)
                }.onFailure { e ->
                    val errorMessage = if (e.message?.contains("API Key") == true) {
                        context.getString(R.string.playlist_view_model_ai_gemini_key_required)
                    } else {
                        e.message ?: context.getString(R.string.common_error_unknown)
                    }
                    _uiState.update { it.copy(isAiGenerating = false, aiGenerationError = errorMessage) }
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(isAiGenerating = false, aiGenerationError = e.message) }
            }
        }
    }

    fun clearAiError() {
        _uiState.update { it.copy(aiGenerationError = null) }
    }

    /**
     * Delete multiple playlists in batch
     */
    fun deletePlaylistsInBatch(playlistIds: List<String>) {
        viewModelScope.launch {
            playlistIds.forEach { playlistId ->
                if (!isFolderPlaylistId(playlistId)) {
                    playlistPreferencesRepository.deletePlaylist(playlistId)
                }
            }
        }
    }

    /**
     * Merge selected playlists into a new playlist
     * Collects all songs from all selected playlists (removing duplicates)
     */
    fun mergeSelectedPlaylists(playlistIds: List<String>, newPlaylistName: String) {
        if (newPlaylistName.isBlank()) return

        viewModelScope.launch {
            try {
                // Get all songs from selected playlists
                val selectedPlaylists = _uiState.value.playlists.filter { it.id in playlistIds }
                val mergedSongIds = selectedPlaylists
                    .flatMap { it.songIds }
                    .distinct() // Remove duplicates
                    .toList()

                if (mergedSongIds.isNotEmpty()) {
                    // Create new playlist with merged songs
                    playlistPreferencesRepository.createPlaylist(newPlaylistName, mergedSongIds)
                    _playlistCreationEvent.emit(true)
                }
            } catch (e: Exception) {
                Log.e("PlaylistViewModel", "Error merging playlists", e)
            }
        }
    }

    /**
     * Get all playlists with their song data for bulk operations
     */
    suspend fun getPlaylistsWithSongs(playlistIds: List<String>): List<Pair<Playlist, List<Song>>> {
        return try {
            val selectedPlaylists = _uiState.value.playlists.filter { it.id in playlistIds }
            selectedPlaylists.map { playlist ->
                val songs = musicRepository.getSongsByIds(playlist.songIds).first()
                playlist to songs
            }
        } catch (e: Exception) {
            Log.e("PlaylistViewModel", "Error getting playlists with songs", e)
            emptyList()
        }
    }

    /**
     * Share all selected playlists as M3U files in a ZIP
     */
    fun shareSelectedPlaylistsAsZip(playlistIds: List<String>, activity: android.app.Activity?) {
        if (activity == null) {
            Log.w("PlaylistViewModel", "Activity is null, cannot share")
            return
        }

        viewModelScope.launch {
            try {
                Log.d("PlaylistViewModel", "Starting share of ${playlistIds.size} playlists")
                // Get all selected playlists with their songs
                val playlistsWithSongs = getPlaylistsWithSongs(playlistIds)

                if (playlistsWithSongs.isEmpty()) {
                    Log.w("PlaylistViewModel", "No playlists found to share")
                    Toast.makeText(context, context.getString(R.string.playlist_view_model_none_to_share), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val shareFile: File
                val shareFileName: String
                val shareMimeType: String

                if (playlistsWithSongs.size == 1) {
                    // Single playlist: share M3U file directly
                    val (playlist, songs) = playlistsWithSongs.first()
                    val m3uContent = m3uManager.generateM3u(playlist, songs)
                    val sanitizedName = sanitizeFileName(playlist.name)
                    shareFileName = "$sanitizedName.m3u"
                    shareFile = File(context.cacheDir, shareFileName)
                    shareFile.writeText(m3uContent)
                    shareMimeType = "audio/mpegurl"
                    Log.d("PlaylistViewModel", "Created M3U file: ${shareFile.absolutePath}, size: ${shareFile.length()} bytes")
                } else {
                    // Multiple playlists: create ZIP file
                    val firstPlaylistName = sanitizeFileName(playlistsWithSongs.first().first.name)
                    val zipFileName = "Playlists_${firstPlaylistName}_and_${playlistsWithSongs.size - 1}_more.zip"
                    shareFile = File(context.cacheDir, zipFileName)
                    val outputStream = FileOutputStream(shareFile)

                    java.util.zip.ZipOutputStream(outputStream).use { zipOut ->
                        val usedNames = mutableSetOf<String>()
                        playlistsWithSongs.forEach { (playlist, songs) ->
                            val m3uContent = m3uManager.generateM3u(playlist, songs)
                            val baseName = sanitizeFileName(playlist.name)
                            var entryName = "$baseName.m3u"
                            var counter = 1
                            while (usedNames.contains(entryName)) {
                                entryName = "${baseName}_$counter.m3u"
                                counter++
                            }
                            usedNames.add(entryName)

                            val entry = java.util.zip.ZipEntry(entryName)
                            zipOut.putNextEntry(entry)
                            zipOut.write(m3uContent.toByteArray())
                            zipOut.closeEntry()
                        }
                    }

                    shareFileName = zipFileName
                    shareMimeType = "application/zip"
                    Log.d("PlaylistViewModel", "Created ZIP file: ${shareFile.absolutePath}, size: ${shareFile.length()} bytes")
                }

                // Share the file
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    shareFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = shareMimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                Log.d("PlaylistViewModel", "Launching share intent for: $shareFileName")
                activity.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.playlist_view_model_share_chooser_title)))
                val n = playlistsWithSongs.size
                val sharingMsg = context.resources.getQuantityString(R.plurals.playlist_view_model_sharing_message, n, n)
                Toast.makeText(context, sharingMsg, Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e("PlaylistViewModel", "Error sharing playlists", e)
                Toast.makeText(context, context.getString(R.string.playlist_view_model_share_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Merge multiple playlists into one new playlist
     * @param playlistIds List of playlist IDs to merge
     * @param newPlaylistName Name for the merged playlist
     */
    fun mergePlaylistsIntoOne(playlistIds: List<String>, newPlaylistName: String) {
        if (playlistIds.isEmpty() || newPlaylistName.isEmpty()) return

        viewModelScope.launch {
            try {
                // Get all playlists first
                val currentPlaylists = _uiState.value.playlists

                // Get all songs from selected playlists
                val allSongs = mutableSetOf<String>()
                playlistIds.forEach { playlistId ->
                    val playlist = currentPlaylists.find { it.id == playlistId }
                    if (playlist != null) {
                        allSongs.addAll(playlist.songIds)
                    }
                }

                // Create new playlist with merged songs
                val newPlaylist = Playlist(
                    id = UUID.randomUUID().toString(),
                    name = newPlaylistName,
                    songIds = allSongs.toList(),
                    createdAt = System.currentTimeMillis(),
                    lastModified = System.currentTimeMillis(),
                    isAiGenerated = false,
                    isQueueGenerated = false
                )

                playlistPreferencesRepository.createPlaylist(
                    name = newPlaylistName,
                    songIds = allSongs.toList(),
                    isAiGenerated = false,
                    isQueueGenerated = false
                )

                Log.d("PlaylistViewModel", "Successfully merged ${playlistIds.size} playlists into '$newPlaylistName' with ${allSongs.size} total unique songs")

            } catch (e: Exception) {
                Log.e("PlaylistViewModel", "Error merging playlists", e)
            }
        }
    }

    /**
     * Export selected playlists as M3U files to device storage
     */
    fun exportPlaylistsAsM3u(playlistIds: List<String>) {
        if (playlistIds.isEmpty()) return

        viewModelScope.launch {
            try {
                Log.d("PlaylistViewModel", "Starting export of ${playlistIds.size} playlists")
                val musicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                if (!musicDir.exists()) {
                    musicDir.mkdirs()
                }

                val exportDir = File(musicDir, "PixelPlayer Exports")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }

                val playlistsWithSongs = getPlaylistsWithSongs(playlistIds)
                if (playlistsWithSongs.isEmpty()) {
                    Log.w("PlaylistViewModel", "No playlists found to export")
                    Toast.makeText(context, context.getString(R.string.playlist_view_model_none_to_export), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                playlistsWithSongs.forEach { (playlist, songs) ->
                    val m3uContent = m3uManager.generateM3u(playlist, songs)
                    val baseName = sanitizeFileName(playlist.name)
                    var file = File(exportDir, "$baseName.m3u")
                    var counter = 1
                    while (file.exists()) {
                        file = File(exportDir, "${baseName}_$counter.m3u")
                        counter++
                    }
                    file.writeText(m3uContent)
                    Log.d("PlaylistViewModel", "Exported playlist '${playlist.name}' to ${file.absolutePath}")
                }

                Log.d("PlaylistViewModel", "Successfully exported ${playlistIds.size} playlists to $exportDir")
                val count = playlistsWithSongs.size
                val folderLabel = context.getString(R.string.playlist_view_model_export_folder_display)
                val exportedMsg = context.resources.getQuantityString(R.plurals.playlist_view_model_exported_message, count, count, folderLabel)
                Toast.makeText(context, exportedMsg, Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e("PlaylistViewModel", "Error exporting playlists", e)
                Toast.makeText(context, context.getString(R.string.playlist_view_model_export_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Watch transfer ---

    /**
     * Refreshes reachable-watch capability + free storage. Call before showing the send-to-watch
     * confirmation sheet so its estimate and availability state are current.
     */
    fun refreshWatchAvailability() {
        if (_isRefreshingWatchAvailability.value) return

        viewModelScope.launch {
            _isRefreshingWatchAvailability.value = true
            val available = wearPhoneTransferSender.isPixelPlayWatchAvailable()
            _isPixelPlayWatchAvailable.value = available
            _isRefreshingWatchAvailability.value = false
            if (available) {
                wearPhoneTransferSender.refreshWatchLibraryState()
            }
        }
        viewModelScope.launch {
            wearPhoneTransferSender.refreshWatchPairingState()
        }
    }

    fun isPlaylistFullyOnWatch(songIds: List<String>): Boolean {
        return songIds.isNotEmpty() && songIds.all { watchTransferStateStore.isSongSavedOnAllReachableWatches(it) }
    }

    /** Size/time estimate over only the songs from [songs] that aren't already on every reachable watch. */
    fun estimateWatchTransfer(songs: List<Song>): WatchPlaylistTransferEstimate {
        val pendingSongs = songs.filterNot { watchTransferStateStore.isSongSavedOnAllReachableWatches(it.id) }
        return WatchPlaylistTransferEstimator.estimate(songs, pendingSongs, watchAudioTranscoder)
    }

    /** Returns the generated batchId immediately; the transfer itself runs asynchronously. */
    fun sendPlaylistToWatch(playlistId: String, playlistName: String, songIds: List<String>): String {
        return playlistWatchTransferCoordinator.requestPlaylistTransfer(playlistId, playlistName, songIds)
    }

    fun cancelPlaylistTransfer(batchId: String) {
        playlistWatchTransferCoordinator.cancelPlaylistTransfer(batchId)
    }
}
