package com.theveloper.pixelplay.presentation.components.subcomps

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.sin

/**
 * Composable que dibuja una línea horizontal con ondulación senoidal.
 *
 * @param modifier Modificador para el Composable.
 * @param color Color de la línea.
 * @param alpha Opacidad (0f..1f).
 * @param strokeWidth Grosor de la línea (Dp).
 * @param amplitude Amplitud de la onda (Dp) — la altura máxima desde el centro.
 * @param wavesDensity Density of wave (float) - as the number in standard screen width 380dp
 * @param phase Desplazamiento de fase estático (radianes). Se usa solo si animate = false.
 * @param animate Si es true, activa una animación de desplazamiento infinita.
 * @param animationDurationMillis Duración en milisegundos de un ciclo completo de animación.
 * @param samples Cantidad de puntos usados para dibujar la curva (más = más suave).
 * @param cap Tipo de extremo de la línea (Round, Butt, Square).
 */
@Composable
fun SineWaveLine(
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    alpha: Float = 1f,
    strokeWidth: Dp = 2.dp,
    amplitude: Dp = 8.dp,
    wavesDensity: Float = 7.6f,
    phase: Float = 0f,
    animate: Boolean? = false,
    animationDurationMillis: Int = 2000,
    samples: Int = 400,
    cap: StrokeCap = StrokeCap.Round
) {
    val density = LocalDensity.current

    // Only allocate an infinite transition when animation is enabled.
    val currentPhase = if (animate == true) {
        val infiniteTransition = rememberInfiniteTransition(label = "SineWaveAnimation")
        val animatedPhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDurationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phaseAnimation"
        )
        animatedPhase
    } else {
        phase
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f

        // Convertimos dp a px dentro del scope de dibujo para eficiencia
        val strokePx = with(density) { strokeWidth.toPx() }
        val ampPx = with(density) { amplitude.toPx() }

        if (w <= 0f || samples < 2) return@Canvas

        // Construimos el path senoidal usando la fase actual (animada o estática)
        val path = Path().apply {
            val step = w / (samples - 1)
            // Usamos currentPhase para el punto inicial
            moveTo(0f, centerY + (ampPx * sin(currentPhase)))
            for (i in 1 until samples) {
                val x = i * step
                // theta recorre 0..(2π * wavesDensity)
                val theta = (x / w) * (2f * PI.toFloat() * (wavesDensity) * size.width / 380.dp.toPx()) + currentPhase
                val y = centerY + ampPx * sin(theta)
                lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokePx,
                cap = cap,
                join = StrokeJoin.Round
            ),
            alpha = alpha
        )
    }
}

/**
 * Ejemplo de uso estático:
 *
 * SineWaveLine(
 *     modifier = Modifier
 *         .fillMaxWidth()
 *         .height(28.dp),
 *     color = Color(0xFF00AEEF),
 *     alpha = 0.95f,
 *     strokeWidth = 3.dp,
 *     amplitude = 10.dp,
 *     waves = 1.6f
 * )
 */
