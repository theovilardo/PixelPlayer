package com.theveloper.pixelplay.presentation.components

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.theveloper.pixelplay.data.model.StorageFilter
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import coil.size.Size
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.LibraryTabId
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.screens.TabAnimation
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.ui.theme.ShapeCache
import kotlinx.coroutines.flow.map
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@OptIn(UnstableApi::class)
@ExperimentalMaterial3Api
@Composable
fun SongPickerBottomSheet(
    initiallySelectedSongIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedSongIds = remember {
        mutableStateMapOf<String, Boolean>().apply {
            initiallySelectedSongIds.forEach { put(it, true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
    ) {
        SongPickerContent(
            selectedSongIds = selectedSongIds,
            onConfirm = onConfirm,
            playerViewModel = playerViewModel
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun SongPickerContent(
    selectedSongIds: MutableMap<String, Boolean>,
    onConfirm: (Set<String>) -> Unit,
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val storageFilter by playerViewModel.playlistPickerStorageFilter.collectAsStateWithLifecycle()
    val hasCloudSongs by playerViewModel.hasCloudSongsFlow.collectAsStateWithLifecycle()
    val showCloudFilter = hasCloudSongs != false

    LaunchedEffect(hasCloudSongs, storageFilter) {
        if (hasCloudSongs == false && storageFilter != StorageFilter.OFFLINE) {
            playerViewModel.setPlaylistPickerStorageFilter(StorageFilter.OFFLINE)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.song_picker_title),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = GoogleSansRounded
                )
            }
        },
        bottomBar = {
            if (showCloudFilter) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val tabs = listOf(
                        StorageFilter.OFFLINE to R.string.library_storage_filter_offline,
                        StorageFilter.ONLINE to R.string.library_storage_filter_online
                    )
                    val selectedTabIndex = tabs.indexOfFirst { it.first == storageFilter }.coerceAtLeast(0)

                    PrimaryTabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(5.dp),
                        containerColor = Color.Transparent,
                        divider = {},
                        indicator = {}
                    ) {
                        tabs.forEachIndexed { index, (filter, labelRes) ->
                            TabAnimation(
                                index = index,
                                title = stringResource(labelRes),
                                selectedIndex = selectedTabIndex,
                                onClick = { playerViewModel.setPlaylistPickerStorageFilter(filter) },
                                transformOrigin = if (index == 0) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (filter == StorageFilter.OFFLINE) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_phonef),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Cloud,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(labelRes),
                                        fontFamily = GoogleSansRounded,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    FilledIconButton(
                        onClick = { onConfirm(selectedSongIds.filterValues { it }.keys) },
                        modifier = Modifier.size(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.song_picker_cd_confirm_add_songs),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    LargeExtendedFloatingActionButton(
                        onClick = { onConfirm(selectedSongIds.filterValues { it }.keys) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.song_picker_action_add),
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        SongPickerSelectionPane(
            selectedSongIds = selectedSongIds,
            modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
            contentPadding = PaddingValues(
                bottom = if (showCloudFilter) 120.dp else 128.dp,
                top = 8.dp
            ),
            playerViewModel = playerViewModel
        )
    }
}
@OptIn(UnstableApi::class)
@Composable
fun SongPickerSelectionPane(
    selectedSongIds: MutableMap<String, Boolean>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 100.dp, top = 20.dp),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    var searchQuery by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    val storageFilter by playerViewModel.playlistPickerStorageFilter.collectAsStateWithLifecycle()
    
    val pagedSongs = playerViewModel.playlistPickerSongs.collectAsLazyPagingItems()
    val pagedFavoriteSongs = playerViewModel.playlistPickerFavoriteSongs.collectAsLazyPagingItems()

    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val searchResultsInitialValue: List<Song>? = remember(searchQuery) {
        if (searchQuery.isBlank()) emptyList() else null
    }
    val searchResults by remember(searchQuery, playerViewModel, storageFilter) {
        playerViewModel.searchSongs(searchQuery)
            .map { songs ->
                when (storageFilter) {
                    StorageFilter.OFFLINE -> songs.filter { s ->
                        s.telegramFileId == null && s.neteaseId == null && s.gdriveFileId == null &&
                                s.qqMusicMid == null && s.navidromeId == null && s.jellyfinId == null
                    }

                    StorageFilter.ONLINE -> songs.filter { s ->
                        s.telegramFileId != null || s.neteaseId != null || s.gdriveFileId != null ||
                                s.qqMusicMid != null || s.navidromeId != null || s.jellyfinId != null
                    }

                    else -> songs
                }
            }
            .map<List<Song>, List<Song>?> { it }
    }.collectAsStateWithLifecycle(initialValue = searchResultsInitialValue)

    val animatedAlbumCornerRadius = 60.dp
    val albumShape = remember(animatedAlbumCornerRadius) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = animatedAlbumCornerRadius,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = animatedAlbumCornerRadius,
            smoothnessAsPercentBR = 60,
            cornerRadiusBL = animatedAlbumCornerRadius,
            smoothnessAsPercentBL = 60,
            cornerRadiusBR = animatedAlbumCornerRadius,
            smoothnessAsPercentTL = 60
        )
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        SearchFilterTextField(
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = favoritesOnly,
                onClick = { favoritesOnly = !favoritesOnly },
                label = {
                    Text(
                        text = stringResource(R.string.song_picker_filter_favorites),
                        fontFamily = GoogleSansRounded,
                        fontWeight = if (favoritesOnly) FontWeight.Bold else FontWeight.Medium
                    )
                },
                shape = if (favoritesOnly) ShapeCache.smooth12 else ShapeCache.smoothPill,
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = favoritesOnly,
                    borderColor = Color.Transparent,
                    selectedBorderColor = Color.Transparent,
                    borderWidth = 0.dp,
                    selectedBorderWidth = 0.dp
                ),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                leadingIcon = {
                    Icon(
                        painter = painterResource(
                            if (favoritesOnly) R.drawable.round_favorite_24
                            else R.drawable.round_favorite_border_24
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            )
        }

        when {
            searchQuery.isNotBlank() -> {
                val displayed = (searchResults ?: emptyList()).let { results ->
                    if (favoritesOnly) results.filter { it.id in favoriteIds } else results
                }
                SongPickerList(
                    filteredSongs = displayed,
                    isLoading = searchResults == null,
                    selectedSongIds = selectedSongIds,
                    albumShape = albumShape,
                    searchQuery = searchQuery,
                    modifier = Modifier.weight(1f),
                    contentPadding = contentPadding
                )
            }
            favoritesOnly -> {
                SongPickerPagingList(
                    pagedSongs = pagedFavoriteSongs,
                    selectedSongIds = selectedSongIds,
                    albumShape = albumShape,
                    tabId = LibraryTabId.LIKED,
                    storageFilter = storageFilter,
                    modifier = Modifier.weight(1f),
                    contentPadding = contentPadding
                )
            }
            else -> {
                SongPickerPagingList(
                    pagedSongs = pagedSongs,
                    selectedSongIds = selectedSongIds,
                    albumShape = albumShape,
                    tabId = LibraryTabId.SONGS,
                    storageFilter = storageFilter,
                    modifier = Modifier.weight(1f),
                    contentPadding = contentPadding
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun SongPickerPagingList(
    pagedSongs: LazyPagingItems<Song>,
    selectedSongIds: MutableMap<String, Boolean>,
    albumShape: androidx.compose.ui.graphics.Shape,
    tabId: LibraryTabId,
    storageFilter: StorageFilter,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 100.dp, top = 20.dp)
) {
    when {
        pagedSongs.loadState.refresh is LoadState.Loading && pagedSongs.itemCount == 0 -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        pagedSongs.loadState.refresh is LoadState.Error && pagedSongs.itemCount == 0 -> {
            val error = (pagedSongs.loadState.refresh as LoadState.Error).error
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = error.localizedMessage ?: stringResource(R.string.song_picker_error_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { pagedSongs.retry() }) {
                        Text(stringResource(R.string.library_action_retry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        pagedSongs.itemCount == 0 && pagedSongs.loadState.refresh is LoadState.NotLoading && pagedSongs.loadState.append.endOfPaginationReached -> {
            SongPickerEmptyState(
                tabId = tabId,
                storageFilter = storageFilter,
                modifier = modifier.padding(bottom = contentPadding.calculateBottomPadding())
            )
        }

        else -> {
            val listState = rememberLazyListState()
            Box(
                modifier = modifier
                    .padding(horizontal = 14.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding(),
                        top = contentPadding.calculateTopPadding(),
                        start = contentPadding.calculateLeftPadding(LayoutDirection.Ltr),
                        end = if (LocalShowScrollbar.current && (listState.canScrollForward || listState.canScrollBackward)) 12.dp else 0.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        count = pagedSongs.itemCount,
                        key = { index -> pagedSongs.peek(index)?.id ?: "song_picker_paged_$index" },
                        contentType = pagedSongs.itemContentType { "song_picker_song" }
                    ) { index ->
                        val song = pagedSongs[index]
                        if (song != null) {
                            SongPickerRow(
                                song = song,
                                selectedSongIds = selectedSongIds,
                                albumShape = albumShape
                            )
                        } else {
                            SongPickerPlaceholderRow()
                        }
                    }

                    if (pagedSongs.loadState.append is LoadState.Loading) {
                        item(key = "song_picker_append_loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    if (pagedSongs.loadState.append is LoadState.Error) {
                        item(key = "song_picker_append_error") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(onClick = { pagedSongs.retry() }) {
                                    Text(stringResource(R.string.song_picker_load_more), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                ExpressiveScrollBar(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            bottom = contentPadding.calculateBottomPadding(),
                            top = contentPadding.calculateTopPadding() + 10.dp
                        )
                )
            }
        }
    }
}

@Composable
private fun SongPickerRow(
    song: Song,
    selectedSongIds: MutableMap<String, Boolean>,
    albumShape: androidx.compose.ui.graphics.Shape
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable {
                val currentSelection = selectedSongIds[song.id] ?: false
                selectedSongIds[song.id] = !currentSelection
            }
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = CircleShape
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = selectedSongIds[song.id] ?: false,
            onCheckedChange = { isChecked ->
                selectedSongIds[song.id] = isChecked
            }
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape
                )
        ) {
            SmartImage(
                model = song.albumArtUriString,
                contentDescription = song.title,
                shape = albumShape,
                targetSize = Size(168, 168),
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SongPickerPlaceholderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = CircleShape
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                )
        )
        Spacer(Modifier.width(18.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape
                )
        )
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .width(132.dp)
                    .height(14.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
            )
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(12.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
fun SongPickerEmptyState(
    tabId: LibraryTabId,
    storageFilter: StorageFilter,
    modifier: Modifier = Modifier
) {
    val spec = when (tabId) {
        LibraryTabId.LIKED -> when (storageFilter) {
            StorageFilter.ALL -> Triple(R.drawable.round_favorite_24, R.string.library_empty_liked_all_title, R.string.library_empty_liked_all_subtitle)
            StorageFilter.OFFLINE -> Triple(R.drawable.round_favorite_24, R.string.library_empty_liked_offline_title, R.string.library_empty_liked_offline_subtitle)
            StorageFilter.ONLINE -> Triple(R.drawable.round_favorite_24, R.string.library_empty_liked_online_title, R.string.library_empty_liked_online_subtitle)
        }
        else -> when (storageFilter) {
            StorageFilter.ALL -> Triple(R.drawable.rounded_music_off_24, R.string.library_empty_songs_all_title, R.string.library_empty_songs_all_subtitle)
            StorageFilter.OFFLINE -> Triple(R.drawable.rounded_music_off_24, R.string.library_empty_songs_offline_title, R.string.library_empty_songs_offline_subtitle)
            StorageFilter.ONLINE -> Triple(R.drawable.rounded_music_off_24, R.string.library_empty_songs_online_title, R.string.library_empty_songs_online_subtitle)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                tonalElevation = 2.dp
            ) {
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = spec.first),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(spec.second),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(spec.third),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SongPickerList(
    filteredSongs: List<Song>,
    isLoading: Boolean,
    selectedSongIds: MutableMap<String, Boolean>,
    albumShape: androidx.compose.ui.graphics.Shape,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 100.dp, top = 20.dp)
) {
    if (isLoading) {
        Box(
            modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (filteredSongs.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(bottom = contentPadding.calculateBottomPadding()),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    tonalElevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_search_24),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank())
                            stringResource(R.string.search_no_results_for_query, searchQuery)
                        else
                            stringResource(R.string.search_no_results_found),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    } else {
        val listState = rememberLazyListState()
        Box(
            modifier = modifier
                .padding(horizontal = 14.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = contentPadding.calculateBottomPadding(),
                    top = contentPadding.calculateTopPadding(),
                    start = contentPadding.calculateLeftPadding(LayoutDirection.Ltr),
                    end = if (LocalShowScrollbar.current && (listState.canScrollForward || listState.canScrollBackward)) 12.dp else 0.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSongs, key = { it.id }) { song ->
                    SongPickerRow(
                        song = song,
                        selectedSongIds = selectedSongIds,
                        albumShape = albumShape
                    )
                }
            }

            ExpressiveScrollBar(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(
                        bottom = contentPadding.calculateBottomPadding(),
                        top = contentPadding.calculateTopPadding() + 10.dp
                    )
            )
        }
    }
}
