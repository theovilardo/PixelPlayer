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
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.theveloper.pixelplay.presentation.components.AlwaysOnScalingPositionIndicator
import com.theveloper.pixelplay.presentation.components.PlayingEqIcon
import com.theveloper.pixelplay.presentation.components.WearTopTimeText
import com.theveloper.pixelplay.presentation.theme.LocalWearPalette
import com.theveloper.pixelplay.presentation.theme.screenBackgroundColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerHighColor
import com.theveloper.pixelplay.presentation.viewmodel.WearLocalPlaylistSongItem
import com.theveloper.pixelplay.presentation.viewmodel.WearLocalPlaylistViewModel
import com.theveloper.pixelplay.presentation.viewmodel.WearPlayerViewModel

/**
 * Songs in a phone playlist synced locally on the watch. Songs still awaiting transfer show as
 * disabled with a "waiting to transfer" label rather than being hidden — the list order and count
 * matches the phone immediately, only playability lags behind.
 */
@Composable
fun LocalPlaylistDetailScreen(
    playlistId: String,
    title: String,
    onPlaybackStarted: () -> Unit = {},
    viewModel: WearLocalPlaylistViewModel = hiltViewModel(),
    playerViewModel: WearPlayerViewModel = hiltViewModel(),
) {
    val playlistDetails by viewModel.playlistDetails.collectAsStateWithLifecycle()
    val songs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val playerState by playerViewModel.playerState.collectAsStateWithLifecycle()
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()
    val background = palette.screenBackgroundColor()
    val displayTitle = playlistDetails?.name ?: title
    val availableCount = songs.count { it.isAvailable }

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }

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
                    text = displayTitle,
                    style = MaterialTheme.typography.title2,
                    fontWeight = FontWeight(760),
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
            }

            if (songs.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            R.string.wear_playlist_pending_songs,
                            availableCount,
                            songs.size,
                        ),
                        style = MaterialTheme.typography.caption2,
                        color = palette.textSecondary.copy(alpha = 0.82f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    )
                }

                item {
                    val playAllEnabled = availableCount > 0
                    val playAllContentColor = if (playAllEnabled) {
                        palette.textPrimary
                    } else {
                        palette.textSecondary.copy(alpha = 0.72f)
                    }
                    Chip(
                        label = { Text(text = stringResource(R.string.wear_play_all), color = playAllContentColor) },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = playAllContentColor,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { viewModel.playAll(); onPlaybackStarted() },
                        enabled = playAllEnabled,
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (playAllEnabled) {
                                palette.shuffleActive.copy(alpha = 0.38f)
                            } else {
                                palette.surfaceContainerHighColor()
                            },
                            contentColor = palette.chipContent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                    )
                }
            }

            if (songs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.wear_playlist_empty),
                        style = MaterialTheme.typography.body2,
                        color = palette.textSecondary.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            } else {
                items(items = songs, key = { it.songId }) { item ->
                    val isCurrentSong = item.song != null &&
                        item.songId == playerState.songId &&
                        playerState.songId.isNotBlank()
                    val isPlayingSong = isCurrentSong && playerState.isPlaying
                    LocalPlaylistSongChip(
                        item = item,
                        isCurrentSong = isCurrentSong,
                        isPlayingSong = isPlayingSong,
                        onClick = {
                            if (item.isAvailable) {
                                viewModel.playFrom(item.songId)
                                onPlaybackStarted()
                            }
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

@Composable
private fun LocalPlaylistSongChip(
    item: WearLocalPlaylistSongItem,
    isCurrentSong: Boolean,
    isPlayingSong: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalWearPalette.current
    val song = item.song
    val title = item.displayTitle
    val containerColor = if (isCurrentSong) palette.surfaceContainerHighColor() else palette.surfaceContainerColor()
    val contentAlpha = if (item.isAvailable) 1f else 0.55f

    Chip(
        label = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = palette.textPrimary.copy(alpha = contentAlpha),
            )
        },
        secondaryLabel = when {
            !item.isAvailable -> {
                {
                    Text(
                        text = stringResource(R.string.wear_song_pending_transfer),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.textSecondary.copy(alpha = 0.72f),
                    )
                }
            }
            !song?.artist.isNullOrEmpty() -> {
                {
                    Text(
                        text = song.artist.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.textSecondary.copy(alpha = 0.78f),
                    )
                }
            }
            else -> null
        },
        icon = {
            when {
                isCurrentSong -> PlayingEqIcon(
                    color = if (isPlayingSong) palette.shuffleActive else palette.textSecondary,
                    isPlaying = isPlayingSong,
                    modifier = Modifier.size(18.dp),
                )
                !item.isAvailable -> CircularProgressIndicator(
                    indicatorColor = palette.textSecondary.copy(alpha = 0.6f),
                    trackColor = palette.surfaceContainerColor(),
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                else -> Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        onClick = onClick,
        enabled = item.isAvailable,
        colors = ChipDefaults.chipColors(
            backgroundColor = containerColor,
            contentColor = palette.chipContent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
