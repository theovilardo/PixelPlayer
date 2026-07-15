package com.theveloper.pixelplay.presentation.screens

import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.theveloper.pixelplay.MainActivity.Companion.LocalHazeState
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.viewmodel.AudioCapabilities
import com.theveloper.pixelplay.presentation.viewmodel.AudioOutputCategory
import com.theveloper.pixelplay.presentation.viewmodel.DeviceCapabilitiesState
import com.theveloper.pixelplay.presentation.viewmodel.DeviceCapabilitiesViewModel
import com.theveloper.pixelplay.presentation.viewmodel.ExoPlayerInfo
import com.theveloper.pixelplay.presentation.viewmodel.FormatSupportInfo
import com.theveloper.pixelplay.presentation.viewmodel.LocalMusicStorageSummary
import com.theveloper.pixelplay.presentation.viewmodel.MemorySummary
import com.theveloper.pixelplay.presentation.viewmodel.PlaybackCompatibilitySummary
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import dev.chrisbanes.haze.hazeSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun DeviceCapabilitiesScreen(
    navController: NavController,
    viewModel: DeviceCapabilitiesViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 188.dp
    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }

    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
    var collapseFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(topBarHeight.value) {
        collapseFraction = 1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx))
            .coerceIn(0f, 1f)
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
                coroutineScope.launch { topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium)) }
            }
        }
    }

    Box(
        modifier = Modifier
            .nestedScroll(nestedScrollConnection)
            .fillMaxSize()
    ) {
        val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = currentTopBarHeightDp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            DeviceCapabilitiesContent(
                state = state,
                lazyListState = lazyListState,
                topPadding = currentTopBarHeightDp,
                onGenerateReport = viewModel::generatePerformanceReport,
                onAdvancedDiagnosticsChange = viewModel::setAdvancedPerformanceDiagnosticsEnabled,
                onMarkLagNow = viewModel::markLagNow,
                modifier = Modifier.fillMaxSize()
            )
        }

        CollapsibleCommonTopBar(
            title = stringResource(R.string.settings_category_device_capabilities_title),
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeightDp,
            onBackClick = { navController.popBackStack() },
            expandedTitleStartPadding = 20.dp,
            collapsedTitleStartPadding = 68.dp,
            maxLines = 2
        )
    }
}

@Composable
private fun DeviceCapabilitiesContent(
    state: DeviceCapabilitiesState,
    lazyListState: LazyListState,
    topPadding: Dp,
    onGenerateReport: () -> Unit,
    onAdvancedDiagnosticsChange: (Boolean) -> Unit,
    onMarkLagNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = lazyListState,
        contentPadding = PaddingValues(
            top = topPadding + 8.dp,
            start = 16.dp,
            end = 16.dp,
            bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.hazeSource(LocalHazeState.current)
    ) {
        item {
            PlaybackReadinessCard(
                deviceInfo = state.deviceInfo,
                audioCapabilities = state.audioCapabilities,
                storageSummary = state.storageSummary,
                playbackCompatibility = state.playbackCompatibility
            )
        }

        state.storageSummary?.let { storage ->
            item {
                LocalMusicStorageCard(storageSummary = storage)
            }
        }

        state.audioCapabilities?.let { audio ->
            item {
                PlaybackPathCard(
                    audioCapabilities = audio,
                    memorySummary = state.memorySummary,
                    exoPlayerInfo = state.exoPlayerInfo
                )
            }
        }

        if (state.formatSupport.isNotEmpty()) {
            item {
                FormatCompatibilityCard(
                    formats = state.formatSupport,
                    playbackCompatibility = state.playbackCompatibility
                )
            }
        }

        state.playbackCompatibility?.let { compatibility ->
            item {
                PlaybackFindingsCard(compatibility = compatibility)
            }
        }

        item {
            DeviceInfoPanel(deviceInfo = state.deviceInfo)
        }

        item {
            PerformanceReportCard(
                report = state.performanceReport,
                isGenerating = state.isGeneratingReport,
                advancedDiagnosticsEnabled = state.advancedDiagnosticsEnabled,
                advancedDiagnosticsExpiresAtEpochMs = state.advancedDiagnosticsExpiresAtEpochMs,
                onGenerate = onGenerateReport,
                onAdvancedDiagnosticsChange = onAdvancedDiagnosticsChange,
                onMarkLagNow = onMarkLagNow
            )
        }
    }
}

@Composable
private fun PerformanceReportCard(
    report: String?,
    isGenerating: Boolean,
    advancedDiagnosticsEnabled: Boolean,
    advancedDiagnosticsExpiresAtEpochMs: Long?,
    onGenerate: () -> Unit,
    onAdvancedDiagnosticsChange: (Boolean) -> Unit,
    onMarkLagNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val copiedMessage = stringResource(R.string.settings_devcaps_report_copied)
    val shareTitle = stringResource(R.string.settings_devcaps_report_share_title)
    val lagMarkedMessage = stringResource(R.string.settings_devcaps_advanced_diagnostics_marked)

    CapabilityCard(
        title = stringResource(R.string.settings_devcaps_report_title),
        icon = Icons.Rounded.Assessment,
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.settings_devcaps_report_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        AdvancedDiagnosticsToggleRow(
            enabled = advancedDiagnosticsEnabled,
            expiresAtEpochMs = advancedDiagnosticsExpiresAtEpochMs,
            onEnabledChange = onAdvancedDiagnosticsChange
        )

        if (advancedDiagnosticsEnabled) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onMarkLagNow()
                    Toast.makeText(context, lagMarkedMessage, Toast.LENGTH_SHORT).show()
                }
            ) {
                Text(stringResource(R.string.settings_devcaps_advanced_diagnostics_mark_lag))
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onGenerate,
            enabled = !isGenerating
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    text = stringResource(
                        if (report == null) R.string.settings_devcaps_report_generate
                        else R.string.settings_devcaps_report_regenerate
                    )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (report != null) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        clipboardManager.setText(AnnotatedString(report))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_devcaps_report_copy))
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, shareTitle)
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, shareTitle))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_devcaps_report_share))
                }
            }
        }

        if (report != null) {
            Surface(
                shape = AbsoluteSmoothCornerShape(18.dp, 60),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = report,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(12.dp)
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedDiagnosticsToggleRow(
    enabled: Boolean,
    expiresAtEpochMs: Long?,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(18.dp, 60),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_devcaps_advanced_diagnostics_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (enabled && expiresAtEpochMs != null) {
                        stringResource(
                            R.string.settings_devcaps_advanced_diagnostics_expires,
                            formatDiagnosticsExpiry(expiresAtEpochMs)
                        )
                    } else {
                        stringResource(R.string.settings_devcaps_advanced_diagnostics_description)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
private fun PlaybackReadinessCard(
    deviceInfo: Map<String, String>,
    audioCapabilities: AudioCapabilities?,
    storageSummary: LocalMusicStorageSummary?,
    playbackCompatibility: PlaybackCompatibilitySummary?,
    modifier: Modifier = Modifier
) {
    val needsReview = playbackCompatibility?.let {
        it.unsupportedLibrarySongCount > 0 || it.resampledLocalSongCount > 0
    } ?: false
    val containerColor = if (needsReview) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (needsReview) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(32.dp, 60),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIcon(
                    icon = if (needsReview) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                    containerColor = contentColor.copy(alpha = 0.12f),
                    contentColor = contentColor
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (needsReview) {
                            stringResource(R.string.settings_devcaps_review_title)
                        } else {
                            stringResource(R.string.settings_devcaps_ready_title)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        modifier = Modifier.semantics { heading() }
                    )
                }
            }

            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeroMetricTile(
                    label = stringResource(R.string.settings_devcaps_metric_formats),
                    value = audioCapabilities?.supportedCodecs
                        ?.flatMap { it.supportedTypes }
                        ?.distinct()
                        ?.size
                        ?.toString()
                        ?: stringResource(R.string.settings_devcaps_unknown),
                    modifier = Modifier.weight(1f),
                    containerColor = contentColor.copy(alpha = 0.10f),
                    contentColor = contentColor
                )
                HeroMetricTile(
                    label = stringResource(R.string.settings_devcaps_metric_hw_decoders),
                    value = audioCapabilities?.supportedCodecs
                        ?.count { it.isHardwareAccelerated }
                        ?.toString()
                        ?: stringResource(R.string.settings_devcaps_unknown),
                    modifier = Modifier.weight(1f),
                    containerColor = contentColor.copy(alpha = 0.10f),
                    contentColor = contentColor
                )
                HeroMetricTile(
                    label = stringResource(R.string.settings_devcaps_metric_local_music),
                    value = storageSummary?.localSongCount?.toString()
                        ?: stringResource(R.string.settings_devcaps_unknown),
                    modifier = Modifier.weight(1f),
                    containerColor = contentColor.copy(alpha = 0.10f),
                    contentColor = contentColor
                )
            }
        }
    }
}

@Composable
private fun LocalMusicStorageCard(
    storageSummary: LocalMusicStorageSummary,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val musicSize = remember(storageSummary.localMusicBytes) {
        Formatter.formatShortFileSize(context, storageSummary.localMusicBytes)
    }
    val availableSize = remember(storageSummary.deviceAvailableBytes) {
        Formatter.formatShortFileSize(context, storageSummary.deviceAvailableBytes)
    }
    val totalSize = remember(storageSummary.deviceTotalBytes) {
        Formatter.formatShortFileSize(context, storageSummary.deviceTotalBytes)
    }
    val musicPercent = storagePercentLabel(storageSummary.localMusicStorageFraction)
    val usedPercent = storagePercentLabel(storageSummary.deviceUsedFraction)

    CapabilityCard(
        title = stringResource(R.string.settings_devcaps_storage_title),
        icon = Icons.Rounded.Storage,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoTile(
                label = stringResource(R.string.settings_devcaps_storage_music_size),
                value = musicSize,
                supporting = stringResource(
                    R.string.settings_devcaps_storage_music_count,
                    storageSummary.localSongCount
                ),
                modifier = Modifier.weight(1f)
            )
            InfoTile(
                label = stringResource(R.string.settings_devcaps_storage_available),
                value = availableSize,
                supporting = stringResource(R.string.settings_devcaps_storage_total, totalSize),
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProgressReadout(
                label = stringResource(R.string.settings_devcaps_storage_music_footprint),
                value = musicPercent,
                progress = storageSummary.localMusicStorageFraction
            )
            ProgressReadout(
                label = stringResource(R.string.settings_devcaps_storage_device_used),
                value = usedPercent,
                progress = storageSummary.deviceUsedFraction,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        if (storageSummary.cloudSongCount > 0 || storageSummary.unavailableLocalFileCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (storageSummary.cloudSongCount > 0) {
                    TonalChip(
                        text = stringResource(
                            R.string.settings_devcaps_storage_cloud_count,
                            storageSummary.cloudSongCount
                        )
                    )
                }
                if (storageSummary.unavailableLocalFileCount > 0) {
                    TonalChip(
                        text = stringResource(
                            R.string.settings_devcaps_storage_unavailable_count,
                            storageSummary.unavailableLocalFileCount
                        ),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackPathCard(
    audioCapabilities: AudioCapabilities,
    memorySummary: MemorySummary?,
    exoPlayerInfo: ExoPlayerInfo?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val availableRam = memorySummary?.availableRamBytes?.let {
        Formatter.formatShortFileSize(context, it)
    } ?: stringResource(R.string.settings_devcaps_unknown)
    val totalRam = memorySummary?.totalRamBytes?.let {
        Formatter.formatShortFileSize(context, it)
    } ?: stringResource(R.string.settings_devcaps_unknown)

    CapabilityCard(
        title = stringResource(R.string.settings_devcaps_playback_path_title),
        icon = Icons.Rounded.Speaker,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoTile(
                label = stringResource(R.string.settings_devcaps_sample_rate_title),
                value = stringResource(R.string.settings_devcaps_sample_rate_value_hz, audioCapabilities.outputSampleRate),
                supporting = stringResource(
                    R.string.settings_devcaps_buffer_frames,
                    audioCapabilities.outputFramesPerBuffer
                ),
                modifier = Modifier.weight(1f)
            )
            InfoTile(
                label = stringResource(R.string.settings_devcaps_hifi_pcm_float_title),
                value = yesNo(audioCapabilities.isPcmFloatSupported),
                supporting = stringResource(R.string.settings_devcaps_hifi_pcm_float_supporting),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoTile(
                label = stringResource(R.string.settings_devcaps_low_latency_title),
                value = yesNo(audioCapabilities.isLowLatencySupported),
                supporting = stringResource(R.string.settings_devcaps_pro_audio_supporting) +
                    ": " + yesNo(audioCapabilities.isProAudioSupported),
                modifier = Modifier.weight(1f)
            )
            InfoTile(
                label = stringResource(R.string.settings_devcaps_memory_title),
                value = availableRam,
                supporting = stringResource(R.string.settings_devcaps_memory_available_of, totalRam),
                modifier = Modifier.weight(1f)
            )
        }

        SectionLabel(text = stringResource(R.string.settings_devcaps_offload_title))
        ChipRow(
            emptyText = stringResource(R.string.settings_devcaps_offload_empty),
            chips = audioCapabilities.offloadSupportedFormats
        )

        SectionLabel(text = stringResource(R.string.settings_devcaps_outputs_title))
        if (audioCapabilities.outputRoutes.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_devcaps_outputs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                audioCapabilities.outputRoutes.take(5).forEach { route ->
                    OutputRouteRow(
                        name = route.name,
                        category = route.category
                    )
                }
            }
        }

        exoPlayerInfo?.let { exo ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoTile(
                    label = stringResource(R.string.settings_devcaps_exoplayer_title),
                    value = exo.version,
                    supporting = stringResource(R.string.settings_devcaps_renderers_count, exo.renderers),
                    icon = Icons.Rounded.Memory,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FormatCompatibilityCard(
    formats: List<FormatSupportInfo>,
    playbackCompatibility: PlaybackCompatibilitySummary?,
    modifier: Modifier = Modifier
) {
    CapabilityCard(
        title = stringResource(R.string.settings_devcaps_formats_title),
        icon = Icons.Rounded.GraphicEq,
        verticalSpacing = 0.dp,
        modifier = modifier
    ) {
        val rows = formats.chunked(2)
        Spacer(
            modifier = Modifier.height(12.dp)
        )
        rows.forEachIndexed { index, rowFormats ->
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowFormats.forEach { format ->
                    FormatSupportTile(
                        format = format,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowFormats.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (index != rows.lastIndex) {
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        playbackCompatibility?.let { compatibility ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalChip(
                    text = stringResource(
                        R.string.settings_devcaps_formats_supported_count,
                        compatibility.supportedLibrarySongCount
                    ),
                    leadingIcon = Icons.Rounded.CheckCircle
                )
                if (compatibility.unknownFormatSongCount > 0) {
                    TonalChip(
                        text = stringResource(
                            R.string.settings_devcaps_formats_unknown_count,
                            compatibility.unknownFormatSongCount
                        ),
                        leadingIcon = Icons.Rounded.Info
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackFindingsCard(
    compatibility: PlaybackCompatibilitySummary,
    modifier: Modifier = Modifier
) {
    val hasFindings = compatibility.unsupportedLibrarySongCount > 0 ||
        compatibility.unknownFormatSongCount > 0 ||
        compatibility.resampledLocalSongCount > 0

    CapabilityCard(
        title = stringResource(R.string.settings_devcaps_findings_title),
        icon = if (hasFindings) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
        modifier = modifier
    ) {
        if (!hasFindings) {
            FindingRow(
                icon = Icons.Rounded.CheckCircle,
                title = stringResource(R.string.settings_devcaps_finding_clear_title),
                body = stringResource(R.string.settings_devcaps_finding_clear_body),
                tone = FindingTone.Success
            )
            return@CapabilityCard
        }

        if (compatibility.unsupportedLibrarySongCount > 0) {
            FindingRow(
                icon = Icons.Rounded.ErrorOutline,
                title = stringResource(
                    R.string.settings_devcaps_finding_unsupported_title,
                    compatibility.unsupportedLibrarySongCount
                ),
                body = stringResource(
                    R.string.settings_devcaps_finding_unsupported_body,
                    compatibility.unsupportedFormats.take(4).joinToString(", ")
                ),
                tone = FindingTone.Error
            )
        }

        if (compatibility.resampledLocalSongCount > 0) {
            Spacer(Modifier.height(8.dp))
            FindingRow(
                icon = Icons.Rounded.Warning,
                title = stringResource(
                    R.string.settings_devcaps_finding_resample_title,
                    compatibility.resampledLocalSongCount
                ),
                body = stringResource(
                    R.string.settings_devcaps_finding_resample_body,
                    compatibility.maxLocalSampleRate ?: 0
                ),
                tone = FindingTone.Warning
            )
        }

        if (compatibility.unknownFormatSongCount > 0) {
            Spacer(Modifier.height(8.dp))
            FindingRow(
                icon = Icons.Rounded.Info,
                title = stringResource(
                    R.string.settings_devcaps_finding_unknown_title,
                    compatibility.unknownFormatSongCount
                ),
                body = stringResource(R.string.settings_devcaps_finding_unknown_body),
                tone = FindingTone.Info
            )
        }
    }
}

@Composable
private fun DeviceInfoPanel(
    deviceInfo: Map<String, String>,
    modifier: Modifier = Modifier
) {
    val orderedEntries = remember(deviceInfo) { orderedDeviceInfoEntries(deviceInfo) }
    val localized = localizedDeviceInfoEntries(orderedEntries)

    CapabilityCard(
        title = stringResource(R.string.settings_devcaps_device_info_title),
        icon = Icons.Rounded.Info,
        verticalSpacing = 0.dp,
        enableTopSpacer = true,
        modifier = modifier
    ) {
        val rows = localized.chunked(2)
        rows.forEachIndexed { index, entries ->
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                entries.forEach { (label, value) ->
                    InfoTile(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (entries.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (index != rows.lastIndex) {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    enableTopSpacer: Boolean = false,
    verticalSpacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(28.dp, 60),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIcon(
                    icon = icon,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
            }

            if (enableTopSpacer) {
                Spacer(
                    modifier = Modifier.height(12.dp)
                )
            }

            content()
        }
    }
}

@Composable
private fun StatusIcon(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(44.dp),
        shape = CircleShape,
        color = containerColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun HeroMetricTile(
    label: String,
    value: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .defaultMinSize(minHeight = 82.dp),
        shape = AbsoluteSmoothCornerShape(18.dp, 60),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.76f),
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InfoTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .defaultMinSize(minHeight = 86.dp),
        shape = AbsoluteSmoothCornerShape(18.dp, 60),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FormatSupportTile(
    format: FormatSupportInfo,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        !format.isDecoderAvailable -> MaterialTheme.colorScheme.errorContainer
        format.isHardwareAccelerated -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val statusContentColor = when {
        !format.isDecoderAvailable -> MaterialTheme.colorScheme.onErrorContainer
        format.isHardwareAccelerated -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .defaultMinSize(minHeight = 112.dp),
        shape = AbsoluteSmoothCornerShape(18.dp, 60),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = format.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = CircleShape,
                    color = statusColor
                ) {
                    Icon(
                        imageVector = if (format.isDecoderAvailable) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = statusContentColor,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(16.dp)
                    )
                }
            }
            Text(
                text = when {
                    !format.isDecoderAvailable -> stringResource(R.string.settings_devcaps_format_unsupported)
                    format.isHardwareAccelerated -> stringResource(R.string.settings_devcaps_format_hardware)
                    else -> stringResource(R.string.settings_devcaps_format_software)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (format.isOffloadSupported) {
                    TonalChip(
                        text = stringResource(R.string.settings_devcaps_format_offload),
                        compact = true
                    )
                }
                if (format.librarySongCount > 0) {
                    TonalChip(
                        text = stringResource(R.string.settings_devcaps_format_library_count, format.librarySongCount),
                        compact = true
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressReadout(
    label: String,
    value: String,
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.visibleProgress() },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

@Composable
private fun storagePercentLabel(fraction: Float): String {
    val percent = fraction * 100f
    return when {
        fraction <= 0f -> stringResource(R.string.settings_devcaps_storage_percent, 0)
        percent < 1f -> stringResource(R.string.settings_devcaps_storage_less_than_one_percent)
        else -> stringResource(R.string.settings_devcaps_storage_percent, percent.roundToInt())
    }
}

private fun Float.visibleProgress(): Float {
    val clamped = coerceIn(0f, 1f)
    return if (clamped > 0f && clamped < 0.01f) 0.01f else clamped
}

private fun formatDiagnosticsExpiry(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun OutputRouteRow(
    name: String,
    category: AudioOutputCategory,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(16.dp, 60),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (category == AudioOutputCategory.BuiltIn) Icons.Rounded.Speaker else Icons.Rounded.Headphones,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = audioOutputCategoryLabel(category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private enum class FindingTone {
    Success,
    Warning,
    Error,
    Info
}

@Composable
private fun FindingRow(
    icon: ImageVector,
    title: String,
    body: String,
    tone: FindingTone,
    modifier: Modifier = Modifier
) {
    val containerColor = when (tone) {
        FindingTone.Success -> MaterialTheme.colorScheme.primaryContainer
        FindingTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        FindingTone.Error -> MaterialTheme.colorScheme.errorContainer
        FindingTone.Info -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (tone) {
        FindingTone.Success -> MaterialTheme.colorScheme.onPrimaryContainer
        FindingTone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        FindingTone.Error -> MaterialTheme.colorScheme.onErrorContainer
        FindingTone.Info -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(18.dp, 60),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun TonalChip(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    compact: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 5.dp else 7.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(if (compact) 14.dp else 16.dp)
                )
            }
            Text(
                text = text,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChipRow(
    chips: List<String>,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    if (chips.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.take(3).forEach {
            TonalChip(text = it)
        }
        if (chips.size > 3) {
            TonalChip(text = stringResource(R.string.settings_devcaps_more_count, chips.size - 3))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
private fun yesNo(value: Boolean): String {
    return if (value) {
        stringResource(R.string.settings_devcaps_status_yes)
    } else {
        stringResource(R.string.settings_devcaps_status_no)
    }
}

@Composable
private fun audioOutputCategoryLabel(category: AudioOutputCategory): String {
    return when (category) {
        AudioOutputCategory.BuiltIn -> stringResource(R.string.settings_devcaps_output_builtin)
        AudioOutputCategory.Bluetooth -> stringResource(R.string.settings_devcaps_output_bluetooth)
        AudioOutputCategory.Usb -> stringResource(R.string.settings_devcaps_output_usb)
        AudioOutputCategory.Wired -> stringResource(R.string.settings_devcaps_output_wired)
        AudioOutputCategory.Cast -> stringResource(R.string.settings_devcaps_output_digital)
        AudioOutputCategory.Other -> stringResource(R.string.settings_devcaps_output_other)
    }
}

@Composable
private fun localizedDeviceInfoEntries(entries: List<Pair<String, String>>): List<Pair<String, String>> {
    val lManufacturer = stringResource(R.string.settings_devcaps_device_info_manufacturer)
    val lModel = stringResource(R.string.settings_devcaps_device_info_model)
    val lBrand = stringResource(R.string.settings_devcaps_device_info_brand)
    val lDevice = stringResource(R.string.settings_devcaps_device_info_device)
    val lAndroid = stringResource(R.string.settings_devcaps_device_info_android_version)
    val lSdk = stringResource(R.string.settings_devcaps_device_info_sdk_version)
    val lHardware = stringResource(R.string.settings_devcaps_device_info_hardware)

    return entries.map { (key, value) ->
        val label = when (key) {
            "Manufacturer" -> lManufacturer
            "Model" -> lModel
            "Brand" -> lBrand
            "Device" -> lDevice
            "Android Version" -> lAndroid
            "SDK Version" -> lSdk
            "Hardware" -> lHardware
            else -> key
        }
        label to value
    }
}

private fun orderedDeviceInfoEntries(deviceInfo: Map<String, String>): List<Pair<String, String>> {
    val preferredOrder = listOf(
        "Manufacturer",
        "Model",
        "Brand",
        "Device",
        "Android Version",
        "SDK Version",
        "Hardware"
    )
    val orderedEntries = mutableListOf<Pair<String, String>>()
    val seenKeys = mutableSetOf<String>()

    preferredOrder.forEach { key ->
        deviceInfo[key]?.let { value ->
            orderedEntries += key to value
            seenKeys += key
        }
    }

    deviceInfo.forEach { (key, value) ->
        if (key !in seenKeys) {
            orderedEntries += key to value
        }
    }
    return orderedEntries
}
