package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.theveloper.pixelplay.data.WearDeviceTier
import com.theveloper.pixelplay.data.WearLifecycleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sin

/**
 * Small "now playing" indicator: a few bars that move with a cheap, low-frequency discrete tick
 * on devices with CPU headroom to spare ([WearDeviceTier.isCapable]), and a static shape
 * everywhere else. A prior version drove two continuous, per-display-frame `Animatable` tweens
 * here; on a 2-core Wear SoC that alone was enough main-thread load to starve ExoPlayer's
 * decode/render threads and cause audible playback stutter (confirmed via on-device profiling).
 */
@Composable
fun PlayingEqIcon(
    modifier: Modifier = Modifier,
    color: Color,
    isPlaying: Boolean = true,
    bars: Int = 3,
    minHeightFraction: Float = 0.28f,
    maxHeightFraction: Float = 1.0f,
    gapFraction: Float = 0.30f,
) {
    val isInteractive by WearLifecycleState.isInteractive.collectAsState(
        initial = WearLifecycleState.isInteractiveNow,
    )
    val animate = isPlaying && isInteractive && WearDeviceTier.isCapable

    var tick by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (isActive) {
            tick += 1f
            delay(TICK_INTERVAL_MS)
        }
    }

    val activity by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "wearPlayingEqActivity",
    )

    val speeds = remember(bars) { List(bars) { (it + 1).toFloat() } }
    val shifts = remember(bars) { List(bars) { i -> i * 0.9f } }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val tentativeBarWidth = width / (bars + (bars - 1) * (1f + gapFraction))
        val gap = tentativeBarWidth * gapFraction
        val barWidth = tentativeBarWidth
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

        repeat(bars) { i ->
            val eased = if (animate) {
                val waveform = (sin(tick * 0.5f + shifts[i] * speeds[i]) + 1f) * 0.5f
                waveform * waveform * (3 - 2 * waveform)
            } else {
                STATIC_BAR_EASED[i % STATIC_BAR_EASED.size]
            }

            val barsHeightFraction = minHeightFraction + (maxHeightFraction - minHeightFraction) * eased
            val barHeight = height * barsHeightFraction
            val dotHeight = barWidth
            val blendedHeight = dotHeight + (barHeight - dotHeight) * activity

            val top = (height - blendedHeight) / 2f
            val left = i * (barWidth + gap)

            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, blendedHeight),
                cornerRadius = corner,
            )
        }
    }
}

private const val TICK_INTERVAL_MS = 140L
private val STATIC_BAR_EASED = floatArrayOf(0.82f, 0.42f, 0.62f)
