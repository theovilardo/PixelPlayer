package com.theveloper.pixelplay.presentation.screens

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.HorizontalPageIndicator as M3HorizontalPageIndicator
import androidx.wear.compose.material3.Icon as M3Icon
import com.google.android.horologist.audio.ui.VolumeUiState
import com.google.android.horologist.audio.ui.volumeRotaryBehavior
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.WearLifecycleState
import com.theveloper.pixelplay.presentation.components.AlwaysOnScalingPositionIndicator
import com.theveloper.pixelplay.presentation.components.CurvedVolumeIndicator
import com.theveloper.pixelplay.presentation.components.outputRouteIcon
import com.theveloper.pixelplay.presentation.components.WearTopTimeText
import com.theveloper.pixelplay.presentation.shapes.RoundedStarShape
import com.theveloper.pixelplay.presentation.theme.LocalWearPalette
import com.theveloper.pixelplay.presentation.theme.radialBackgroundBrush
import com.theveloper.pixelplay.presentation.theme.surfaceContainerColor
import com.theveloper.pixelplay.presentation.viewmodel.WearPlayerViewModel
import com.theveloper.pixelplay.shared.WearPlayerState
import com.theveloper.pixelplay.shared.WearSyncedLyricLine
import com.theveloper.pixelplay.shared.WearVolumeState
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.abs
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun PlayerScreen(
    onBrowseCategoryClick: (browseType: String, title: String) -> Unit = { _, _ -> },
    onVolumeClick: () -> Unit = {},
    onOutputClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    onQueueClick: () -> Unit = {},
    viewModel: WearPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsState()
    val isPhoneConnected by viewModel.isPhoneConnected.collectAsState()
    val isWatchOutputSelected by viewModel.isWatchOutputSelected.collectAsState()
    val activeOutputRouteType by viewModel.activeOutputRouteType.collectAsState()
    val activeVolumeState by viewModel.activeVolumeState.collectAsState()
    val albumArt by viewModel.albumArt.collectAsState()
    val localPlaybackError by viewModel.localPlaybackError.collectAsState()

    PlayerContent(
        state = state,
        albumArt = albumArt,
        isPhoneConnected = isPhoneConnected,
        isWatchOutputSelected = isWatchOutputSelected,
        activeVolumeState = activeVolumeState,
        onTogglePlayPause = viewModel::togglePlayPause,
        onNext = viewModel::next,
        onPrevious = viewModel::previous,
        onSetActiveVolume = viewModel::setActiveVolume,
        activeOutputRouteType = activeOutputRouteType,
        onBrowseCategoryClick = onBrowseCategoryClick,
        onVolumeClick = onVolumeClick,
        onOutputClick = onOutputClick,
        onMoreClick = onMoreClick,
        onQueueClick = onQueueClick,
        localPlaybackError = localPlaybackError,
        onConsumeLocalPlaybackError = viewModel::consumeLocalPlaybackError,
    )
}

@Composable
private fun PlayerContent(
    state: WearPlayerState,
    albumArt: Bitmap?,
    isPhoneConnected: Boolean,
    isWatchOutputSelected: Boolean = false,
    activeVolumeState: WearVolumeState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSetActiveVolume: (Int) -> Unit,
    activeOutputRouteType: String,
    onBrowseCategoryClick: (browseType: String, title: String) -> Unit,
    onVolumeClick: () -> Unit,
    onOutputClick: () -> Unit,
    onMoreClick: () -> Unit,
    onQueueClick: () -> Unit,
    localPlaybackError: String? = null,
    onConsumeLocalPlaybackError: () -> Unit = {},
) {
    val palette = LocalWearPalette.current
    val isAmbient by WearLifecycleState.isAmbient.collectAsState()
    // Memoize: radialGradient allocates Shader inputs on every call. PlayerContent
    // recomposes whenever the play-button ring animation ticks, so without this
    // we'd churn the GC for nothing.
    val background = remember(palette) { palette.radialBackgroundBrush() }

    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    var mainPageQueueReveal by remember { mutableFloatStateOf(0f) }
    var albumRevealProgress by remember { mutableFloatStateOf(0f) }
    val isMainPlayerPage = pagerState.currentPage == 1
    val isMainPlayerOverlayVisible =
        mainPageQueueReveal > 0.05f || albumRevealProgress > 0.05f
    val showPageIndicator =
        !isMainPlayerPage || (pagerState.isScrollInProgress && !isMainPlayerOverlayVisible)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isAmbient) {
                    Modifier.background(Color.Black)
                } else {
                    Modifier.background(background)
                }
            ),
    ) {

        if (!isAmbient && albumArt != null) {
            Image(
                bitmap = albumArt.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.45f),
                    //.blur(12.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.55f to Color.Transparent,
                                0.80f to palette.gradientTop.copy(alpha = 0.45f),
                                1f to palette.gradientBottom.copy(alpha = 0.85f),
                            ),
                        ),
                    ),
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            when (page) {
                0 -> {
                    WearLyricsPage(
                        state = state,
                        isPhoneConnected = isPhoneConnected,
                        isWatchOutputSelected = isWatchOutputSelected,
                        isAmbient = isAmbient,
                    )
                }

                1 -> {
                    PlayerMainPageHost(
                        state = state,
                        albumArt = albumArt,
                        isCurrentPage = pagerState.currentPage == 1,
                        isAmbient = isAmbient,
                        isPhoneConnected = isPhoneConnected,
                        isWatchOutputSelected = isWatchOutputSelected,
                        activeVolumeState = activeVolumeState,
                        onTogglePlayPause = onTogglePlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onSetActiveVolume = onSetActiveVolume,
                        activeOutputRouteType = activeOutputRouteType,
                        onVolumeClick = onVolumeClick,
                        onOutputClick = onOutputClick,
                        onMoreClick = onMoreClick,
                        onQueueClick = onQueueClick,
                        onQueueShortcutRevealChanged = { mainPageQueueReveal = it },
                        onAlbumRevealProgressChanged = { albumRevealProgress = it },
                    )
                }

                else -> {
                    BrowseScreen(
                        onCategoryClick = onBrowseCategoryClick,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showPageIndicator,
            enter = fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = fadeOut(animationSpec = tween(durationMillis = 240)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(6f),
        ) {
            M3HorizontalPageIndicator(
                pagerState = pagerState,
                modifier = Modifier
                    .padding(bottom = 10.dp),
                selectedColor = palette.textPrimary,
                unselectedColor = palette.textPrimary.copy(alpha = 0.52f),
                backgroundColor = Color.Transparent,
            )
        }

        LaunchedEffect(localPlaybackError) {
            if (localPlaybackError != null) {
                delay(3000L)
                onConsumeLocalPlaybackError()
            }
        }

        AnimatedVisibility(
            visible = localPlaybackError != null,
            enter = fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = fadeOut(animationSpec = tween(durationMillis = 240)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 36.dp)
                .zIndex(7f),
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = localPlaybackError.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun PlayerMainPageHost(
    state: WearPlayerState,
    albumArt: Bitmap?,
    isCurrentPage: Boolean,
    isAmbient: Boolean,
    isPhoneConnected: Boolean,
    isWatchOutputSelected: Boolean,
    activeVolumeState: WearVolumeState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSetActiveVolume: (Int) -> Unit,
    activeOutputRouteType: String,
    onVolumeClick: () -> Unit,
    onOutputClick: () -> Unit,
    onMoreClick: () -> Unit,
    onQueueClick: () -> Unit,
    onQueueShortcutRevealChanged: (Float) -> Unit,
    onAlbumRevealProgressChanged: (Float) -> Unit,
) {
    val palette = LocalWearPalette.current
    val density = LocalDensity.current
    val canShowAlbumArt = !state.isEmpty
    var isDraggingAlbumReveal by remember { mutableStateOf(false) }
    var rawAlbumRevealProgress by remember { mutableFloatStateOf(0f) }
    val albumRevealProgress by animateFloatAsState(
        targetValue = rawAlbumRevealProgress,
        animationSpec = if (isDraggingAlbumReveal) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
            )
        },
        label = "albumRevealProgress",
    )

    LaunchedEffect(canShowAlbumArt, isCurrentPage) {
        if (!canShowAlbumArt || !isCurrentPage) {
            isDraggingAlbumReveal = false
            rawAlbumRevealProgress = 0f
        }
    }

    SideEffect {
        onAlbumRevealProgressChanged(albumRevealProgress)
    }

    BackHandler(enabled = albumRevealProgress > 0.01f) {
        isDraggingAlbumReveal = false
        rawAlbumRevealProgress = 0f
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val heightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val revealDistancePx = heightPx * 0.62f
        val overlayOffsetPx = -heightPx * (1f - albumRevealProgress)
        val mainTranslationY = -heightPx * 0.08f * albumRevealProgress
        val mainScale = 1f - (0.04f * albumRevealProgress)
        val mainAlpha = 1f - (0.26f * albumRevealProgress)
        val timeAlpha = (1f - (albumRevealProgress * 1.6f)).coerceIn(0f, 1f)

        fun settleAlbumReveal(progress: Float, velocityY: Float = 0f) {
            isDraggingAlbumReveal = false
            rawAlbumRevealProgress = if (shouldOpenAlbumArtReveal(progress, velocityY)) 1f else 0f
        }

        Box(modifier = Modifier.fillMaxSize()) {
            MainPlayerPage(
                state = state,
                isPhoneConnected = isPhoneConnected,
                isWatchOutputSelected = isWatchOutputSelected,
                isAmbient = isAmbient,
                activeVolumeState = activeVolumeState,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onSetActiveVolume = onSetActiveVolume,
                rotaryEnabled = isCurrentPage && albumRevealProgress <= 0.01f,
                activeOutputRouteType = activeOutputRouteType,
                onVolumeClick = onVolumeClick,
                onOutputClick = onOutputClick,
                onMoreClick = onMoreClick,
                onQueueClick = onQueueClick,
                onQueueShortcutRevealChanged = onQueueShortcutRevealChanged,
                modifier = Modifier.graphicsLayer {
                    translationY = mainTranslationY
                    scaleX = mainScale
                    scaleY = mainScale
                    alpha = mainAlpha
                },
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(42.dp)
                    .albumArtRevealDragGesture(
                        enabled = canShowAlbumArt && isCurrentPage,
                        currentProgress = albumRevealProgress,
                        revealDistancePx = revealDistancePx,
                        onDragStart = {
                            isDraggingAlbumReveal = true
                            rawAlbumRevealProgress = albumRevealProgress
                        },
                        onProgressChange = { rawAlbumRevealProgress = it },
                        onRelease = { progress, velocityY ->
                            settleAlbumReveal(progress, velocityY)
                        },
                    )
                    .clickable(enabled = canShowAlbumArt && isCurrentPage) {
                        isDraggingAlbumReveal = false
                        rawAlbumRevealProgress = 1f
                    }
                    .zIndex(5f),
            )

            WearTopTimeText(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = timeAlpha }
                    .zIndex(6f),
                color = palette.textPrimary,
            )

            if (!isAmbient && (canShowAlbumArt || albumRevealProgress > 0.01f)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = overlayOffsetPx
                            alpha = albumRevealProgress
                        }
                        .albumArtRevealDragGesture(
                            enabled = isCurrentPage,
                            currentProgress = albumRevealProgress,
                            revealDistancePx = revealDistancePx,
                            onDragStart = {
                                isDraggingAlbumReveal = true
                                rawAlbumRevealProgress = albumRevealProgress
                            },
                            onProgressChange = { rawAlbumRevealProgress = it },
                            onRelease = { progress, velocityY ->
                                settleAlbumReveal(progress, velocityY)
                            },
                        )
                        .zIndex(8f),
                ) {
                    AlbumArtPage(
                        state = state,
                        albumArt = albumArt,
                        onTap = {
                            isDraggingAlbumReveal = false
                            rawAlbumRevealProgress = 0f
                        },
                    )
                }
            }
        }
    }
}

private fun Modifier.albumArtRevealDragGesture(
    enabled: Boolean,
    currentProgress: Float,
    revealDistancePx: Float,
    onDragStart: () -> Unit,
    onProgressChange: (Float) -> Unit,
    onRelease: (Float, Float) -> Unit,
): Modifier {
    if (!enabled) return this

    return this.pointerInput(enabled, currentProgress, revealDistancePx) {
        val velocityTracker = VelocityTracker()
        var dragProgress = currentProgress
        var openingFromClosed = false
        detectVerticalDragGestures(
            onDragStart = {
                velocityTracker.resetTracking()
                dragProgress = currentProgress
                openingFromClosed = currentProgress <= 0.01f
                onDragStart()
            },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val progressDelta = if (openingFromClosed) {
                    abs(dragAmount) / revealDistancePx
                } else {
                    -dragAmount / revealDistancePx
                }
                dragProgress = (dragProgress + progressDelta).coerceIn(0f, 1f)
                onProgressChange(dragProgress)
            },
            onDragEnd = {
                onRelease(dragProgress, velocityTracker.calculateVelocity().y)
            },
            onDragCancel = {
                onRelease(dragProgress, 0f)
            },
        )
    }
}

private fun shouldOpenAlbumArtReveal(
    progress: Float,
    velocityY: Float,
): Boolean = when {
    velocityY <= -900f -> true
    velocityY >= 900f -> false
    progress >= 0.34f -> true
    else -> false
}

@Composable
private fun AlbumArtPage(
    state: WearPlayerState,
    albumArt: Bitmap?,
    onTap: () -> Unit,
) {
    val palette = LocalWearPalette.current
    val textColors = remember(albumArt, palette.textPrimary, palette.textSecondary) {
        deriveAlbumOverlayTextColors(
            albumArt = albumArt,
            fallbackPrimary = palette.textPrimary,
            fallbackSecondary = palette.textSecondary,
        )
    }
    val contrastOverlay = remember(albumArt, textColors) {
        deriveAlbumContrastOverlay(
            albumArt = albumArt,
            textColors = textColors,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onTap),
    ) {
        if (albumArt != null) {
            Image(
                bitmap = albumArt.asImageBitmap(),
                contentDescription = "Album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val fallbackBrush = remember(palette) { palette.radialBackgroundBrush() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(fallbackBrush),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.78f to Color.Transparent,
                            0.94f to Color.Black.copy(alpha = 0.52f),
                            1f to Color.Black.copy(alpha = 0.95f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.32f),
                            0.14f to Color.Black.copy(alpha = 0.08f),
                            0.24f to Color.Transparent,
                            0.68f to Color.Transparent,
                            0.86f to Color.Black.copy(alpha = 0.10f),
                            1f to Color.Black.copy(alpha = 0.34f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to contrastOverlay.topColor.copy(alpha = contrastOverlay.topAlpha),
                            contrastOverlay.topFadeEnd to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            contrastOverlay.bottomFadeStart to Color.Transparent,
                            1f to contrastOverlay.bottomColor.copy(alpha = contrastOverlay.bottomAlpha),
                        ),
                    ),
                ),
        )

        LargeAlbumClockText(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .zIndex(5f),
            color = textColors.clock,
            shadow = textColors.clockShadow,
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.songTitle.ifEmpty { "No song playing" },
                style = MaterialTheme.typography.title2.copy(
                    shadow = textColors.bottomShadow,
                ),
                fontWeight = FontWeight.Bold,
                color = textColors.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = state.artistName.ifEmpty { "Connect phone playback" },
                style = MaterialTheme.typography.body1.copy(
                    shadow = textColors.bottomShadow,
                ),
                color = textColors.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun LargeAlbumClockText(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    shadow: Shadow? = null,
) {
    val displayTime by produceState(initialValue = "--:--") {
        val formatter = DateTimeFormatter.ofPattern("H:mm")
        while (true) {
            val now = LocalTime.now()
            value = now.format(formatter)
            // Sleep until the next minute boundary so we never recompose mid-minute.
            val secondsToNextMinute = (60 - now.second).coerceAtLeast(1)
            delay(secondsToNextMinute * 1000L)
        }
    }
    val gSansFlex = remember {
        FontFamily(
            Font(
                resId = R.font.gflex_variable,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(650),
                    FontVariation.width(146f),
                    FontVariation.Setting("ROND", 56f),
                    FontVariation.Setting("XTRA", 520f),
                    FontVariation.Setting("YOPQ", 90f),
                    FontVariation.Setting("YTLC", 505f),
                ),
            ),
        )
    }

    Text(
        text = displayTime,
        color = color,
        fontFamily = gSansFlex,
        fontWeight = FontWeight(760),
        fontSize = 26.sp,
        lineHeight = 26.sp,
        style = if (shadow != null) TextStyle(shadow = shadow) else TextStyle.Default,
        modifier = modifier,
    )
}

private data class AlbumOverlayTextColors(
    val clock: Color,
    val title: Color,
    val artist: Color,
    val clockShadow: Shadow,
    val bottomShadow: Shadow,
)

private data class AlbumContrastOverlay(
    val topColor: Color,
    val topAlpha: Float,
    val topFadeEnd: Float,
    val bottomColor: Color,
    val bottomAlpha: Float,
    val bottomFadeStart: Float,
)

private fun deriveAlbumOverlayTextColors(
    albumArt: Bitmap?,
    fallbackPrimary: Color,
    fallbackSecondary: Color,
): AlbumOverlayTextColors {
    if (albumArt == null || albumArt.width <= 0 || albumArt.height <= 0) {
        val defaultShadow = Shadow(
            color = Color.Black.copy(alpha = 0.56f),
            offset = Offset(0f, 1.6f),
            blurRadius = 5f,
        )
        return AlbumOverlayTextColors(
            clock = fallbackPrimary,
            title = fallbackPrimary,
            artist = fallbackSecondary.copy(alpha = 0.92f),
            clockShadow = defaultShadow,
            bottomShadow = defaultShadow,
        )
    }

    val topBg = sampleRegionAverageColor(albumArt, startYFraction = 0f, endYFraction = 0.24f)
    val bottomBg = sampleRegionAverageColor(albumArt, startYFraction = 0.66f, endYFraction = 1f)

    val preferredClock = deriveClockTintFromAlbumArt(albumArt, fallbackPrimary)
    val clockColor = deriveReadableTintedColor(
        preferred = preferredClock,
        background = topBg,
        minContrast = 4.8,
        tintStrength = 0.34f,
    )
    val titleColor = deriveReadableTintedColor(
        preferred = fallbackPrimary.copy(alpha = 0.99f),
        background = bottomBg,
        minContrast = 5.2,
        tintStrength = 0.18f,
    )
    val artistColor = deriveReadableTintedColor(
        preferred = fallbackSecondary.copy(alpha = 0.96f),
        background = bottomBg,
        minContrast = 4.4,
        tintStrength = 0.22f,
    ).copy(alpha = 0.97f)

    val clockShadow = if (clockColor.luminance() < 0.5f) {
        Shadow(
            color = Color.White.copy(alpha = 0.36f),
            offset = Offset(0f, 1.2f),
            blurRadius = 4f,
        )
    } else {
        Shadow(
            color = Color.Black.copy(alpha = 0.58f),
            offset = Offset(0f, 1.6f),
            blurRadius = 5f,
        )
    }
    val bottomShadow = if (titleColor.luminance() < 0.5f) {
        Shadow(
            color = Color.White.copy(alpha = 0.30f),
            offset = Offset(0f, 1.2f),
            blurRadius = 4f,
        )
    } else {
        Shadow(
            color = Color.Black.copy(alpha = 0.55f),
            offset = Offset(0f, 1.6f),
            blurRadius = 5f,
        )
    }

    return AlbumOverlayTextColors(
        clock = clockColor,
        title = titleColor,
        artist = artistColor,
        clockShadow = clockShadow,
        bottomShadow = bottomShadow,
    )
}

private fun deriveAlbumContrastOverlay(
    albumArt: Bitmap?,
    textColors: AlbumOverlayTextColors,
): AlbumContrastOverlay {
    if (albumArt == null || albumArt.width <= 0 || albumArt.height <= 0) {
        return AlbumContrastOverlay(
            topColor = Color.Black,
            topAlpha = 0.30f,
            topFadeEnd = 0.30f,
            bottomColor = Color.Black,
            bottomAlpha = 0.36f,
            bottomFadeStart = 0.64f,
        )
    }

    val topBg = sampleRegionAverageColor(albumArt, startYFraction = 0f, endYFraction = 0.24f)
    val bottomBg = sampleRegionAverageColor(albumArt, startYFraction = 0.62f, endYFraction = 1f)

    val topBase = if (textColors.clock.luminance() > 0.5f) Color.Black else Color.White
    val topAlpha = solveScrimAlphaForContrast(
        textColor = textColors.clock,
        backgroundColor = topBg,
        scrimBaseColor = topBase,
        minContrast = 6.0,
        extraHeadroom = 0.10f,
    )

    val bottomBase = if (textColors.title.luminance() > 0.5f) Color.Black else Color.White
    val bottomTitleAlpha = solveScrimAlphaForContrast(
        textColor = textColors.title,
        backgroundColor = bottomBg,
        scrimBaseColor = bottomBase,
        minContrast = 6.4,
        extraHeadroom = 0.10f,
    )
    val bottomArtistAlpha = solveScrimAlphaForContrast(
        textColor = textColors.artist,
        backgroundColor = bottomBg,
        scrimBaseColor = bottomBase,
        minContrast = 5.1,
        extraHeadroom = 0.08f,
    )

    return AlbumContrastOverlay(
        topColor = topBase,
        topAlpha = topAlpha,
        topFadeEnd = 0.33f,
        bottomColor = bottomBase,
        bottomAlpha = max(bottomTitleAlpha, bottomArtistAlpha),
        bottomFadeStart = 0.56f,
    )
}

private fun solveScrimAlphaForContrast(
    textColor: Color,
    backgroundColor: Color,
    scrimBaseColor: Color,
    minContrast: Double,
    extraHeadroom: Float,
): Float {
    val opaqueBg = toOpaqueArgb(backgroundColor)
    val textArgb = textColor.toArgb()
    val currentContrast = ColorUtils.calculateContrast(textArgb, opaqueBg)
    if (currentContrast >= minContrast) {
        return 0f
    }

    var alpha = 0f
    while (alpha <= 0.84f) {
        val scrimArgb = ColorUtils.setAlphaComponent(
            scrimBaseColor.toArgb(),
            (alpha * 255f).toInt().coerceIn(0, 255),
        )
        val compositedBg = ColorUtils.compositeColors(scrimArgb, opaqueBg)
        val contrast = ColorUtils.calculateContrast(textArgb, compositedBg)
        if (contrast >= minContrast) {
            return (alpha + extraHeadroom).coerceIn(0f, 0.84f)
        }
        alpha += 0.03f
    }

    return 0.84f
}

private fun toOpaqueArgb(color: Color): Int {
    val argb = color.toArgb()
    return if (android.graphics.Color.alpha(argb) >= 255) {
        argb
    } else {
        ColorUtils.compositeColors(argb, Color.Black.toArgb())
    }
}

private fun deriveReadableTintedColor(
    preferred: Color,
    background: Color,
    minContrast: Double,
    tintStrength: Float,
): Color {
    val lightBase = Color(0xFFF7F7F7)
    val darkBase = Color(0xFF111111)

    val lightContrast = ColorUtils.calculateContrast(lightBase.toArgb(), background.toArgb())
    val darkContrast = ColorUtils.calculateContrast(darkBase.toArgb(), background.toArgb())
    val highContrastBase = if (lightContrast >= darkContrast) lightBase else darkBase

    val preferredContrast = ColorUtils.calculateContrast(preferred.toArgb(), background.toArgb())
    if (preferredContrast >= minContrast) return preferred

    val clampedTintStrength = tintStrength.coerceIn(0f, 0.5f)
    val tintSteps = floatArrayOf(
        clampedTintStrength,
        clampedTintStrength * 0.72f,
        clampedTintStrength * 0.46f,
        clampedTintStrength * 0.24f,
        0f,
    )

    tintSteps.forEach { blend ->
        val candidate = androidx.compose.ui.graphics.lerp(highContrastBase, preferred, blend)
        val contrast = ColorUtils.calculateContrast(candidate.toArgb(), background.toArgb())
        if (contrast >= minContrast) {
            return candidate
        }
    }

    return highContrastBase
}

private fun sampleRegionAverageColor(
    albumArt: Bitmap,
    startYFraction: Float,
    endYFraction: Float,
): Color {
    val width = albumArt.width
    val height = albumArt.height
    val yStart = (height * startYFraction).toInt().coerceIn(0, height - 1)
    val yEnd = (height * endYFraction).toInt().coerceIn(yStart + 1, height)
    val step = (min(width, (yEnd - yStart).coerceAtLeast(1)) / 24).coerceAtLeast(1)

    var redSum = 0L
    var greenSum = 0L
    var blueSum = 0L
    var count = 0L

    var y = yStart
    while (y < yEnd) {
        var x = 0
        while (x < width) {
            val pixel = albumArt.getPixel(x, y)
            val alpha = android.graphics.Color.alpha(pixel)
            if (alpha >= 28) {
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                if (r + g + b > 30) {
                    redSum += r
                    greenSum += g
                    blueSum += b
                    count++
                }
            }
            x += step
        }
        y += step
    }

    if (count == 0L) return Color.Black
    return Color(
        android.graphics.Color.rgb(
            (redSum / count).toInt(),
            (greenSum / count).toInt(),
            (blueSum / count).toInt(),
        )
    )
}

private fun deriveClockTintFromAlbumArt(albumArt: Bitmap?, fallback: Color): Color {
    if (albumArt == null || albumArt.width <= 0 || albumArt.height <= 0) return fallback

    val width = albumArt.width
    val height = albumArt.height
    val step = (min(width, height) / 28).coerceAtLeast(1)

    var redSum = 0L
    var greenSum = 0L
    var blueSum = 0L
    var count = 0L

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = albumArt.getPixel(x, y)
            val alpha = android.graphics.Color.alpha(pixel)
            if (alpha >= 28) {
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                if (r + g + b > 30) {
                    redSum += r
                    greenSum += g
                    blueSum += b
                    count++
                }
            }
            x += step
        }
        y += step
    }

    if (count == 0L) return fallback

    val avgColor = android.graphics.Color.rgb(
        (redSum / count).toInt(),
        (greenSum / count).toInt(),
        (blueSum / count).toInt(),
    )
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(avgColor, hsl)
    hsl[1] = max(0.42f, hsl[1]).coerceAtMost(0.92f)
    hsl[2] = max(0.68f, hsl[2]).coerceAtMost(0.90f)

    var tinted = Color(ColorUtils.HSLToColor(hsl))
    val lum = tinted.luminance()
    tinted = when {
        lum < 0.60f -> androidx.compose.ui.graphics.lerp(tinted, Color.White, 0.32f)
        lum > 0.92f -> androidx.compose.ui.graphics.lerp(tinted, Color.White, 0.08f)
        else -> tinted
    }
    return tinted.copy(alpha = 0.98f)
}

@Composable
@OptIn(ExperimentalWearFoundationApi::class)
private fun MainPlayerPage(
    state: WearPlayerState,
    isPhoneConnected: Boolean,
    isWatchOutputSelected: Boolean = false,
    isAmbient: Boolean,
    activeVolumeState: WearVolumeState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSetActiveVolume: (Int) -> Unit,
    rotaryEnabled: Boolean,
    activeOutputRouteType: String,
    onVolumeClick: () -> Unit,
    onOutputClick: () -> Unit,
    onMoreClick: () -> Unit,
    onQueueClick: () -> Unit,
    onQueueShortcutRevealChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWearPalette.current
    val volumeEnabled = isPhoneConnected || isWatchOutputSelected
    val columnState = rememberResponsiveColumnState(
        contentPadding = {
            PaddingValues(
                start = 0.dp,
                end = 0.dp,
                top = 2.dp,
                bottom = 20.dp,
            )
        },
    )

    val livePositionMs by rememberLivePositionMs(state)
    val trackProgressTarget = if (state.totalDurationMs > 0L) {
        (livePositionMs.toFloat() / state.totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val trackProgress by animateFloatAsState(
        targetValue = trackProgressTarget,
        animationSpec = tween(durationMillis = 280),
        label = "trackProgress",
    )
    val queueShortcutRevealTarget by remember(columnState.state) {
        derivedStateOf {
            val layoutInfo = columnState.state.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems <= 1) return@derivedStateOf 0f
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf 0f

            val firstVisibleIndex = visibleItems.minOf { it.index }
            val lastVisibleIndex = visibleItems.maxOf { it.index }
            val visibleCount = (lastVisibleIndex - firstVisibleIndex + 1).coerceAtLeast(1)
            val maxFirstVisibleIndex = (totalItems - visibleCount).coerceAtLeast(0)
            if (maxFirstVisibleIndex == 0) return@derivedStateOf 0f

            if (lastVisibleIndex >= totalItems - 1) {
                1f
            } else {
                (firstVisibleIndex.toFloat() / maxFirstVisibleIndex.toFloat()).coerceIn(0f, 1f)
            }
        }
    }
    val queueShortcutReveal by animateFloatAsState(
        targetValue = queueShortcutRevealTarget,
        animationSpec = tween(durationMillis = 220),
        label = "queueShortcutReveal",
    )
    val rotaryFocusRequester = remember { FocusRequester() }
    val rotaryVolumeUiState = remember(activeVolumeState.level, activeVolumeState.max) {
        VolumeUiState(
            current = activeVolumeState.level.coerceAtLeast(0),
            max = activeVolumeState.max.coerceAtLeast(0),
            min = 0,
        )
    }
    var showVolumeOverlay by remember { mutableStateOf(false) }
    var volumeOverlayInteractionTick by remember { mutableIntStateOf(0) }
    val volumeProgress = if (activeVolumeState.max > 0) {
        (activeVolumeState.level.toFloat() / activeVolumeState.max.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    LaunchedEffect(rotaryEnabled) {
        if (!rotaryEnabled) {
            showVolumeOverlay = false
        }
    }
    LaunchedEffect(volumeOverlayInteractionTick, rotaryEnabled) {
        if (!rotaryEnabled || volumeOverlayInteractionTick == 0) return@LaunchedEffect
        showVolumeOverlay = true
        delay(2_000L)
        showVolumeOverlay = false
    }
    LaunchedEffect(queueShortcutReveal) {
        onQueueShortcutRevealChanged(queueShortcutReveal)
    }

    val rotaryModifier = if (rotaryEnabled && volumeEnabled && activeVolumeState.max > 0) {
        Modifier
            .requestFocusOnHierarchyActive()
            .rotaryScrollable(
                behavior = volumeRotaryBehavior(
                    volumeUiStateProvider = { rotaryVolumeUiState },
                    onRotaryVolumeInput = { newVolume ->
                        showVolumeOverlay = true
                        volumeOverlayInteractionTick++
                        onSetActiveVolume(newVolume)
                    },
                ),
                focusRequester = rotaryFocusRequester,
            )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(rotaryModifier)
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            columnState = columnState,
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }

            item {
                HeaderBlock(
                    state = state,
                    isPhoneConnected = isPhoneConnected,
                    isWatchOutputSelected = isWatchOutputSelected,
                )
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            item {
                MainControlsRow(
                    isPlaying = state.isPlaying,
                    isEmpty = state.isEmpty,
                    enabled = if (isWatchOutputSelected) !state.isEmpty else isPhoneConnected,
                    outlined = isAmbient,
                    trackProgress = trackProgress,
                    onTogglePlayPause = onTogglePlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                )
            }

            item {
                SecondaryControlsRow(
                    volumeEnabled = volumeEnabled,
                    deviceEnabled = volumeEnabled,
                    outlined = isAmbient,
                    deviceRouteType = if (isWatchOutputSelected) {
                        com.theveloper.pixelplay.shared.WearVolumeState.ROUTE_TYPE_WATCH
                    } else {
                        activeOutputRouteType
                    },
                    moreEnabled = !state.isEmpty,
                    onVolumeClick = onVolumeClick,
                    onOutputClick = onOutputClick,
                    onMoreClick = onMoreClick,
                    deviceActiveColor = palette.shuffleActive,
                )
            }

            item { Spacer(modifier = Modifier.height(50.dp)) }
        }

        PlayerCrownVolumeIndicator(
            progress = volumeProgress,
            visible = showVolumeOverlay,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f),
        )

        BottomQueueShortcut(
            revealProgress = queueShortcutReveal,
            enabled = isPhoneConnected,
            onClick = onQueueClick,
            modifier = Modifier
                .align(Alignment.BottomCenter),
        )

        if (!isAmbient) {
            AlwaysOnScalingPositionIndicator(
                listState = columnState.state,
                modifier = Modifier.align(Alignment.CenterEnd),
                color = palette.textPrimary,
            )
        }
    }
}

@Composable
private fun rememberLivePositionMs(state: WearPlayerState): androidx.compose.runtime.State<Long> {
    val safeDuration = state.totalDurationMs.coerceAtLeast(0L)
    val safeAnchorPosition = state.currentPositionMs.coerceIn(0L, safeDuration)
    val positionKey = remember(
        state.songId,
        safeAnchorPosition,
        safeDuration,
        state.isPlaying,
        state.positionUpdatedElapsedRealtimeMs,
    ) {
        "${state.songId}|$safeAnchorPosition|$safeDuration|${state.isPlaying}|${state.positionUpdatedElapsedRealtimeMs}"
    }
    return produceState(
        initialValue = state.livePositionFromAnchor(safeAnchorPosition, safeDuration),
        key1 = positionKey,
    ) {
        value = state.livePositionFromAnchor(safeAnchorPosition, safeDuration)
        if (!state.isPlaying || safeDuration <= 0L) {
            return@produceState
        }

        while (true) {
            val next = state.livePositionFromAnchor(safeAnchorPosition, safeDuration)
            value = next
            if (next >= safeDuration) break
            delay(250L)
        }
    }
}

private fun WearPlayerState.livePositionFromAnchor(
    safeAnchorPosition: Long,
    safeDuration: Long,
): Long {
    if (!isPlaying || safeDuration <= 0L) return safeAnchorPosition
    val anchorElapsedRealtime = positionUpdatedElapsedRealtimeMs
    if (anchorElapsedRealtime <= 0L) return safeAnchorPosition
    val elapsed = (SystemClock.elapsedRealtime() - anchorElapsedRealtime).coerceAtLeast(0L)
    return (safeAnchorPosition + elapsed).coerceIn(0L, safeDuration)
}

@Composable
private fun WearLyricsPage(
    state: WearPlayerState,
    isPhoneConnected: Boolean,
    isWatchOutputSelected: Boolean,
    isAmbient: Boolean,
) {
    val palette = LocalWearPalette.current
    val columnState = rememberResponsiveColumnState()
    val lyrics = state.lyrics
    val syncedLines = lyrics?.synced.orEmpty()
    val activeLineIndex by rememberActiveLyricLineIndex(
        state = state,
        lines = syncedLines,
    )

    LaunchedEffect(state.songId, activeLineIndex, isAmbient) {
        if (activeLineIndex >= 0) {
            val targetItem = (activeLineIndex + 2).coerceAtLeast(0)
            if (isAmbient) {
                columnState.state.scrollToItem(targetItem)
            } else {
                columnState.state.animateScrollToItem(targetItem)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            columnState = columnState,
        ) {
            item { Spacer(modifier = Modifier.height(28.dp)) }

            item {
                Text(
                    text = "Lyrics",
                    style = MaterialTheme.typography.caption2,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (lyrics?.hasLyrics == true) {
                if (syncedLines.isNotEmpty()) {
                    syncedLines.forEachIndexed { index, line ->
                        item(key = "${line.timeMs}-$index") {
                            WearSyncedLyricLineItem(
                                line = line,
                                active = index == activeLineIndex,
                                isAmbient = isAmbient,
                            )
                        }
                    }
                } else {
                    lyrics.plain.forEachIndexed { index, line ->
                        item(key = "plain-$index") {
                            WearPlainLyricLineItem(line = line)
                        }
                    }
                }
            } else {
                item {
                    WearLyricsEmptyState(
                        isPhoneConnected = isPhoneConnected,
                        isWatchOutputSelected = isWatchOutputSelected,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(42.dp)) }
        }

        AlwaysOnScalingPositionIndicator(
            listState = columnState.state,
            modifier = Modifier.align(Alignment.CenterEnd),
            color = palette.textPrimary,
            )
    }
}

@Composable
private fun WearSyncedLyricLineItem(
    line: WearSyncedLyricLine,
    active: Boolean,
    isAmbient: Boolean,
) {
    val palette = LocalWearPalette.current
    val targetTextColor = if (active) palette.textPrimary else palette.textSecondary.copy(alpha = 0.72f)
    val textColor = if (isAmbient) {
        targetTextColor
    } else {
        animateColorAsState(
            targetValue = targetTextColor,
            animationSpec = spring(),
            label = "wearLyricLineColor",
        ).value
    }
    val scale = if (isAmbient) {
        1f
    } else {
        animateFloatAsState(
            targetValue = if (active) 1.08f else 1f,
            animationSpec = spring(),
            label = "wearLyricLineScale",
        ).value
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = line.line.ifBlank { line.translation.orEmpty() },
            style = if (active) MaterialTheme.typography.body1 else MaterialTheme.typography.caption1,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        val secondaryText = line.translation?.takeIf { it.isNotBlank() }
            ?: line.romanization?.takeIf { it.isNotBlank() }
        if (active && !secondaryText.isNullOrBlank() && secondaryText != line.line) {
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.caption2,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun WearPlainLyricLineItem(line: String) {
    val palette = LocalWearPalette.current
    Text(
        text = line,
        style = MaterialTheme.typography.caption1,
        color = palette.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

@Composable
private fun WearLyricsEmptyState(
    isPhoneConnected: Boolean,
    isWatchOutputSelected: Boolean,
) {
    val palette = LocalWearPalette.current
    val message = when {
        isWatchOutputSelected -> "Phone lyrics sync is available while controlling phone playback"
        !isPhoneConnected -> "Connect phone to sync lyrics"
        else -> "No lyrics synced yet"
    }
    Text(
        text = message,
        style = MaterialTheme.typography.caption1,
        color = palette.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    )
}

private fun List<WearSyncedLyricLine>.activeLyricLineIndex(positionMs: Long): Int {
    if (isEmpty()) return -1
    val index = indexOfLast { it.timeMs.toLong() <= positionMs }
    return index.coerceAtLeast(0)
}

@Composable
private fun rememberActiveLyricLineIndex(
    state: WearPlayerState,
    lines: List<WearSyncedLyricLine>,
): androidx.compose.runtime.State<Int> {
    val safeDuration = state.totalDurationMs.coerceAtLeast(0L)
    val safeAnchorPosition = state.currentPositionMs.coerceIn(0L, safeDuration)
    val positionKey = remember(
        state.songId,
        safeAnchorPosition,
        safeDuration,
        state.isPlaying,
        state.positionUpdatedElapsedRealtimeMs,
        lines,
    ) {
        "${state.songId}|$safeAnchorPosition|$safeDuration|${state.isPlaying}|${state.positionUpdatedElapsedRealtimeMs}|${lines.size}|${lines.firstOrNull()?.timeMs}|${lines.lastOrNull()?.timeMs}"
    }

    return produceState(
        initialValue = lines.activeLyricLineIndex(
            state.livePositionFromAnchor(safeAnchorPosition, safeDuration),
        ),
        key1 = positionKey,
    ) {
        if (lines.isEmpty()) {
            value = -1
            return@produceState
        }

        while (true) {
            val livePositionMs = state.livePositionFromAnchor(safeAnchorPosition, safeDuration)
            val currentIndex = lines.activeLyricLineIndex(livePositionMs)
            value = currentIndex

            if (!state.isPlaying || safeDuration <= 0L) {
                return@produceState
            }

            val nextLineTimeMs = lines.getOrNull(currentIndex + 1)?.timeMs?.toLong()
                ?: return@produceState
            val delayUntilNextLine = (nextLineTimeMs - livePositionMs)
                .coerceIn(80L, 60_000L)
            delay(delayUntilNextLine)
        }
    }
}

@Composable
private fun PlayerCrownVolumeIndicator(
    progress: Float,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWearPalette.current
    val overlayAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 180 else 220),
        label = "playerVolumeOverlayAlpha",
    )
    val overlayScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.96f,
        animationSpec = tween(durationMillis = if (visible) 180 else 220),
        label = "playerVolumeOverlayScale",
    )

    if (overlayAlpha <= 0.01f && !visible) return

    Box(
        modifier = modifier.graphicsLayer {
            alpha = overlayAlpha
            scaleX = overlayScale
            scaleY = overlayScale
            transformOrigin = TransformOrigin(0f, 0.5f)
        }
    ) {
        CurvedVolumeIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize(),
            startAngle = 144f,
            sweepAngle = 72f,
            strokeWidth = 4.dp,
            inset = 10.dp,
            trackColor = palette.surfaceContainerColor().copy(alpha = 0.42f),
            progressColor = palette.controlContainer.copy(alpha = 0.94f),
        )
    }
}

@Composable
private fun HeaderBlock(
    state: WearPlayerState,
    isPhoneConnected: Boolean,
    isWatchOutputSelected: Boolean = false,
) {
    val palette = LocalWearPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.songTitle.ifEmpty { "Song name" },
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.SemiBold,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = when {
                isWatchOutputSelected -> {
                    val artistAlbum = buildList {
                        if (state.artistName.isNotBlank()) add(state.artistName)
                        if (state.albumName.isNotBlank()) add(state.albumName)
                    }.joinToString(" · ")
                    if (artistAlbum.isNotBlank()) artistAlbum else "On watch"
                }
                !isPhoneConnected -> "No phone"
                state.artistName.isNotEmpty() -> state.artistName
                state.isEmpty -> "Waiting playback"
                else -> "Artist name"
            },
            style = MaterialTheme.typography.body1,
            color = when {
                isWatchOutputSelected -> palette.textSecondary
                !isPhoneConnected -> palette.textError
                else -> palette.textSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

//        if (isWatchOutputSelected) {
//            Text(
//                text = "On watch",
//                style = MaterialTheme.typography.caption3,
//                color = palette.shuffleActive.copy(alpha = 0.85f),
//                textAlign = TextAlign.Center,
//                modifier = Modifier.fillMaxWidth(),
//            )
//        }
    }
}

@Composable
private fun MainControlsRow(
    isPlaying: Boolean,
    isEmpty: Boolean,
    enabled: Boolean,
    outlined: Boolean,
    trackProgress: Float,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlattenedControlButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = "Previous",
            enabled = enabled,
            outlined = outlined,
            onClick = onPrevious,
            width = 44.dp,
            height = 54.dp,
        )

        Spacer(modifier = Modifier.width(10.dp))

        CenterPlayButton(
            isPlaying = isPlaying,
            enabled = enabled && !isEmpty,
            outlined = outlined,
            trackProgress = trackProgress,
            onClick = onTogglePlayPause,
        )

        Spacer(modifier = Modifier.width(10.dp))

        FlattenedControlButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "Next",
            enabled = enabled,
            outlined = outlined,
            onClick = onNext,
            width = 44.dp,
            height = 54.dp,
        )
    }
}

@Composable
private fun FlattenedControlButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    outlined: Boolean,
    onClick: () -> Unit,
    width: Dp,
    height: Dp,
) {
    val palette = LocalWearPalette.current
    val shape = CircleShape
    val container = when {
        outlined -> Color.Transparent
        enabled -> palette.transportContainer
        else -> palette.controlDisabledContainer
    }
    val tint = when {
        outlined && enabled -> palette.transportContainer
        enabled -> palette.transportContent
        else -> palette.controlDisabledContent
    }
    val borderColor = if (enabled) {
        palette.transportContainer.copy(alpha = 0.82f)
    } else {
        palette.controlDisabledContent.copy(alpha = 0.34f)
    }

    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(container)
            .then(
                if (outlined) {
                    Modifier.border(1.5.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun CenterPlayButton(
    isPlaying: Boolean,
    enabled: Boolean,
    outlined: Boolean,
    trackProgress: Float,
    onClick: () -> Unit,
) {
    val palette = LocalWearPalette.current

    val animatedCurve by animateFloatAsState(
        targetValue = if (isPlaying) 0.08f else 0.00f,
        animationSpec = spring(),
        label = "playStarCurve",
    )
    val rotation = remember { Animatable(0f) }
    val isInteractive by WearLifecycleState.isInteractive.collectAsState(
        initial = WearLifecycleState.isInteractiveNow,
    )
    LaunchedEffect(isPlaying, isInteractive) {
        if (!isPlaying || !isInteractive) {
            val normalizedRotation = ((rotation.value % 360f) + 360f) % 360f
            rotation.snapTo(normalizedRotation)
            rotation.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 616,
                    easing = FastOutSlowInEasing,
                ),
            )
            return@LaunchedEffect
        }
        while (true) {
            val current = rotation.value
            rotation.animateTo(
                targetValue = current + 360f,
                animationSpec = tween(
                    durationMillis = 13800,
                    easing = LinearEasing,
                ),
            )
            if (rotation.value >= 3600f) {
                rotation.snapTo(rotation.value % 360f)
            }
        }
    }
    val animatedRotation = rotation.value
    val animatedSize by animateDpAsState(
        targetValue = if (isPlaying) 60.dp else 56.dp,
        animationSpec = spring(),
        label = "playButtonSize",
    )
    val container by animateColorAsState(
        targetValue = when {
            outlined -> Color.Transparent
            enabled -> palette.controlContainer
            else -> palette.controlDisabledContainer
        },
        animationSpec = spring(),
        label = "playContainer",
    )
    val tint by animateColorAsState(
        targetValue = when {
            outlined && enabled -> palette.controlContainer
            enabled -> palette.controlContent
            else -> palette.controlDisabledContent
        },
        animationSpec = spring(),
        label = "playTint",
    )

    val ringProgress = trackProgress.coerceIn(0f, 1f)
    val playButtonShape = RoundedStarShape(
        sides = 8,
        curve = animatedCurve.toDouble(),
        rotation = animatedRotation,
    )

    Box(
        modifier = Modifier.size(animatedSize + 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 4.dp.toPx()
            val ringInset = (strokeWidth / 2f) + 1.5.dp.toPx()
            val ringRadius = ((size.minDimension - (ringInset * 2f)) / 2f).coerceAtLeast(0f)
            val ringPath = buildPlayButtonRingPath(
                centerX = size.width / 2f,
                centerY = size.height / 2f,
                radius = ringRadius,
                curve = animatedCurve,
                rotation = animatedRotation,
            )
            val ringStroke = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )

            drawPath(
                path = ringPath,
                color = palette.surfaceContainerLowest, //.copy(alpha = 0.62f),
                style = ringStroke,
            )

            val pathMeasure = PathMeasure().apply {
                setPath(ringPath, forceClosed = true)
            }
            val pathLength = pathMeasure.length
            val progressLength = pathLength * ringProgress
            if (progressLength > 0f && pathLength > 0f) {
                val normalizedRotation = ((animatedRotation % 360f) + 360f) % 360f
                val startDistance = (pathLength * (normalizedRotation / 360f)).coerceIn(0f, pathLength)
                val boundedProgress = progressLength.coerceAtMost(pathLength)
                val progressPath = Path()

                if (startDistance + boundedProgress <= pathLength) {
                    pathMeasure.getSegment(
                        startDistance = startDistance,
                        stopDistance = startDistance + boundedProgress,
                        destination = progressPath,
                        startWithMoveTo = true,
                    )
                } else {
                    val firstLeg = pathLength - startDistance
                    pathMeasure.getSegment(
                        startDistance = startDistance,
                        stopDistance = pathLength,
                        destination = progressPath,
                        startWithMoveTo = true,
                    )
                    pathMeasure.getSegment(
                        startDistance = 0f,
                        stopDistance = (boundedProgress - firstLeg).coerceAtLeast(0f),
                        destination = progressPath,
                        startWithMoveTo = false,
                    )
                }

                drawPath(
                    path = progressPath,
                    color = palette.controlContainer.copy(alpha = if (enabled) 1f else 0.95f),
                    style = ringStroke,
                )
            }
        }

        Box(
            modifier = Modifier
                .size(animatedSize)
                .clip(playButtonShape)
                .background(container)
                .then(
                    if (outlined) {
                        Modifier.border(
                            width = 1.6.dp,
                            color = if (enabled) {
                                palette.controlContainer.copy(alpha = 0.86f)
                            } else {
                                palette.controlDisabledContent.copy(alpha = 0.34f)
                            },
                            shape = playButtonShape,
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

private fun buildPlayButtonRingPath(
    centerX: Float,
    centerY: Float,
    radius: Float,
    curve: Float,
    rotation: Float,
    sides: Int = 8,
    steps: Int = 320,
): Path {
    val twoPi = Math.PI * 2.0
    val startAngle = -Math.PI / 2.0
    val angleStep = twoPi / steps.toDouble()
    val rotationRad = Math.toRadians(rotation.toDouble())
    val ringPath = Path()
    val boundedCurve = curve.coerceIn(0f, 0.12f)
    val boundedRadius = radius.coerceAtLeast(0f)

    fun pointAt(t: Double): Offset {
        val angle = startAngle + t
        val wave = 1f + (boundedCurve * cos((sides * angle)).toFloat())
        val distance = boundedRadius * wave
        val x = centerX + (distance * cos(angle - rotationRad).toFloat())
        val y = centerY + (distance * sin(angle - rotationRad).toFloat())
        return Offset(x, y)
    }

    val firstPoint = pointAt(0.0)
    ringPath.moveTo(firstPoint.x, firstPoint.y)
    var t = angleStep
    while (t <= twoPi) {
        val point = pointAt(t)
        ringPath.lineTo(point.x, point.y)
        t += angleStep
    }
    ringPath.close()
    return ringPath
}

@Composable
private fun SecondaryControlsRow(
    volumeEnabled: Boolean,
    deviceEnabled: Boolean,
    outlined: Boolean,
    deviceRouteType: String,
    moreEnabled: Boolean,
    onVolumeClick: () -> Unit,
    onOutputClick: () -> Unit,
    onMoreClick: () -> Unit,
    deviceActiveColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(
            space = 10.dp,
            alignment = Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.Top,
    ) {
        SecondaryActionSlot(lower = false) {
            SecondaryActionButton(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                enabled = volumeEnabled,
                active = false,
                activeColor = deviceActiveColor,
                outlined = outlined,
                raisedInactiveStyle = true,
                onClick = onVolumeClick,
                contentDescription = "Volume",
            )
        }
        SecondaryActionSlot(lower = true) {
            SecondaryActionButton(
                icon = outputRouteIcon(deviceRouteType),
                enabled = deviceEnabled,
                active = deviceRouteType == com.theveloper.pixelplay.shared.WearVolumeState.ROUTE_TYPE_WATCH,
                activeColor = deviceActiveColor,
                outlined = outlined,
                raisedInactiveStyle = true,
                onClick = onOutputClick,
                contentDescription = "Output device",
            )
        }
        SecondaryActionSlot(lower = false) {
            SecondaryActionButton(
                icon = Icons.Rounded.MoreVert,
                enabled = moreEnabled,
                active = false,
                activeColor = deviceActiveColor,
                outlined = outlined,
                raisedInactiveStyle = true,
                heavilyMutedWhenDisabled = true,
                onClick = onMoreClick,
                contentDescription = "More options",
            )
        }
    }
}

@Composable
private fun SecondaryActionButton(
    icon: ImageVector,
    enabled: Boolean,
    active: Boolean,
    activeColor: Color,
    outlined: Boolean,
    raisedInactiveStyle: Boolean = false,
    heavilyMutedWhenDisabled: Boolean = false,
    onClick: () -> Unit,
    contentDescription: String,
) {
    val palette = LocalWearPalette.current
    val activeContainerColor = activeColor.copy(alpha = 0.84f)
    val inactiveContainerColor = if (raisedInactiveStyle) {
        palette.surfaceContainerHighest
    } else {
        palette.chipContainer
    }
    val inactiveContentColor = if (raisedInactiveStyle) {
        palette.textPrimary.copy(alpha = 0.94f)
    } else {
        palette.chipContent
    }
    val disabledContainerColor = if (heavilyMutedWhenDisabled) {
        palette.surfaceContainerLowest.copy(alpha = 0.92f)
    } else {
        palette.controlDisabledContainer
    }
    val disabledContentColor = if (heavilyMutedWhenDisabled) {
        palette.textSecondary.copy(alpha = 0.48f)
    } else {
        palette.controlDisabledContent
    }
    val container by animateColorAsState(
        targetValue = when {
            outlined -> Color.Transparent
            !enabled -> disabledContainerColor
            active -> activeContainerColor
            else -> inactiveContainerColor
        },
        animationSpec = spring(),
        label = "secondaryContainer",
    )
    val tint by animateColorAsState(
        targetValue = when {
            outlined && enabled -> if (active) activeColor else inactiveContentColor
            !enabled -> disabledContentColor
            active -> if (activeContainerColor.luminance() > 0.52f) Color.Black else Color.White
            else -> inactiveContentColor
        },
        animationSpec = spring(),
        label = "secondaryTint",
    )

    val shape = RoundedCornerShape(18.dp)
    val borderColor = when {
        !enabled -> disabledContentColor.copy(alpha = 0.32f)
        active -> activeColor.copy(alpha = 0.84f)
        else -> inactiveContentColor.copy(alpha = 0.62f)
    }

    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 36.dp)
            .clip(shape)
            .background(container)
            .then(
                if (outlined) {
                    Modifier.border(1.4.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun SecondaryActionSlot(
    lower: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.size(width = 48.dp, height = 48.dp),
        contentAlignment = if (lower) Alignment.BottomCenter else Alignment.TopCenter,
    ) {
        content()
    }
}

@Composable
private fun BottomQueueShortcut(
    revealProgress: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWearPalette.current
    val clampedProgress = revealProgress.coerceIn(0f, 1f)
    if (clampedProgress <= 0.01f) return

    val containerColor by animateColorAsState(
        targetValue = if (enabled) palette.controlContainer else palette.controlDisabledContainer,
        animationSpec = spring(),
        label = "queueShortcutContainer",
    )
    val iconColor by animateColorAsState(
        targetValue = if (enabled) palette.controlContent else palette.controlDisabledContent,
        animationSpec = spring(),
        label = "queueShortcutIcon",
    )

    val edgeHeight = lerp(16.dp, 66.dp, clampedProgress)
    val iconSize = lerp(14.dp, 24.dp, clampedProgress)
    val containerAlpha = (clampedProgress * 1.1f).coerceIn(0f, 1f)

    EdgeButton(
        onClick = onClick,
        enabled = enabled && clampedProgress > 0.65f,
        buttonSize = EdgeButtonSize.Small,
        colors = M3ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = iconColor,
            disabledContainerColor = palette.controlDisabledContainer,
            disabledContentColor = palette.controlDisabledContent,
        ),
        modifier = modifier
            //.height(edgeHeight)
            .graphicsLayer {
                alpha = containerAlpha
                scaleY = 0.55f + (0.45f * clampedProgress)
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
        ,
    ) {
        M3Icon(
            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = "Queue",
            modifier = Modifier.size(iconSize),
        )
    }
}
