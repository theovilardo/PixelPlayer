package com.theveloper.pixelplay.presentation.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.TransferState
import com.theveloper.pixelplay.presentation.components.AlwaysOnScalingPositionIndicator
import com.theveloper.pixelplay.presentation.components.PlayingEqIcon
import com.theveloper.pixelplay.presentation.components.WearTopTimeText
import com.theveloper.pixelplay.presentation.theme.surfaceContainerHighestColor
import com.theveloper.pixelplay.presentation.theme.LocalWearPalette
import com.theveloper.pixelplay.presentation.theme.screenBackgroundColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerHighColor
import com.theveloper.pixelplay.data.local.LocalSongEntity
import com.theveloper.pixelplay.presentation.viewmodel.WearDownloadsUiEvent
import com.theveloper.pixelplay.presentation.viewmodel.WearDownloadsViewModel
import com.theveloper.pixelplay.presentation.viewmodel.WearPlayerViewModel
import com.theveloper.pixelplay.shared.WearTransferProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/**
 * Screen showing songs stored locally on the watch.
 * Tapping a song starts local ExoPlayer playback.
 */
@Composable
fun DownloadsScreen(
    onPlaylistsClick: () -> Unit = {},
    onPlaybackStarted: () -> Unit = {},
    viewModel: WearDownloadsViewModel = hiltViewModel(),
    playerViewModel: WearPlayerViewModel = hiltViewModel(),
) {
    val localSongs by viewModel.localSongs.collectAsStateWithLifecycle()
    val activeTransfers by viewModel.activeTransfers.collectAsStateWithLifecycle()
    val deviceSongs by viewModel.deviceSongs.collectAsStateWithLifecycle()
    val isDeviceLibraryLoading by viewModel.isDeviceLibraryLoading.collectAsStateWithLifecycle()
    val deviceLibraryError by viewModel.deviceLibraryError.collectAsStateWithLifecycle()
    val pendingPhonePlaybackSongId by viewModel.pendingPhonePlaybackSongId.collectAsStateWithLifecycle()
    val playerState by playerViewModel.playerState.collectAsStateWithLifecycle()
    val isPhoneConnected by playerViewModel.isPhoneConnected.collectAsStateWithLifecycle()
    val palette = LocalWearPalette.current
    val watchLibraryTitleFont = rememberWatchLibraryTitleFont()
    val columnState = rememberResponsiveColumnState()
    val context = LocalContext.current
    val audioPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    var hasAudioPermission by remember {
        mutableStateOf(hasAudioLibraryPermission(context, audioPermission))
    }
    var selectedLocalSongForMenu by remember { mutableStateOf<LocalSongEntity?>(null) }
    var selectedLocalSongForDeleteConfirmation by remember { mutableStateOf<LocalSongEntity?>(null) }
    var selectedTransferForCancelConfirmation by remember { mutableStateOf<TransferState?>(null) }
    var inlineMessage by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
    }

    LaunchedEffect(Unit) {
        hasAudioPermission = hasAudioLibraryPermission(context, audioPermission)
    }
    LaunchedEffect(hasAudioPermission) {
        viewModel.refreshDeviceLibrary(hasPermission = hasAudioPermission)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is WearDownloadsUiEvent.Message -> inlineMessage = event.value
            }
        }
    }
    LaunchedEffect(inlineMessage) {
        val message = inlineMessage ?: return@LaunchedEffect
        delay(4_000L)
        if (inlineMessage == message) {
            inlineMessage = null
        }
    }
    LaunchedEffect(activeTransfers, selectedTransferForCancelConfirmation?.requestId) {
        val requestId = selectedTransferForCancelConfirmation?.requestId ?: return@LaunchedEffect
        val transfer = activeTransfers[requestId]
        if (transfer?.status != WearTransferProgress.STATUS_TRANSFERRING) {
            selectedTransferForCancelConfirmation = null
        }
    }
    val transferringStates = activeTransfers.values
        .filter { it.status == WearTransferProgress.STATUS_TRANSFERRING }
        .sortedByDescending { it.bytesTransferred }
    val failedTransfers = activeTransfers.values
        .filter {
            it.status == WearTransferProgress.STATUS_FAILED ||
                it.status == WearTransferProgress.STATUS_CANCELLED
        }
        .sortedBy { it.songTitle.lowercase() }
    val sectionAccentColor = MaterialTheme.colors.primary

    val background = palette.screenBackgroundColor()
    val surfaceContainer = palette.surfaceContainerColor()
    val elevatedSurfaceContainer = palette.surfaceContainerHighColor()

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
                    text = stringResource(R.string.wear_watch_library_title),
                    style = MaterialTheme.typography.title2,
                    fontFamily = watchLibraryTitleFont,
                    fontWeight = FontWeight(780),
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            item {
                Chip(
                    label = {
                        Text(
                            text = stringResource(R.string.wear_local_playlists_entry),
                            color = palette.textPrimary,
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = onPlaylistsClick,
                    colors = ChipDefaults.chipColors(
                        backgroundColor = surfaceContainer,
                        contentColor = palette.chipContent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            if (inlineMessage != null) {
                item {
                    Chip(
                        label = {
                            Text(
                                text = inlineMessage.orEmpty(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textPrimary,
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = palette.textError,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { inlineMessage = null },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = elevatedSurfaceContainer,
                            contentColor = palette.chipContent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }

            if (transferringStates.isNotEmpty()) {
                item {
                    DownloadsSectionHeader(
                        title = stringResource(R.string.wear_transferring_from_phone),
                        color = sectionAccentColor,
                    )
                }

                items(transferringStates.size) { index ->
                    val transfer = transferringStates[index]
                    val progressText = if (transfer.totalBytes > 0L) {
                        "${(transfer.progress * 100f).toInt().coerceIn(0, 100)}%"
                    } else {
                        context.getString(R.string.wear_transfer_starting)
                    }
                    Chip(
                        label = {
                            Text(
                                text = transfer.songTitle.ifBlank { context.getString(R.string.wear_preparing_transfer) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textPrimary,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = progressText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textSecondary.copy(alpha = 0.82f),
                            )
                        },
                        icon = {
                            CircularProgressIndicator(
                                indicatorColor = palette.shuffleActive,
                                trackColor = surfaceContainer,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                        onClick = { selectedTransferForCancelConfirmation = transfer },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = elevatedSurfaceContainer,
                            contentColor = palette.chipContent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (failedTransfers.isNotEmpty()) {
                item {
                    DownloadsSectionHeader(
                        title = stringResource(R.string.wear_transfer_issues),
                        color = palette.textError,
                    )
                }

                items(failedTransfers.size) { index ->
                    val transfer = failedTransfers[index]
                    val statusText = when (transfer.status) {
                        WearTransferProgress.STATUS_CANCELLED -> context.getString(R.string.wear_transfer_cancelled)
                        else -> transfer.error?.ifBlank { null } ?: context.getString(R.string.wear_transfer_failed)
                    }
                    Chip(
                        label = {
                            Text(
                                text = transfer.songTitle.ifBlank { context.getString(R.string.wear_transfer_failed) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textPrimary,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = statusText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textError.copy(alpha = 0.90f),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = stringResource(R.string.wear_transfer_dismiss_hint),
                                tint = palette.textError,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { viewModel.dismissTransfer(transfer.requestId) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = elevatedSurfaceContainer,
                            contentColor = palette.chipContent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (localSongs.isNotEmpty()) {
                item {
                    DownloadsSectionHeader(
                        title = stringResource(R.string.wear_saved_from_phone),
                        color = sectionAccentColor,
                    )
                }

                items(localSongs.size) { index ->
                    val song = localSongs[index]
                    val isCurrentSong = song.songId == playerState.songId && playerState.songId.isNotBlank()
                    val isPlayingSong = isCurrentSong && playerState.isPlaying
                    val secondaryText = if (
                        song.artist.isNotEmpty() ||
                        song.album.isNotEmpty() ||
                        song.duration > 0L
                    ) {
                        buildSongSubtitle(
                            artist = song.artist,
                            album = song.album,
                            durationMs = song.duration,
                        )
                    } else {
                        ""
                    }
                    DownloadedSongChip(
                        title = song.title,
                        secondaryText = secondaryText,
                        isCurrentSong = isCurrentSong,
                        isPlayingSong = isPlayingSong,
                        onClick = {
                            viewModel.playLocalSong(song.songId)
                        },
                        onMenuClick = {
                            selectedLocalSongForMenu = song
                        },
                    )
                }
            }

            if (deviceSongs.isNotEmpty()) {
                item {
                    DownloadsSectionHeader(
                        title = stringResource(R.string.wear_songs_watch_storage),
                        color = sectionAccentColor,
                        topPadding = 10.dp,
                    )
                }
            }

            if (!hasAudioPermission) {
                item {
                    Chip(
                        label = {
                            Text(
                                text = stringResource(R.string.wear_allow_audio_access),
                                color = palette.textPrimary,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = stringResource(R.string.wear_read_watch_library),
                                color = palette.textSecondary.copy(alpha = 0.8f),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Security,
                                contentDescription = null,
                                tint = palette.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { permissionLauncher.launch(audioPermission) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = surfaceContainer,
                            contentColor = palette.chipContent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (isDeviceLibraryLoading) {
                item {
                    Text(
                        text = stringResource(R.string.wear_scanning_watch_storage),
                        style = MaterialTheme.typography.body2,
                        color = palette.textSecondary.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            } else if (deviceLibraryError != null) {
                item {
                    Chip(
                        label = {
                            Text(
                                text = stringResource(R.string.wear_retry_scan),
                                color = palette.textPrimary,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = deviceLibraryError.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textSecondary.copy(alpha = 0.8f),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = palette.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { viewModel.refreshDeviceLibrary(hasPermission = true) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = surfaceContainer,
                            contentColor = palette.chipContent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (deviceSongs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.wear_no_local_songs),
                        style = MaterialTheme.typography.body2,
                        color = palette.textSecondary.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            } else {
                items(deviceSongs.size) { index ->
                    val song = deviceSongs[index]
                    val isCurrentSong = song.songId == playerState.songId && playerState.songId.isNotBlank()
                    val isPlayingSong = isCurrentSong && playerState.isPlaying
                    val secondaryText = if (song.artist.isNotEmpty() || song.album.isNotEmpty()) {
                        buildSongSubtitle(
                            artist = song.artist,
                            album = song.album,
                            durationMs = song.durationMs,
                        )
                    } else {
                        ""
                    }
                    Chip(
                        label = {
                            Text(
                                text = song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.textPrimary,
                            )
                        },
                        secondaryLabel = if (secondaryText.isNotEmpty() || isCurrentSong) {
                            {
                                val statusWord = stringResource(
                                    if (isPlayingSong) R.string.wear_status_playing else R.string.wear_status_current
                                )
                                Text(
                                    text = if (isCurrentSong) {
                                        if (secondaryText.isNotEmpty()) {
                                            "$statusWord · $secondaryText"
                                        } else {
                                            statusWord
                                        }
                                    } else {
                                        secondaryText
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrentSong && isPlayingSong) {
                                        palette.shuffleActive.copy(alpha = 0.90f)
                                    } else {
                                        palette.textSecondary.copy(alpha = 0.78f)
                                    },
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
                        onClick = {
                            viewModel.playDeviceSong(song.songId)
                        },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (isCurrentSong) elevatedSurfaceContainer else surfaceContainer,
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

        val menuSong = selectedLocalSongForMenu
        if (menuSong != null) {
            val isCurrentSong = menuSong.songId == playerState.songId && playerState.songId.isNotBlank()
            val isPlayingSong = isCurrentSong && playerState.isPlaying
            DownloadedSongActionScreen(
                song = menuSong,
                canPlayOnPhone = isPhoneConnected,
                isPhonePlaybackPending = pendingPhonePlaybackSongId == menuSong.songId,
                isCurrentWatchSong = isCurrentSong,
                isPlayingWatchSong = isPlayingSong,
                onDismiss = { selectedLocalSongForMenu = null },
                onPlayOnWatch = {
                    viewModel.playLocalSong(menuSong.songId)
                    selectedLocalSongForMenu = null
                    onPlaybackStarted()
                },
                onPlayOnPhone = {
                    viewModel.playSongOnPhone(menuSong.songId)
                    selectedLocalSongForMenu = null
                    onPlaybackStarted()
                },
                onDeleteFromWatch = {
                    selectedLocalSongForMenu = null
                    selectedLocalSongForDeleteConfirmation = menuSong
                },
            )
        }

        val confirmDeleteSong = selectedLocalSongForDeleteConfirmation
        if (confirmDeleteSong != null) {
            ConfirmDeleteDownloadedSongScreen(
                song = confirmDeleteSong,
                onDismiss = { selectedLocalSongForDeleteConfirmation = null },
                onConfirm = {
                    viewModel.deleteSong(confirmDeleteSong.songId)
                    selectedLocalSongForDeleteConfirmation = null
                },
            )
        }

        val confirmCancelTransfer = selectedTransferForCancelConfirmation
        if (confirmCancelTransfer != null) {
            ConfirmCancelTransferScreen(
                transfer = confirmCancelTransfer,
                onDismiss = { selectedTransferForCancelConfirmation = null },
                onConfirm = {
                    viewModel.cancelTransfer(confirmCancelTransfer.requestId)
                    selectedTransferForCancelConfirmation = null
                },
            )
        }
    }
}

@Composable
private fun DownloadsSectionHeader(
    title: String,
    color: Color,
    topPadding: androidx.compose.ui.unit.Dp = 6.dp,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.caption2,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 2.dp),
    )
}

@Composable
private fun DownloadedSongChip(
    title: String,
    secondaryText: String,
    isCurrentSong: Boolean,
    isPlayingSong: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    val palette = LocalWearPalette.current
    val containerColor = if (isCurrentSong) {
        palette.surfaceContainerHighColor()
    } else {
        palette.surfaceContainerColor()
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Chip(
            label = {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.textPrimary,
                )
            },
            secondaryLabel = if (secondaryText.isNotEmpty() || isCurrentSong) {
                {
                    val statusWord = stringResource(
                        if (isPlayingSong) R.string.wear_status_playing else R.string.wear_status_current
                    )
                    Text(
                        text = if (isCurrentSong) {
                            if (secondaryText.isNotEmpty()) {
                                "$statusWord · $secondaryText"
                            } else {
                                statusWord
                            }
                        } else {
                            secondaryText
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isCurrentSong && isPlayingSong) {
                            palette.shuffleActive.copy(alpha = 0.90f)
                        } else {
                            palette.textSecondary.copy(alpha = 0.78f)
                        },
                    )
                }
            } else {
                null
            },
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
            onClick = onClick,
            colors = ChipDefaults.chipColors(
                backgroundColor = containerColor,
                contentColor = palette.chipContent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 40.dp),
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(34.dp)
                .background(
                    color = palette.surfaceContainerHighColor().copy(alpha = 0.74f),
                    shape = CircleShape,
                )
                .clickable(onClick = onMenuClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.wear_cd_more_options),
                tint = palette.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DownloadedSongActionScreen(
    song: LocalSongEntity,
    canPlayOnPhone: Boolean,
    isPhonePlaybackPending: Boolean,
    isCurrentWatchSong: Boolean,
    isPlayingWatchSong: Boolean,
    onDismiss: () -> Unit,
    onPlayOnWatch: () -> Unit,
    onPlayOnPhone: () -> Unit,
    onDeleteFromWatch: () -> Unit,
) {
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()
    val subtitle = buildSongSubtitle(song.artist, song.album, song.duration)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.screenBackgroundColor())
            .zIndex(12f),
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            columnState = columnState,
        ) {
            item { Spacer(modifier = Modifier.height(18.dp)) }

            item {
                Text(
                    text = song.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.title3,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
            }

            if (subtitle.isNotEmpty()) {
                item {
                    Text(
                        text = subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.caption2,
                        color = palette.textSecondary.copy(alpha = 0.82f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                    )
                }
            }

            item {
                DownloadsActionChip(
                    icon = Icons.Rounded.PlayArrow,
                    label = when {
                        isCurrentWatchSong && isPlayingWatchSong -> stringResource(R.string.wear_playing_on_watch)
                        isCurrentWatchSong -> stringResource(R.string.wear_current_on_watch)
                        else -> stringResource(R.string.wear_play_on_watch)
                    },
                    backgroundColor = palette.shuffleActive.copy(alpha = 0.38f),
                    onClick = onPlayOnWatch,
                )
            }

            item {
                DownloadsActionChip(
                    icon = Icons.Rounded.PhoneAndroid,
                    label = when {
                        isPhonePlaybackPending -> stringResource(R.string.wear_starting_on_phone)
                        !canPlayOnPhone -> stringResource(R.string.wear_phone_disconnected)
                        else -> stringResource(R.string.wear_play_on_phone)
                    },
                    backgroundColor = if (canPlayOnPhone && !isPhonePlaybackPending) {
                        palette.repeatActive.copy(alpha = 0.38f)
                    } else {
                        palette.surfaceContainerHighestColor()
                    },
                    enabled = canPlayOnPhone && !isPhonePlaybackPending,
                    onClick = onPlayOnPhone,
                )
            }

            item {
                DownloadsActionChip(
                    icon = Icons.Rounded.Delete,
                    label = stringResource(R.string.wear_delete_from_watch),
                    backgroundColor = palette.favoriteActive.copy(alpha = 0.38f),
                    onClick = onDeleteFromWatch,
                )
            }

            item {
                DownloadsActionChip(
                    icon = Icons.Rounded.Close,
                    label = stringResource(R.string.wear_back),
                    backgroundColor = palette.surfaceContainerColor(),
                    onClick = onDismiss,
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

@Composable
private fun ConfirmDeleteDownloadedSongScreen(
    song: LocalSongEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.screenBackgroundColor())
            .zIndex(12f),
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            columnState = columnState,
        ) {
            item { Spacer(modifier = Modifier.height(18.dp)) }

            item {
                Text(
                    text = stringResource(R.string.wear_delete_from_watch_confirm_title),
                    style = MaterialTheme.typography.title3,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
            }

            item {
                Text(
                    text = song.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.body2,
                    color = palette.textSecondary.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
            }

            item {
                Text(
                    text = stringResource(R.string.wear_delete_watch_copy_message),
                    style = MaterialTheme.typography.caption2,
                    color = palette.textSecondary.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            item {
                DownloadsActionChip(
                    icon = Icons.Rounded.Delete,
                    label = stringResource(R.string.wear_delete),
                    backgroundColor = palette.favoriteActive.copy(alpha = 0.38f),
                    onClick = onConfirm,
                )
            }

            item {
                DownloadsActionChip(
                    icon = Icons.Rounded.Close,
                    label = stringResource(R.string.wear_cancel),
                    backgroundColor = palette.surfaceContainerColor(),
                    onClick = onDismiss,
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

@Composable
private fun ConfirmCancelTransferScreen(
    transfer: TransferState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.screenBackgroundColor())
            .zIndex(12f),
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            columnState = columnState,
        ) {
            item { Spacer(modifier = Modifier.height(18.dp)) }

            item {
                Text(
                    text = stringResource(R.string.wear_cancel_transfer_title),
                    style = MaterialTheme.typography.title3,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
            }

            item {
                Text(
                    text = transfer.songTitle.ifBlank { context.getString(R.string.wear_current_transfer_fallback) },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.body2,
                    color = palette.textSecondary.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
            }

            item {
                Text(
                    text = stringResource(R.string.wear_cancel_transfer_body),
                    style = MaterialTheme.typography.caption2,
                    color = palette.textSecondary.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            item {
                DownloadsActionChip(
                    icon = Icons.Rounded.Close,
                    label = stringResource(R.string.wear_yes_cancel_transfer),
                    backgroundColor = MaterialTheme.colors.error,
                    contentColor = MaterialTheme.colors.onError,
                    onClick = onConfirm,
                )
            }

            item {
                DownloadsActionChip(
                    icon = Icons.Rounded.PlayArrow,
                    label = stringResource(R.string.wear_keep_sending),
                    backgroundColor = palette.surfaceContainerColor(),
                    onClick = onDismiss,
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

@Composable
private fun DownloadsActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    backgroundColor: androidx.compose.ui.graphics.Color,
    contentColor: Color = LocalWearPalette.current.textPrimary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalWearPalette.current
    val disabledContentColor = palette.textSecondary.copy(alpha = 0.72f)
    Chip(
        label = {
            Text(
                text = label,
                color = if (enabled) contentColor else disabledContentColor,
            )
        },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) contentColor else disabledContentColor,
                modifier = Modifier.size(18.dp),
            )
        },
        onClick = { if (enabled) onClick() },
        colors = ChipDefaults.chipColors(
            backgroundColor = backgroundColor,
            contentColor = palette.chipContent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun hasAudioLibraryPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun buildSongSubtitle(artist: String, album: String, durationMs: Long): String {
    val parts = buildList {
        if (artist.isNotBlank()) add(artist)
        if (album.isNotBlank()) add(album)
        if (durationMs > 0L) add(formatDuration(durationMs))
    }
    return parts.joinToString(" · ")
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun rememberWatchLibraryTitleFont(): FontFamily {
    return remember {
        FontFamily(
            Font(
                resId = R.font.gflex_variable,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(710),
                    FontVariation.width(132f),
                    FontVariation.Setting("ROND", 82f),
                    FontVariation.Setting("XTRA", 540f),
                    FontVariation.Setting("YOPQ", 92f),
                    FontVariation.Setting("YTLC", 512f),
                ),
            ),
        )
    }
}
