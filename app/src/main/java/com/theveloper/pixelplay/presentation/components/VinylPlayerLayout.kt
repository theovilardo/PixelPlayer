package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R

@Composable
fun VinylPlayerLayout(
    modifier: Modifier = Modifier,
    albumArtUrl: String?,
    isPlaying: Boolean,
    backgroundColor: Color = Color.Black,
    rotationSpeed: Int = 15000 
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(rotationSpeed, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val animatedRotation = if (isPlaying) rotation else 0f

    // 1. Determine Vinyl Asset based on background color with Debounce
    // This prevents the vinyl from flashing intermediate colors (like brown) while
    // the background color is smoothly animating between songs.
    var vinylAsset by remember { mutableStateOf(getVinylAssetForColor(backgroundColor)) }
    
    LaunchedEffect(backgroundColor) {
        kotlinx.coroutines.delay(150) // Wait for animation to settle
        vinylAsset = getVinylAssetForColor(backgroundColor)
    }

    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        // 3. The Vinyl Disc (Rotating)
        Box(
            modifier = Modifier
                .fillMaxSize(0.95f)
                .clip(CircleShape)
                .graphicsLayer { rotationZ = animatedRotation }
        ) {
            // Main Vinyl Disc Image (Decoded on background thread to prevent jitter)
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(vinylAsset)
                    .build(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { 
                        scaleX = 1.08f
                        scaleY = 1.08f
                    }
            )

            // 4. Song Cover in the Middle
            Box(
                modifier = Modifier
                    .fillMaxSize(0.42f) 
                    .align(Alignment.Center)
                    .clip(CircleShape)
            ) {
                SmartImage(
                    model = albumArtUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // 5. Center Pin Hole
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color(0xFF111111))
            )
        }

        // 6. Rainbow Glass Shine Overlay (Iphone Glassmorphism style)
        Canvas(modifier = Modifier.fillMaxSize(0.95f)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2

            // Static "Rainbow" shine brush
            val rainbowBrush = Brush.sweepGradient(
                0.0f to Color.Red.copy(alpha = 0.05f),
                0.2f to Color.Yellow.copy(alpha = 0.05f),
                0.4f to Color.Green.copy(alpha = 0.05f),
                0.6f to Color.Cyan.copy(alpha = 0.05f),
                0.8f to Color.Blue.copy(alpha = 0.05f),
                1.0f to Color.Red.copy(alpha = 0.05f),
                center = center
            )

            drawCircle(
                brush = rainbowBrush,
                radius = radius,
                center = center,
                blendMode = BlendMode.Screen
            )

            // Strong Glossy Reflection
            drawArc(
                brush = Brush.linearGradient(
                    colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.White.copy(alpha = 0.05f)),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                ),
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = true,
                size = Size(size.width, size.height),
                topLeft = Offset.Zero
            )
        }
    }
}

/**
 * Intelligent Selection of Vinyl Asset:
 * Based on new naming: black, blue, light_blue, maroon_red, orange, pink.
 */
private fun getVinylAssetForColor(color: Color): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    
    val hue = hsv[0]
    val saturation = hsv[1]
    val value = hsv[2]

    // If the color is basically grayscale
    if (saturation < 0.1f) {
        return if (value >= 0.8f) R.drawable.vinyl_white else R.drawable.vinyl_black
    }

    return when {
        // Red / Maroon shades
        hue > 330f || hue < 20f -> R.drawable.vinyl_maroon_red
        
        // Brown shades (dark orange)
        hue in 20.0f..45.0f && value < 0.5f -> R.drawable.vinyl_brown

        // Orange shades
        hue in 20.0f..45.0f -> R.drawable.vinyl_orange
        
        // Yellow shades
        hue in 45.0f..70.0f -> R.drawable.vinyl_yellow
        
        // Green shades (Using rainbow for green since we lack a green vinyl)
        hue in 70.0f..160.0f -> R.drawable.vinyl_rainbow
        
        // Light Blue / Cyan shades (Using white vinyl)
        hue in 160.0f..200.0f -> R.drawable.vinyl_white
        
        // Blue shades
        hue in 200.0f..260.0f -> R.drawable.vinyl_blue
        
        // Purple shades
        hue in 260.0f..290.0f -> R.drawable.vinyl_purple

        // Pink shades
        hue in 290.0f..330.0f -> R.drawable.vinyl_pink
        
        // Fallback
        else -> R.drawable.vinyl_black
    }
}
