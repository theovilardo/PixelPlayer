@file:OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class
)

package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.LibraryTabId
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.StorageFilter
import com.theveloper.pixelplay.presentation.components.ExpressiveScrollBar
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistContainer
import com.theveloper.pixelplay.presentation.components.albumFastScrollLabel
import com.theveloper.pixelplay.presentation.components.artistFastScrollLabel
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemePair
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.text.style.TextOverflow

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryAlbumsTab(
    albums: LazyPagingItems<Album>,
    isLoading: Boolean,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    isListView: Boolean,
    currentAlbumSortOption: SortOption,
    onAlbumClick: (Long) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedAlbumIds: Set<Long> = emptySet(),
    onAlbumLongPress: (Album) -> Unit = {},
    onAlbumSelectionToggle: (Album) -> Unit = {},
    getSelectionIndex: (Long) -> Int? = { null },
    storageFilter: StorageFilter = StorageFilter.ALL,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
) {
    val hasCurrentSong by remember(playerViewModel) {
        playerViewModel.stablePlayerState
            .map { it.currentSong != null && it.currentSong != Song.emptySong() }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val dummyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val dummyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    val albumFastScrollLabelProvider = remember(albums, currentAlbumSortOption) {
        { index: Int ->
            albumFastScrollLabel(
                album = albums.peek(index),
                sortOption = currentAlbumSortOption
            )
        }
    }

    var lastHandledAlbumSortKey by remember {
        mutableStateOf(currentAlbumSortOption.storageKey)
    }
    var pendingAlbumSortScrollReset by remember { mutableStateOf(false) }
    var albumSortSawRefreshLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentAlbumSortOption) {
        val currentSortKey = currentAlbumSortOption.storageKey
        if (currentSortKey == lastHandledAlbumSortKey) return@LaunchedEffect
        lastHandledAlbumSortKey = currentSortKey
        pendingAlbumSortScrollReset = true
        albumSortSawRefreshLoading = false
        if (isListView) {
            listState.scrollToItem(0)
        } else {
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(albums.loadState.refresh, pendingAlbumSortScrollReset, isListView) {
        if (!pendingAlbumSortScrollReset) return@LaunchedEffect
        if (albums.loadState.refresh is LoadState.Loading) {
            albumSortSawRefreshLoading = true
            return@LaunchedEffect
        }
        if (!albumSortSawRefreshLoading) return@LaunchedEffect
        if (isListView) {
            listState.scrollToItem(0)
        } else {
            gridState.scrollToItem(0)
        }
        pendingAlbumSortScrollReset = false
    }

    // P2-3: Debounce 150ms to avoid firing on every scroll frame.
    // Reduced prefetchCount from 10 to 4 to lower memory/IO pressure.
    LaunchedEffect(albums, gridState, listState, isListView) {
        if (isListView) {
            snapshotFlow { listState.layoutInfo }
                .debounce(150)
                .distinctUntilChanged()
                .collect { layoutInfo ->
                    val visibleItemsInfo = layoutInfo.visibleItemsInfo
                    if (visibleItemsInfo.isNotEmpty() && albums.itemCount > 0) {
                        val lastVisibleItemIndex = visibleItemsInfo.last().index
                        val totalItemsCount = albums.itemCount
                        val prefetchThreshold = 5
                        val prefetchCount = 4

                        if (totalItemsCount > lastVisibleItemIndex + 1 && lastVisibleItemIndex + prefetchThreshold >= totalItemsCount - prefetchCount) {
                            val startIndexToPrefetch = lastVisibleItemIndex + 1
                            val endIndexToPrefetch = (startIndexToPrefetch + prefetchCount).coerceAtMost(totalItemsCount)

                            (startIndexToPrefetch until endIndexToPrefetch).forEach { indexToPrefetch ->
                                val album = albums.peek(indexToPrefetch)
                                album?.albumArtUriString?.let { uri ->
                                    val request = ImageRequest.Builder(context)
                                        .data(uri)
                                        .size(Size(256, 256))
                                        .build()
                                    imageLoader.enqueue(request)
                                }
                            }
                        }
                    }
                }
        } else {
            snapshotFlow { gridState.layoutInfo }
                .debounce(150)
                .distinctUntilChanged()
                .collect { layoutInfo ->
                    val visibleItemsInfo = layoutInfo.visibleItemsInfo
                    if (visibleItemsInfo.isNotEmpty() && albums.itemCount > 0) {
                        val lastVisibleItemIndex = visibleItemsInfo.last().index
                        val totalItemsCount = albums.itemCount
                        val prefetchThreshold = 5
                        val prefetchCount = 4

                        if (totalItemsCount > lastVisibleItemIndex + 1 && lastVisibleItemIndex + prefetchThreshold >= totalItemsCount - prefetchCount) {
                            val startIndexToPrefetch = lastVisibleItemIndex + 1
                            val endIndexToPrefetch = (startIndexToPrefetch + prefetchCount).coerceAtMost(totalItemsCount)

                            (startIndexToPrefetch until endIndexToPrefetch).forEach { indexToPrefetch ->
                                val album = albums.peek(indexToPrefetch)
                                album?.albumArtUriString?.let { uri ->
                                    val request = ImageRequest.Builder(context)
                                        .data(uri)
                                        .size(Size(256, 256))
                                        .build()
                                    imageLoader.enqueue(request)
                                }
                            }
                        }
                    }
            }
        }
    }

    val refreshState = albums.loadState.refresh
    val reachedEndOfPagination = albums.loadState.append.endOfPaginationReached
    val shouldShowInitialLoading = albums.itemCount == 0 && isLoading

    when {
        refreshState is LoadState.Error && albums.itemCount == 0 -> {
            val error = (refreshState as LoadState.Error).error
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.library_album_detail_error_loading_albums), style = MaterialTheme.typography.titleMedium)
                    Text(
                        error.localizedMessage ?: stringResource(R.string.common_error_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { albums.retry() }) {
                        Text(stringResource(R.string.library_action_retry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        shouldShowInitialLoading -> {
            if (isListView) {
                LazyColumn(
                    modifier = Modifier
                        .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = PlayerSheetCollapsedCornerRadius,
                                bottomEnd = PlayerSheetCollapsedCornerRadius
                            )
                        )
                        .fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap + 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(8, key = { "skeleton_album_list_$it" }) {
                        AlbumListItem(
                            album = Album.empty(),
                            albumColorSchemePairFlow = MutableStateFlow<ColorSchemePair?>(null),
                            onClick = {},
                            isLoading = true
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    modifier = Modifier
                        .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = PlayerSheetCollapsedCornerRadius,
                                bottomEnd = PlayerSheetCollapsedCornerRadius
                            )
                        )
                        .fillMaxSize(),
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(bottom = bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap + 4.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(8, key = { "skeleton_album_grid_$it" }) {
                        AlbumGridItemRedesigned(
                            album = Album.empty(),
                            albumColorSchemePairFlow = MutableStateFlow<ColorSchemePair?>(null),
                            onClick = {},
                            isLoading = true
                        )
                    }
                }
            }
        }

        albums.itemCount == 0 && refreshState is LoadState.NotLoading -> {
            LibraryExpressiveEmptyState(
                tabId = LibraryTabId.ALBUMS,
                storageFilter = storageFilter,
                bottomBarHeight = bottomBarHeight
            )
        }

        else -> {
            Box(modifier = Modifier.fillMaxSize()) {
                val albumsPullToRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    state = albumsPullToRefreshState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = albumsPullToRefreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isListView) {
                            val activeListState = if (albums.itemCount > 0) listState else dummyListState
                            LazyColumn(
                                modifier = Modifier
                                    .padding(start = 14.dp, end = if (LocalShowScrollbar.current && (activeListState.canScrollForward || activeListState.canScrollBackward)) 24.dp else 14.dp, bottom = 6.dp)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = PlayerSheetCollapsedCornerRadius,
                                            bottomEnd = PlayerSheetCollapsedCornerRadius
                                        )
                                    ),
                                state = activeListState,
                                contentPadding = PaddingValues(bottom = bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap + 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    count = albums.itemCount,
                                    key = { index -> albums.peek(index)?.id ?: "album_placeholder_$index" },
                                    contentType = { "album_list_item" }
                                ) { index ->
                                    val album = albums[index]
                                    if (album != null) {
                                        val albumSpecificColorSchemeFlow =
                                            playerViewModel.themeStateHolder.getAlbumColorSchemeFlow(album.albumArtUriString ?: "")
                                        val rememberedOnClick = remember(album.id, onAlbumClick) {
                                            { onAlbumClick(album.id) }
                                        }
                                        val rememberedOnLongPress = remember(album.id, onAlbumLongPress) {
                                            { onAlbumLongPress(album) }
                                        }
                                        val rememberedOnSelectionToggle = remember(album.id, onAlbumSelectionToggle) {
                                            { onAlbumSelectionToggle(album) }
                                        }
                                        AlbumListItem(
                                            album = album,
                                            albumColorSchemePairFlow = albumSpecificColorSchemeFlow,
                                            onClick = rememberedOnClick,
                                            isLoading = false,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = selectedAlbumIds.contains(album.id),
                                            selectionIndex = getSelectionIndex(album.id),
                                            onLongPress = rememberedOnLongPress,
                                            onSelectionToggle = rememberedOnSelectionToggle
                                        )
                                    } else {
                                        AlbumListItem(
                                            album = Album.empty(),
                                            albumColorSchemePairFlow = MutableStateFlow<ColorSchemePair?>(null),
                                            onClick = {},
                                            isLoading = true
                                        )
                                    }
                                }
                            }
                            val bottomPadding = if (hasCurrentSong)
                                bottomBarHeight + MiniPlayerHeight + 16.dp
                            else
                                bottomBarHeight + 16.dp

                            ExpressiveScrollBar(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                                listState = activeListState,
                                dragLabelProvider = albumFastScrollLabelProvider
                            )
                        } else {
                            val activeGridState = if (albums.itemCount > 0) gridState else dummyGridState
                            LazyVerticalGrid(
                                modifier = Modifier
                                    .padding(start = 14.dp, end = if (LocalShowScrollbar.current && (activeGridState.canScrollForward || activeGridState.canScrollBackward)) 24.dp else 14.dp, bottom = 6.dp)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = PlayerSheetCollapsedCornerRadius,
                                            bottomEnd = PlayerSheetCollapsedCornerRadius
                                        )
                                    ),
                                state = activeGridState,
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(bottom = bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap + 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    count = albums.itemCount,
                                    key = { index -> albums.peek(index)?.id ?: "album_grid_placeholder_$index" },
                                    contentType = { "album_grid_item" }
                                ) { index ->
                                    val album = albums[index]
                                    if (album != null) {
                                        val albumSpecificColorSchemeFlow =
                                            playerViewModel.themeStateHolder.getAlbumColorSchemeFlow(album.albumArtUriString ?: "")
                                        val rememberedOnClick = remember(album.id, onAlbumClick) {
                                            { onAlbumClick(album.id) }
                                        }
                                        val rememberedOnLongPress = remember(album.id, onAlbumLongPress) {
                                            { onAlbumLongPress(album) }
                                        }
                                        val rememberedOnSelectionToggle = remember(album.id, onAlbumSelectionToggle) {
                                            { onAlbumSelectionToggle(album) }
                                        }
                                        AlbumGridItemRedesigned(
                                            album = album,
                                            albumColorSchemePairFlow = albumSpecificColorSchemeFlow,
                                            onClick = rememberedOnClick,
                                            isLoading = false,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = selectedAlbumIds.contains(album.id),
                                            selectionIndex = getSelectionIndex(album.id),
                                            onLongPress = rememberedOnLongPress,
                                            onSelectionToggle = rememberedOnSelectionToggle
                                        )
                                    } else {
                                        AlbumGridItemRedesigned(
                                            album = Album.empty(),
                                            albumColorSchemePairFlow = MutableStateFlow<ColorSchemePair?>(null),
                                            onClick = {},
                                            isLoading = true
                                        )
                                    }
                                }
                            }

                            val bottomPadding = if (hasCurrentSong)
                                bottomBarHeight + MiniPlayerHeight + 16.dp
                            else
                                bottomBarHeight + 16.dp

                            ExpressiveScrollBar(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                                gridState = activeGridState,
                                dragLabelProvider = albumFastScrollLabelProvider
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryArtistsTab(
    artists: LazyPagingItems<Artist>,
    isLoading: Boolean,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    currentArtistSortOption: SortOption,
    onArtistClick: (Long) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    storageFilter: StorageFilter = StorageFilter.ALL,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    val hasCurrentSong by remember(playerViewModel) {
        playerViewModel.stablePlayerState
            .map { it.currentSong != null && it.currentSong != Song.emptySong() }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val dummyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val artistFastScrollLabelProvider = remember(artists, currentArtistSortOption) {
        { index: Int ->
            artistFastScrollLabel(
                artist = artists.peek(index),
                sortOption = currentArtistSortOption
            )
        }
    }
    var lastHandledArtistSortKey by remember {
        mutableStateOf(currentArtistSortOption.storageKey)
    }
    var pendingArtistSortScrollReset by remember { mutableStateOf(false) }
    var artistSortSawRefreshLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentArtistSortOption) {
        val currentSortKey = currentArtistSortOption.storageKey
        if (currentSortKey == lastHandledArtistSortKey) return@LaunchedEffect
        lastHandledArtistSortKey = currentSortKey
        pendingArtistSortScrollReset = true
        artistSortSawRefreshLoading = false
        listState.scrollToItem(0)
    }

    LaunchedEffect(artists.loadState.refresh, pendingArtistSortScrollReset) {
        if (!pendingArtistSortScrollReset) return@LaunchedEffect
        if (artists.loadState.refresh is LoadState.Loading) {
            artistSortSawRefreshLoading = true
            return@LaunchedEffect
        }
        if (!artistSortSawRefreshLoading) return@LaunchedEffect
        listState.scrollToItem(0)
        pendingArtistSortScrollReset = false
    }

    val refreshState = artists.loadState.refresh
    val reachedEndOfPagination = artists.loadState.append.endOfPaginationReached
    val shouldShowInitialLoading = artists.itemCount == 0 && isLoading

    when {
        refreshState is LoadState.Error && artists.itemCount == 0 -> {
            val error = (refreshState as LoadState.Error).error
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.library_artist_error_loading_artists), style = MaterialTheme.typography.titleMedium)
                    Text(
                        error.localizedMessage ?: stringResource(R.string.common_error_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { artists.retry() }) {
                        Text(stringResource(R.string.library_action_retry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        shouldShowInitialLoading -> {
            LazyColumn(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 26.dp,
                            topEnd = 26.dp,
                            bottomStart = PlayerSheetCollapsedCornerRadius,
                            bottomEnd = PlayerSheetCollapsedCornerRadius
                        )
                    )
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap)
            ) {
                item(key = "skeleton_top_spacer") { Spacer(Modifier.height(4.dp)) }
                items(10, key = { "skeleton_artist_$it" }) {
                    ArtistListItem(
                        artist = Artist.empty(),
                        onClick = {},
                        isLoading = true
                    )
                }
            }
        }

        artists.itemCount == 0 && refreshState is LoadState.NotLoading -> {
            LibraryExpressiveEmptyState(
                tabId = LibraryTabId.ARTISTS,
                storageFilter = storageFilter,
                bottomBarHeight = bottomBarHeight
            )
        }

        else -> {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                val genresPullToRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    state = genresPullToRefreshState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = genresPullToRefreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val activeListState = if (artists.itemCount > 0) listState else dummyListState
                        LazyColumn(
                            modifier = Modifier
                                .padding(start = 12.dp, end = if (LocalShowScrollbar.current && (activeListState.canScrollForward || activeListState.canScrollBackward)) 22.dp else 12.dp, bottom = 6.dp)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 26.dp,
                                        topEnd = 26.dp,
                                        bottomStart = PlayerSheetCollapsedCornerRadius,
                                        bottomEnd = PlayerSheetCollapsedCornerRadius
                                    )
                                ),
                            state = activeListState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap)
                        ) {
                            items(
                                count = artists.itemCount,
                                key = { index -> artists.peek(index)?.id ?: "artist_placeholder_$index" },
                                contentType = { "artist" }
                            ) { index ->
                                val artist = artists[index]
                                if (artist != null) {
                                    val rememberedOnClick = remember(artist.id, onArtistClick) {
                                        { onArtistClick(artist.id) }
                                    }
                                    ArtistListItem(artist = artist, onClick = rememberedOnClick)
                                } else {
                                    ArtistListItem(
                                        artist = Artist.empty(),
                                        onClick = {},
                                        isLoading = true
                                    )
                                }
                            }
                        }

                        val bottomPadding = if (hasCurrentSong)
                            bottomBarHeight + MiniPlayerHeight + 16.dp
                        else
                            bottomBarHeight + 16.dp

                        ExpressiveScrollBar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                            listState = activeListState,
                            dragLabelProvider = artistFastScrollLabelProvider
                        )
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryPlaylistsTab(
    playlistUiState: PlaylistUiState,
    filteredPlaylists: List<com.theveloper.pixelplay.data.model.Playlist> = playlistUiState.playlists,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedPlaylistIds: Set<String> = emptySet(),
    onPlaylistLongPress: (com.theveloper.pixelplay.data.model.Playlist) -> Unit = {},
    onPlaylistSelectionToggle: (com.theveloper.pixelplay.data.model.Playlist) -> Unit = {},
    onPlaylistOptionsClick: () -> Unit = {}
) {
    PlaylistContainer(
        playlistUiState = playlistUiState,
        filteredPlaylists = filteredPlaylists,
        currentSortOption = playlistUiState.currentPlaylistSortOption,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        bottomBarHeight = bottomBarHeight,
        navController = navController,
        playerViewModel = playerViewModel,
        isSelectionMode = isSelectionMode,
        selectedPlaylistIds = selectedPlaylistIds,
        onPlaylistLongPress = onPlaylistLongPress,
        onPlaylistSelectionToggle = onPlaylistSelectionToggle
    )
}
