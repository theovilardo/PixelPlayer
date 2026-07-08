package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.usb.UsbDeviceInfo
import com.theveloper.pixelplay.data.usb.UsbExclusiveModeController
import com.theveloper.pixelplay.data.usb.UsbExclusiveState
import com.theveloper.pixelplay.data.usb.UsbRememberedDevice
import com.theveloper.pixelplay.data.service.player.usb.PcmRepacker
import com.theveloper.pixelplay.usbaudio.negotiation.FormatNegotiator
import com.theveloper.pixelplay.usbaudio.negotiation.SourceFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltViewModel
class UsbAudioSettingsViewModel @Inject constructor(
    private val controller: UsbExclusiveModeController,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    data class UiState(
        val enabled: Boolean = false,
        val state: UsbExclusiveState = UsbExclusiveState.Disabled,
        val rememberedDevices: Map<String, UsbRememberedDevice> = emptyMap(),
        val maxVolumeAcknowledged: Boolean = false
    )

    val uiState: StateFlow<UiState> = combine(
        userPreferencesRepository.usbExclusiveModeEnabledFlow,
        controller.state,
        userPreferencesRepository.usbRememberedDevicesFlow,
        userPreferencesRepository.usbExclusiveMaxVolumeAckFlow
    ) { enabled, state, remembered, acknowledged ->
        UiState(enabled, state, remembered, acknowledged)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setUsbExclusiveModeEnabled(enabled) }
    }

    fun retryPermission(device: UsbDeviceInfo) = controller.retryPermission(device)

    /** Retry after a recoverable error (probe/claim failure). */
    fun refresh() = controller.refresh()

    fun setAutoResume(device: UsbDeviceInfo, autoResume: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.rememberUsbDevice(
                device.key,
                UsbRememberedDevice(label = device.displayName, autoResume = autoResume)
            )
        }
    }

    fun acknowledgeMaxVolume() {
        viewModelScope.launch { userPreferencesRepository.setUsbExclusiveMaxVolumeAck(true) }
    }

    fun setHardwareVolume(fraction: Float) {
        viewModelScope.launch(Dispatchers.IO) { controller.setHardwareVolume(fraction) }
    }

    /** The DAC's actual volume when a session comes up, so the slider starts truthful. */
    val hardwareVolumeFraction: StateFlow<Float?> = controller.state
        .map { state ->
            if (state is UsbExclusiveState.Ready || state is UsbExclusiveState.Active) {
                withContext(Dispatchers.IO) { controller.hardwareVolumeFraction() }
            } else {
                null
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Software volume (dB) for volume-less DACs, null when the gain stage is off. */
    val softwareVolumeDb: StateFlow<Float?> = controller.softwareVolumeDb

    val fixedVolumeOutput: StateFlow<Boolean> = userPreferencesRepository.usbFixedVolumeOutputFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setSoftwareVolumeDb(db: Float) {
        controller.setSoftwareVolumeDb(db)
    }

    fun setFixedVolumeOutput(fixed: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setUsbFixedVolumeOutput(fixed) }
    }

    // ─── Debug: raw driver bring-up tone (bypasses ExoPlayer entirely) ────────

    private var toneJob: Job? = null

    /** Plays 2 s of 440 Hz straight through the session; only meaningful while Ready. */
    fun playTestTone() {
        if (controller.state.value !is UsbExclusiveState.Ready) return
        val session = controller.activeSession ?: return
        if (toneJob?.isActive == true) return

        toneJob = viewModelScope.launch(Dispatchers.Default) {
            val format = FormatNegotiator.negotiate(
                SourceFormat(sampleRateHz = 48_000, bitDepth = 16, channels = 2, isFloat = false),
                session.capabilities
            ) ?: return@launch
            if (!session.configure(format)) {
                Timber.tag("UsbTestTone").w("configure failed: %s", session.lastError)
                return@launch
            }
            session.resume()

            val candidate = format.candidate
            val rate = format.sampleRateHz
            val frameBytes = candidate.channels * candidate.subslotBytes
            val chunkFrames = 4096
            val buffer = ByteBuffer.allocateDirect(chunkFrames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)

            var frame = 0L
            val totalFrames = rate.toLong() * 2
            while (frame < totalFrames && isActive) {
                buffer.clear()
                var inChunk = 0
                while (inChunk < chunkFrames && frame < totalFrames) {
                    val amplitude = sin(2.0 * PI * 440.0 * frame / rate) * 0.25
                    val s32top = (amplitude * Int.MAX_VALUE).toInt()
                    repeat(candidate.channels) {
                        PcmRepacker.writeSubslot(buffer, s32top, candidate.subslotBytes)
                    }
                    inChunk++
                    frame++
                }
                buffer.flip()
                while (buffer.hasRemaining() && isActive) {
                    val written = session.write(buffer, buffer.position(), buffer.remaining())
                    if (written < 0) return@launch
                    if (written == 0) delay(10) else buffer.position(buffer.position() + written)
                }
            }
            // Let the ring drain, then park the stream silently.
            delay(300)
            session.pause()
        }
    }

    override fun onCleared() {
        toneJob?.cancel()
        super.onCleared()
    }
}
