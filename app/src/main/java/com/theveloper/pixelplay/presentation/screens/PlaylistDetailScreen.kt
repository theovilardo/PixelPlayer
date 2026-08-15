package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil.size.Size
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.QueuePlaylistSongItem
import com.theveloper.pixelplay.presentation.components.SongPickerBottomSheet
import com.theveloper.pixelplay.presentation.components.ExpressiveScrollBar
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.components.subcomps.TightWrapText
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel.Companion.FOLDER_PLAYLIST_PREFIX
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppCompatHapticFeedback
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistSongsOrderMode
import com.theveloper.pixelplay.utils.formatSongCount
import com.theveloper.pixelplay.utils.formatTotalDuration
import com.theveloper.pixelplay.utils.formatListeningDurationCompact
import com.theveloper.pixelplay.data.service.wear.PhoneWatchBatchTransferState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.theveloper.pixelplay.presentation.components.LibrarySortBottomSheet
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.PlaylistShapeType
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBackClick: () -> Unit,
    onDeletePlayListClick: () -> Unit,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    navController: NavController
) {
    val uiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val playerStableState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fallbackPlaylistName = stringResource(R.string.common_playlist)
    val sortSongsLabel = stringResource(R.string.playlist_sort_songs_title)
    val moreOptionsLabel = stringResource(R.string.playlist_more_options_title)
    val playItLabel = stringResource(R.string.playlist_action_play_it)
    val shuffleLabel = stringResource(R.string.common_shuffle)
    val addSongsCd = stringResource(R.string.playlist_cd_add_songs)
    val addLabel = stringResource(R.string.playlist_action_add_songs)
    val removeLabel = stringResource(R.string.playlist_action_remove_songs)
    val removeSongsCd = stringResource(R.string.playlist_cd_remove_songs)
    val reorderLabel = stringResource(R.string.playlist_action_reorder_songs)
    val reorderSongsCd = stringResource(R.string.playlist_cd_reorder_songs)
    val reorderSongCd = stringResource(R.string.playlist_cd_reorder_songs)
    val playlistEmptyTitle = stringResource(R.string.playlist_empty_title)
    val playlistEmptyFolder = stringResource(R.string.playlist_empty_folder_label)
    val playlistEmptyAddHint = stringResource(R.string.playlist_empty_add_hint)
    val playlistOptionsTitle = stringResource(R.string.playlist_options_title)
    val editPlaylistLabel = stringResource(R.string.playlist_action_edit_playlist)
    val deletePlaylistLabel = stringResource(R.string.playlist_action_delete_playlist)
    val setDefaultTransitionLabel = stringResource(R.string.playlist_action_set_default_transition)
    val exportPlaylistLabel = stringResource(R.string.playlist_action_export_playlist)
    val sendToWatchLabel = stringResource(R.string.playlist_action_send_to_watch)
    val updateOnWatchLabel = stringResource(R.string.playlist_action_update_on_watch)
    val sendToWatchCd = stringResource(R.string.playlist_cd_send_to_watch)
    val deletePlaylistConfirmTitle = stringResource(R.string.playlist_dialog_delete_title)
    val deletePlaylistConfirmBody = stringResource(R.string.playlist_dialog_delete_body)
    val sortSheetTitle = stringResource(R.string.playlist_sort_songs_title)
    val toastAddedToQueue = stringResource(R.string.library_toast_added_to_queue)
    val toastPlayingNext = stringResource(R.string.library_toast_playing_next)
    val currentPlaylist = uiState.currentPlaylistDetails
    val isFolderPlaylist = currentPlaylist?.id?.startsWith(FOLDER_PLAYLIST_PREFIX) == true
    val songsInPlaylist = uiState.currentPlaylistSongs

    LaunchedEffect(playlistId) {
        playlistViewModel.loadPlaylistDetails(playlistId)
    }

    // So "Send to Watch" in the options sheet below can be gated on isAnyWatchPaired before the
    // user ever opens that sheet, not only refreshed reactively once they tap it.
    LaunchedEffect(Unit) {
        playlistViewModel.refreshWatchAvailability()
    }

    var showAddSongsSheet by remember { mutableStateOf(false) }

    var isReorderModeEnabled by remember { mutableStateOf(false) }
    var isRemoveModeEnabled by remember { mutableStateOf(false) }
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showPlaylistOptionsSheet by remember { mutableStateOf(false) }
    var showEditPlaylistDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showSendToWatchDialog by remember { mutableStateOf(false) }

    val m3uExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        uri?.let {
            currentPlaylist?.let { playlist ->
                playlistViewModel.exportM3u(playlist, it, context)
            }
        }
    }

    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle() // Reintroducir favoriteIds aquí
    val isPixelPlayWatchAvailable by playlistViewModel.isPixelPlayWatchAvailable.collectAsStateWithLifecycle()
    val isAnyWatchPaired by playlistViewModel.isAnyWatchPaired.collectAsStateWithLifecycle()
    val watchSongIds by playlistViewModel.watchSongIds.collectAsStateWithLifecycle()
    val watchPlaylistIds by playlistViewModel.watchPlaylistIds.collectAsStateWithLifecycle()
    val activeBatchTransfer by playlistViewModel.activePlaylistBatchTransfer.collectAsStateWithLifecycle()
    val activePlaylistTransfer = activeBatchTransfer?.takeIf { it.playlistId == playlistId }
    // Mutating the playlist while it's mid-transfer to the watch could desync what's actually
    // being sent from what the user sees on screen — reorder/add/remove are disabled (not
    // removed, to avoid a layout jump) for the duration.
    val isTransferActive = activePlaylistTransfer != null
    // Unlike isTransferActive above (this playlist only), this covers ANY playlist currently
    // transferring — there's no real transfer queue yet (multiple batches would race over the
    // watch's single Bluetooth link), so starting a second one is blocked entirely rather than
    // silently running concurrently.
    val isAnyBatchTransferActive = activeBatchTransfer != null
    LaunchedEffect(isTransferActive) {
        if (isTransferActive) {
            isReorderModeEnabled = false
            isRemoveModeEnabled = false
        }
    }
    // "Update" vs "send" is about this playlist having been synced to the watch before, which is
    // not the same as some of its songs happening to be there: two playlists sharing one song
    // would otherwise both claim to be already on the watch, and a watch whose app was
    // reinstalled (library wiped) would still offer "update" for a playlist it no longer has.
    val isPlaylistOnWatch = remember(playlistId, watchPlaylistIds) {
        playlistId in watchPlaylistIds
    }
    val stableOnMoreOptionsClick: (Song) -> Unit = remember {
        { song ->
            playerViewModel.selectSongForInfo(song)
            showSongInfoBottomSheet = true
        }
    }
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var localReorderableSongs by remember(songsInPlaylist) { mutableStateOf(songsInPlaylist) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current
    var lastMovedFrom by remember { mutableStateOf<Int?>(null) }
    var lastMovedTo by remember { mutableStateOf<Int?>(null) }

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            localReorderableSongs = localReorderableSongs.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            if (lastMovedFrom == null) {
                lastMovedFrom = from.index
            }
            lastMovedTo = to.index
        }
    )

    LaunchedEffect(reorderableState.isAnyItemDragging, isFolderPlaylist) {
        if (!isFolderPlaylist && !reorderableState.isAnyItemDragging && lastMovedFrom != null && lastMovedTo != null) {
            currentPlaylist?.let {
                playlistViewModel.reorderSongsInPlaylist(it.id, lastMovedFrom!!, lastMovedTo!!)
            }
            lastMovedFrom = null
            lastMovedTo = null
        } else if (isFolderPlaylist && !reorderableState.isAnyItemDragging) {
            lastMovedFrom = null
            lastMovedTo = null
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = currentPlaylist?.name ?: fallbackPlaylistName,
                        fontFamily = GoogleSansRounded,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    scrolledContainerColor = Color.Transparent,
                    containerColor = Color.Transparent
                ),
                subtitle = {
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(
                            R.string.playlist_song_duration_line,
                            formatSongCount(songsInPlaylist.size),
                            formatTotalDuration(songsInPlaylist)
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = GoogleSansRounded),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                navigationIcon = {
                    FilledTonalIconButton(
                        modifier = Modifier.padding(start = 10.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = onBackClick
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            playerViewModel.showSortingSheet() 
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = sortSongsLabel
                        )
                    }
                    if (!isFolderPlaylist) {
                        FilledTonalIconButton(
                            modifier = Modifier.padding(end = 10.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            onClick = { showPlaylistOptionsSheet = true }
                        ) { Icon(Icons.Filled.MoreVert, moreOptionsLabel) }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading && currentPlaylist == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()), Alignment.Center
            ) { CircularProgressIndicator() }
        } else if (uiState.playlistNotFound) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()), Alignment.Center
            ) { Text(stringResource(id = R.string.playlist_not_found)) }
        } else if (currentPlaylist == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()), Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                activePlaylistTransfer?.let { batch ->
                    WatchTransferProgressBanner(
                        batch = batch,
                        onCancelClick = { playlistViewModel.cancelPlaylistTransfer(batch.batchId) },
                    )
                }
                val actionButtonsHeight = 42.dp
                // Single gap value between every stacked section below (playback row, action
                // row, search field, song list card) — previously each used its own ad-hoc
                // value (6dp/8dp/2dp/12dp), which read as visually inconsistent spacing.
                val sectionSpacing = 12.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = sectionSpacing),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (localReorderableSongs.isNotEmpty()) {
                                playerViewModel.playSongs(
                                    localReorderableSongs,
                                    localReorderableSongs.first(),
                                    currentPlaylist.name
                                )
                                if (playerStableState.isShuffleEnabled) playerViewModel.toggleShuffle()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp),
                        enabled = localReorderableSongs.isNotEmpty(),
                        shape = AbsoluteSmoothCornerShape(
                            cornerRadiusTL = 60.dp,
                            smoothnessAsPercentTR = 60,
                            cornerRadiusTR = 14.dp,
                            smoothnessAsPercentTL = 60,
                            cornerRadiusBL = 60.dp,
                            smoothnessAsPercentBR = 60,
                            cornerRadiusBR = 14.dp,
                            smoothnessAsPercentBL = 60
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.common_play),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        TightWrapText(
                                text = playItLabel,
                                modifier = Modifier.padding(end = 4.dp),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                                lineHeight = 20.sp
                            )
                    }
                    FilledTonalButton(
                        onClick = {
                            if (localReorderableSongs.isNotEmpty()) {
                                playerViewModel.playSongsShuffled(
                                    songsToPlay = localReorderableSongs,
                                    queueName = currentPlaylist.name,
                                    playlistId = currentPlaylist.id,
                                    startAtZero = true,
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp),
                        enabled = localReorderableSongs.isNotEmpty(),
                        shape = AbsoluteSmoothCornerShape(
                            cornerRadiusTL = 14.dp,
                            smoothnessAsPercentTR = 60,
                            cornerRadiusTR = 60.dp,
                            smoothnessAsPercentTL = 60,
                            cornerRadiusBL = 14.dp,
                            smoothnessAsPercentBR = 60,
                            cornerRadiusBR = 60.dp,
                            smoothnessAsPercentBL = 60
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            contentDescription = shuffleLabel,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        TightWrapText(
                                text = shuffleLabel,
                                modifier = Modifier.padding(end = 4.dp),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                                lineHeight = 20.sp
                            )
                    }
                }

                if (!isFolderPlaylist) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = sectionSpacing),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val reorderCornerRadius by animateDpAsState(
                            targetValue = if (isReorderModeEnabled) 24.dp else 12.dp,
                            label = "reorderCornerRadius"
                        )
                        val reorderButtonColor by animateColorAsState(
                            targetValue = if (isReorderModeEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            label = "reorderButtonColor"
                        )
                        val reorderIconColor by animateColorAsState(
                            targetValue = if (isReorderModeEnabled) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                            label = "reorderIconColor"
                        )

                        val removeCornerRadius by animateDpAsState(
                            targetValue = if (isRemoveModeEnabled) 24.dp else 12.dp,
                            label = "removeCornerRadius"
                        )
                        val removeButtonColor by animateColorAsState(
                            targetValue = if (isRemoveModeEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            label = "removeButtonColor"
                        )
                        val removeIconColor by animateColorAsState(
                            targetValue = if (isRemoveModeEnabled) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                            label = "removeIconColor"
                        )

                        Button(
                            onClick = { showAddSongsSheet = true },
                            enabled = !isTransferActive,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ),
                            modifier = Modifier
                                .height(actionButtonsHeight)
                                .animateContentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = addSongsCd,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                modifier = Modifier.padding(end = 4.dp),
                                text = addLabel,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        val scrollState = rememberScrollState()
                        val showStartFade by remember { derivedStateOf { scrollState.value > 0 } }
                        val showEndFade by remember { derivedStateOf { scrollState.value < scrollState.maxValue } }

                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    val gradientWidth = 8.dp.toPx()

                                    if (showStartFade) {
                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(Color.Transparent, Color.Black),
                                                endX = gradientWidth
                                            ),
                                            blendMode = BlendMode.DstIn
                                        )
                                    }

                                    if (showEndFade) {
                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(Color.Black, Color.Transparent),
                                                startX = this.size.width - gradientWidth
                                            ),
                                            blendMode = BlendMode.DstIn
                                        )
                                    }
                                }
                        ) {
                            val containerWidthPx = constraints.maxWidth

                            Layout(
                                modifier = Modifier.horizontalScroll(scrollState),
                                content = {
                                    Button(
                                        onClick = { isRemoveModeEnabled = !isRemoveModeEnabled },
                                        enabled = !isTransferActive,
                                        shape = RoundedCornerShape(removeCornerRadius),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = removeButtonColor,
                                            contentColor = removeIconColor
                                        ),
                                        modifier = Modifier
                                            .height(actionButtonsHeight)
                                            .animateContentSize()
                                            .clip(RoundedCornerShape(removeCornerRadius))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RemoveCircleOutline,
                                            contentDescription = removeSongsCd,
                                            modifier = Modifier.size(16.dp),
                                            tint = removeIconColor
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            modifier = Modifier.padding(end = 4.dp),
                                            text = removeLabel,
                                            color = removeIconColor,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }

                                    Button(
                                        onClick = { isReorderModeEnabled = !isReorderModeEnabled },
                                        enabled = !isTransferActive,
                                        shape = RoundedCornerShape(reorderCornerRadius),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = reorderButtonColor,
                                            contentColor = reorderIconColor
                                        ),
                                        modifier = Modifier
                                            .height(actionButtonsHeight)
                                            .animateContentSize()
                                            .clip(RoundedCornerShape(reorderCornerRadius))
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.drag_order_icon),
                                            contentDescription = reorderSongsCd,
                                            modifier = Modifier.size(22.dp),
                                            tint = reorderIconColor
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            modifier = Modifier.padding(end = 4.dp),
                                            text = reorderLabel,
                                            color = reorderIconColor,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            ) { measurables, childConstraints ->
                                val spacingPx = 8.dp.roundToPx()
                                val totalSpacing = spacingPx * (measurables.size - 1)

                                // Query maxIntrinsicWidth of each child without measuring
                                val totalNaturalWidth = measurables.sumOf { measurable ->
                                    measurable.maxIntrinsicWidth(Constraints.Infinity)
                                } + totalSpacing

                                val finalPlaceables = if (totalNaturalWidth <= containerWidthPx && containerWidthPx > 0) {
                                    // Stretch them equally to fill containerWidthPx
                                    val availableSpace = containerWidthPx - totalSpacing
                                    val equalWidth = (availableSpace / measurables.size).coerceAtLeast(0)
                                    measurables.map { measurable ->
                                        measurable.measure(
                                            childConstraints.copy(
                                                minWidth = equalWidth,
                                                maxWidth = equalWidth
                                            )
                                        )
                                    }
                                } else {
                                    // Measure naturally
                                    measurables.map { measurable ->
                                        measurable.measure(
                                            childConstraints.copy(
                                                minWidth = 0,
                                                maxWidth = Constraints.Infinity
                                            )
                                        )
                                    }
                                }

                                val layoutWidth = if (totalNaturalWidth <= containerWidthPx && containerWidthPx > 0) {
                                    containerWidthPx
                                } else {
                                    totalNaturalWidth
                                }

                                val height = finalPlaceables.maxOfOrNull { it.height } ?: 0

                                layout(layoutWidth, height) {
                                    var xPosition = 0
                                    finalPlaceables.forEach { placeable ->
                                        placeable.placeRelative(x = xPosition, y = 0)
                                        xPosition += placeable.width + spacingPx
                                    }
                                }
                            }
                        }
                    }
                }

                if (localReorderableSongs.isEmpty()) {
                    Box(Modifier
                        .fillMaxSize()
                        .weight(1f), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.MusicOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(playlistEmptyTitle, style = MaterialTheme.typography.titleMedium)
                            val emptyMessage = if (isFolderPlaylist) {
                                playlistEmptyFolder
                            } else {
                                playlistEmptyAddHint
                            }
                            Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            //.padding(horizontal = 10.dp)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(
                                    AbsoluteSmoothCornerShape(
                                        cornerRadiusTR = 32.dp,
                                        smoothnessAsPercentTR = 60,
                                        cornerRadiusTL = 32.dp,
                                        smoothnessAsPercentTL = 60,
                                    )
                                )
                                .background(color = MaterialTheme.colorScheme.surfaceContainerHigh),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(
                                top = 12.dp,
                                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                                end = 0.dp
                            ).let {
                                val showScrollBar = LocalShowScrollbar.current && (listState.canScrollForward || listState.canScrollBackward)
                                PaddingValues(
                                    top = it.calculateTopPadding(),
                                    bottom = it.calculateBottomPadding(),
                                    start = it.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                                    end = if (showScrollBar) 24.dp else 0.dp
                                )
                            }
                        ) {
                            itemsIndexed(
                                localReorderableSongs,
                                key = { _, item -> item.id },
                                contentType = { _, _ -> "playlist_song" }) { _, song ->
                                ReorderableItem(
                                    state = reorderableState,
                                    key = song.id,
                                ) { isDragging ->
                                    val scale by animateFloatAsState(
                                        if (isDragging) 1.05f else 1f,
                                        label = "scale"
                                    )

                                    QueuePlaylistSongItem(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 0.dp)
                                            .graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                            },
                                        onClick = {
                                            playerViewModel.playSongs(
                                                localReorderableSongs,
                                                song,
                                                currentPlaylist.name,
                                                currentPlaylist.id
                                            )
                                        },
                                        song = song,
                                        isCurrentSong = playerStableState.currentSong?.id == song.id,
                                        isPlaying = playerStableState.isPlaying,
                                        isDragging = isDragging,
                                        onRemoveClick = {
                                            if (!isFolderPlaylist) {
                                                currentPlaylist.let {
                                                    playlistViewModel.removeSongFromPlaylist(it.id, song.id)
                                                }
                                            }
                                        },
                                        isFromPlaylist = true,
                                        isReorderModeEnabled = isReorderModeEnabled,
                                        isDragHandleVisible = isReorderModeEnabled,
                                        isRemoveButtonVisible = isRemoveModeEnabled,
                                        onMoreOptionsClick = stableOnMoreOptionsClick,
                                        dragHandle = {
                                            IconButton(
                                                onClick = {},
                                                modifier = Modifier
                                                    .draggableHandle(
                                                        onDragStarted = {
                                                            performAppCompatHapticFeedback(
                                                                view,
                                                                appHapticsConfig,
                                                                HapticFeedbackConstantsCompat.GESTURE_START
                                                            )
                                                        },
                                                        onDragStopped = {
                                                            performAppCompatHapticFeedback(
                                                                view,
                                                                appHapticsConfig,
                                                                HapticFeedbackConstantsCompat.GESTURE_END
                                                            )
                                                        }
                                                    )
                                                    .size(40.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DragIndicator,
                                                    contentDescription = reorderSongCd,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        ExpressiveScrollBar(
                            listState = listState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(
                                    bottom = if (playerStableState.currentSong != null) MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                                    end = 14.dp,
                                    top = 18.dp // Increased to 16.dp as requested
                                )
                        )
                    }
                }
            }
        }
    }

    if (showAddSongsSheet && currentPlaylist != null && !isFolderPlaylist) {
        SongPickerBottomSheet(
            initiallySelectedSongIds = currentPlaylist.songIds.toSet(),
            onDismiss = { showAddSongsSheet = false },
            onConfirm = { selectedIds ->
                playlistViewModel.addSongsToPlaylist(currentPlaylist.id, selectedIds.toList())
                showAddSongsSheet = false
            }
        )
    }
    if (showPlaylistOptionsSheet && !isFolderPlaylist) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { showPlaylistOptionsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 4.dp,
//            dragHandle = {
//                SheetDefaults.DragHandle(
//                    color = MaterialTheme.colorScheme.onSurfaceVariant
//                )
//            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = playlistOptionsTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    currentPlaylist?.name?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                PlaylistActionItem(
                    icon = painterResource(R.drawable.rounded_edit_24),
                    label = editPlaylistLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        showEditPlaylistDialog = true
                    }
                )
                if (isAnyWatchPaired) {
                    PlaylistActionItem(
                        icon = painterResource(R.drawable.rounded_watch_arrow_down_24),
                        label = if (isPlaylistOnWatch) updateOnWatchLabel else sendToWatchLabel,
                        enabled = !isAnyBatchTransferActive,
                        onClick = {
                            showPlaylistOptionsSheet = false
                            playlistViewModel.refreshWatchAvailability()
                            showSendToWatchDialog = true
                        }
                    )
                }
                PlaylistActionItem(
                    icon = painterResource(R.drawable.rounded_delete_24),
                    label = deletePlaylistLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        showDeleteConfirmation = true
                    }
                )
                PlaylistActionItem(
                    icon = painterResource(R.drawable.outline_graph_1_24),
                    label = setDefaultTransitionLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        navController.navigateSafely(Screen.EditTransition.createRoute(playlistId))
                    }
                )
                PlaylistActionItem(
                    icon = painterResource(R.drawable.rounded_attach_file_24),
                    label = exportPlaylistLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        val sanitizedName = PlaylistViewModel.sanitizeFileName(currentPlaylist?.name ?: fallbackPlaylistName)
                        m3uExportLauncher.launch("$sanitizedName.m3u")
                    }
                )
            }
        }
    }
    
    if (showEditPlaylistDialog && currentPlaylist != null) {
        val initialShapeType = try {
            currentPlaylist.coverShapeType?.let { PlaylistShapeType.valueOf(it) } ?: PlaylistShapeType.Circle
        } catch (e: Exception) {
            PlaylistShapeType.Circle
        }
        
        EditPlaylistDialog(
            visible = showEditPlaylistDialog,
            currentName = currentPlaylist.name,
            currentImageUri = currentPlaylist.coverImageUri,
            currentColor = currentPlaylist.coverColorArgb,
            currentIconName = currentPlaylist.coverIconName,
            currentShapeType = initialShapeType,
            currentShapeDetail1 = currentPlaylist.coverShapeDetail1,
            currentShapeDetail2 = currentPlaylist.coverShapeDetail2,
            currentShapeDetail3 = currentPlaylist.coverShapeDetail3,
            currentShapeDetail4 = currentPlaylist.coverShapeDetail4,
            onDismiss = { showEditPlaylistDialog = false },
            onSave = { name, imageUri, color, icon, scale, panX, panY, shapeType, d1, d2, d3, d4 ->
                playlistViewModel.updatePlaylistParameters(
                    playlistId = currentPlaylist.id,
                    name = name,
                    coverImageUri = imageUri,
                    coverColor = color,
                    coverIcon = icon,
                    cropScale = scale,
                    cropPanX = panX,
                    cropPanY = panY,
                    coverShapeType = shapeType,
                    coverShapeDetail1 = d1,
                    coverShapeDetail2 = d2,
                    coverShapeDetail3 = d3,
                    coverShapeDetail4 = d4
                )
                showEditPlaylistDialog = false
            }
        )
    }
    if (showDeleteConfirmation && currentPlaylist != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(deletePlaylistConfirmTitle) },
            text = {
                Text(deletePlaylistConfirmBody)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playlistViewModel.deletePlaylist(currentPlaylist.id)
                        onDeletePlayListClick()
                        showDeleteConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.common_delete), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    if (showSendToWatchDialog && currentPlaylist != null) {
        val playlistName = currentPlaylist.name
        val estimate = remember(songsInPlaylist, watchSongIds) {
            playlistViewModel.estimateWatchTransfer(songsInPlaylist)
        }
        val estimatedSizeText = android.text.format.Formatter.formatShortFileSize(context, estimate.estimatedBytes)
        val estimatedTimeText = formatListeningDurationCompact(estimate.estimatedTransferSeconds * 1000L)
        val canSend = isPixelPlayWatchAvailable && estimate.pendingSongCount > 0 && !isAnyBatchTransferActive

        AlertDialog(
            onDismissRequest = { showSendToWatchDialog = false },
            title = {
                Text(
                    if (isPlaylistOnWatch) {
                        stringResource(R.string.playlist_send_to_watch_dialog_update_title, playlistName)
                    } else {
                        stringResource(R.string.playlist_send_to_watch_dialog_title, playlistName)
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        !isPixelPlayWatchAvailable -> Text(stringResource(R.string.playlist_send_to_watch_dialog_watch_unavailable))
                        estimate.pendingSongCount == 0 -> Text(
                            stringResource(R.string.playlist_send_to_watch_dialog_all_songs, estimate.totalSongCount)
                        )
                        else -> {
                            Text(
                                if (estimate.pendingSongCount == estimate.totalSongCount) {
                                    stringResource(R.string.playlist_send_to_watch_dialog_all_songs, estimate.totalSongCount)
                                } else {
                                    stringResource(
                                        R.string.playlist_send_to_watch_dialog_pending_songs,
                                        estimate.pendingSongCount,
                                        estimate.totalSongCount,
                                    )
                                }
                            )
                            Text(
                                text = stringResource(
                                    R.string.playlist_send_to_watch_dialog_estimate,
                                    estimatedSizeText,
                                    estimatedTimeText,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSend,
                    onClick = {
                        showSendToWatchDialog = false
                        playlistViewModel.sendPlaylistToWatch(
                            currentPlaylist.id,
                            playlistName,
                            songsInPlaylist.map { it.id },
                        )
                        playerViewModel.sendToast(
                            context.getString(R.string.playlist_watch_transfer_started_toast, playlistName)
                        )
                    }
                ) {
                    Text(
                        if (isPlaylistOnWatch) {
                            stringResource(R.string.playlist_send_to_watch_dialog_update_confirm)
                        } else {
                            stringResource(R.string.playlist_send_to_watch_dialog_confirm)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendToWatchDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteIds) {
            derivedStateOf {
                currentSong?.let {
                    favoriteIds.contains(
                        it.id
                    )
                }
            }
        }.value ?: false

        if (currentSong != null) {
            SongInfoBottomSheet(
                song = currentSong,
                isFavorite = isFavorite,
                onToggleFavorite = {
                    // Directly use PlayerViewModel's method to toggle, which should handle UserPreferencesRepository
                    playerViewModel.toggleFavoriteSpecificSong(currentSong) // Assumes such a method exists or will be added to PlayerViewModel
                },
                onDismiss = { showSongInfoBottomSheet = false },
                onPlaySong = {
                    playerViewModel.showAndPlaySong(currentSong)
                },
                onAddToQueue = {
                    playerViewModel.addSongToQueue(currentSong) // Assumes such a method exists or will be added
                    playerViewModel.sendToast(toastAddedToQueue)
                },
                onAddNextToQueue = {
                    playerViewModel.addSongNextToQueue(currentSong)
                    playerViewModel.sendToast(toastPlayingNext)
                },
                onAddToPlayList = {
                    showPlaylistBottomSheet = true;
                },
                onDeleteFromDevice = playerViewModel::deleteFromDevice,
                onNavigateToAlbum = {
                    navController.navigateSafelyReplacing(
                        route = Screen.AlbumDetail.createRoute(currentSong.albumId),
                        patternToPop = Screen.AlbumDetail.route
                    )
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtist = {
                    navController.navigateSafelyReplacing(
                        route = Screen.ArtistDetail.createRoute(currentSong.artistId),
                        patternToPop = Screen.ArtistDetail.route
                    )
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtistById = { artistId ->
                    navController.navigateSafelyReplacing(
                        route = Screen.ArtistDetail.createRoute(artistId),
                        patternToPop = Screen.ArtistDetail.route
                    )
                    showSongInfoBottomSheet = false
                },
                onNavigateToGenre = {
                    currentSong.genre?.let {
                        navController.navigateSafelyReplacing(
                            route = Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")),
                            patternToPop = Screen.GenreDetail.route
                        )
                    }
                    showSongInfoBottomSheet = false
                },
                onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                    playerViewModel.editSongMetadata(
                        currentSong,
                        newTitle,
                        newArtist,
                        newAlbum,
                        newAlbumArtist,
                        newComposer,
                        newGenre,
                        newLyrics,
                        newTrackNumber,
                        newDiscNumber,
                        replayGainTrackGainDb,
                        replayGainAlbumGainDb,
                        coverArtUpdate
                    )
                },
                removeFromListTrigger = {
                    playlistViewModel.removeSongFromPlaylist(playlistId, currentSong.id)
                }
            )
            if (showPlaylistBottomSheet) {
                val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

                PlaylistBottomSheet(
                    playlistUiState = playlistUiState,
                    songs = listOf(currentSong),
                    onDismiss = {
                        showPlaylistBottomSheet = false
                    },
                    currentPlaylistId = playlistId,
                    bottomBarHeight = bottomBarHeightDp,
                    playerViewModel = playerViewModel,
                )
            }
        }
    }

    val isSortSheetVisible by playerViewModel.isSortingSheetVisible.collectAsStateWithLifecycle()

    if (isSortSheetVisible) {
        // Check if playlist is in Manual mode (which corresponds to Default Order)
        val isManualMode = uiState.playlistSongsOrderMode is PlaylistSongsOrderMode.Manual
        val rawOption = uiState.currentPlaylistSongsSortOption
        // If in Manual mode, show SongDefaultOrder as selected; otherwise use the stored sort option
        val currentSortOption = if (isManualMode) {
            SortOption.SongDefaultOrder
        } else if (currentPlaylist != null) {
            rawOption
        } else {
            SortOption.SongTitleAZ
        }

        // Build options list inline to avoid potential static initialization issues
        val songSortOptions = listOf(
            SortOption.SongDefaultOrder,
            SortOption.SongTitleAZ,
            SortOption.SongTitleZA,
            SortOption.SongArtist,
            SortOption.SongArtistDesc,
            SortOption.SongAlbum,
            SortOption.SongAlbumDesc,
            SortOption.SongDateAdded,
            SortOption.SongDateAddedAsc,
            SortOption.SongDuration,
            SortOption.SongDurationAsc
        )

        LibrarySortBottomSheet(
            title = sortSheetTitle,
            options = songSortOptions,
            selectedOption = currentSortOption,
            onDismiss = { playerViewModel.hideSortingSheet() },
            onOptionSelected = { option ->
                 playlistViewModel.sortPlaylistSongs(option)
                 playerViewModel.hideSortingSheet()
                 // Auto-scroll to first item after sorting (delay to allow list to update)
                 scope.launch {
                     kotlinx.coroutines.delay(100)
                     listState.animateScrollToItem(0)
                 }
            },
            onDirectionToggle = { option ->
                playlistViewModel.sortPlaylistSongs(option)
                scope.launch {
                    kotlinx.coroutines.delay(100)
                    listState.animateScrollToItem(0)
                }
            },
            showViewToggle = false 
        )
    }
}


@Composable
private fun PlaylistActionItem(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
        )
    }
}

/**
 * Non-blocking playlist-transfer indicator shown at the top of the songs list — the user can
 * leave the screen (or the app) while it continues; the foreground notification (see
 * `WatchTransferForegroundService`) is what tracks completion once they do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WatchTransferProgressBanner(
    batch: PhoneWatchBatchTransferState,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overallProgress by remember(batch.processedSongCount, batch.currentSongProgress, batch.totalSongCount) {
        derivedStateOf {
            if (batch.totalSongCount > 0) {
                ((batch.processedSongCount + batch.currentSongProgress) / batch.totalSongCount.toFloat())
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    val animatedProgress by animateFloatAsState(
        targetValue = overallProgress,
        animationSpec = tween(durationMillis = 300),
        label = "WatchTransferProgressBanner",
    )

    // Same icon-badge + row treatment as PlaylistActionItem right below it (40dp circular badge
    // on surfaceContainerHighest, 16dp horizontal padding, 18dp corner radius) — this banner is
    // conceptually one more row in that same list, not a separate, unrelated status card.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_watch_arrow_down_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.watch_transfer_batch_progress,
                    batch.processedSongCount,
                    batch.totalSongCount,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (batch.currentSongTitle.isNotBlank()) {
                Text(
                    text = batch.currentSongTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            LinearWavyProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onCancelClick) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.watch_transfer_action_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

