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
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
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
import com.theveloper.pixelplay.presentation.viewmodel.WearLocalPlaylistViewModel
import com.theveloper.pixelplay.presentation.viewmodel.WearPlayerViewModel

/**
 * Song list for one playlist snapshot. Songs that haven't finished transferring yet are shown
 * dimmed with a cloud icon and aren't tappable — they resolve to normal, playable rows on their
 * own once the transfer completes (see [WearLocalPlaylistViewModel.playlistSongs]).
 */
@Composable
fun LocalPlaylistDetailScreen(
    playlistId: String,
    playlistName: String,
    viewModel: WearLocalPlaylistViewModel = hiltViewModel(),
    playerViewModel: WearPlayerViewModel = hiltViewModel(),
) {
    LaunchedEffect(playlistId) { viewModel.loadPlaylist(playlistId) }

    val songs by viewModel.playlistSongs.collectAsState()
    val playerState by playerViewModel.playerState.collectAsState()
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()
    val background = palette.screenBackgroundColor()
    val surfaceContainer = palette.surfaceContainerColor()
    val elevatedSurfaceContainer = palette.surfaceContainerHighColor()
    val availableCount = songs.count { it.isAvailable }

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
                    text = playlistName,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.title2,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
            }

            item {
                Text(
                    text = pluralStringResource(
                        R.plurals.wear_local_playlist_song_count,
                        songs.size,
                        songs.size,
                    ),
                    style = MaterialTheme.typography.caption2,
                    color = palette.textSecondary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            if (availableCount > 0) {
                item {
                    Chip(
                        label = {
                            Text(
                                text = stringResource(R.string.wear_play_all),
                                color = palette.textPrimary,
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = palette.textPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { viewModel.playAll() },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = palette.shuffleActive.copy(alpha = 0.38f),
                            contentColor = palette.chipContent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            items(songs.size) { index ->
                val item = songs[index]
                if (item.isAvailable) {
                    val song = item.song!!
                    val isCurrentSong = song.songId == playerState.songId && playerState.songId.isNotBlank()
                    val isPlayingSong = isCurrentSong && playerState.isPlaying
                    Chip(
                        label = {
                            Text(
                                text = song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textPrimary,
                            )
                        },
                        secondaryLabel = if (song.artist.isNotEmpty()) {
                            {
                                Text(
                                    text = song.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = palette.textSecondary.copy(alpha = 0.78f),
                                )
                            }
                        } else null,
                        icon = {
                            if (isCurrentSong) {
                                PlayingEqIcon(
                                    color = if (isPlayingSong) palette.shuffleActive else palette.textSecondary,
                                    isPlaying = isPlayingSong,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        onClick = { viewModel.playFrom(song.songId) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (isCurrentSong) elevatedSurfaceContainer else surfaceContainer,
                            contentColor = palette.chipContent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Chip(
                        label = {
                            Text(
                                text = stringResource(R.string.wear_song_pending_transfer),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textSecondary.copy(alpha = 0.6f),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                tint = palette.textSecondary.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {},
                        enabled = false,
                        colors = ChipDefaults.chipColors(
                            backgroundColor = surfaceContainer.copy(alpha = 0.5f),
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
