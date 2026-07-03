package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.usb.UsbDeviceInfo
import com.theveloper.pixelplay.data.usb.UsbExclusiveState
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.viewmodel.UsbAudioSettingsViewModel
import com.theveloper.pixelplay.usbaudio.descriptor.UacCapabilities
import com.theveloper.pixelplay.usbaudio.descriptor.UacVersion
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun UsbAudioSettingsScreen(
    onNavigationIconClick: () -> Unit,
    viewModel: UsbAudioSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
        collapseFraction =
            1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)
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

        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(top = currentTopBarHeightDp + 8.dp, bottom = 120.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "usb_toggle_section") {
                SettingsSection(
                    title = stringResource(R.string.settings_usb_audio_title),
                    icon = { Icon(Icons.Rounded.Usb, null, tint = MaterialTheme.colorScheme.primary) }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchSettingItem(
                            title = stringResource(R.string.settings_usb_exclusive_toggle_title),
                            subtitle = stringResource(R.string.settings_usb_exclusive_toggle_subtitle),
                            checked = uiState.enabled,
                            onCheckedChange = { viewModel.setEnabled(it) },
                            leadingIcon = { Icon(Icons.Rounded.Usb, null, tint = MaterialTheme.colorScheme.secondary) }
                        )
                        if (!uiState.enabled) {
                            Text(
                                text = stringResource(R.string.settings_usb_disabled_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }

            if (uiState.enabled) {
                item(key = "usb_status_section") {
                    UsbStatusCard(
                        state = uiState.state,
                        onRetryPermission = viewModel::retryPermission
                    )
                }

                val capabilities = (uiState.state as? UsbExclusiveState.Ready)?.capabilities
                    ?: (uiState.state as? UsbExclusiveState.Active)?.capabilities
                val device = (uiState.state as? UsbExclusiveState.Ready)?.device
                    ?: (uiState.state as? UsbExclusiveState.Active)?.device

                if (capabilities != null && device != null) {
                    item(key = "usb_device_section") {
                        UsbDeviceCard(
                            device = device,
                            capabilities = capabilities,
                            autoResume = uiState.rememberedDevices[device.key]?.autoResume ?: true,
                            onAutoResumeChange = { viewModel.setAutoResume(device, it) }
                        )
                    }

                    item(key = "usb_volume_section") {
                        UsbVolumeSection(
                            capabilities = capabilities,
                            maxVolumeAcknowledged = uiState.maxVolumeAcknowledged,
                            onAcknowledge = viewModel::acknowledgeMaxVolume,
                            onVolumeChange = viewModel::setHardwareVolume
                        )
                    }

                    if (BuildConfig.DEBUG && uiState.state is UsbExclusiveState.Ready) {
                        item(key = "usb_debug_tone") {
                            OutlinedButton(
                                onClick = viewModel::playTestTone,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Rounded.GraphicEq, null)
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text(stringResource(R.string.settings_usb_test_tone))
                            }
                        }
                    }
                }
            }
        }

        CollapsibleCommonTopBar(
            title = stringResource(R.string.settings_usb_audio_title),
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeightDp,
            onBackClick = onNavigationIconClick
        )
    }
}

@Composable
private fun UsbStatusCard(
    state: UsbExclusiveState,
    onRetryPermission: (UsbDeviceInfo) -> Unit
) {
    val (message, isError) = when (state) {
        UsbExclusiveState.Disabled -> null to false
        UsbExclusiveState.NoDevice -> stringResource(R.string.settings_usb_state_no_device) to false
        is UsbExclusiveState.DeviceDetected ->
            stringResource(R.string.settings_usb_state_detected, state.device.displayName) to false
        is UsbExclusiveState.PermissionPending ->
            stringResource(R.string.settings_usb_state_permission_pending) to false
        is UsbExclusiveState.PermissionDenied ->
            stringResource(R.string.settings_usb_state_permission_denied, state.device.displayName) to true
        is UsbExclusiveState.Ready ->
            stringResource(R.string.settings_usb_state_ready, state.device.displayName) to false
        is UsbExclusiveState.Active ->
            stringResource(R.string.settings_usb_state_active, state.device.displayName) to false
        is UsbExclusiveState.Error -> state.message to true
    }
    if (message == null) return

    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface
            )
            if (state is UsbExclusiveState.Active) {
                val conversionText = if (state.conversion.isBitPerfect) {
                    stringResource(R.string.settings_usb_conversion_bit_perfect)
                } else {
                    stringResource(R.string.settings_usb_conversion_converted)
                }
                Text(
                    text = "${formatUsbFormat(state.format.candidate.bitResolution, state.format.sampleRateHz)} • $conversionText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (state is UsbExclusiveState.PermissionDenied) {
                OutlinedButton(onClick = { onRetryPermission(state.device) }) {
                    Text(stringResource(R.string.settings_usb_retry_permission))
                }
            }
        }
    }
}

@Composable
private fun UsbDeviceCard(
    device: UsbDeviceInfo,
    capabilities: UacCapabilities,
    autoResume: Boolean,
    onAutoResumeChange: (Boolean) -> Unit
) {
    SettingsSection(
        title = stringResource(R.string.settings_usb_device_section),
        icon = { Icon(Icons.Rounded.Usb, null, tint = MaterialTheme.colorScheme.primary) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    device.manufacturerName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    InfoRow(
                        label = stringResource(R.string.settings_usb_device_class),
                        value = if (capabilities.version == UacVersion.UAC2) "2.0" else "1.0"
                    )
                    InfoRow(
                        label = stringResource(R.string.settings_usb_device_rates),
                        value = capabilities.allSampleRatesHz.joinToString(", ") { formatKhz(it) }
                    )
                    InfoRow(
                        label = stringResource(R.string.settings_usb_device_depths),
                        value = capabilities.allBitResolutions.joinToString(", ") { "${it}-bit" }
                    )
                }
            }
            SwitchSettingItem(
                title = stringResource(R.string.settings_usb_remember_title),
                subtitle = stringResource(R.string.settings_usb_remember_subtitle),
                checked = autoResume,
                onCheckedChange = onAutoResumeChange,
                leadingIcon = { Icon(Icons.Rounded.Usb, null, tint = MaterialTheme.colorScheme.secondary) }
            )
        }
    }
}

@Composable
private fun UsbVolumeSection(
    capabilities: UacCapabilities,
    maxVolumeAcknowledged: Boolean,
    onAcknowledge: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    SettingsSection(
        title = stringResource(R.string.settings_usb_volume_section),
        icon = { Icon(Icons.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.primary) }
    ) {
        if (capabilities.volume != null) {
            var sliderValue by remember { mutableFloatStateOf(0.75f) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_usb_hw_volume_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { onVolumeChange(sliderValue) }
                    )
                }
            }
        } else {
            Surface(
                color = if (maxVolumeAcknowledged) MaterialTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Warning,
                            null,
                            tint = if (maxVolumeAcknowledged) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(
                            text = stringResource(R.string.settings_usb_max_volume_warning_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (maxVolumeAcknowledged) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_usb_max_volume_warning_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (maxVolumeAcknowledged) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (!maxVolumeAcknowledged) {
                        OutlinedButton(onClick = onAcknowledge) {
                            Text(stringResource(R.string.settings_usb_max_volume_ack))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(2f, fill = false)
        )
    }
}

private fun formatKhz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000}" else String.format(Locale.US, "%.1f", hz / 1000.0)

private fun formatUsbFormat(bits: Int, rateHz: Int): String =
    "${bits}-bit / ${formatKhz(rateHz)} kHz"
