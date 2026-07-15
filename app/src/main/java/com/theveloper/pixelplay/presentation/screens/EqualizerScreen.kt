package com.theveloper.pixelplay.presentation.screens

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntAsState // Added
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset // Added
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize // Added
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton // Added
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.TabPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf // Added
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale // Added
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign // Added
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.pointer.pointerInput
 // Added
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.equalizer.EqualizerPreset
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.ExpressiveTopBarContent
import com.theveloper.pixelplay.presentation.viewmodel.EqualizerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.rounded.Check // Added import for Switch check icon
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import com.theveloper.pixelplay.presentation.components.WavyArcSlider
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Edit // Added
import androidx.compose.material.icons.rounded.ExpandMore // Added
import androidx.compose.material.icons.rounded.Save // Added
import androidx.compose.material.icons.filled.Star // Added
import androidx.compose.material3.Surface
import com.theveloper.pixelplay.presentation.components.CustomPresetsSheet
import com.theveloper.pixelplay.presentation.components.ReorderPresetsSheet
import com.theveloper.pixelplay.presentation.components.SavePresetDialog
import com.theveloper.pixelplay.presentation.components.RenamePresetDialog
import com.theveloper.pixelplay.data.preferences.EqualizerViewMode
import androidx.compose.material.icons.rounded.ViewQuilt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.ViewQuilt
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.theveloper.pixelplay.MainActivity.Companion.LocalHazeState
import dev.chrisbanes.haze.hazeSource

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EqualizerScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    equalizerViewModel: EqualizerViewModel = hiltViewModel()
) {
    val uiState by equalizerViewModel.uiState.collectAsStateWithLifecycle()

    // Sheet States
    var showCustomPresetsSheet by remember { mutableStateOf(false) }
    var showReorderSheet by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<EqualizerPreset?>(null) }
    
    // Handlers
    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name -> equalizerViewModel.saveCurrentAsCustomPreset(name) }
        )
    }
    
    renameTarget?.let { preset ->
        RenamePresetDialog(
            currentName = preset.displayName,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                equalizerViewModel.renameCustomPreset(preset.name, newName)
            }
        )
    }
    
    if (showCustomPresetsSheet) {
        CustomPresetsSheet(
            presets = uiState.customPresets,
            pinnedPresetsNames = uiState.pinnedPresetsNames,
            onPresetSelected = { equalizerViewModel.selectPreset(it) },
            onPinToggled = { equalizerViewModel.togglePinPreset(it.name) },
            onRename = { renameTarget = it },
            onDelete = { equalizerViewModel.deleteCustomPreset(it) },
            onDismiss = { showCustomPresetsSheet = false }
        )
    }
    
    ReorderPresetsSheet(
        visible = showReorderSheet,
        allAvailablePresets = uiState.allAvailablePresets,
        pinnedPresetsNames = uiState.pinnedPresetsNames,
        onSave = { newOrder -> equalizerViewModel.updatePinnedPresetsOrder(newOrder) },
        onReset = { equalizerViewModel.resetPinnedPresetsToDefault() },
        onDismiss = { showReorderSheet = false }
    )
    
    // Transition animations
    val transitionState = remember { MutableTransitionState(false) }
    LaunchedEffect(true) { transitionState.targetState = true }
    
    val transition = rememberTransition(transitionState, label = "EqualizerAppearTransition")
    
    val contentAlpha by transition.animateFloat(
        label = "ContentAlpha",
        transitionSpec = { tween(durationMillis = 500) }
    ) { if (it) 1f else 0f }
    
    val contentOffset by transition.animateDp(
        label = "ContentOffset",
        transitionSpec = { tween(durationMillis = 400, easing = FastOutSlowInEasing) }
    ) { if (it) 0.dp else 40.dp }
    
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 180.dp
    
    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
    
    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
    var collapseFraction by remember { mutableFloatStateOf(0f) }
    
    LaunchedEffect(topBarHeight.value) {
        collapseFraction = 1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)
    }
    
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0
                
                if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }
                
                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight
                
                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch { topBarHeight.snapTo(newHeight) }
                }
                
                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }
        }
    }
    
    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
            
            val targetValue = if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx
            
            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }
    
    Box(
        modifier = Modifier
            .nestedScroll(nestedScrollConnection)
            .fillMaxSize()
            .graphicsLayer {
                alpha = contentAlpha
                translationY = contentOffset.toPx()
            }
    ) {
        val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }
        
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(
                top = currentTopBarHeightDp + 8.dp,
                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
            ),
            modifier = Modifier.fillMaxSize().hazeSource(LocalHazeState.current),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Preset Tabs
            item(key = "preset_tabs") {
                val visiblePresets = remember(uiState.accessiblePresets) {
                    val defaultPresets = uiState.accessiblePresets.filter { !it.isCustom }
                    defaultPresets + EqualizerPreset.custom(List(10) { 0 }) // Always show "Custom" tab at end
                }
                
                PresetTabsRow(
                    presets = visiblePresets,
                    selectedPreset = uiState.currentPreset,
                    onPresetSelected = {
                        equalizerViewModel.selectPreset(it) 
                    },
                    onEditClick = { showReorderSheet = true }
                )
            }
            
            // Band Sliders
            item(key = "band_sliders") {
                BandSlidersSection(
                    bandLevels = uiState.bandLevels,
                    isEnabled = uiState.isEnabled,
                    currentPreset = uiState.currentPreset,
                    editingPresetName = uiState.editingPresetName,
                    onBandLevelChanged = { bandId, level ->
                        equalizerViewModel.setBandLevel(bandId, level)
                    },
                    viewMode = uiState.viewMode,
                    onSaveClick = { showSaveDialog = true },
                    onUpdateClick = {
                        uiState.editingPresetName?.let { equalizerViewModel.updateCustomPresetBands(it) }
                    },
                    onPresetsListClick = { showCustomPresetsSheet = true },
                    onUnpinClick = { }
                )
            }
            
            // Effect Controls
            item(key = "effect_controls") {
                EffectControlsSection(
                    bassBoostEnabled = uiState.bassBoostEnabled,
                    bassBoostStrength = uiState.bassBoostStrength, // Now Float
                    virtualizerEnabled = uiState.virtualizerEnabled,
                    virtualizerStrength = uiState.virtualizerStrength, // Now Float
                    loudnessEnabled = uiState.loudnessEnhancerEnabled,
                    loudnessStrength = uiState.loudnessEnhancerStrength, // Now Float
                    isBassBoostSupported = uiState.isBassBoostSupported,
                    isVirtualizerSupported = uiState.isVirtualizerSupported,
                    isLoudnessEnhancerSupported = uiState.isLoudnessEnhancerSupported,
                    isBassBoostDismissed = uiState.isBassBoostDismissed,
                    isVirtualizerDismissed = uiState.isVirtualizerDismissed,
                    isLoudnessDismissed = uiState.isLoudnessDismissed,
                    onBassBoostEnabledChange = { equalizerViewModel.setBassBoostEnabled(it) },
                    onBassBoostStrengthChange = { equalizerViewModel.setBassBoostStrength(it.roundToInt()) },
                    onVirtualizerEnabledChange = { equalizerViewModel.setVirtualizerEnabled(it) },
                    onVirtualizerStrengthChange = { equalizerViewModel.setVirtualizerStrength(it.roundToInt()) },
                    onLoudnessEnabledChange = { equalizerViewModel.setLoudnessEnhancerEnabled(it) },
                    onLoudnessStrengthChange = { equalizerViewModel.setLoudnessEnhancerStrength(it.roundToInt()) },
                    onDismissBassBoost = { equalizerViewModel.setBassBoostDismissed(true) },
                    onDismissVirtualizer = { equalizerViewModel.setVirtualizerDismissed(true) },
                    onDismissLoudness = { equalizerViewModel.setLoudnessDismissed(true) }
                )
            }
            
            // Volume Control
            item(key = "volume_control") {
                val volume by equalizerViewModel.systemVolume.collectAsStateWithLifecycle()
                VolumeControlCard(
                    volume = volume,
                    onVolumeChange = { equalizerViewModel.setSystemVolume(it) }
                )
            }
        }
        
        CollapsibleCommonTopBar(
            title = stringResource(R.string.settings_category_equalizer_title),
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeightDp,
            onBackClick = { navController.popBackStack() },
            expandedTitleStartPadding = 20.dp,
            collapsedTitleStartPadding = 72.dp,
            actions = {
                // View Mode Toggle
                FilledIconButton(
                    onClick = { equalizerViewModel.cycleViewMode() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = when(uiState.viewMode) {
                            EqualizerViewMode.SLIDERS -> Icons.Rounded.GraphicEq
                            EqualizerViewMode.GRAPH -> Icons.AutoMirrored.Rounded.ShowChart
                            EqualizerViewMode.HYBRID -> Icons.AutoMirrored.Rounded.ViewQuilt
                        },
                        contentDescription = stringResource(R.string.equalizer_change_view_mode_cd)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Power toggle
                val isEnabled = uiState.isEnabled
                val powerButtonCorner by animateIntAsState(
                    targetValue = if (isEnabled) 50 else 12,
                    label = "PowerButtonShape"
                )

                FilledIconToggleButton(
                    checked = isEnabled,
                    onCheckedChange = { equalizerViewModel.toggleEqualizer() },
                    shape = RoundedCornerShape(powerButtonCorner),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = if (isEnabled) {
                            stringResource(R.string.equalizer_disable_cd)
                        } else {
                            stringResource(R.string.equalizer_enable_cd)
                        }
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
            }
        )
    }
}

// EqualizerTopBar removed, replaced by CollapsibleCommonTopBar

@Composable
private fun PresetTabsRow(
    presets: List<EqualizerPreset>,
    selectedPreset: EqualizerPreset,
    onPresetSelected: (EqualizerPreset) -> Unit,
    onEditClick: () -> Unit
) {
    val showTabIndicator = false
    val selectedIndex = remember(presets, selectedPreset) {
        if (selectedPreset.isCustom || selectedPreset.name == "custom") {
             presets.indexOfLast { it.name == "Custom" || it.name == "custom" } // Match the placeholder
        } else {
             presets.indexOfFirst { it.name == selectedPreset.name }.coerceAtLeast(0)
        }
    }.coerceAtLeast(0)
    val coroutineScope = rememberCoroutineScope()
    
    // We don't use a Pager, so we need a manual scroll state if we wanted to auto-scroll.
    // Standard ScrollableTabRow handles scrolling to selected index automatically.
    
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 12.dp,
        containerColor = Color.Transparent,
        divider = {},
        indicator = {
            if (showTabIndicator) {
                 TabRowDefaults.PrimaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(selectedTabIndex = selectedIndex),
                    height = 3.dp,
                    width = 20.dp, // Fixed width for expressive dot? Or default width? Library used default.
                    // Library code: Modifier.tabIndicatorOffset(selectedTabIndex = pagerState.currentPage), height = 3.dp
                    // Let's stick to default width (match content) but custom height/color.
                    shape = RoundedCornerShape(3.dp),
                    color = MaterialTheme.colorScheme.primary
                 )
            }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp) // Reduced height? Standard is often 48-64. 56 is good.
    ) {
        presets.forEachIndexed { index, preset ->
            val isPinnedCustom = preset.isCustom
            
            TabAnimation(
                index = index,
                title = preset.name,
                unselectedColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                selectedIndex = selectedIndex,
                onClick = { onPresetSelected(preset) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Medium
                    )
                    if (isPinnedCustom) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = stringResource(R.string.equalizer_custom_preset_cd),
                            modifier = Modifier.size(10.dp), // Slightly smaller
                            tint = if (selectedIndex == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary 
                            // Note: TabAnimation handles content color usually, but Icon tint might need explicit handling or use LocalContentColor.
                            // TabAnimation uses: selectedContentColor = contentColor.
                            // So if I don't set tint, it will use LocalContentColor which is animated. 
                            // So remove manual tint or use LocalContentColor.current.
                        )
                    }
                }
            }
        }
        
        // Edit Button as a specific Tab (unselectable)
        TabAnimation(
            index = -1,
            title = stringResource(R.string.equalizer_edit_tab_title),
            unselectedColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            selectedIndex = selectedIndex,
            onClick = onEditClick 
        ) {
             Icon(
                Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.equalizer_edit_presets_cd),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BandSlidersSection(
    bandLevels: List<Int>,
    isEnabled: Boolean,
    currentPreset: EqualizerPreset,
    editingPresetName: String?,
    onBandLevelChanged: (Int, Int) -> Unit,
    viewMode: EqualizerViewMode,
    onSaveClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onUnpinClick: () -> Unit,
    onPresetsListClick: () -> Unit
) {
    val frequencies = EqualizerPreset.BAND_FREQUENCIES
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                 val isCustomOrSaved = editingPresetName != null || currentPreset.name == "custom" || currentPreset.isCustom
                 val displayLabel = editingPresetName ?: currentPreset.displayName
                 
                 Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                    onClick = onPresetsListClick,
                    enabled = isCustomOrSaved 
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        
                        if (isCustomOrSaved) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.ExpandMore,
                                contentDescription = stringResource(R.string.equalizer_presets_cd),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                
                if (currentPreset.name == "custom" && editingPresetName == null) {
                     Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = CircleShape,
                        onClick = onSaveClick
                    ) {
                          Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             Icon(
                                 imageVector = Icons.Rounded.Save,
                                 contentDescription = null,
                                 modifier = Modifier.size(20.dp),
                                 tint = MaterialTheme.colorScheme.onTertiaryContainer
                             )
                             Spacer(modifier = Modifier.width(6.dp))
                             Text(
                                 text = stringResource(R.string.common_save),
                                 color = MaterialTheme.colorScheme.onTertiaryContainer,
                                 fontWeight = FontWeight.Bold
                             )
                          }
                     }
                }
                
                if (editingPresetName != null) {
                    // Update Option
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        onClick = onUpdateClick
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.equalizer_action_update),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Save New Option
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = CircleShape,
                        onClick = onSaveClick
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.equalizer_action_save_new),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(10.dp))

            when (viewMode) {
                EqualizerViewMode.GRAPH -> {
                    GraphBandSliders(
                        bandLevels = bandLevels,
                        isEnabled = isEnabled,
                        frequencies = frequencies,
                        onBandLevelChanged = onBandLevelChanged
                    )
                }
                EqualizerViewMode.HYBRID -> {
                    HybridBandSliders(
                        bandLevels = bandLevels,
                        isEnabled = isEnabled,
                        frequencies = frequencies,
                        onBandLevelChanged = onBandLevelChanged
                    )
                }
                EqualizerViewMode.SLIDERS -> {
                    val pagerState = rememberPagerState(pageCount = { 2 })

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(270.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val start = page * 5
                            val end = minOf(start + 5, bandLevels.size)

                            for (index in start until end) {
                                val level = bandLevels.getOrElse(index) { 0 }
                                VerticalBandSlider(
                                    frequency = frequencies.getOrElse(index) { "${index * 1000}Hz" },
                                    level = level,
                                    isEnabled = isEnabled,
                                    onLevelChanged = { newLevel -> onBandLevelChanged(index, newLevel) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Page Indicator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(pagerState.pageCount) { iteration ->
                             val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .size(if (pagerState.currentPage == iteration) 10.dp else 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphBandSliders(
    bandLevels: List<Int>,
    isEnabled: Boolean,
    frequencies: List<String>,
    onBandLevelChanged: (Int, Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(290.dp) // Slightly taller for graph headroom
    ) {
        val density = LocalDensity.current
        
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            bandLevels.forEachIndexed { index, level ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // Value Text (Top)
                    Text(
                        text = if (level > 0) "+$level" else "$level",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(20.dp)
                    )
                    

                    CustomVerticalSlider(
                        value = level.toFloat(),
                        onValueChange = { onBandLevelChanged(index, it.roundToInt()) },
                        valueRange = -15f..15f,
                        enabled = isEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        activeTrackColor = Color.Transparent, 
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                        thumbColor = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        trackThickness = 4.dp, 
                        thumbSize = 16.dp,
                        thumbShape = CircleShape
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Frequency
                    Text(
                        text = frequencies.getOrElse(index) { "" }.replace("Hz", "").replace("k", "k"),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Graph Canvas Overlay
        if (isEnabled) {
            val primaryColor = MaterialTheme.colorScheme.primary
            
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                val widthPerBand = size.width / bandLevels.size
                val path = Path()
                
                // Constants from CustomVerticalSlider
                val sliderTopPadding = 20.dp.toPx()
                val sliderBottomPadding = 24.dp.toPx()
                val thumbSize = 16.dp.toPx() // Updated to 16dp
                val verticalPadding = 4.dp.toPx()
                val availableHeight = size.height - sliderTopPadding - sliderBottomPadding
                val trackHeight = availableHeight - thumbSize - (verticalPadding * 2)
                val topOffset = sliderTopPadding + verticalPadding + (thumbSize / 2)
                
                val points = bandLevels.mapIndexed { index, level ->
                    val x = (widthPerBand * index) + (widthPerBand / 2)
                    val normalized = ((level - (-15f)) / (15f - -15f)).coerceIn(0f, 1f)
                    val yNormalized = 1f - normalized
                    val y = topOffset + (yNormalized * trackHeight)
                    Offset(x, y)
                }
                
                if (points.isNotEmpty()) {
                    path.moveTo(points[0].x, points[0].y)
                    
                    for (i in 0 until points.size - 1) {
                        val p0 = points[maxOf(0, i - 1)]
                        val p1 = points[i]
                        val p2 = points[i + 1]
                        val p3 = points[minOf(points.size - 1, i + 2)]
                        
                        val cp1X = p1.x + (p2.x - p0.x) * 0.2f
                        val cp1Y = p1.y + (p2.y - p0.y) * 0.2f
                        val cp2X = p2.x - (p3.x - p1.x) * 0.2f
                        val cp2Y = p2.y - (p3.y - p1.y) * 0.2f
                        
                        path.cubicTo(cp1X, cp1Y, cp2X, cp2Y, p2.x, p2.y)
                    }
                    
                    // Draw Line
                    drawPath(
                        path = path,
                        color = primaryColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                    
                    // Draw Fill
                    val fillPath = Path()
                    fillPath.addPath(path)
                    fillPath.lineTo(points.last().x, size.height - sliderBottomPadding)
                    fillPath.lineTo(points.first().x, size.height - sliderBottomPadding)
                    fillPath.close()
                    
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.3f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = size.height
                        )
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VerticalBandSlider(
    frequency: String,
    level: Int,
    isEnabled: Boolean,
    onLevelChanged: (Int) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
    ) {
        // Level indicator
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (isEnabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (level >= 0) "+$level" else "$level",
                style = MaterialTheme.typography.labelSmall,
                color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Custom vertical slider
        CustomVerticalSlider(
            value = level.toFloat(),
            onValueChange = { onLevelChanged(it.roundToInt()) },
            valueRange = -15f..15f,
            enabled = isEnabled,
            modifier = Modifier
                .weight(1f)
                .width(40.dp),
            activeTrackColor = if (isEnabled) MaterialTheme.colorScheme.primary 
                              else MaterialTheme.colorScheme.onSurfaceVariant,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            thumbColor = if (isEnabled) MaterialTheme.colorScheme.onPrimary // Contrast for thick slider
                        else MaterialTheme.colorScheme.onSurfaceVariant
            // Default params used: trackThickness = Unspecified (fill), thumbSize = 24.dp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Frequency label
        Text(
            text = frequency,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}



// ... imports ...


@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun CustomVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    activeTrackColor: Color,
    inactiveTrackColor: Color,
    thumbColor: Color,
    trackThickness: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    thumbSize: androidx.compose.ui.unit.Dp = 24.dp,
    thumbShape: androidx.compose.ui.graphics.Shape? = null
) {
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val view = LocalView.current
    val thumbSizePx = with(density) { thumbSize.toPx() }
    val thumbRadiusPx = thumbSizePx / 2
    
    // Geometry correction: Adding padding so thumb doesn't touch the absolute container edges
    val verticalPaddingDp = 4.dp
    val verticalPaddingPx = with(density) { verticalPaddingDp.toPx() }
    
    // Normalize value to 0..1 range
    val normalizedValue = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    
    // Track previous integer value for haptic feedback
    var lastHapticValue by remember { mutableIntStateOf(value.roundToInt()) }
    var isInteracting by remember { mutableStateOf(false) }
    var dragNormalizedValue by remember { mutableFloatStateOf(normalizedValue) }

    LaunchedEffect(normalizedValue, isInteracting) {
        if (!isInteracting) {
            dragNormalizedValue = normalizedValue
        }
    }
    
    // Create the Path
    val starShape = remember { com.theveloper.pixelplay.utils.shapes.RoundedStarShape(sides = 8, curve = 0.1) }
    val finalShape = thumbShape ?: starShape
    
    val thumbPath = remember(thumbSizePx, finalShape) {
        val outline = finalShape.createOutline(
            androidx.compose.ui.geometry.Size(thumbSizePx, thumbSizePx),
            androidx.compose.ui.unit.LayoutDirection.Ltr,
            density
        )
        when (outline) {
            is androidx.compose.ui.graphics.Outline.Generic -> outline.path
            is androidx.compose.ui.graphics.Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            is androidx.compose.ui.graphics.Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        }
    }

    // Colors for "inside" look
    val actualActiveTrackColor = if (enabled) activeTrackColor else activeTrackColor.copy(alpha = 0.3f)
    val actualInactiveTrackColor = inactiveTrackColor
    val actualThumbColor = if (enabled) thumbColor else MaterialTheme.colorScheme.onSurfaceVariant

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val heightPx = with(density) { maxHeight.toPx() }
        
        // Usable track height (center of thumb travels within this range, respecting padding)
        val trackHeight = heightPx - thumbSizePx - (verticalPaddingPx * 2)
        val safeTrackHeight = trackHeight.coerceAtLeast(1f)
        val displayNormalizedValue = if (isInteracting) dragNormalizedValue else normalizedValue
        
        // thumb Y position (center)
        val thumbCenterY = heightPx - verticalPaddingPx - thumbRadiusPx - (displayNormalizedValue * safeTrackHeight)
        
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(50))
                .pointerInput(enabled, valueRange.start, valueRange.endInclusive, safeTrackHeight, heightPx) {
                    if (!enabled) return@pointerInput
                    fun dispatchValue(touchY: Float, forceHaptic: Boolean = false) {
                        val trackTopY = verticalPaddingPx + thumbRadiusPx
                        val relativeY = (touchY - trackTopY).coerceIn(0f, safeTrackHeight)
                        val newNormalized = 1f - (relativeY / safeTrackHeight)
                        dragNormalizedValue = newNormalized
                        val newValue = valueRange.start + newNormalized * (valueRange.endInclusive - valueRange.start)
                        onValueChange(newValue)

                        val newInt = newValue.roundToInt()
                        if (forceHaptic || newInt != lastHapticValue) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            lastHapticValue = newInt
                        }
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        isInteracting = true
                        down.consume()
                        dispatchValue(down.position.y, forceHaptic = true)

                        var activePointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointerChange = event.changes.firstOrNull { it.id == activePointerId }
                                ?: event.changes.firstOrNull { it.pressed }?.also { activePointerId = it.id }
                                ?: break

                            if (!pointerChange.pressed) {
                                pointerChange.consume()
                                break
                            }

                            if (pointerChange.position.y != pointerChange.previousPosition.y) {
                                pointerChange.consume()
                                dispatchValue(pointerChange.position.y)
                            }
                        }

                        isInteracting = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
        ) {
            val centerX = size.width / 2
            
            // Determine track drawing width
            val trackWidth = if (trackThickness != androidx.compose.ui.unit.Dp.Unspecified) {
                with(density) { trackThickness.toPx() }
            } else {
                size.width
            }
            // If explicit thickness, center it. If fill, left is 0.
            val trackLeft = if (trackThickness != androidx.compose.ui.unit.Dp.Unspecified) {
                centerX - (trackWidth / 2)
            } else {
                0f
            }
            
            // 1. Draw Inactive Track
            drawRoundRect(
                color = actualInactiveTrackColor,
                topLeft = androidx.compose.ui.geometry.Offset(trackLeft, 0f), 
                size = androidx.compose.ui.geometry.Size(trackWidth, size.height), // Use height not size.width
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackWidth / 2)
            )
            
            // 2. Draw Active Track
            // Cap at thumb center
            drawCircle(
                color = actualActiveTrackColor,
                radius = trackWidth / 2, // Use trackWidth
                center = androidx.compose.ui.geometry.Offset(centerX, thumbCenterY)
            )
            
            val activeRectTop = thumbCenterY
            val activeRectHeight = size.height - activeRectTop
            
            if (activeRectHeight > 0f) {
                val activeTrackPath = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            rect = androidx.compose.ui.geometry.Rect(
                                offset = androidx.compose.ui.geometry.Offset(trackLeft, activeRectTop),
                                size = androidx.compose.ui.geometry.Size(trackWidth, activeRectHeight)
                            ),
                            topLeft = androidx.compose.ui.geometry.CornerRadius.Zero,
                            topRight = androidx.compose.ui.geometry.CornerRadius.Zero,
                            bottomLeft = androidx.compose.ui.geometry.CornerRadius(trackWidth / 2),
                            bottomRight = androidx.compose.ui.geometry.CornerRadius(trackWidth / 2)
                        )
                    )
                }
                drawPath(
                    path = activeTrackPath,
                    color = actualActiveTrackColor
                )
            }

            // 3. Draw Thumb
            translate(
                left = centerX - thumbRadiusPx, 
                top = thumbCenterY - thumbRadiusPx
            ) {
                // Rotate thumb based on normalized value (0 at bottom -> 360 at top)
                rotate(
                    degrees = displayNormalizedValue * 360f,
                    pivot = androidx.compose.ui.geometry.Offset(thumbRadiusPx, thumbRadiusPx)
                ) {
                    drawPath(
                        path = thumbPath,
                        color = actualThumbColor
                    )
                }
            }
        }
    }
}

@Composable
private fun EffectControlsSection(
    bassBoostEnabled: Boolean,
    bassBoostStrength: Float, // Int -> Float
    virtualizerEnabled: Boolean,
    virtualizerStrength: Float, // Int -> Float
    loudnessEnabled: Boolean,
    loudnessStrength: Float, // Int -> Float
    isBassBoostSupported: Boolean,
    isVirtualizerSupported: Boolean,
    isLoudnessEnhancerSupported: Boolean,
    isBassBoostDismissed: Boolean = false,
    isVirtualizerDismissed: Boolean = false,
    isLoudnessDismissed: Boolean = false,
    onBassBoostEnabledChange: (Boolean) -> Unit,
    onBassBoostStrengthChange: (Float) -> Unit, // Int -> Float
    onVirtualizerEnabledChange: (Boolean) -> Unit,
    onVirtualizerStrengthChange: (Float) -> Unit, // Int -> Float
    onLoudnessEnabledChange: (Boolean) -> Unit,
    onLoudnessStrengthChange: (Float) -> Unit, // Int -> Float
    onDismissBassBoost: () -> Unit,
    onDismissVirtualizer: () -> Unit,
    onDismissLoudness: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .height(androidx.compose.foundation.layout.IntrinsicSize.Max) // Ensure equal heights
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Bass Boost
        if (isBassBoostSupported) {
            EffectCard(
                title = stringResource(R.string.equalizer_bass_boost),
                value = bassBoostStrength, // Already Float
                valueRange = 0f..1000f, // Keeping range as is, assuming VM handles 0-100 normalization? 
                // Wait, if VM stores 0-100 Float, but repo uses 0-1000 Int.
                // If I incorrectly changed VM to store 0-100 Float, I must match UI ranges.
                // Originally ranges were 0..1000. 
                // Let's assume VM exposes raw 0..1000 as Float to match slider requirements.
                isEnabled = bassBoostEnabled,
                onValueChange = { onBassBoostStrengthChange(it) }, // Pass Float directly
                onEnabledChange = onBassBoostEnabledChange
            )
        } else if (!isBassBoostDismissed) {
             UnsupportedEffectCard(
                title = stringResource(R.string.equalizer_bass_boost),
                onDismiss = onDismissBassBoost
            )
        }
        
        // Virtualizer
        if (isVirtualizerSupported) {
            EffectCard(
                title = stringResource(R.string.equalizer_virtualizer),
                value = virtualizerStrength,
                valueRange = 0f..1000f,
                isEnabled = virtualizerEnabled,
                onValueChange = { onVirtualizerStrengthChange(it) },
                onEnabledChange = onVirtualizerEnabledChange
            )
        } else if (!isVirtualizerDismissed) {
             UnsupportedEffectCard(
                title = stringResource(R.string.equalizer_virtualizer),
                onDismiss = onDismissVirtualizer
            )
        }

        // Loudness Enhancer
        if (isLoudnessEnhancerSupported) {
            EffectCard(
                title = stringResource(R.string.equalizer_loudness),
                value = loudnessStrength,
                valueRange = 0f..1000f,
                isEnabled = loudnessEnabled,
                onValueChange = { onLoudnessStrengthChange(it) },
                onEnabledChange = onLoudnessEnabledChange
            )
        } else if (!isLoudnessDismissed) {
             UnsupportedEffectCard(
                title = stringResource(R.string.equalizer_loudness),
                onDismiss = onDismissLoudness
            )
        }
    }
}

@Composable
private fun EffectCard(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    isEnabled: Boolean,
    onValueChange: (Float) -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.width(150.dp).fillMaxHeight(), // Match parent intrinsic height
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp) // Reduced spacing
        ) {

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(150.dp)
                    .height(110.dp) 
            ) {
                Box(
                    modifier = Modifier
                        .requiredSize(150.dp) // Force render size
                        .offset(y = (5).dp), // Shift UP slightly to center clearer
                    contentAlignment = Alignment.Center
                ) {
                    WavyArcSlider(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxSize(),
                        enabled = isEnabled,
                        valueRange = valueRange,
                        activeTrackColor = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        thumbColor = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        waveAmplitude = 3.dp
                    )
                    
                    // Percentage Text
                    val percentage = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start) * 100).toInt()
                    Text(
                        text = stringResource(R.string.common_percentage_text, percentage),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.scale(0.8f) 
            )
            
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun UnsupportedEffectCard(
    title: String,
    onDismiss: () -> Unit
) {
     Card(
        modifier = Modifier.width(150.dp).fillMaxHeight(), // Match parent intrinsic height
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_dismiss),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.equalizer_effect_not_supported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun UnsupportedEffectRow(
    title: String,
    message: String = "",
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) // Subtle warning
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        val resolvedMessage = if (message.isNotEmpty()) {
            message
        } else {
            stringResource(R.string.equalizer_effect_not_supported_device)
        }
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = resolvedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IndividualEffectRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isEnabled: Boolean,
    strength: Int,
    onEnabledChange: (Boolean) -> Unit,
    onStrengthChange: (Int) -> Unit,
    maxStrength: Int = 1000
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                thumbContent = if (isEnabled) {
                    {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else null
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Slider(
            value = strength.toFloat(),
            onValueChange = { onStrengthChange(it.roundToInt()) },
            valueRange = 0f..maxStrength.toFloat(),
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth(),
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(36.dp)
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun VolumeControlCard(
    volume: Float, 
    onVolumeChange: (Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var lastHapticValue by remember { mutableStateOf(volume) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.equalizer_volume),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = volume,
                        onValueChange = { newValue ->
                            // Subtle haptic feedback on each 5% change
                            val currentPercent = (newValue * 100).roundToInt()
                            val lastPercent = (lastHapticValue * 100).roundToInt()
                            if (currentPercent / 5 != lastPercent / 5) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastHapticValue = newValue
                            }
                            onVolumeChange(newValue)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        track = { sliderState ->
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                modifier = Modifier.height(36.dp)
                            )
                        }
                    )
                }
                
                Text(
                    modifier = Modifier.width(46.dp),
                    text = stringResource(
                        R.string.common_percentage_text,
                        (volume * 100).roundToInt()
                    ),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun HybridBandSliders(
    bandLevels: List<Int>,
    isEnabled: Boolean,
    frequencies: List<String>,
    onBandLevelChanged: (Int, Int) -> Unit
) {
    val bandBass = stringResource(R.string.equalizer_band_bass)
    val bandLowMids = stringResource(R.string.equalizer_band_low_mids)
    val bandHighMids = stringResource(R.string.equalizer_band_high_mids)
    val bandTreble = stringResource(R.string.equalizer_band_treble)
    val bandBassLow = stringResource(R.string.equalizer_band_bass_low)
    val bandMidHigh = stringResource(R.string.equalizer_band_mid_high)
    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. Static Compact Graph
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp) // Enlarged from 140.dp
                .padding(horizontal = 4.dp) // Reduced outer padding
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp)
        ) {
             Text(
                text = stringResource(R.string.equalizer_frequency_response),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(bottom = 8.dp)
             )
             
             Box(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
                 HybridFrequencyResponseGraph(bandLevels, isEnabled)
                 
                 // Draw simplistic X axis labels
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).offset(y = 4.dp), 
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val labels = if (bandLevels.size > 5) {
                        listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
                    } else {
                         listOf("60", "230", "910", "4k", "14k")
                    }
                    labels.forEach { 
                        Text(it, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f))
                    }
                }
             }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Tabs & Sliders
        // Calculate pages (max 3 per page)
        val itemsPerPage = 3
        val pageCount = (bandLevels.size + itemsPerPage - 1) / itemsPerPage
        
        // Dynamic Tabs
        val tabs = if (pageCount == 4) {
            listOf(bandBass, bandLowMids, bandHighMids, bandTreble)
        } else if (pageCount == 2) {
            listOf(bandBassLow, bandMidHigh)
        } else {
            (1..pageCount).map { stringResource(R.string.equalizer_page_n, it) }
        }

        val pagerState = rememberPagerState(pageCount = { pageCount })
        val coroutineScope = rememberCoroutineScope()
        
        // Use pagerState.currentPage as the source of truth to avoid feedback loops
        val selectedTabIndex = pagerState.currentPage
        val showBandPageTabIndicator = false

        Column(modifier = Modifier.padding(horizontal = 0.dp)) {
            // Tabs Row (Matching PresetTabsRow style)
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 12.dp,
                divider = {},
                indicator = {
                    if (showBandPageTabIndicator) {
                         TabRowDefaults.PrimaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(selectedTabIndex = selectedTabIndex),
                            height = 3.dp,
                            width = 20.dp,
                            shape = RoundedCornerShape(3.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { 
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { 
                            Text(
                                title, 
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium
                            ) 
                        },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

             Spacer(modifier = Modifier.height(24.dp))

             HorizontalPager(
                 state = pagerState, 
                 modifier = Modifier.fillMaxWidth(),
                 userScrollEnabled = true,
                 verticalAlignment = Alignment.Top
             ) { page ->
                 // Content for this page
                 val start = page * itemsPerPage
                 val end = minOf(start + itemsPerPage, bandLevels.size)
                 val indices = start until end
                 
                 Column(
                     verticalArrangement = Arrangement.spacedBy(16.dp), // Reduced from 24.dp
                     modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                 ) {
                     indices.forEach { index ->
                         if (index < bandLevels.size) {
                            HybridHorizontalSlider(
                                frequency = frequencies.getOrElse(index) { "" },
                                level = bandLevels[index],
                                isEnabled = isEnabled,
                                onLevelChanged = { onBandLevelChanged(index, it) }
                            )
                         }
                     }
                 }
             }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HybridHorizontalSlider(
    frequency: String,
    level: Int,
    isEnabled: Boolean,
    onLevelChanged: (Int) -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    var lastHapticValue by remember { mutableIntStateOf(level) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Frequency Label
        Column(modifier = Modifier.width(36.dp)) {
            val freqVal = frequency.replace("Hz", "").replace("k", "k")
            Text(
                text = freqVal,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.equalizer_unit_hz),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Horizontal Slider (Thick Track)
        Box(modifier = Modifier.weight(1f)) {
            androidx.compose.material3.Slider(
                value = level.toFloat(),
                onValueChange = { 
                    val intVal = it.roundToInt()
                    if (intVal != lastHapticValue) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastHapticValue = intVal
                    }
                    onLevelChanged(intVal) 
                },
                valueRange = -15f..15f,
                steps = 0,
                enabled = isEnabled,
                modifier = Modifier.fillMaxWidth(),
                track = { sliderState ->
                     SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(36.dp)
                    )
                }
            )
        }

        // Value Label
        Text(
            text = (if (level > 0) "+$level" else "$level") + "dB",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (level != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun HybridFrequencyResponseGraph(
    bandLevels: List<Int>,
    isEnabled: Boolean,
    frequencies: List<String>? = null
) {
     val primaryColor = MaterialTheme.colorScheme.primary
     val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
     
     androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val widthPerBand = size.width / bandLevels.size
        val path = Path()
        
        // Graph Metrics
        val trackHeight = size.height * 0.7f 
        val topOffset = size.height * 0.15f
        
        // Draw Grid Lines (Horizontal)
        // Range -15 to +15. Grid at -10, -5, 0, 5, 10
        val gridLevels = listOf(-10, -5, 0, 5, 10)
        gridLevels.forEach { lvl ->
            val normalized = ((lvl - (-15f)) / (15f - -15f)).coerceIn(0f, 1f)
            val yNormalized = 1f - normalized
            val y = topOffset + (yNormalized * trackHeight)
            
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        }
        
        val points = bandLevels.mapIndexed { index, level ->
            val x = (widthPerBand * index) + (widthPerBand / 2)
            val normalized = ((level - (-15f)) / (15f - -15f)).coerceIn(0f, 1f)
            val yNormalized = 1f - normalized
            val y = topOffset + (yNormalized * trackHeight)
            Offset(x, y)
        }
        
        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            
            for (i in 0 until points.size - 1) {
                val p0 = points[maxOf(0, i - 1)]
                val p1 = points[i]
                val p2 = points[i + 1]
                val p3 = points[minOf(points.size - 1, i + 2)]
                
                val cp1X = p1.x + (p2.x - p0.x) * 0.2f
                val cp1Y = p1.y + (p2.y - p0.y) * 0.2f
                val cp2X = p2.x - (p3.x - p1.x) * 0.2f
                val cp2Y = p2.y - (p3.y - p1.y) * 0.2f
                
                path.cubicTo(cp1X, cp1Y, cp2X, cp2Y, p2.x, p2.y)
            }
            
            // Draw Line
            drawPath(
                path = path,
                color = if (isEnabled) primaryColor else primaryColor.copy(alpha=0.5f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            
            // Draw Dots
            points.forEach { point ->
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = point
                )
            }
        }
     }
}
