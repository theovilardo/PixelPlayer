package com.theveloper.pixelplay.presentation.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Colors
import com.theveloper.pixelplay.shared.WearThemePalette
import kotlin.math.max
import kotlin.math.min

@Immutable
data class WearPalette(
    val gradientTop: Color,
    val gradientMiddle: Color,
    val gradientBottom: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textError: Color,
    val controlContainer: Color,
    val controlContent: Color,
    val controlDisabledContainer: Color,
    val controlDisabledContent: Color,
    val transportContainer: Color,
    val transportContent: Color,
    val chipContainer: Color,
    val chipContent: Color,
    val favoriteActive: Color,
    val shuffleActive: Color,
    val repeatActive: Color,
)

private val DefaultWearPalette = WearPalette(
    gradientTop = Color(0xFF6C3AD8),
    gradientMiddle = Color(0xFF2C1858),
    gradientBottom = Color(0xFF130B23),
    surfaceContainerLowest = Color(0xFF171224),
    surfaceContainerLow = Color(0xFF211A30),
    surfaceContainer = Color(0xFF2A2140),
    surfaceContainerHigh = Color(0xFF33274D),
    surfaceContainerHighest = Color(0xFF3D2E5B),
    textPrimary = Color(0xFFF4EEFF),
    textSecondary = Color(0xFFE1D5FF),
    textError = Color(0xFFFFB7C5),
    controlContainer = Color(0xFFF0E7FF).copy(alpha = 0.96f),
    controlContent = Color(0xFF24114A),
    controlDisabledContainer = Color(0xFF3D2E5B),
    controlDisabledContent = bestContrastContent(Color(0xFF3D2E5B)),
    transportContainer = Color(0xFFE6DBFF).copy(alpha = 0.95f),
    transportContent = Color(0xFF2C0C62),
    chipContainer = Color(0xFF2A2140),
    chipContent = Color(0xFFE8E0FF),
    favoriteActive = Color(0xFFF1608E),
    shuffleActive = Color(0xFF44CDC4),
    repeatActive = Color(0xFF70A6FF),
)

val LocalWearPalette = staticCompositionLocalOf { DefaultWearPalette }

fun WearPalette.radialBackgroundBrush(): Brush = Brush.radialGradient(
    colorStops = arrayOf(
        0.0f to gradientTop,
        0.40f to gradientMiddle,
        0.74f to gradientBottom,
        0.92f to Color.Black,
        1.0f to Color.Black,
    ),
)

fun WearPalette.screenBackgroundColor(): Color = Color.Black
fun WearPalette.surfaceContainerLowestColor(): Color = surfaceContainerLowest
fun WearPalette.surfaceContainerLowColor(): Color = surfaceContainerLow
fun WearPalette.surfaceContainerColor(): Color = surfaceContainer
fun WearPalette.surfaceContainerHighColor(): Color = surfaceContainerHigh
fun WearPalette.surfaceContainerHighestColor(): Color = surfaceContainerHighest

@Composable
fun WearPixelPlayTheme(
    albumArt: Bitmap? = null,
    seedColorArgb: Int? = null,
    themePalette: WearThemePalette? = null,
    content: @Composable () -> Unit,
) {
    val palette = remember(themePalette, albumArt, seedColorArgb) {
        when {
            themePalette != null -> themePalette.toWearPalette()
            // Prefer the seed over re-deriving one from the full bitmap: WearLocalPlayerRepository
            // already extracts this cheaply off the main thread (small downsampled bitmap,
            // recycled after use). Falling through to buildPaletteFromAlbumArt here would redo
            // that work on the main thread against the full-size bitmap (~576 getPixel() calls)
            // for no benefit, every time albumArt is present alongside a seed.
            seedColorArgb != null -> buildPaletteFromSeedColor(Color(seedColorArgb))
            albumArt != null -> buildPaletteFromAlbumArt(albumArt)
            else -> DefaultWearPalette
        }
    }
    val wearColors = remember(palette) {
        Colors(
            primary = palette.controlContainer,
            primaryVariant = lerp(palette.controlContainer, Color.Black, 0.18f),
            secondary = palette.chipContainer,
            secondaryVariant = lerp(palette.chipContainer, Color.Black, 0.24f),
            error = palette.textError,
            onPrimary = palette.controlContent,
            onSecondary = palette.textPrimary,
            onError = Color.Black,
        )
    }

    CompositionLocalProvider(LocalWearPalette provides palette) {
        MaterialTheme(
            colors = wearColors,
            content = content,
        )
    }
}

private fun WearThemePalette.toWearPalette(): WearPalette {
    val surface = colorOrDefault(surfaceContainerArgb, Color(chipContainerArgb))
    val surfaceLowest = colorOrDefault(surfaceContainerLowestArgb, lerp(surface, Color.Black, 0.16f))
    val surfaceLow = colorOrDefault(surfaceContainerLowArgb, lerp(surface, Color.Black, 0.06f))
    val surfaceHigh = colorOrDefault(surfaceContainerHighArgb, lerp(surface, Color.White, 0.08f))
    val surfaceHighest = colorOrDefault(surfaceContainerHighestArgb, lerp(surface, Color.White, 0.14f))
    val disabledContainer = colorOrDefault(controlDisabledContainerArgb, surfaceHighest)
    val transportContainer = colorOrDefault(transportContainerArgb, Color(controlContainerArgb))

    return WearPalette(
        gradientTop = Color(gradientTopArgb),
        gradientMiddle = Color(gradientMiddleArgb),
        gradientBottom = Color(gradientBottomArgb),
        surfaceContainerLowest = surfaceLowest,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHighest,
        textPrimary = Color(textPrimaryArgb),
        textSecondary = Color(textSecondaryArgb),
        textError = Color(textErrorArgb),
        controlContainer = Color(controlContainerArgb),
        controlContent = Color(controlContentArgb),
        controlDisabledContainer = disabledContainer,
        controlDisabledContent = colorOrDefault(controlDisabledContentArgb, bestContrastContent(disabledContainer)),
        transportContainer = transportContainer,
        transportContent = colorOrDefault(
            transportContentArgb,
            colorOrDefault(controlContentArgb, bestContrastContent(transportContainer)),
        ),
        chipContainer = colorOrDefault(chipContainerArgb, surface),
        chipContent = Color(chipContentArgb),
        favoriteActive = Color(favoriteActiveArgb),
        shuffleActive = Color(shuffleActiveArgb),
        repeatActive = Color(repeatActiveArgb),
    )
}

private fun buildPaletteFromAlbumArt(bitmap: Bitmap): WearPalette {
    val seed = extractSeedColor(bitmap)
    return buildPaletteFromSeedColor(seed)
}

private fun buildPaletteFromSeedColor(seed: Color): WearPalette {
    val seedArgb = seed.toArgb()
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seedArgb, hsl)
    hsl[1] = (hsl[1] * 1.18f).coerceIn(0.30f, 0.82f)
    hsl[2] = hsl[2].coerceIn(0.32f, 0.56f)
    val tunedSeed = Color(ColorUtils.HSLToColor(hsl))

    val top = lerp(tunedSeed, Color.Black, 0.30f)
    val middle = lerp(tunedSeed, Color.Black, 0.57f)
    val bottom = lerp(tunedSeed, Color.Black, 0.84f)
    val surfaceBackground = lerp(middle, bottom, 0.58f)
    val surfaceContainerLowest = lerp(surfaceBackground, Color.Black, 0.18f).copy(alpha = 0.96f)
    val surfaceContainerLow = lerp(surfaceBackground, Color.White, 0.06f).copy(alpha = 0.95f)
    val surfaceContainer = lerp(surfaceBackground, Color.White, 0.10f).copy(alpha = 0.95f)
    val surfaceContainerHigh = lerp(surfaceBackground, Color.White, 0.14f).copy(alpha = 0.97f)
    val surfaceContainerHighest = lerp(surfaceBackground, Color.White, 0.20f).copy(alpha = 0.98f)
    val chipContainer = surfaceContainer
    val controlContainer = lerp(surfaceBackground, Color.White, 0.26f).copy(alpha = 0.98f)
    val controlContent = bestContrastContent(controlContainer)
    val transportContainer = lerp(surfaceBackground, Color.White, 0.20f).copy(alpha = 0.98f)
    val transportContent = bestContrastContent(transportContainer)
    val controlDisabledContainer = surfaceContainerHighest
    val controlDisabledContent = bestContrastContent(controlDisabledContainer)

    return WearPalette(
        gradientTop = top,
        gradientMiddle = middle,
        gradientBottom = bottom,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        textPrimary = Color(0xFFF7F2FF),
        textSecondary = Color(0xFFE8DEF8),
        textError = Color(0xFFFFB8C7),
        controlContainer = controlContainer.copy(alpha = 0.96f),
        controlContent = controlContent,
        controlDisabledContainer = controlDisabledContainer,
        controlDisabledContent = controlDisabledContent,
        transportContainer = transportContainer,
        transportContent = transportContent,
        chipContainer = chipContainer,
        chipContent = Color(0xFFF2EBFF),
        favoriteActive = buildAccentFromSeed(seedArgb, hueShift = 34f),
        shuffleActive = buildAccentFromSeed(seedArgb, hueShift = -72f),
        repeatActive = buildAccentFromSeed(seedArgb, hueShift = -22f),
    )
}

private fun colorOrDefault(argb: Int, fallback: Color): Color {
    return if (argb != 0) Color(argb) else fallback
}

private fun buildAccentFromSeed(seedColor: Int, hueShift: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seedColor, hsl)
    hsl[0] = (hsl[0] + hueShift + 360f) % 360f
    hsl[1] = (hsl[1] * 1.22f).coerceIn(0.46f, 0.92f)
    hsl[2] = (hsl[2] + 0.08f).coerceIn(0.40f, 0.72f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun bestContrastContent(background: Color): Color {
    val light = Color(0xFFF6F2FF)
    val dark = Color(0xFF17141E)
    // ColorUtils.calculateContrast requires an opaque background.
    val opaqueBackgroundArgb = if (background.alpha >= 0.999f) {
        background.toArgb()
    } else {
        ColorUtils.compositeColors(
            background.toArgb(),
            Color.Black.toArgb(),
        )
    }
    val lightContrast = ColorUtils.calculateContrast(light.toArgb(), opaqueBackgroundArgb)
    val darkContrast = ColorUtils.calculateContrast(dark.toArgb(), opaqueBackgroundArgb)
    return if (lightContrast >= darkContrast) light else dark
}

private fun extractSeedColor(bitmap: Bitmap): Color {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return DefaultWearPalette.gradientTop

    val step = max(1, min(width, height) / 24)
    var redSum = 0L
    var greenSum = 0L
    var blueSum = 0L
    var count = 0L

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = AndroidColor.alpha(pixel)
            if (alpha >= 28) {
                val red = AndroidColor.red(pixel)
                val green = AndroidColor.green(pixel)
                val blue = AndroidColor.blue(pixel)
                if (red + green + blue > 36) {
                    redSum += red
                    greenSum += green
                    blueSum += blue
                    count++
                }
            }
            x += step
        }
        y += step
    }

    if (count == 0L) return DefaultWearPalette.gradientTop
    val avgColor = AndroidColor.rgb(
        (redSum / count).toInt(),
        (greenSum / count).toInt(),
        (blueSum / count).toInt(),
    )
    return Color(avgColor)
}
