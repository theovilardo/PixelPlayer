@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.PixelPlayStatusBarStyle
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil.compose.AsyncImagePainter
import coil.size.Size
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.utils.LocalArtworkUri
import com.theveloper.pixelplay.presentation.components.CoverArtCropperDialog
import com.theveloper.pixelplay.presentation.components.OnlineCoverArtPickerSheet
import com.theveloper.pixelplay.data.media.CoverArtUpdate
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.ExpressiveScrollBar
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.AlbumDetailViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.utils.formatSongCount
import com.theveloper.pixelplay.utils.shapes.RoundedStarShape
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource

private const val UseSharedCollapsibleTopBarProbe = true

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()

    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showCoverArtPicker by remember { mutableStateOf(false) }
    var pendingCoverArtUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    // The writer's revision, which changes once a cover has actually been
    // written. The songs are not that signal: a row keeps the canonical URI it
    // already held, so the list re-emits equal. Nor is a token bumped where the
    // action fires, which is before the write it stands for has run.
    val context = LocalContext.current
    val coverArtRevision by playerViewModel.appliedCoverArtRevision.collectAsStateWithLifecycle()
    val batchEditInProgress by playerViewModel.batchEditInProgress.collectAsStateWithLifecycle()

    // Album rows keep the same artwork URI when a cover is replaced, so this
    // token forces the header to reload in place -- but only once the new cover
    // is on disk, or the reload re-caches the old image under the new URI.
    val coverArtToken = coverArtRevision

    // Which removal this album needs, deciding what the menu offers and whether
    // it asks first. "All of them" rather than "any": where only some tracks
    // hold an applied cover, removal still has files to rewrite for the rest,
    // so it is the destructive one.
    var appliedCoverTrackCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(uiState.songs, coverArtRevision) {
        appliedCoverTrackCount = withContext(Dispatchers.IO) {
            uiState.songs.count { song ->
                song.id.toLongOrNull()
                    ?.let { AlbumArtUtils.getAppliedAlbumArtFile(context, it) != null } == true
            }
        }
    }
    val songCount = uiState.songs.size
    val everyTrackHoldsAppliedCover = songCount > 0 && appliedCoverTrackCount == songCount
    // Nothing to take off an album that is not showing a cover in the first
    // place. Read from the row rather than the files: the header draws from it,
    // so it is exactly what the user is looking at.
    val albumShowsCoverArt = uiState.album?.albumArtUriString != null
    var showDeleteCoverFromFilesDialog by remember { mutableStateOf(false) }

    // Shared by the two top bar variants below, which differ only in which
    // composable draws them.
    val onRemoveCoverArt: () -> Unit = {
        // Removal always clears the applied cover, so the menu can drop the
        // entry now rather than waiting for the probe to confirm what is
        // already decided.
        appliedCoverTrackCount = 0
        playerViewModel.removeAppliedCoverArt(uiState.songs)
    }
    val onDeleteCoverFromFiles: () -> Unit = {
        showDeleteCoverFromFilesDialog = false
        // The tag editor's cover delete, for every track at once. The save
        // sorts the album out track by track, and asks for write consent first.
        playerViewModel.saveBatchMetadata(
            songs = uiState.songs,
            title = null,
            artist = null,
            album = null,
            albumArtist = null,
            composer = null,
            genre = null,
            lyrics = null,
            trackNumber = null,
            discNumber = null,
            replayGainTrackGainDb = null,
            replayGainAlbumGainDb = null,
            coverArtUpdate = CoverArtUpdate(isDeletion = true)
        )
    }
    val pickCoverArtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pendingCoverArtUri = uri }
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val isDarkTheme = LocalPixelPlayDarkTheme.current
    val baseColorScheme = MaterialTheme.colorScheme
    val albumArtUri = uiState.album?.albumArtUriString?.takeIf { it.isNotBlank() }
    val albumColorSchemeFlow = remember(albumArtUri) {
        albumArtUri?.let { playerViewModel.themeStateHolder.getAlbumColorSchemeFlow(it, eager = false) }
    }
    val albumColorSchemePair = albumColorSchemeFlow?.collectAsStateWithLifecycle()?.value
    val albumColorScheme = remember(albumColorSchemePair, isDarkTheme, baseColorScheme) {
        albumColorSchemePair?.let { pair -> if (isDarkTheme) pair.dark else pair.light }
            ?: baseColorScheme
    }
    var headerArtworkLoaded by remember(albumArtUri) { mutableStateOf(albumArtUri == null) }
    var themeRequestIssued by remember(albumArtUri) { mutableStateOf(albumArtUri == null) }
    LaunchedEffect(albumArtUri, headerArtworkLoaded, themeRequestIssued) {
        if (!themeRequestIssued && headerArtworkLoaded && albumArtUri != null) {
            themeRequestIssued = true
            playerViewModel.themeStateHolder.ensureAlbumColorScheme(albumArtUri)
        }
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    MaterialTheme(
        colorScheme = albumColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {

        val isMiniPlayerVisible = stablePlayerState.currentSong != null
        val fabBottomPadding by animateDpAsState(
            targetValue = if (isMiniPlayerVisible) MiniPlayerHeight + 16.dp else 16.dp,
            label = "fabPadding"
        )

        when {
            uiState.isLoading && uiState.album == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ContainedLoadingIndicator()
                }
            }

            uiState.error != null && uiState.album == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            uiState.album != null -> {
                val album = uiState.album!!
                val songs = uiState.songs
                val songsByDisc = remember(songs) {
                    songs.groupBy { it.discNumber ?: 1 }
                }
                val lazyListState = rememberLazyListState()

                val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val minTopBarHeight = 64.dp + statusBarHeight
                val maxTopBarHeight = 300.dp

                val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
                val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
                val headerImageRequestSize = remember(
                    configuration.screenWidthDp,
                    density.density,
                    maxTopBarHeightPx
                ) {
                    Size(
                        width = with(density) { configuration.screenWidthDp.dp.roundToPx() },
                        height = maxTopBarHeightPx.roundToInt()
                    )
                }

                val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
                val collapseFraction by remember(minTopBarHeightPx, maxTopBarHeightPx) {
                    derivedStateOf {
                        1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(
                            0f,
                            1f
                        )
                    }
                }

                val nestedScrollConnection = remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset {
                            val delta = available.y
                            val isScrollingDown = delta < 0

                            if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                                return Offset.Zero
                            }

                            val previousHeight = topBarHeight.value
                            val newHeight =
                                (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                            val consumed = newHeight - previousHeight

                            if (consumed.roundToInt() != 0) {
                                coroutineScope.launch {
                                    topBarHeight.snapTo(newHeight)
                                }
                            }

                            val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                            return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
                        }

                        override suspend fun onPostFling(
                            consumed: Velocity,
                            available: Velocity
                        ): Velocity {
                            return super.onPostFling(consumed, available)
                        }
                    }
                }

                LaunchedEffect(lazyListState.isScrollInProgress) {
                    if (!lazyListState.isScrollInProgress) {
                        val shouldExpand =
                            topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
                        val canExpand =
                            lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0

                        val targetValue = if (shouldExpand && canExpand) {
                            maxTopBarHeightPx
                        } else {
                            minTopBarHeightPx
                        }

                        if (topBarHeight.value != targetValue) {
                            coroutineScope.launch {
                                topBarHeight.animateTo(
                                    targetValue,
                                    spring(stiffness = Spring.StiffnessMedium)
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = MaterialTheme.colorScheme.surface
                        )
                        .nestedScroll(nestedScrollConnection)
                ) {
                    val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }
                    val showScrollBar =
                        LocalShowScrollbar.current &&
                        collapseFraction > 0.95f &&
                            (lazyListState.canScrollForward || lazyListState.canScrollBackward)

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .offset {
                                val extraHeight =
                                    (topBarHeight.value - minTopBarHeightPx).roundToInt()
                                IntOffset(0, extraHeight)
                            },
                        contentPadding = PaddingValues(
                            top = minTopBarHeight + 8.dp,
                            start = 16.dp,
                            end = if (showScrollBar) 24.dp else 16.dp,
                            bottom = fabBottomPadding + 80.dp // To account for FAB
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        songsByDisc.forEach { (discNumber, discSongs) ->
                            if (songsByDisc.size > 1) {
                                item(key = "disc_header_$discNumber") {
                                    Text(
                                        text = stringResource(R.string.album_disc_number_header, discNumber),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .padding(top = 16.dp, bottom = 8.dp, start = 8.dp)
                                    )
                                }
                            }
                            items(
                                items = discSongs,
                                key = { song -> "album_song_${song.id}" },
                                contentType = { "album_song" }
                            ) { song ->
                                EnhancedSongListItem(
                                    song = song,
                                    isCurrentSong = stablePlayerState.currentSong?.id == song.id,
                                    isPlaying = stablePlayerState.isPlaying,
                                    showAlbumArt = false,
                                    onMoreOptionsClick = {
                                        playerViewModel.selectSongForInfo(song)
                                        showSongInfoBottomSheet = true
                                    },
                                    onClick = { playerViewModel.showAndPlaySong(song, songs) }
                                )
                            }
                        }
                    }

                    if (showScrollBar) {
                        ExpressiveScrollBar(
                            listState = lazyListState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(
                                    top = minTopBarHeight + 12.dp,
                                    bottom = fabBottomPadding + 80.dp
                                )
                        )
                    }

                    if (UseSharedCollapsibleTopBarProbe) {
                        SharedAlbumTopBarProbe(
                            album = album,
                            songsCount = songs.size,
                            collapseFraction = collapseFraction,
                            headerHeight = currentTopBarHeightDp,
                            headerImageRequestSize = headerImageRequestSize,
                            onHeaderArtworkState = { state ->
                                if (state is AsyncImagePainter.State.Success) {
                                    headerArtworkLoaded = true
                                }
                            },
                            onBackPressed = { navController.popBackStack() },
                            onPlayClick = {
                                if (songs.isNotEmpty()) {
                                    val randomSong = songs.random()
                                    playerViewModel.showAndPlaySong(randomSong, songs)
                                }
                            },
                            onSearchCoverArtOnline = { showCoverArtPicker = true },
                            onPickCoverArtFromGallery = {
                                pickCoverArtLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            canRemoveCoverArt = everyTrackHoldsAppliedCover,
                            onRemoveCoverArt = onRemoveCoverArt,
                            canDeleteCoverFromFiles = albumShowsCoverArt,
                            onDeleteCoverFromFiles = { showDeleteCoverFromFilesDialog = true },
                            coverArtToken = coverArtToken
                        )
                    } else {
                        CollapsingAlbumTopBar(
                            album = album,
                            songsCount = songs.size,
                            collapseFraction = collapseFraction,
                            headerHeight = currentTopBarHeightDp,
                            headerImageRequestSize = headerImageRequestSize,
                            onHeaderArtworkState = { state ->
                                if (state is AsyncImagePainter.State.Success) {
                                    headerArtworkLoaded = true
                                }
                            },
                            onBackPressed = { navController.popBackStack() },
                            onPlayClick = {
                                if (songs.isNotEmpty()) {
                                    val randomSong = songs.random()
                                    playerViewModel.showAndPlaySong(randomSong, songs)
                                }
                            },
                            onSearchCoverArtOnline = { showCoverArtPicker = true },
                            onPickCoverArtFromGallery = {
                                pickCoverArtLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            canRemoveCoverArt = everyTrackHoldsAppliedCover,
                            onRemoveCoverArt = onRemoveCoverArt,
                            canDeleteCoverFromFiles = albumShowsCoverArt,
                            onDeleteCoverFromFiles = { showDeleteCoverFromFilesDialog = true },
                            coverArtToken = coverArtToken
                        )
                    }

                    // The cropper closes the moment the write starts, so
                    // without this the screen sits unchanged for seconds on the
                    // one path writing to the user's files. Clear of the mini
                    // player; a bar at the top edge the header art would swallow.
                    AnimatedVisibility(
                        visible = batchEditInProgress,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomBarHeightDp + 16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            tonalElevation = 3.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(
                                        R.string.metadata_edit_updating_n_songs,
                                        uiState.songs.size
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        // The one action in this menu that cannot be undone: the artwork lives
        // in the audio files and nothing else holds a copy of it.
        if (showDeleteCoverFromFilesDialog) {
            AlertDialog(
                icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                title = { Text(stringResource(R.string.album_delete_cover_dialog_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.album_delete_cover_dialog_body,
                            uiState.songs.size
                        )
                    )
                },
                onDismissRequest = { showDeleteCoverFromFilesDialog = false },
                confirmButton = {
                    TextButton(onClick = onDeleteCoverFromFiles) {
                        Text(
                            stringResource(R.string.common_delete),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteCoverFromFilesDialog = false }) {
                        Text(
                            stringResource(R.string.common_cancel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        }

        val albumForCoverArt = uiState.album
        // Kept in composition while it animates away, so visibility is the
        // picker's own business rather than a branch around it.
        if (albumForCoverArt != null) {
            OnlineCoverArtPickerSheet(
                visible = showCoverArtPicker,
                initialAlbum = albumForCoverArt.title,
                initialArtist = albumForCoverArt.artist,
                noteText = stringResource(
                    R.string.cover_art_search_album_scope,
                    uiState.songs.size
                ),
                onDismiss = { showCoverArtPicker = false },
                onCoverDownloaded = { uri ->
                    showCoverArtPicker = false
                    pendingCoverArtUri = uri
                }
            )
        }

        pendingCoverArtUri?.let { sourceUri ->
            // Same cropper as every other cover art edit; the confirmed image is
            // applied to every track of the album so the whole album moves together.
            CoverArtCropperDialog(
                sourceUri = sourceUri,
                onDismiss = { pendingCoverArtUri = null },
                onConfirm = { result ->
                    pendingCoverArtUri = null
                    playerViewModel.saveBatchMetadata(
                        songs = uiState.songs,
                        title = null,
                        artist = null,
                        album = null,
                        albumArtist = null,
                        composer = null,
                        genre = null,
                        lyrics = null,
                        trackNumber = null,
                        discNumber = null,
                        replayGainTrackGainDb = null,
                        replayGainAlbumGainDb = null,
                        coverArtUpdate = result.update
                    )
                }
            )
        }

        if (showSongInfoBottomSheet && selectedSongForInfo != null) {
            val currentSong = selectedSongForInfo
            val isFavorite = remember(currentSong?.id, favoriteIds) {
                derivedStateOf { currentSong?.let { favoriteIds.contains(it.id) } }
            }.value ?: false

            if (currentSong != null) {
                val removeFromListTrigger = remember(uiState.songs) {
                    {
                        viewModel.update(uiState.songs.filterNot { it.id == currentSong.id })
                    }
                }
                SongInfoBottomSheet(
                    song = currentSong,
                    isFavorite = isFavorite,
                    onToggleFavorite = {
                        playerViewModel.toggleFavoriteSpecificSong(currentSong)
                    },
                    onDismiss = { showSongInfoBottomSheet = false },
                    onPlaySong = {
                        playerViewModel.showAndPlaySong(currentSong)
                    },
                    onAddToQueue = {
                        playerViewModel.addSongToQueue(currentSong)
                    },
                    onAddNextToQueue = {
                        playerViewModel.addSongNextToQueue(currentSong)
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
                    removeFromListTrigger = removeFromListTrigger
                )
                if (showPlaylistBottomSheet) {
                    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

                    PlaylistBottomSheet(
                        playlistUiState = playlistUiState,
                        songs = listOf(currentSong),
                        onDismiss = { showPlaylistBottomSheet = false },
                        bottomBarHeight = bottomBarHeightDp,
                        playerViewModel = playerViewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedAlbumTopBarProbe(
    album: Album,
    songsCount: Int,
    collapseFraction: Float,
    headerHeight: Dp,
    headerImageRequestSize: Size,
    onHeaderArtworkState: ((AsyncImagePainter.State) -> Unit)? = null,
    onBackPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onSearchCoverArtOnline: () -> Unit,
    onPickCoverArtFromGallery: () -> Unit,
    canRemoveCoverArt: Boolean,
    onRemoveCoverArt: () -> Unit,
    canDeleteCoverFromFiles: Boolean,
    onDeleteCoverFromFiles: () -> Unit,
    coverArtToken: Long
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val statusBarColor =
        if (LocalPixelPlayDarkTheme.current) Color.Black.copy(alpha = 0.6f)
        else Color.White.copy(alpha = 0.4f)
    val solidAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
    val expandedContentAlpha = 1f - solidAlpha
    val headerOverlayBrush = remember(surfaceColor, expandedContentAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                surfaceColor.copy(alpha = 0.22f * expandedContentAlpha),
                surfaceColor.copy(alpha = 0.82f * expandedContentAlpha),
                surfaceColor
            )
        )
    }
    val statusBarBrush = remember(statusBarColor) {
        Brush.verticalGradient(colors = listOf(statusBarColor, Color.Transparent))
    }
    val expandedStatusBarFallback = remember(statusBarColor, surfaceColor) {
        statusBarColor.compositeOver(surfaceColor)
    }
    val fallbackStatusBarColor = remember(expandedStatusBarFallback, surfaceColor, solidAlpha) {
        lerpColor(expandedStatusBarFallback, surfaceColor, solidAlpha)
    }
    val titleVerticalBias = lerp(1f, -1f, collapseFraction)
    val shuffleAlignment = BiasAlignment(horizontalBias = 1f, verticalBias = titleVerticalBias)

    PixelPlayStatusBarStyle(color = fallbackStatusBarColor)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .clipToBounds()
    ) {
        if (expandedContentAlpha > 0.01f) {
            SmartImage(
                model = album.albumArtUriString.withCoverArtToken(coverArtToken),
                contentDescription = stringResource(R.string.album_cover_for, album.title),
                contentScale = ContentScale.Crop,
                targetSize = headerImageRequestSize,
                allowHardware = true,
                crossfadeDurationMillis = 0,
                alpha = expandedContentAlpha,
                onState = onHeaderArtworkState,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(headerOverlayBrush)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(statusBarBrush)
                .align(Alignment.TopCenter)
        )

        CollapsibleCommonTopBar(
            title = album.title,
            subtitle = "${album.artist} • ${formatSongCount(songsCount)}",
            collapseFraction = collapseFraction,
            headerHeight = headerHeight,
            onBackClick = onBackPressed,
            containerColor = surfaceColor.copy(alpha = solidAlpha),
            collapsedTitleStartPadding = 68.dp,
            expandedTitleStartPadding = 24.dp,
            collapsedTitleEndPadding = 24.dp,
            expandedTitleEndPadding = 136.dp,
            containerHeightRange = 112.dp to 56.dp,
            titleStyle = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.SemiBold,
                textGeometricTransform = TextGeometricTransform(scaleX = 1.08f)
            ),
            titleScaleRange = 1f to 1f,
            titleFontSizeRange = 30.sp to 18.sp,
            maxLines = if (collapseFraction < 0.5f) 2 else 1,
            collapsedSubtitleMaxLines = 1,
            expandedSubtitleMaxLines = 2,
            contentColor = MaterialTheme.colorScheme.onSurface,
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            fadeSubtitleOnCollapse = false,
            syncStatusBarWithContainer = false,
            actions = {
                Box(modifier = Modifier.padding(end = 12.dp, top = 4.dp)) {
                    CoverArtMenuButton(
                        onSearchOnline = onSearchCoverArtOnline,
                        onPickFromGallery = onPickCoverArtFromGallery,
                        canRemoveCover = canRemoveCoverArt,
                        canDeleteCoverFromFiles = canDeleteCoverFromFiles,
                        onDeleteCoverFromFiles = onDeleteCoverFromFiles,
                        onRemoveCover = onRemoveCoverArt
                    )
                }
            }
        )

        LargeExtendedFloatingActionButton(
            onClick = onPlayClick,
            shape = RoundedStarShape(sides = 8, curve = 0.05, rotation = 0f),
            modifier = Modifier
                .align(shuffleAlignment)
                .statusBarsPadding()
                .padding(end = 16.dp)
                .graphicsLayer {
                    scaleX = expandedContentAlpha
                    scaleY = expandedContentAlpha
                    alpha = expandedContentAlpha
                }
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.common_shuffle_play_album))
        }
    }
}

/**
 * Adds a cache busting token to an artwork request without touching what is
 * stored, so a replaced cover reloads in place.
 *
 * Uses `t`, the token [LocalArtworkUri] writes and reads, rather than inventing
 * a second one: a token only this file knows about is invisible to everything
 * that already understands these URIs.
 */
private fun String?.withCoverArtToken(token: Long): String? {
    val uri = this ?: return null
    if (token == 0L) return uri

    val separator = uri.indexOf('?')
    if (separator < 0) return "$uri?t=$token"

    // Any token already on the uri is dropped rather than joined. The reader
    // takes the first `t` it finds, so appending a second one leaves the old
    // value winning and the cover never reloads.
    val base = uri.substring(0, separator)
    val params = uri.substring(separator + 1)
        .split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') != "t" }

    return (params + "t=$token").joinToString(separator = "&", prefix = "$base?")
}

/**
 * Cover art actions for an album. Online search covers most releases, and the
 * gallery covers the ones no catalog carries -- Bandcamp-only releases, private
 * pressings, anything self-released.
 */
@Composable
private fun CoverArtMenuButton(
    onSearchOnline: () -> Unit,
    onPickFromGallery: () -> Unit,
    canRemoveCover: Boolean,
    onRemoveCover: () -> Unit,
    canDeleteCoverFromFiles: Boolean,
    onDeleteCoverFromFiles: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    FilledIconButton(
        onClick = { expanded = true },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = stringResource(R.string.album_cd_edit_cover_art)
        )
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.album_action_search_cover_online)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            onClick = {
                expanded = false
                onSearchOnline()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.album_action_pick_cover_from_gallery)) },
            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
            onClick = {
                expanded = false
                onPickFromGallery()
            }
        )
        // At most one of these. Taking back an app-held cover is reversible;
        // taking one out of the audio files destroys the image, so they are
        // separate entries and only the second asks first.
        if (canRemoveCover) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.album_action_remove_cover)) },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRemoveCover()
                }
            )
        } else if (canDeleteCoverFromFiles) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.album_action_delete_cover_from_files)) },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDeleteCoverFromFiles()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsingAlbumTopBar(
    album: Album,
    songsCount: Int,
    collapseFraction: Float,
    headerHeight: Dp,
    headerImageRequestSize: Size,
    onHeaderArtworkState: ((AsyncImagePainter.State) -> Unit)? = null,
    onBackPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onSearchCoverArtOnline: () -> Unit,
    onPickCoverArtFromGallery: () -> Unit,
    canRemoveCoverArt: Boolean,
    onRemoveCoverArt: () -> Unit,
    canDeleteCoverFromFiles: Boolean,
    onDeleteCoverFromFiles: () -> Unit,
    coverArtToken: Long
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val statusBarColor =
        if (LocalPixelPlayDarkTheme.current) Color.Black.copy(alpha = 0.6f) else Color.White.copy(
            alpha = 0.4f
        )

    // Animation Values
    val fabScale = 1f - collapseFraction
    val backgroundAlpha = collapseFraction
    val headerContentAlpha = 1f - (collapseFraction * 2).coerceAtMost(1f)
    val showExpandedArtwork = headerContentAlpha > 0.01f
    val headerOverlayBrush = remember(surfaceColor, headerContentAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                surfaceColor.copy(alpha = 0.30f * headerContentAlpha),
                surfaceColor.copy(alpha = 0.90f * headerContentAlpha),
                surfaceColor.copy(alpha = headerContentAlpha)
            )
        )
    }
    val statusBarBrush = remember(statusBarColor) {
        Brush.verticalGradient(
            colors = listOf(
                statusBarColor,
                Color.Transparent
            )
        )
    }
    val solidAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
    val expandedStatusBarFallback = remember(statusBarColor, surfaceColor) {
        statusBarColor.compositeOver(surfaceColor)
    }
    val fallbackStatusBarColor = remember(expandedStatusBarFallback, surfaceColor, solidAlpha) {
        lerpColor(expandedStatusBarFallback, surfaceColor, solidAlpha)
    }

    // Title animation
    val titleScale = lerp(1f, 0.75f, collapseFraction)
    val titlePaddingStart = lerp(24.dp, 58.dp, collapseFraction)
    val titleMaxLines = if (collapseFraction < 0.5f) 2 else 1
    val titleVerticalBias = lerp(1f, -1f, collapseFraction)
    val animatedTitleAlignment =
        BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias)
    val titleContainerHeight = lerp(88.dp, 56.dp, collapseFraction)
    val yOffsetCorrection = lerp((titleContainerHeight / 2) - 64.dp, 0.dp, collapseFraction)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .clipToBounds()
    ) {
        PixelPlayStatusBarStyle(color = fallbackStatusBarColor)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .background(surfaceColor.copy(alpha = backgroundAlpha))
        ) {
            if (showExpandedArtwork) {
                SmartImage(
                    model = album.albumArtUriString.withCoverArtToken(coverArtToken),
                    contentDescription = stringResource(R.string.album_cover_for, album.title),
                    contentScale = ContentScale.Crop,
                    targetSize = headerImageRequestSize,
                    allowHardware = true,
                    crossfadeDurationMillis = 0,
                    alpha = headerContentAlpha,
                    onState = onHeaderArtworkState,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(headerOverlayBrush)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(statusBarBrush)
                    .align(Alignment.TopCenter)
            )

            // Top bar content (buttons, title, etc.)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                FilledIconButton(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 4.dp),
                    onClick = onBackPressed,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = 4.dp)
                ) {
                    CoverArtMenuButton(
                        onSearchOnline = onSearchCoverArtOnline,
                        onPickFromGallery = onPickCoverArtFromGallery,
                        canRemoveCover = canRemoveCoverArt,
                        canDeleteCoverFromFiles = canDeleteCoverFromFiles,
                        onDeleteCoverFromFiles = onDeleteCoverFromFiles,
                        onRemoveCover = onRemoveCoverArt
                    )
                }

                Box(
                    modifier = Modifier
                        .align(animatedTitleAlignment)
                        .height(titleContainerHeight)
                        .fillMaxWidth()
                        .offset(y = yOffsetCorrection)
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = titlePaddingStart, end = 120.dp)
                            .graphicsLayer {
                                scaleX = titleScale
                                scaleY = titleScale
                            },
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = 26.sp,
                                textGeometricTransform = TextGeometricTransform(scaleX = 1.2f),
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = titleMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.album_detail_meta_line,
                                album.artist,
                                formatSongCount(songsCount)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                LargeExtendedFloatingActionButton(
                    onClick = onPlayClick,
                    shape = RoundedStarShape(sides = 8, curve = 0.05, rotation = 0f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                            alpha = fabScale
                        }
                ) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.common_shuffle_play_album))
                }
            }
        }
    }
}
