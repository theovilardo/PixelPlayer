package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import com.theveloper.pixelplay.presentation.components.AlwaysOnScalingPositionIndicator
import com.theveloper.pixelplay.presentation.components.WearTopTimeText
import com.theveloper.pixelplay.presentation.theme.LocalWearPalette
import com.theveloper.pixelplay.presentation.theme.screenBackgroundColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerHighColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerHighestColor
import com.theveloper.pixelplay.presentation.viewmodel.BrowseUiState
import com.theveloper.pixelplay.presentation.viewmodel.WearBrowseViewModel
import com.theveloper.pixelplay.presentation.viewmodel.WearDownloadsViewModel
import com.theveloper.pixelplay.presentation.viewmodel.WearPlayerViewModel
import com.theveloper.pixelplay.shared.WearBrowseRequest
import com.theveloper.pixelplay.shared.WearTransferProgress

@Composable
fun MoreScreen(
    onQueueClick: () -> Unit,
    onSettingsClick: () -> Unit,
    playerViewModel: WearPlayerViewModel = hiltViewModel(),
    browseViewModel: WearBrowseViewModel = hiltViewModel(),
    downloadsViewModel: WearDownloadsViewModel = hiltViewModel(),
) {
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()

    val playerState by playerViewModel.playerState.collectAsStateWithLifecycle()
    val isPhoneConnected by playerViewModel.isPhoneConnected.collectAsStateWithLifecycle()
    val isWatchOutputSelected by playerViewModel.isWatchOutputSelected.collectAsStateWithLifecycle()
    val canCurrentSongBeFavorited by playerViewModel.canCurrentSongBeFavorited.collectAsStateWithLifecycle()
    val queueState by browseViewModel.uiState.collectAsStateWithLifecycle()
    val downloadedSongIds by downloadsViewModel.downloadedSongIds.collectAsStateWithLifecycle()
    val activeTransfers by downloadsViewModel.activeTransfers.collectAsStateWithLifecycle()

    LaunchedEffect(isPhoneConnected, isWatchOutputSelected) {
        if (isPhoneConnected && !isWatchOutputSelected) {
            browseViewModel.loadItems(WearBrowseRequest.QUEUE)
        }
    }
    LaunchedEffect(playerState.queueRevision, isPhoneConnected, isWatchOutputSelected) {
        if (isPhoneConnected && !isWatchOutputSelected) {
            browseViewModel.loadItems(WearBrowseRequest.QUEUE)
        }
    }
    LaunchedEffect(
        isWatchOutputSelected,
        isPhoneConnected,
        canCurrentSongBeFavorited,
        playerState.songId,
    ) {
        if (isWatchOutputSelected && isPhoneConnected && canCurrentSongBeFavorited) {
            playerViewModel.refreshCurrentSongFavoriteState()
        }
    }

    val queueItems = (queueState as? BrowseUiState.Success)?.items.orEmpty()
    val upNextTitle = queueItems.drop(1).firstOrNull()?.title ?: "Nothing queued"
    val currentSongId = playerState.songId
    val favoriteActionEnabled = !playerState.isEmpty && when {
        isWatchOutputSelected -> canCurrentSongBeFavorited
        else -> isPhoneConnected && canCurrentSongBeFavorited
    }
    val playbackModeActionsEnabled = !playerState.isEmpty && (
        isWatchOutputSelected || isPhoneConnected
    )
    val isCurrentSongDownloaded = currentSongId.isNotBlank() && downloadedSongIds.contains(currentSongId)
    val isCurrentSongTransferring = activeTransfers.values.any {
        it.songId == currentSongId && it.status == WearTransferProgress.STATUS_TRANSFERRING
    }
    val canSaveCurrentSong = currentSongId.isNotBlank() &&
        !isWatchOutputSelected &&
        !isCurrentSongDownloaded &&
        !isCurrentSongTransferring

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.screenBackgroundColor()),
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            columnState = columnState,
        ) {
            item { Spacer(modifier = Modifier.height(2.dp)) }

            item {
                Text(
                    text = playerState.songTitle.ifBlank { "Song name" },
                    style = MaterialTheme.typography.title3,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Text(
                    text = playerState.artistName.ifBlank { "Artist name" },
                    style = MaterialTheme.typography.body2,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                )
            }

            item {
                MoreQuickActionsRow(
                    isFavorite = playerState.isFavorite,
                    isShuffleEnabled = playerState.isShuffleEnabled,
                    repeatMode = playerState.repeatMode,
                    favoriteEnabled = favoriteActionEnabled,
                    shuffleEnabled = playbackModeActionsEnabled,
                    repeatEnabled = playbackModeActionsEnabled,
                    onToggleFavorite = playerViewModel::toggleFavorite,
                    onToggleShuffle = playerViewModel::toggleShuffle,
                    onCycleRepeat = playerViewModel::cycleRepeat,
                )
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            item {
                MoreActionChip(
                    label = "Playlist",
                    subtitle = if (queueItems.isNotEmpty()) "Current queue" else "Queue unavailable",
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    onClick = onQueueClick,
                    enabled = isPhoneConnected && !isWatchOutputSelected,
                )
            }

            item {
                MoreActionChip(
                    label = "Up next",
                    subtitle = upNextTitle,
                    icon = Icons.Rounded.SkipNext,
                    onClick = onQueueClick,
                    enabled = isPhoneConnected && !isWatchOutputSelected,
                )
            }

            item {
                if (currentSongId.isNotBlank() && (!isCurrentSongDownloaded || isCurrentSongTransferring)) {
                    MoreActionChip(
                        label = if (isCurrentSongTransferring) {
                            "Transferring to watch"
                        } else {
                            "Transfer to watch"
                        },
                        subtitle = when {
                            isCurrentSongTransferring -> "Transfer in progress"
                            isWatchOutputSelected -> "Switch to phone output to transfer"
                            else -> "Save this song on watch"
                        },
                        icon = Icons.Rounded.LibraryAdd,
                        onClick = {
                            if (canSaveCurrentSong) {
                                downloadsViewModel.requestDownload(currentSongId)
                            }
                        },
                        enabled = canSaveCurrentSong,
                    )
                }
            }

            item {
                MoreActionChip(
                    label = "Settings",
                    subtitle = "Playback and devices",
                    icon = Icons.Rounded.Settings,
                    onClick = onSettingsClick,
                    enabled = true,
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
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
private fun MoreQuickActionsRow(
    isFavorite: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    favoriteEnabled: Boolean,
    shuffleEnabled: Boolean,
    repeatEnabled: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val palette = LocalWearPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoreQuickActionButton(
            modifier = Modifier.weight(0.3f),
            icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            active = isFavorite,
            activeColor = palette.favoriteActive,
            enabled = favoriteEnabled,
            onClick = onToggleFavorite,
            contentDescription = "Like",
        )
        MoreQuickActionButton(
            modifier = Modifier.weight(0.3f),
            icon = Icons.Rounded.Shuffle,
            active = isShuffleEnabled,
            activeColor = palette.shuffleActive,
            enabled = shuffleEnabled,
            onClick = onToggleShuffle,
            contentDescription = "Shuffle",
        )
        MoreQuickActionButton(
            modifier = Modifier.weight(0.3f),
            icon = if (repeatMode == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            active = repeatMode != 0,
            activeColor = palette.repeatActive,
            enabled = repeatEnabled,
            onClick = onCycleRepeat,
            contentDescription = "Repeat",
        )
    }
}

@Composable
private fun MoreQuickActionButton(
    modifier: Modifier,
    icon: ImageVector,
    active: Boolean,
    activeColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
) {
    val palette = LocalWearPalette.current
    val container by animateColorAsState(
        targetValue = when {
            !enabled -> palette.surfaceContainerHighestColor()
            active -> activeColor.copy(alpha = 0.88f)
            else -> palette.surfaceContainerHighColor()
        },
        animationSpec = spring(),
        label = "moreQuickActionContainer",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> palette.textSecondary
            active && activeColor.luminance() > 0.52f -> Color.Black
            active -> Color.White
            else -> palette.textPrimary
        },
        animationSpec = spring(),
        label = "moreQuickActionTint",
    )

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(container, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun MoreActionChip(
    label: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val palette = LocalWearPalette.current
    Chip(
        label = {
            Text(
                text = label,
                color = if (enabled) palette.textPrimary else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = {
            Text(
                text = subtitle,
                color = palette.textSecondary.copy(alpha = 0.84f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) palette.textSecondary else palette.textSecondary.copy(alpha = 0.72f),
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
        enabled = enabled,
        colors = ChipDefaults.chipColors(
            backgroundColor = if (enabled) palette.surfaceContainerColor() else palette.surfaceContainerHighestColor(),
            contentColor = palette.chipContent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    )
}
