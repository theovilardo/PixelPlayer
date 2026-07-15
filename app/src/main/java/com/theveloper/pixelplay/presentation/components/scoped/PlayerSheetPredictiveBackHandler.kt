package com.theveloper.pixelplay.presentation.components.scoped

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.util.lerp
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.presentation.viewmodel.PlayerSheetState
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

@OptIn(UnstableApi::class)
@Composable
internal fun PlayerSheetPredictiveBackHandler(
    enabled: Boolean,
    playerViewModel: PlayerViewModel,
    sheetCollapsedTargetY: Float,
    sheetExpandedTargetY: Float,
    sheetMotionController: SheetMotionController,
    animationDurationMs: Int,
    onSwipeEdgeChanged: (Int?) -> Unit,
    registrationKey: Any?
) {
    val scope = rememberCoroutineScope()
    key(registrationKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PredictiveBackHandler(enabled = enabled) { progressFlow ->
                try {
                    val startingExpansionFraction = playerViewModel.playerContentExpansionFraction.value
                    progressFlow.collect { backEvent ->
                        onSwipeEdgeChanged(backEvent.swipeEdge)
                        playerViewModel.updatePredictiveBackCollapseFraction(backEvent.progress)
                        val contractedFraction = ((1f - backEvent.progress) * startingExpansionFraction).coerceIn(0f, 1f)
                        playerViewModel.playerContentExpansionFraction.snapTo(contractedFraction)
                    }
                    scope.launch {
                        val progressAtRelease = playerViewModel.predictiveBackCollapseFraction.value
                        val currentVisualY = lerp(sheetExpandedTargetY, sheetCollapsedTargetY, progressAtRelease)
                        val currentVisualExpansionFraction = (1f - progressAtRelease).coerceIn(0f, 1f)
                        sheetMotionController.snapTo(
                            translationYValue = currentVisualY,
                            expansionFractionValue = currentVisualExpansionFraction
                        )
                        playerViewModel.updatePredictiveBackCollapseFraction(1f)
                        playerViewModel.collapsePlayerSheet()
                        playerViewModel.playerContentExpansionFraction.snapTo(0f)
                        playerViewModel.updatePredictiveBackCollapseFraction(0f)
                        onSwipeEdgeChanged(null)
                    }
                } catch (_: CancellationException) {
                    scope.launch {
                        Animatable(playerViewModel.predictiveBackCollapseFraction.value).animateTo(
                            targetValue = 0f,
                            animationSpec = tween(animationDurationMs)
                        ) {
                            playerViewModel.updatePredictiveBackCollapseFraction(this.value)
                        }

                        if (playerViewModel.sheetState.value == PlayerSheetState.EXPANDED) {
                            playerViewModel.expandPlayerSheet()
                            playerViewModel.playerContentExpansionFraction.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(animationDurationMs)
                            )
                        } else {
                            playerViewModel.collapsePlayerSheet()
                            playerViewModel.playerContentExpansionFraction.snapTo(0f)
                        }

                        onSwipeEdgeChanged(null)
                    }
                }
            }
        } else {
            BackHandler(enabled = enabled) {
                playerViewModel.collapsePlayerSheet()
            }
        }
    }
}
