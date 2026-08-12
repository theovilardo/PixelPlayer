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
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
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
import com.theveloper.pixelplay.data.WearAudioOutputRoute
import com.theveloper.pixelplay.data.WearLifecycleState
import com.theveloper.pixelplay.R
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import com.theveloper.pixelplay.data.WearOutputTarget
import com.theveloper.pixelplay.presentation.components.AlwaysOnScalingPositionIndicator
import com.theveloper.pixelplay.presentation.components.outputRouteIcon
import com.theveloper.pixelplay.presentation.components.WearTopTimeText
import com.theveloper.pixelplay.presentation.theme.LocalWearPalette
import com.theveloper.pixelplay.presentation.theme.screenBackgroundColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerHighestColor
import com.theveloper.pixelplay.presentation.viewmodel.WearPlayerViewModel
import com.theveloper.pixelplay.shared.WearPlayerState
import com.theveloper.pixelplay.shared.WearVolumeState
import kotlinx.coroutines.delay

@Composable
fun OutputScreen(
    viewModel: WearPlayerViewModel = hiltViewModel(),
) {
    val outputTarget by viewModel.outputTarget.collectAsStateWithLifecycle()
    val isPhoneConnected by viewModel.isPhoneConnected.collectAsStateWithLifecycle()
    val canCurrentSongPlayOnWatch by viewModel.canCurrentSongPlayOnWatch.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val phoneVolumeState by viewModel.phoneVolumeState.collectAsStateWithLifecycle()
    val watchAudioRoutes by viewModel.watchAudioRoutes.collectAsStateWithLifecycle()
    val watchVolumeState by viewModel.watchVolumeState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()
    val phoneRouteType = phoneVolumeState.routeType.ifBlank { WearVolumeState.ROUTE_TYPE_PHONE }
    val phoneRouteName = phoneVolumeState.routeName.ifBlank { context.getString(R.string.wear_phone) }
    val canSwitchToWatch = canCurrentSongPlayOnWatch || outputTarget == WearOutputTarget.WATCH

    DisposableEffect(viewModel) {
        viewModel.setWatchRouteDiscoveryEnabled(true)
        onDispose {
            viewModel.setWatchRouteDiscoveryEnabled(false)
        }
    }
    // The MediaRouter callback in WearVolumeRepository pushes route/volume changes
    // reactively while discovery is on (DisposableEffect above). This loop is just a
    // slow safety net and pauses whenever the screen turns off.
    LaunchedEffect(viewModel) {
        WearLifecycleState.isInteractive.collect { interactive ->
            if (!interactive) return@collect
            while (WearLifecycleState.isInteractiveNow) {
                viewModel.refreshWatchAudioState()
                delay(2_500L)
            }
        }
    }

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
                    text = stringResource(R.string.wear_device),
                    style = MaterialTheme.typography.title2,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            item {
                Text(
                    text = stringResource(R.string.wear_available_outputs),
                    style = MaterialTheme.typography.caption2,
                    color = palette.textSecondary.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            item {
                OutputTargetChip(
                    label = context.getString(R.string.wear_phone),
                    subtitle = when {
                        !isPhoneConnected -> context.getString(R.string.wear_phone_disconnected)
                        outputTarget == WearOutputTarget.PHONE -> context.getString(
                            R.string.wear_controlling_route,
                            phoneRouteName
                        )
                        playerState.songId.isBlank() -> context.getString(R.string.wear_switch_to_phone_playback)
                        else -> context.getString(R.string.wear_switch_current_song_to_route, phoneRouteName)
                    },
                    icon = outputRouteIcon(phoneRouteType),
                    selected = outputTarget == WearOutputTarget.PHONE,
                    enabled = isPhoneConnected,
                    onClick = { viewModel.selectOutput(WearOutputTarget.PHONE) },
                )
            }

            if (watchAudioRoutes.isEmpty()) {
                item {
                    OutputTargetChip(
                        label = watchVolumeState.routeName.ifBlank { context.getString(R.string.wear_watch_speaker) },
                        subtitle = when {
                            outputTarget == WearOutputTarget.WATCH && playerState.isPlaying -> context.getString(R.string.wear_playing_on_watch)
                            outputTarget == WearOutputTarget.WATCH -> context.getString(R.string.wear_watch_selected)
                            canSwitchToWatch -> context.getString(R.string.wear_switch_song_to_watch)
                            playerState.songId.isBlank() -> context.getString(R.string.wear_play_song_first)
                            else -> context.getString(R.string.wear_save_song_on_watch_first)
                        },
                        icon = Icons.Rounded.Watch,
                        selected = outputTarget == WearOutputTarget.WATCH,
                        enabled = canSwitchToWatch,
                        onClick = { viewModel.selectOutput(WearOutputTarget.WATCH) },
                    )
                }
            } else {
                watchAudioRoutes.forEach { route ->
                    item {
                        OutputTargetChip(
                            label = route.name,
                            subtitle = watchOutputSubtitle(
                                context = context,
                                route = route,
                                outputTarget = outputTarget,
                                playerState = playerState,
                                canSwitchToWatch = canSwitchToWatch,
                            ),
                            icon = outputRouteIcon(route.routeType),
                            selected = outputTarget == WearOutputTarget.WATCH && route.isActive,
                            enabled = canSwitchToWatch,
                            onClick = { viewModel.selectWatchOutput(route.id) },
                        )
                    }
                }
            }

            item {
                OutputTargetChip(
                    label = context.getString(R.string.wear_bluetooth_devices),
                    subtitle = if (watchAudioRoutes.any { it.isBluetooth }) {
                        context.getString(R.string.wear_find_headset)
                    } else {
                        context.getString(R.string.wear_connect_headphones_watch)
                    },
                    icon = Icons.Rounded.Bluetooth,
                    selected = false,
                    enabled = true,
                    onClick = viewModel::openWatchOutputPicker,
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

private fun watchOutputSubtitle(
    context: Context,
    route: WearAudioOutputRoute,
    outputTarget: WearOutputTarget,
    playerState: WearPlayerState,
    canSwitchToWatch: Boolean,
): String {
    return when {
        route.isActive && outputTarget == WearOutputTarget.WATCH && playerState.isPlaying ->
            context.getString(R.string.wear_playing_on_watch)
        route.isActive && outputTarget == WearOutputTarget.WATCH ->
            context.getString(R.string.wear_selected_on_watch)
        route.isConnecting ->
            context.getString(R.string.wear_connecting)
        route.isConnected && outputTarget == WearOutputTarget.WATCH ->
            context.getString(R.string.wear_connected_to_watch)
        !canSwitchToWatch && playerState.songId.isBlank() ->
            context.getString(R.string.wear_play_song_first)
        !canSwitchToWatch ->
            context.getString(R.string.wear_save_song_on_watch_first)
        route.isConnected ->
            context.getString(R.string.wear_switch_current_song_to_route, route.name)
        else ->
            context.getString(R.string.wear_connect_play_watch)
    }
}

@Composable
private fun OutputTargetChip(
    label: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalWearPalette.current
    val containerColor = when {
        !enabled -> palette.surfaceContainerHighestColor()
        selected -> palette.controlContainer.copy(alpha = 0.95f)
        else -> palette.surfaceContainerColor()
    }
    val contentColor = when {
        !enabled -> palette.textSecondary
        selected -> palette.controlContent
        else -> palette.textPrimary
    }
    val secondaryColor = when {
        !enabled -> palette.textSecondary.copy(alpha = 0.82f)
        selected -> palette.controlContent.copy(alpha = 0.76f)
        else -> palette.textSecondary.copy(alpha = 0.80f)
    }

    Chip(
        label = {
            Text(
                text = label,
                color = contentColor,
            )
        },
        secondaryLabel = {
            Text(
                text = subtitle,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
        enabled = enabled,
        colors = ChipDefaults.chipColors(
            backgroundColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
