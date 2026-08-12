package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.WearLifecycleState
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun PlayingEqIcon(
    modifier: Modifier = Modifier,
    color: Color,
    isPlaying: Boolean = true,
    bars: Int = 3,
    minHeightFraction: Float = 0.28f,
    maxHeightFraction: Float = 1.0f,
    phaseDurationMillis: Int = 2400,
    wanderDurationMillis: Int = 8000,
    gapFraction: Float = 0.30f,
) {
    val fullRotation = (2f * PI).toFloat()
    val phaseAnim = remember { Animatable(0f) }
    val wanderAnim = remember { Animatable(0f) }
    val isInteractive by WearLifecycleState.isInteractive.collectAsStateWithLifecycle(
        initialValue = WearLifecycleState.isInteractiveNow,
    )
    val animate = isPlaying && isInteractive

    LaunchedEffect(animate, phaseDurationMillis) {
        if (!animate) return@LaunchedEffect
        while (isActive) {
            val start = (phaseAnim.value % fullRotation).let { if (it < 0f) it + fullRotation else it }
            phaseAnim.snapTo(start)
            phaseAnim.animateTo(
                targetValue = start + fullRotation,
                animationSpec = tween(durationMillis = phaseDurationMillis, easing = LinearEasing),
            )
        }
    }

    LaunchedEffect(animate, wanderDurationMillis) {
        if (!animate) return@LaunchedEffect
        while (isActive) {
            val start = (wanderAnim.value % fullRotation).let { if (it < 0f) it + fullRotation else it }
            wanderAnim.snapTo(start)
            wanderAnim.animateTo(
                targetValue = start + fullRotation,
                animationSpec = tween(durationMillis = wanderDurationMillis, easing = LinearEasing),
            )
        }
    }

    val phase = phaseAnim.value
    val wander = wanderAnim.value

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
            val slowShift = 0.6f * sin(wander + i * 0.4f)
            val slowAmplitude = 0.85f + 0.15f * sin(wander * 0.5f + 1.1f + i * 0.3f)

            val waveform = (sin(phase * speeds[i] + shifts[i] + slowShift) * slowAmplitude + 1f) * 0.5f
            val eased = waveform * waveform * (3 - 2 * waveform)

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
