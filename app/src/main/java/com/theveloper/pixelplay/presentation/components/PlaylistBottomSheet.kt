package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.subcomps.LibraryActionRow
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistUiState
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaylistBottomSheet(
    playlistUiState: PlaylistUiState,
    songs: List<Song>,
    onDismiss: () -> Unit,
    bottomBarHeight: Dp,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    currentPlaylistId: String? = null
) {
    val playlistCreatedAndSongsAddedMessage =
        stringResource(R.string.playlist_sheet_created_and_songs_added)
    val setAiProviderApiKeyFirstMessage =
        stringResource(R.string.library_toast_set_ai_provider_api_key_first)
    val songAddedToPlaylistsMessage =
        stringResource(R.string.playlist_sheet_song_added_to_playlists)
    val commonSavedMessage = stringResource(R.string.common_saved)
    val saveActionText = stringResource(R.string.common_save)
    val internalStorageText = stringResource(R.string.playlist_sheet_internal_storage)

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true }
    )

    var searchQuery by remember { mutableStateOf("") }

    val filteredPlaylists = remember(searchQuery, playlistUiState.playlists) {
        if (searchQuery.isBlank()) playlistUiState.playlists
        else playlistUiState.playlists.filter { it.name.contains(searchQuery, true) }
    }

    val hasActiveAiProviderApiKey by playerViewModel.hasActiveAiProviderApiKey.collectAsStateWithLifecycle()

    // Capture the initial membership snapshot once when the sheet opens.
    // For a single song: true = song is already in that playlist.
    // For multiple songs: always false (additive-only mode).
    val initialSelection = remember {
        if (songs.size == 1) {
            val songId = songs.first().id
            filteredPlaylists.associate { it.id to it.songIds.contains(songId) }
        } else {
            filteredPlaylists.associate { it.id to false }
        }
    }

    val selectedPlaylists = remember {
        mutableStateMapOf<String, Boolean>().apply {
            putAll(initialSelection)
        }
    }

    // Save is enabled whenever the current selection differs from the initial state,
    // including the case where all playlists are deselected (remove from all).
    val hasSelectionChanged = selectedPlaylists.any { (id, checked) ->
        initialSelection[id] != checked
    }

    val alpha by animateFloatAsState(
        targetValue = if (hasSelectionChanged) 1f else 0.4f,
        label = "fab_alpha"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { BottomSheetDefaults.modalWindowInsets } // Manejo de insets como el teclado
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (songs.size > 1) {
                            stringResource(R.string.playlist_sheet_add_songs_title, songs.size)
                        } else {
                            stringResource(R.string.playlist_sheet_select_playlists)
                        },
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = GoogleSansRounded
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        unfocusedTrailingIconColor = Color.Transparent,
                        focusedSupportingTextColor = Color.Transparent,
                    ),
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.playlist_sheet_search_playlists_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = CircleShape,
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, null)
                        }
                    }
                )

                LibraryActionRow(
                    modifier = Modifier.padding(
                        top = 10.dp,
                        start = 10.dp,
                        end = 10.dp
                    ),
                    onMainActionClick = { showCreatePlaylistDialog = true },
                    iconRotation = 0f,
                    showSortButton = false,
                    showImportButton = false,
                    onSortClick = { },
                    isPlaylistTab = true,
                    isFoldersTab = false,
                    currentFolder = null,
                    folderRootPath = "",
                    folderRootLabel = internalStorageText,
                    onFolderClick = { },
                    onNavigateBack = { }
                )

                Spacer(modifier = Modifier.height(8.dp))

                PlaylistContainer(
                    playlistUiState = playlistUiState,
                    isRefreshing = false,
                    onRefresh = { },
                    bottomBarHeight = bottomBarHeight,
                    navController = null,
                    playerViewModel = playerViewModel,
                    isAddingToPlaylist = true,
                    currentSong = songs.firstOrNull() ?: Song.emptySong(),
                    filteredPlaylists = filteredPlaylists,
                    selectedPlaylists = selectedPlaylists
                )

                if (showCreatePlaylistDialog) {
                    CreatePlaylistDialogRedesigned(
                        onDismiss = { showCreatePlaylistDialog = false },
                        onCreate = { name ->
                            playlistViewModel.createPlaylist(name, songIds = songs.map { it.id })
                            showCreatePlaylistDialog = false
                            onDismiss()
                            playerViewModel.sendToast(playlistCreatedAndSongsAddedMessage)
                        },
                        onGenerateClick = {
                            showCreatePlaylistDialog = false
                            if (hasActiveAiProviderApiKey) {
                                playerViewModel.showAiPlaylistSheet()
                            } else {
                                playerViewModel.sendToast(setAiProviderApiKeyFirstMessage)
                            }
                        }
                    )
                }
            }

            MediumExtendedFloatingActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 18.dp, end = 8.dp)
                    .graphicsLayer { this.alpha = alpha },
                shape = CircleShape,
                onClick = {
                    if (!hasSelectionChanged) return@MediumExtendedFloatingActionButton
                    if (songs.size == 1) {
                        playlistViewModel.addOrRemoveSongFromPlaylists(
                            songs.first().id,
                            selectedPlaylists.filter { it.value }.keys.toList(),
                            currentPlaylistId
                        )
                    } else {
                        // Batch add: multi-song mode is additive only
                        val selectedPlaylistIds =
                            selectedPlaylists.filter { it.value }.keys.toList()
                        if (selectedPlaylistIds.isNotEmpty()) {
                            playlistViewModel.addSongsToPlaylists(
                                songs.map { it.id },
                                selectedPlaylistIds
                            )
                        }
                    }
                    onDismiss()
                    playerViewModel.sendToast(
                        if (songs.size > 1) songAddedToPlaylistsMessage else commonSavedMessage
                    )
                    playerViewModel.multiSelectionStateHolder.clearSelection()
                },
                icon = { Icon(Icons.Rounded.Save, saveActionText) },
                text = { Text(if (songs.size > 1) stringResource(R.string.common_add) else saveActionText) },
            )
        }
    }
}
