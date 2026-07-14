package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

import com.theveloper.pixelplay.presentation.components.MultiSelectionBottomSheet
import com.theveloper.pixelplay.presentation.components.subcomps.SelectionActionRow
import com.theveloper.pixelplay.presentation.components.subcomps.SelectionCountPill

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.AlbumArtPaletteStyle
import com.theveloper.pixelplay.presentation.components.AutoScrollingTextOnDemand
import com.theveloper.pixelplay.presentation.components.ExpressiveTopBarContent
import com.theveloper.pixelplay.presentation.components.ExpressiveScrollBar
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.presentation.components.GenreSortBottomSheet
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.SmartImageCompactListTargetSize
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.extractFastScrollGlyph
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.screens.QuickFillDialog
import com.theveloper.pixelplay.presentation.viewmodel.GenreDetailListItem
import com.theveloper.pixelplay.presentation.viewmodel.GenreDetailViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SortOption
import com.theveloper.pixelplay.presentation.viewmodel.SectionData
import com.theveloper.pixelplay.presentation.viewmodel.AlbumData
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import com.theveloper.pixelplay.utils.formatDuration
import com.theveloper.pixelplay.utils.formatSongCount
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.theveloper.pixelplay.MainActivity.Companion.LocalHazeState
import com.theveloper.pixelplay.presentation.components.subcomps.TightWrapText
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.StateFlow

// --- Data Models & Helpers ---

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GenreDetailScreen(
    navController: NavHostController,
    genreId: String,
    decodedGenreId: String = java.net.URLDecoder.decode(genreId, "UTF-8"),
    playerViewModel: PlayerViewModel,
    viewModel: GenreDetailViewModel = hiltViewModel(),
    playlistViewModel: com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val libraryGenres by playerViewModel.genres.collectAsStateWithLifecycle()
    
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val multiSelectionState = playerViewModel.multiSelectionStateHolder
    val selectedSongs by multiSelectionState.selectedSongs.collectAsStateWithLifecycle()
    val isSelectionMode by multiSelectionState.isSelectionMode.collectAsStateWithLifecycle()
    val selectedSongIds by multiSelectionState.selectedSongIds.collectAsStateWithLifecycle()
    var showMultiSelectionSheet by remember { mutableStateOf(false) }
    var playlistSheetSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    BackHandler(enabled = isSelectionMode) {
        multiSelectionState.clearSelection()
    }

    val onSongLongPress: (Song) -> Unit = remember(multiSelectionState, haptic) {
        { song -> 
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            multiSelectionState.toggleSelection(song) 
        }
    }
    
    // Defer heavy list rendering until navigation transition settles
    var isTransitionFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        isTransitionFinished = true
    }

    val density = LocalDensity.current
    val darkMode = LocalPixelPlayDarkTheme.current

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val minTopBarHeight = 58.dp + statusBarHeight // Reduced by 6dp from 64.dp
    val maxTopBarHeight = 200.dp
    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }

    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
    val collapseFraction by remember(minTopBarHeightPx, maxTopBarHeightPx) {
        derivedStateOf {
            1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)
        }
    }
    val isScrollbarEnabled = LocalShowScrollbar.current
    val showScrollBar by remember(isScrollbarEnabled) {
        derivedStateOf {
            isScrollbarEnabled &&
            collapseFraction > 0.95f &&
                (lazyListState.canScrollForward || lazyListState.canScrollBackward)
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0

                // If scrolling up (content going down) and list is not at top, don't expand yet
                if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }

                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight

                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch { topBarHeight.snapTo(newHeight) }
                }

                // Make sure we consume scroll only if we actually resized the bar
                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val currentHeight = topBarHeight.value
                if (currentHeight > minTopBarHeightPx && currentHeight < maxTopBarHeightPx) {
                    // Decide target based on proximity and velocity
                    val targetHeight = if (available.y > 500f) {
                        maxTopBarHeightPx // Flinging down -> Expand
                    } else if (available.y < -500f) {
                        minTopBarHeightPx // Flinging up -> Collapse
                    } else {
                        // Snap to nearest
                        if (currentHeight > (minTopBarHeightPx + maxTopBarHeightPx) / 2) maxTopBarHeightPx else minTopBarHeightPx
                    }
                    
                    coroutineScope.launch {
                        topBarHeight.animateTo(
                            targetValue = targetHeight,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow) 
                        )
                    }
                }
                return super.onPostFling(consumed, available)
            }
        }
    }

    // Colors
    val defaultContainer = MaterialTheme.colorScheme.surfaceVariant
    val defaultOnContainer = MaterialTheme.colorScheme.onSurfaceVariant
    val themeGenre = uiState.genre
    val themeColor = remember(themeGenre, decodedGenreId, darkMode, defaultContainer, defaultOnContainer) {
        if (themeGenre != null) {
            com.theveloper.pixelplay.ui.theme.GenreThemeUtils.getGenreThemeColor(
                genre = themeGenre,
                isDark = darkMode,
                fallbackGenreId = decodedGenreId
            )
        } else {
            com.theveloper.pixelplay.ui.theme.GenreThemeColor(
                defaultContainer,
                defaultOnContainer
            )
        }
    }
    
    val startColor = themeColor.container
    val contentColor = themeColor.onContainer
    
    // Optimization: Calculate a fast display name for the title while the full Genre object is loading.
    // This prevents the "Genre" placeholder from flashing during the navigation transition.
    val initialDisplayName = remember(decodedGenreId) {
        decodedGenreId
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
    val genreDisplayName = themeGenre?.name ?: uiState.genre?.name ?: initialDisplayName
    val genreShuffleLabel = stringResource(R.string.genre_shuffle_label, genreDisplayName)
    val genreFastScrollLabelProvider = remember(uiState.flattenedItems, uiState.sortOption) {
        { index: Int ->
            genreFastScrollLabel(
                items = uiState.flattenedItems,
                index = index,
                sortOption = uiState.sortOption
            )
        }
    }
    
    val toastAddedToQueue = stringResource(R.string.library_toast_added_to_queue)
    val toastPlayingNext = stringResource(R.string.library_toast_playing_next)

    // FAB Logic
    var showSortSheet by remember { mutableStateOf(false) }
    var showSongOptionsSheet by remember { mutableStateOf<Song?>(null) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var showQuickFillDialog by remember { mutableStateOf(false) }

    val isUnknownGenre = remember(decodedGenreId) {
        decodedGenreId.equals("unknown", ignoreCase = true) || decodedGenreId.equals("unknown genre", ignoreCase = true)
    }
    
    val customGenres by playerViewModel.customGenres.collectAsStateWithLifecycle()
    val customGenreIcons by playerViewModel.customGenreIcons.collectAsStateWithLifecycle()
    val genrePaletteStyle by playerViewModel.albumArtPaletteStyle.collectAsStateWithLifecycle(
        initialValue = AlbumArtPaletteStyle.default
    )
    val isMiniPlayerVisible = stablePlayerState.currentSong != null
    val fabBottomPadding by animateDpAsState(
        targetValue = if (isMiniPlayerVisible) MiniPlayerHeight + systemNavBarInset + 16.dp else systemNavBarInset + 16.dp,
        label = "fabPadding"
    )

    // Capture Neutral Colors from the App Theme (before overriding)
    val baseColorScheme = MaterialTheme.colorScheme

    // Dynamic Theme
    val genreColorScheme = remember(themeGenre, decodedGenreId, darkMode, genrePaletteStyle) {
        com.theveloper.pixelplay.ui.theme.GenreThemeUtils.getGenreDetailColorScheme(
            genre = themeGenre,
            fallbackGenreId = decodedGenreId,
            isDark = darkMode,
            paletteStyle = genrePaletteStyle
        )
    }

    MaterialTheme(colorScheme = genreColorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .background(MaterialTheme.colorScheme.background) // Uses new theme background
        ) {
            // Optimization: Cache Dp conversions
            val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }

            // Optimization: Use fixed padding and offset instead of dynamic contentPadding 
            // to avoid triggered remeasures of the entire list on every pixel of scroll.
            
            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(
                    top = minTopBarHeight + 8.dp, // FIXED padding
                    start = 8.dp,
                    end = if (showScrollBar) 24.dp else 8.dp,
                    bottom = fabBottomPadding + 148.dp
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        // Offset the entire list down by the current "expansion" of the top bar
                        val extraHeight = (topBarHeight.value - minTopBarHeightPx).roundToInt()
                        IntOffset(0, extraHeight)
                    }.hazeSource(LocalHazeState.current)
            ) {
                // Optimization: Limit rendered items during the navigation transition 
                // to ensure the slide-in animation remains smooth.
                val displayItems = if (isTransitionFinished || uiState.flattenedItems.size < 20) {
                    uiState.flattenedItems
                } else {
                    uiState.flattenedItems.take(20)
                }

                items(
                    items = displayItems,
                    key = { it.key },
                    contentType = { it::class } // CRITICAL: Content type for optimized recycling
                ) { item ->
                    when (item) {
                        is GenreDetailListItem.ArtistHeader -> {
                            GenreArtistHeader(item.artistName, item.artistImageUrl)
                        }
                        is GenreDetailListItem.AlbumHeader -> {
                            GenreAlbumHeader(
                                album = item.album,
                                useArtistStyle = item.useArtistStyle,
                                onSongClick = { song ->
                                    playerViewModel.showAndPlaySong(song, uiState.sortedSongs, genreDisplayName)
                                }
                            )
                        }
                        is GenreDetailListItem.SongItem -> {
                            val isSelected = selectedSongIds.contains(item.song.id)
                            val selectionIndex = multiSelectionState.getSelectionIndex(item.song.id)
                            GenreSongItemWrapper(
                                item = item,
                                stablePlayerStateFlow = playerViewModel.stablePlayerState,
                                onSongClick = { song ->
                                    playerViewModel.showAndPlaySong(song, uiState.sortedSongs, genreDisplayName)
                                },
                                onMoreOptionsClick = { song -> showSongOptionsSheet = song },
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                selectionIndex = selectionIndex,
                                onLongPress = { onSongLongPress(item.song) }
                            )
                        }
                        is GenreDetailListItem.Spacer -> {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(item.heightDp.dp)
                                    .run {
                                        if (item.useSurfaceBackground) background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
                                        else this
                                    }
                            )
                        }
                        is GenreDetailListItem.Divider -> {
                             Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                HorizontalDivider(modifier = Modifier.alpha(0.3f))
                            }
                        }
                    }
                }
            }

            // Only show scrollbar when the top bar is mostly collapsed to avoid visual conflict
            if (showScrollBar) {
                ExpressiveScrollBar(
                    listState = lazyListState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = minTopBarHeight + 12.dp, // Stable padding for performance
                            bottom = fabBottomPadding + 112.dp // Stable padding
                        ),
                    dragLabelProvider = genreFastScrollLabelProvider
                )
            }

            // Collapsible Top Bar with Gradient (On Top of List, High Z-Index)
            // This ensures the gradient is ON TOP of the scrolling content, so content scrolls BEHIND it.
            GenreCollapsibleTopBar(
                title = genreDisplayName,
                collapseFraction = collapseFraction,
                headerHeight = currentTopBarHeightDp,
                onBackPressed = { navController.popBackStack() },
                startColor = startColor,
                contentColor = contentColor,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                collapsedContentColor = MaterialTheme.colorScheme.onSurface
            )
        
            // Selection Count Pill (Top-Center below collapsed top bar)
            SelectionCountPill(
                selectedCount = selectedSongs.size,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(6f)
                    .padding(top = minTopBarHeight + 24.dp)
            )

            // FAB / SelectionActionRow (Bottom-Center or Bottom-End)
            Box(
                 modifier = Modifier
                    .align(if (isSelectionMode) Alignment.BottomCenter else Alignment.BottomEnd)
                    .padding(
                        bottom = fabBottomPadding + if (isSelectionMode) 16.dp else 26.dp,
                        start = if (isSelectionMode) 16.dp else 0.dp,
                        end = 16.dp
                    )
                    .run {
                        if (isSelectionMode) fillMaxWidth() else this
                    }
                    .zIndex(10f) // Ensure FAB is above everything
            ) {
                if (isSelectionMode) {
                    Card(
                        shape = AbsoluteSmoothCornerShape(28.dp, 60),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionActionRow(
                            selectedCount = selectedSongs.size,
                            onSelectAll = {
                                multiSelectionState.selectAll(uiState.songs)
                            },
                            onDeselect = {
                                multiSelectionState.clearSelection()
                            },
                            onOptionsClick = {
                                showMultiSelectionSheet = true
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                } else {
                     MediumFloatingActionButton(
                        onClick = { showSortSheet = true },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = AbsoluteSmoothCornerShape(24.dp, 60)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.common_options),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        
            // Sorting/Options Bottom Sheet
            if (showSortSheet) {
                GenreSortBottomSheet(
                    onDismiss = { showSortSheet = false },
                    currentSort = uiState.sortOption,
                    onSortSelected = {
                        viewModel.updateSortOption(it)
                        showSortSheet = false
                    },
                    onShuffle = {
                        if (uiState.songs.isNotEmpty()) {
                            playerViewModel.showAndPlaySong(uiState.sortedSongs.random(), uiState.sortedSongs, genreShuffleLabel)
                            showSortSheet = false
                        }
                    },
                    headerContent = if (isUnknownGenre) {
                        {
                            Button(
                                onClick = {
                                    showSortSheet = false
                                    showQuickFillDialog = true
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                shape = racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape(16.dp, 60)
                            ) {
                                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                TightWrapText(
                                    text = stringResource(R.string.genre_quick_fill),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(end = 4.dp),
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 2,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    } else null
                )
            }

            // Quick Fill Dialog
            // QuickFillDialog with Base Theme (Independent of Genre Theme)
            MaterialTheme(colorScheme = baseColorScheme) {
                QuickFillDialog(
                    visible = showQuickFillDialog,
                    songs = uiState.songs,
                    customGenres = customGenres,
                    customGenreIcons = customGenreIcons,
                    onDismiss = { showQuickFillDialog = false },
                    onApply = { songs, genre ->
                        playerViewModel.batchEditGenre(songs, genre)
                        showQuickFillDialog = false
                    },
                    onAddCustomGenre = { genre, iconRes ->
                        playerViewModel.addCustomGenre(genre, iconRes)
                    }
                )
            }
        
            // Song Options Bottom Sheet
            showSongOptionsSheet?.let { song ->
                val isFavorite = favoriteSongIds.contains(song.id)

                MaterialTheme(
                    colorScheme = genreColorScheme,
                    typography = MaterialTheme.typography,
                    shapes = MaterialTheme.shapes
                ) {
                    SongInfoBottomSheet(
                        song = song,
                        isFavorite = isFavorite,
                        onToggleFavorite = {
                            playerViewModel.toggleFavoriteSpecificSong(song)
                        },
                        onDismiss = { showSongOptionsSheet = null },
                        onPlaySong = {
                            playerViewModel.showAndPlaySong(song, uiState.sortedSongs, genreDisplayName)
                        },
                        onAddToQueue = {
                            playerViewModel.addSongToQueue(song)
                            playerViewModel.sendToast(toastAddedToQueue)
                        },
                        onAddNextToQueue = {
                            playerViewModel.addSongNextToQueue(song)
                            playerViewModel.sendToast(toastPlayingNext)
                        },
                        onAddToPlayList = {
                            showPlaylistBottomSheet = true
                        },
                        onDeleteFromDevice = playerViewModel::deleteFromDevice,
                        onNavigateToAlbum = {
                            navController.navigateSafelyReplacing(
                                route = com.theveloper.pixelplay.presentation.navigation.Screen.AlbumDetail.createRoute(song.albumId),
                                patternToPop = com.theveloper.pixelplay.presentation.navigation.Screen.AlbumDetail.route
                            )
                            showSongOptionsSheet = null
                        },
                        onNavigateToArtist = {
                            navController.navigateSafelyReplacing(
                                route = com.theveloper.pixelplay.presentation.navigation.Screen.ArtistDetail.createRoute(song.artistId),
                                patternToPop = com.theveloper.pixelplay.presentation.navigation.Screen.ArtistDetail.route
                            )
                            showSongOptionsSheet = null
                        },
                        onNavigateToArtistById = { artistId ->
                            navController.navigateSafelyReplacing(
                                route = com.theveloper.pixelplay.presentation.navigation.Screen.ArtistDetail.createRoute(artistId),
                                patternToPop = com.theveloper.pixelplay.presentation.navigation.Screen.ArtistDetail.route
                            )
                            showSongOptionsSheet = null
                        },
                        onNavigateToGenre = {
                            song.genre?.let {
                                navController.navigateSafelyReplacing(
                                    route = com.theveloper.pixelplay.presentation.navigation.Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")),
                                    patternToPop = com.theveloper.pixelplay.presentation.navigation.Screen.GenreDetail.route
                                )
                            }
                            showSongOptionsSheet = null
                        },
                        onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                            playerViewModel.editSongMetadata(
                                song,
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
                        removeFromListTrigger = {}
                    )
                }

            }
        
            // Loading/Error States
            if (uiState.isLoadingSongs) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    // Multi-Selection Bottom Sheet
    if (showMultiSelectionSheet && selectedSongs.isNotEmpty()) {
        val activity = context as? android.app.Activity
        val favoriteIds = favoriteSongIds.toSet()

        MultiSelectionBottomSheet(
            selectedSongs = selectedSongs,
            favoriteSongIds = favoriteIds,
            onDismiss = { showMultiSelectionSheet = false },
            onPlayAll = {
                playerViewModel.playSelectedSongs(selectedSongs)
                showMultiSelectionSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSelectedToQueue(selectedSongs)
                showMultiSelectionSheet = false
            },
            onPlayNext = {
                playerViewModel.addSelectedAsNext(selectedSongs)
                showMultiSelectionSheet = false
            },
            onAddToPlaylist = {
                playlistSheetSongs = selectedSongs
                showMultiSelectionSheet = false
                showPlaylistBottomSheet = true
            },
            onToggleLikeAll = { shouldLike ->
                if (shouldLike) {
                    playerViewModel.likeSelectedSongs(selectedSongs)
                } else {
                    playerViewModel.unlikeSelectedSongs(selectedSongs)
                }
                showMultiSelectionSheet = false
            },
            onShareAll = {
                playerViewModel.shareSelectedAsZip(selectedSongs)
                showMultiSelectionSheet = false
            },
            onDeleteAll = { _, onComplete ->
                activity?.let {
                    playerViewModel.deleteSelectedFromDevice(it, selectedSongs) {
                        showMultiSelectionSheet = false
                        onComplete(true)
                    }
                }
            },
            onBatchEdit = {
                showMultiSelectionSheet = false
            }
        )
    }

    // Playlist Bottom Sheet (Single or Multi additions)
    if (showPlaylistBottomSheet) {
        val songsToAddToPlaylist = if (playlistSheetSongs.isNotEmpty()) {
            playlistSheetSongs
        } else {
            showSongOptionsSheet?.let { listOf(it) } ?: emptyList()
        }

        if (songsToAddToPlaylist.isNotEmpty()) {
            com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = songsToAddToPlaylist,
                onDismiss = {
                    showPlaylistBottomSheet = false
                    playlistSheetSongs = emptyList()
                },
                bottomBarHeight = systemNavBarInset,
                playerViewModel = playerViewModel
            )
        }
    }
}

private fun genreFastScrollLabel(
    items: List<GenreDetailListItem>,
    index: Int,
    sortOption: SortOption
): String? {
    if (items.isEmpty()) return null

    val clampedIndex = index.coerceIn(0, items.lastIndex)
    for (candidateIndex in clampedIndex downTo 0) {
        val label = items[candidateIndex].fastScrollLabel(sortOption)
        if (!label.isNullOrBlank()) {
            return label
        }
    }

    return null
}

private fun GenreDetailListItem.fastScrollLabel(sortOption: SortOption): String? =
    when (sortOption) {
        SortOption.ARTIST -> when (this) {
            is GenreDetailListItem.ArtistHeader -> extractFastScrollGlyph(artistName)
            is GenreDetailListItem.AlbumHeader -> extractFastScrollGlyph(album.songs.firstOrNull()?.artist)
            is GenreDetailListItem.SongItem -> extractFastScrollGlyph(song.artist)
            is GenreDetailListItem.Spacer,
            is GenreDetailListItem.Divider -> null
        }

        SortOption.ALBUM -> when (this) {
            is GenreDetailListItem.ArtistHeader -> null
            is GenreDetailListItem.AlbumHeader -> extractFastScrollGlyph(album.name)
            is GenreDetailListItem.SongItem -> extractFastScrollGlyph(song.album)
            is GenreDetailListItem.Spacer,
            is GenreDetailListItem.Divider -> null
        }

        SortOption.TITLE -> when (this) {
            is GenreDetailListItem.ArtistHeader -> null
            is GenreDetailListItem.AlbumHeader -> null
            is GenreDetailListItem.SongItem -> extractFastScrollGlyph(song.title)
            is GenreDetailListItem.Spacer,
            is GenreDetailListItem.Divider -> null
        }
    }

// --- Top Bar Component ---
@Composable
fun GenreCollapsibleTopBar(
    title: String,
    collapseFraction: Float,
    headerHeight: Dp,
    onBackPressed: () -> Unit,
    startColor: Color,
    containerColor: Color,
    contentColor: Color,
    collapsedContentColor: Color
) {
    val solidAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
    val animatedContentColor = androidx.compose.ui.graphics.lerp(
        start = contentColor,
        stop = collapsedContentColor,
        fraction = solidAlpha
    )

    // Optimization: Pre-calculate alpha values
    val gradientAlpha = 0.8f * (1f - solidAlpha)
    
    // Optimization: Reuse gradient brush to avoid allocation on every pixel scroll
    val verticalGradient = remember(startColor, gradientAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                startColor.copy(alpha = gradientAlpha),
                startColor.copy(alpha = 0f)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .zIndex(5f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(containerColor.copy(alpha = solidAlpha)) 
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(verticalGradient)
        )

        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
             FilledIconButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 4.dp)
                    .zIndex(10f),
                onClick = onBackPressed,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = animatedContentColor.copy(alpha = 0.1f),
                    contentColor = animatedContentColor
                )
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = animatedContentColor)
            }

            ExpressiveTopBarContent(
                title = title,
                collapseFraction = collapseFraction,
                modifier = Modifier.fillMaxSize(),
                collapsedTitleStartPadding = 68.dp,
                expandedTitleStartPadding = 20.dp,
                maxLines = 1,
                contentColor = animatedContentColor
            )
        }
    }
}


// --- Section Extensions ---

// --- Item Composables for Flattened List ---

@Composable
fun GenreArtistHeader(
    artistName: String,
    artistImageUrl: String?
) {
    val headerShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = 24.dp, smoothnessAsPercentTR = 60,
            cornerRadiusTL = 24.dp, smoothnessAsPercentTL = 60,
            cornerRadiusBR = 0.dp, smoothnessAsPercentBR = 0,
            cornerRadiusBL = 0.dp, smoothnessAsPercentBL = 0
        )
    }

    val context = LocalContext.current
    val imageRequest = remember(artistImageUrl) {
        if (!artistImageUrl.isNullOrEmpty()) {
            ImageRequest.Builder(context)
                .data(artistImageUrl)
                .crossfade(true)
                .build()
        } else null
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        shape = headerShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageRequest != null) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = artistName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = stringResource(R.string.genre_cd_generic_artist),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(10.dp)
                                .fillMaxSize()
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun GenreAlbumHeader(
    album: AlbumData,
    useArtistStyle: Boolean,
    onSongClick: (Song) -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
    val shape = remember(useArtistStyle) {
        if (useArtistStyle) {
            RectangleShape
        } else {
             AbsoluteSmoothCornerShape(
                cornerRadiusTR = 24.dp, smoothnessAsPercentTR = 60,
                cornerRadiusTL = 24.dp, smoothnessAsPercentTL = 60,
                cornerRadiusBR = 0.dp, smoothnessAsPercentBR = 0,
                cornerRadiusBL = 0.dp, smoothnessAsPercentBL = 0
            )
        }
    }
    
    Box(
         modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = album.artUri,
                contentDescription = null,
                targetSize = SmartImageCompactListTargetSize,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val shouldScroll = album.name.length > 20
                if (shouldScroll) {
                    AutoScrollingTextOnDemand(
                        text = album.name,
                        style = MaterialTheme.typography.titleMedium,
                        gradientEdgeColor = MaterialTheme.colorScheme.surface,
                        expansionFractionProvider = { 1f },
                    )
                } else {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = formatSongCount(album.songs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    if(album.songs.isNotEmpty()) onSongClick(album.songs.first())
                },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.common_play_album))
            }
        }
    }
}

@Composable
fun GenreSongItemWrapper(
    item: com.theveloper.pixelplay.presentation.viewmodel.GenreDetailListItem.SongItem,
    stablePlayerStateFlow: StateFlow<StablePlayerState>,
    onSongClick: (Song) -> Unit,
    onMoreOptionsClick: (Song) -> Unit,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    onLongPress: () -> Unit = {}
) {
    val song = item.song
    val isFirstInAlbum = item.isFirstInAlbum
    val isLastInAlbum = item.isLastInAlbum
    val isLastAlbumInSection = item.isLastAlbumInSection
    val useArtistStyle = item.useArtistStyle

    val stablePlayerState by stablePlayerStateFlow.collectAsStateWithLifecycle()

    // Optimization: Cache shapes to avoid reallocation during scroll
    val songItemShape = remember(isFirstInAlbum, isLastInAlbum) {
        when {
            isFirstInAlbum && isLastInAlbum -> RoundedCornerShape(16.dp)
            isFirstInAlbum -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
            isLastInAlbum -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
            else -> RoundedCornerShape(4.dp)
        }
    }
    
    val containerShape = remember(isLastInAlbum, isLastAlbumInSection) {
        if (isLastInAlbum && isLastAlbumInSection) {
            AbsoluteSmoothCornerShape(
                cornerRadiusTR = 0.dp, smoothnessAsPercentTR = 0,
                cornerRadiusTL = 0.dp, smoothnessAsPercentTL = 0,
                cornerRadiusBR = 24.dp, smoothnessAsPercentBR = 60,
                cornerRadiusBL = 24.dp, smoothnessAsPercentBL = 60
            ) 
        } else {
           RectangleShape
        }
    }
   
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f), containerShape)
            .padding(horizontal = 8.dp) 
            .padding(bottom = if (isLastInAlbum && !isLastAlbumInSection && useArtistStyle) 8.dp else 0.dp)
    ) {
        Column {
            if (!isFirstInAlbum) Spacer(Modifier.height(2.dp))
            
            // Optimization: De-reference stable state values to avoid observing the whole object
            val isCurrent = stablePlayerState.currentSong?.id == song.id
            val isPlaying = stablePlayerState.isPlaying

            EnhancedSongListItem(
                 song = song,
                 isPlaying = isPlaying,
                 isCurrentSong = isCurrent,
                 showAlbumArt = false,
                 customShape = songItemShape,
                 onClick = { onSongClick(song) },
                 onMoreOptionsClick = onMoreOptionsClick,
                 isSelected = isSelected,
                 selectionIndex = selectionIndex,
                 isSelectionMode = isSelectionMode,
                 onLongPress = onLongPress,
                stablePlayerStateFlow = stablePlayerStateFlow,
             )
             
             if (isLastInAlbum) Spacer(Modifier.height(8.dp))
        }
    }
}
