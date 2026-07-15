package com.theveloper.pixelplay.presentation.components.subcomps

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import kotlinx.coroutines.flow.StateFlow

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayingEqIconV2(
    modifier: Modifier = Modifier,
    color: Color,
    isPlaying: Boolean,
    stablePlayerStateFlow: StateFlow<StablePlayerState>
) {

    val stablePlayerState by stablePlayerStateFlow.collectAsStateWithLifecycle()

    val audioAmplitude = stablePlayerState.audioAmplitude
    // 使用 tween 加快反应速度，使动画紧跟音乐节奏，同时保持一定的平滑过渡
    val animatedAmp by animateFloatAsState(
        targetValue = if (isPlaying) audioAmplitude else 0.1f,
        animationSpec = tween(durationMillis = 80),
        label = "eq_amplitude"
    )

    // 通过简单的数学偏移，让一根基础振幅数据变成三根看起来独立的跳动柱子
    // 保证它们最小有 0.1f 的高度（不会完全消失），最大不超过 1f
    val bar1Height = (animatedAmp * 0.7f + 0.1f).coerceIn(0.1f, 1f)
    val bar2Height = (animatedAmp * 1.0f).coerceIn(0.2f, 1f) // 中间这根最高
    val bar3Height = (animatedAmp * 0.5f + 0.3f).coerceIn(0.1f, 1f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(bar1Height)
                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(bar2Height)
                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(bar3Height)
                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                .background(color)
        )
    }
}