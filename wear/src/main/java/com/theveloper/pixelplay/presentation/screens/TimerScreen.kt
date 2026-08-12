package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.WearPlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.WearSleepTimerMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer

private val TIMER_PRESETS_MINUTES = listOf(5, 10, 20, 30, 45, 60)

@Composable
fun TimerScreen(
    viewModel: WearPlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val palette = LocalWearPalette.current
    val timerState by viewModel.sleepTimerUiState.collectAsStateWithLifecycle()
    val isPhoneConnected by viewModel.isPhoneConnected.collectAsStateWithLifecycle()
    val isWatchOutputSelected by viewModel.isWatchOutputSelected.collectAsStateWithLifecycle()
    val enabled = isPhoneConnected && !isWatchOutputSelected
    val columnState = rememberResponsiveColumnState()

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
            item { Spacer(modifier = Modifier.height(20.dp)) }

            item {
                Text(
                    text = stringResource(R.string.wear_sleep_timer_title),
                    style = MaterialTheme.typography.title3,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Text(
                    text = when (timerState.mode) {
                        WearSleepTimerMode.DURATION -> context.getString(
                            R.string.wear_timer_active_duration,
                            timerState.durationMinutes
                        )
                        WearSleepTimerMode.END_OF_TRACK -> context.getString(R.string.wear_timer_active_end_track)
                        WearSleepTimerMode.OFF -> context.getString(R.string.wear_timer_off_status)
                    },
                    style = MaterialTheme.typography.caption2,
                    color = palette.textSecondary.copy(alpha = 0.82f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 4.dp),
                )
            }

            if (!enabled) {
                item {
                    Text(
                        text = if (!isPhoneConnected) {
                            context.getString(R.string.wear_connect_phone_for_timer)
                        } else {
                            context.getString(R.string.wear_switch_output_to_phone)
                        },
                        style = MaterialTheme.typography.body2,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }
            } else {
                items(TIMER_PRESETS_MINUTES.size) { index ->
                    val minutes = TIMER_PRESETS_MINUTES[index]
                    TimerOptionChip(
                        icon = Icons.Rounded.Timer,
                        label = context.getString(R.string.wear_timer_minutes_format, minutes),
                        active = timerState.mode == WearSleepTimerMode.DURATION &&
                            timerState.durationMinutes == minutes,
                        activeColor = palette.shuffleActive,
                        onClick = {
                            viewModel.setSleepTimerDuration(minutes)
                        },
                    )
                }

                item {
                    TimerOptionChip(
                        icon = Icons.Rounded.Schedule,
                        label = stringResource(R.string.wear_end_of_track),
                        active = timerState.mode == WearSleepTimerMode.END_OF_TRACK,
                        activeColor = palette.repeatActive,
                        onClick = {
                            viewModel.setSleepTimerEndOfTrack(true)
                        },
                    )
                }

                item {
                    TimerOptionChip(
                        icon = Icons.Rounded.Close,
                        label = stringResource(R.string.wear_turn_off),
                        active = timerState.mode == WearSleepTimerMode.OFF,
                        activeColor = palette.favoriteActive,
                        onClick = {
                            viewModel.cancelSleepTimer()
                        },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(18.dp)) }
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
private fun TimerOptionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
) {
    val palette = LocalWearPalette.current
    val container by animateColorAsState(
        targetValue = if (active) activeColor.copy(alpha = 0.85f) else palette.surfaceContainerColor(),
        animationSpec = spring(),
        label = "timerOptionContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            if (activeColor.luminance() > 0.52f) Color.Black else Color.White
        } else {
            palette.chipContent
        },
        animationSpec = spring(),
        label = "timerOptionContent",
    )

    Chip(
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentColor,
            )
        },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
        },
        onClick = onClick,
        colors = ChipDefaults.chipColors(
            backgroundColor = container,
            contentColor = contentColor,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    )
}
