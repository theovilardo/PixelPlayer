package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import com.theveloper.pixelplay.presentation.theme.LocalWearPalette
import com.theveloper.pixelplay.presentation.theme.screenBackgroundColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerColor
import com.theveloper.pixelplay.presentation.viewmodel.WearLocalPlaylistViewModel

/**
 * Lists playlist snapshots synced from the phone (see [WearLocalPlaylistViewModel]). A playlist
 * shows up here as soon as its metadata syncs, even before any of its songs finish transferring.
 */
@Composable
fun LocalPlaylistsScreen(
    viewModel: WearLocalPlaylistViewModel = hiltViewModel(),
    onPlaylistClick: (playlistId: String, name: String) -> Unit,
) {
    val playlists by viewModel.playlists.collectAsState()
    val playlistIdsReceiving by viewModel.playlistIdsReceiving.collectAsState()
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()
    val background = palette.screenBackgroundColor()
    val surfaceContainer = palette.surfaceContainerColor()

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
                    text = stringResource(R.string.wear_local_playlists_title),
                    style = MaterialTheme.typography.title2,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            if (playlists.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.wear_no_local_playlists),
                        style = MaterialTheme.typography.body2,
                        color = palette.textSecondary.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.wear_no_local_playlists_hint),
                        style = MaterialTheme.typography.caption2,
                        color = palette.textSecondary.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            } else {
                items(playlists.size) { index ->
                    val playlist = playlists[index]
                    val isReceiving = playlist.playlistId in playlistIdsReceiving
                    Chip(
                        label = {
                            Text(
                                text = playlist.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textPrimary,
                            )
                        },
                        secondaryLabel = if (isReceiving) {
                            {
                                Text(
                                    text = stringResource(R.string.wear_local_playlist_receiving),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = palette.textSecondary.copy(alpha = 0.82f),
                                )
                            }
                        } else {
                            null
                        },
                        icon = {
                            if (isReceiving) {
                                CircularProgressIndicator(
                                    indicatorColor = palette.shuffleActive,
                                    trackColor = surfaceContainer,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        onClick = { onPlaylistClick(playlist.playlistId, playlist.name) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = surfaceContainer,
                            contentColor = palette.chipContent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
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
