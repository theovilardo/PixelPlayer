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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.local.LocalPlaylistEntity
import com.theveloper.pixelplay.presentation.components.AlwaysOnScalingPositionIndicator
import com.theveloper.pixelplay.presentation.components.WearTopTimeText
import com.theveloper.pixelplay.presentation.theme.LocalWearPalette
import com.theveloper.pixelplay.presentation.theme.screenBackgroundColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerHighColor
import com.theveloper.pixelplay.presentation.viewmodel.WearLocalPlaylistViewModel

/**
 * Playlists synced from the phone, stored locally on the watch. Tapping one opens
 * [LocalPlaylistDetailScreen] regardless of whether every song has finished transferring yet —
 * the playlist's membership/order arrives before its audio (see `WearPlaylistSync`), so the list
 * itself is meaningful immediately.
 */
@Composable
fun LocalPlaylistsScreen(
    onPlaylistClick: (playlistId: String, title: String) -> Unit,
    viewModel: WearLocalPlaylistViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistIdsReceiving by viewModel.playlistIdsReceiving.collectAsStateWithLifecycle()
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()
    val background = palette.screenBackgroundColor()

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
                    text = stringResource(R.string.wear_playlists_title),
                    style = MaterialTheme.typography.title2,
                    fontWeight = FontWeight(760),
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
                        color = palette.textSecondary.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            } else {
                items(items = playlists, key = { it.playlistId }) { playlist ->
                    LocalPlaylistChip(
                        playlist = playlist,
                        isReceiving = playlistIdsReceiving.contains(playlist.playlistId),
                        onClick = { onPlaylistClick(playlist.playlistId, playlist.name) },
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

@Composable
private fun LocalPlaylistChip(
    playlist: LocalPlaylistEntity,
    isReceiving: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalWearPalette.current
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
                    text = stringResource(R.string.wear_playlist_receiving),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.shuffleActive.copy(alpha = 0.90f),
                )
            }
        } else {
            null
        },
        icon = {
            if (isReceiving) {
                CircularProgressIndicator(
                    indicatorColor = palette.shuffleActive,
                    trackColor = palette.surfaceContainerColor(),
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
        onClick = onClick,
        colors = ChipDefaults.chipColors(
            backgroundColor = if (isReceiving) {
                palette.surfaceContainerHighColor()
            } else {
                palette.surfaceContainerColor()
            },
            contentColor = palette.chipContent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
