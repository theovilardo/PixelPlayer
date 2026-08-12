@file:OptIn(ExperimentalWearFoundationApi::class)

package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.horologist.audio.ui.VolumeUiState
import com.google.android.horologist.audio.ui.volumeRotaryBehavior
import com.theveloper.pixelplay.data.WearLifecycleState
import com.theveloper.pixelplay.presentation.components.CurvedVolumeIndicator
import com.theveloper.pixelplay.presentation.components.WearTopTimeText
import com.theveloper.pixelplay.presentation.theme.LocalWearPalette
import com.theveloper.pixelplay.presentation.theme.screenBackgroundColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerColor
import com.theveloper.pixelplay.presentation.theme.surfaceContainerHighColor
import com.theveloper.pixelplay.presentation.viewmodel.WearPlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun VolumeScreen(
    viewModel: WearPlayerViewModel = hiltViewModel(),
) {
    val palette = LocalWearPalette.current
    val volumeState by viewModel.activeVolumeState.collectAsStateWithLifecycle()
    val volumePercent by viewModel.activeVolumePercent.collectAsStateWithLifecycle()
    val activeDeviceName by viewModel.activeVolumeDeviceName.collectAsStateWithLifecycle()

    // Enable MediaRouter discovery while this screen is visible so the
    // route-callback path in WearVolumeRepository pushes updates reactively.
    DisposableEffect(viewModel) {
        viewModel.setWatchRouteDiscoveryEnabled(true)
        viewModel.refreshActiveVolumeState()
        onDispose { viewModel.setWatchRouteDiscoveryEnabled(false) }
    }
    // Slow safety-net poll for volume changes that the route callback might miss
    // (e.g. system-wide hard-key presses on some Wear builds). 1.5s is plenty
    // for a UI knob; it pauses immediately when the screen turns off.
    LaunchedEffect(viewModel) {
        WearLifecycleState.isInteractive.collect { interactive ->
            if (!interactive) return@collect
            while (WearLifecycleState.isInteractiveNow) {
                viewModel.refreshActiveVolumeState()
                delay(1_500L)
            }
        }
    }
    val progressTarget = if (volumeState.max > 0) {
        (volumeState.level.toFloat() / volumeState.max.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val progress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = spring(),
        label = "volumeProgress",
    )
    val background = palette.screenBackgroundColor()
    val rotaryFocusRequester = remember { FocusRequester() }
    val rotaryVolumeUiState = remember(volumeState.level, volumeState.max) {
        VolumeUiState(
            current = volumeState.level.coerceAtLeast(0),
            max = volumeState.max.coerceAtLeast(0),
            min = 0,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .requestFocusOnHierarchyActive()
            .rotaryScrollable(
                behavior = volumeRotaryBehavior(
                    volumeUiStateProvider = { rotaryVolumeUiState },
                    onRotaryVolumeInput = viewModel::setActiveVolume,
                ),
                focusRequester = rotaryFocusRequester,
            )
            .background(background),
    ) {
        CurvedVolumeIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 30.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            VolumeStepButton(
                icon = Icons.Rounded.Add,
                contentDescription = "Volume up",
                onClick = viewModel::volumeUp,
            )

            VolumeValuePill(
                level = volumeState.level,
                percent = volumePercent,
                deviceName = activeDeviceName,
            )

            VolumeStepButton(
                icon = Icons.Rounded.Remove,
                contentDescription = "Volume down",
                onClick = viewModel::volumeDown,
            )
        }

        WearTopTimeText(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(5f),
            color = palette.textPrimary,
        )
    }
}

@Composable
private fun VolumeValuePill(
    level: Int,
    percent: Int,
    deviceName: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWearPalette.current
    val container = palette.chipContent
    val icon = if (level <= 0) {
        Icons.AutoMirrored.Rounded.VolumeOff
    } else {
        Icons.AutoMirrored.Rounded.VolumeUp
    }

    Row(
        modifier = modifier
            .width(150.dp)
            .height(64.dp)
            .background(container, RoundedCornerShape(32.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.controlContent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = deviceName,
                color = palette.controlContent,
                style = MaterialTheme.typography.title3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VolumeStepButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWearPalette.current
    val container by animateColorAsState(
        targetValue = palette.surfaceContainerColor().copy(alpha = 0.98f),
        animationSpec = spring(),
        label = "volumeStepContainer",
    )

    Box(
        modifier = modifier
            .width(92.dp)
            .height(42.dp)
            .clip(CircleShape)
            .background(container, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = palette.chipContent,
            modifier = Modifier.size(22.dp),
        )
    }
}
