package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.AlwaysOnScalingPositionIndicator
import com.theveloper.pixelplay.presentation.components.WearTopTimeText
import com.theveloper.pixelplay.presentation.viewmodel.BrowseUiState
import com.theveloper.pixelplay.presentation.viewmodel.WearBrowseViewModel
import com.theveloper.pixelplay.presentation.theme.LocalWearPalette
import com.theveloper.pixelplay.presentation.theme.rememberBrowseSubscreenTitleFont
import com.theveloper.pixelplay.presentation.theme.screenBackgroundColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerColor
import com.theveloper.pixelplay.shared.WearBrowseRequest
import com.theveloper.pixelplay.shared.WearLibraryItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh

/**
 * Generic list screen for albums, artists, or playlists.
 * Loads items from the phone via [WearBrowseViewModel] and displays them.
 * Clicking an item navigates to [SongListScreen] to show the songs within.
 */
@Composable
fun LibraryListScreen(
    browseType: String,
    title: String,
    onItemClick: (item: WearLibraryItem, subBrowseType: String, itemTitle: String) -> Unit,
    viewModel: WearBrowseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val palette = LocalWearPalette.current
    val subscreenTitleFont = rememberBrowseSubscreenTitleFont()

    LaunchedEffect(browseType) {
        viewModel.loadItems(browseType)
    }

    val background = palette.screenBackgroundColor()

    when (val state = uiState) {
        is BrowseUiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    indicatorColor = palette.textSecondary,
                )

                WearTopTimeText(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(5f),
                    color = palette.textPrimary,
                )
            }
        }

        is BrowseUiState.Error -> {
            val columnState = rememberResponsiveColumnState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background),
            ) {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    columnState = columnState,
                ) {
                    item { Spacer(modifier = Modifier.height(18.dp)) }

                    item {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.title2,
                            fontFamily = subscreenTitleFont,
                            fontWeight = FontWeight(760),
                            color = palette.textPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.body2,
                            color = palette.textError,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                    item {
                        Chip(
                            label = { Text(stringResource(R.string.wear_retry), color = palette.textPrimary) },
                            icon = {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.wear_cd_retry),
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = { viewModel.refresh() },
                            colors = ChipDefaults.chipColors(
                                backgroundColor = palette.surfaceContainerColor(),
                                contentColor = palette.chipContent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }

                AlwaysOnScalingPositionIndicator(
                    listState = columnState.state,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    color = palette.textPrimary,
                )

                WearTopTimeText(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(5f),
                    color = palette.textPrimary,
                )
            }
        }

        is BrowseUiState.Success -> {
            val columnState = rememberResponsiveColumnState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background),
            ) {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    columnState = columnState,
                ) {
                    item { Spacer(modifier = Modifier.height(18.dp)) }

                    item {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.title2,
                            fontFamily = subscreenTitleFont,
                            fontWeight = FontWeight(760),
                            color = palette.textPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp),
                        )
                    }

                    if (state.items.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.wear_no_items),
                                style = MaterialTheme.typography.body2,
                                color = palette.textSecondary.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                    } else {
                        items(state.items.size) { index ->
                            val item = state.items[index]
                            LibraryItemChip(
                                item = item,
                                browseType = browseType,
                                onClick = {
                                    val subBrowseType = when (browseType) {
                                        WearBrowseRequest.ALBUMS -> WearBrowseRequest.ALBUM_SONGS
                                        WearBrowseRequest.ARTISTS -> WearBrowseRequest.ARTIST_SONGS
                                        WearBrowseRequest.PLAYLISTS -> WearBrowseRequest.PLAYLIST_SONGS
                                        else -> browseType
                                    }
                                    onItemClick(item, subBrowseType, item.title)
                                },
                            )
                        }
                    }
                }

                AlwaysOnScalingPositionIndicator(
                    listState = columnState.state,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    color = palette.textPrimary,
                )

                WearTopTimeText(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(5f),
                    color = palette.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun LibraryItemChip(
    item: WearLibraryItem,
    browseType: String,
    onClick: () -> Unit,
) {
    val palette = LocalWearPalette.current
    val icon = when (browseType) {
        WearBrowseRequest.ALBUMS -> Icons.Rounded.Album
        WearBrowseRequest.ARTISTS -> Icons.Rounded.Person
        WearBrowseRequest.PLAYLISTS -> Icons.AutoMirrored.Rounded.QueueMusic
        else -> Icons.Rounded.Album
    }

    val iconTint = when (browseType) {
        WearBrowseRequest.ALBUMS -> palette.repeatActive
        WearBrowseRequest.ARTISTS -> palette.textSecondary
        WearBrowseRequest.PLAYLISTS -> palette.shuffleActive
        else -> palette.textSecondary
    }

    Chip(
        label = {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = palette.textPrimary,
            )
        },
        secondaryLabel = if (item.subtitle.isNotEmpty()) {
            {
                Text(
                    text = item.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.textSecondary.copy(alpha = 0.78f),
                )
            }
        } else null,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
        colors = ChipDefaults.chipColors(
            backgroundColor = palette.surfaceContainerColor(),
            contentColor = palette.chipContent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
