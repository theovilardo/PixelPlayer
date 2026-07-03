package com.theveloper.pixelplay.data.usb

import android.hardware.usb.UsbDeviceConnection
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.usbaudio.UsbAudioSession
import com.theveloper.pixelplay.usbaudio.descriptor.ParseResult
import com.theveloper.pixelplay.usbaudio.descriptor.UacCapabilities
import com.theveloper.pixelplay.usbaudio.descriptor.UacCapabilityProber
import com.theveloper.pixelplay.usbaudio.descriptor.UsbControlTransfer
import com.theveloper.pixelplay.usbaudio.descriptor.UsbDescriptorParser
import com.theveloper.pixelplay.usbaudio.negotiation.Conversion
import com.theveloper.pixelplay.usbaudio.negotiation.NegotiatedFormat
import com.theveloper.pixelplay.usbaudio.negotiation.SourceFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Everything the UI (and MusicService) needs to know about USB exclusive mode. */
sealed interface UsbExclusiveState {
    /** The preference is off — feature entirely dormant, zero cost. */
    data object Disabled : UsbExclusiveState

    /** Enabled, but no USB Audio Class device attached. */
    data object NoDevice : UsbExclusiveState

    /** A DAC is attached; permission not yet requested/granted. */
    data class DeviceDetected(val device: UsbDeviceInfo) : UsbExclusiveState

    /** System permission dialog in flight. */
    data class PermissionPending(val device: UsbDeviceInfo) : UsbExclusiveState

    /** The user said no; a retry can be offered. */
    data class PermissionDenied(val device: UsbDeviceInfo) : UsbExclusiveState

    /** Device opened, capabilities probed, session established — the engine can attach. */
    data class Ready(val device: UsbDeviceInfo, val capabilities: UacCapabilities) : UsbExclusiveState

    /** Audio is flowing to the DAC. */
    data class Active(
        val device: UsbDeviceInfo,
        val capabilities: UacCapabilities,
        val format: NegotiatedFormat,
        val source: SourceFormat,
        val conversion: Conversion,
        val hardwareVolume: Boolean
    ) : UsbExclusiveState

    /** Something failed; [recoverable] states can be retried from the UI. */
    data class Error(val message: String, val recoverable: Boolean) : UsbExclusiveState
}

/**
 * Owns the USB exclusive mode state machine and the [UsbAudioSession] lifecycle:
 * preference × attached devices × permission events in, session + [UsbExclusiveState] out.
 * MusicService attaches/detaches the engine's sink in response to [state]; the settings and
 * player UIs render it.
 */
@Singleton
class UsbExclusiveModeController @Inject constructor(
    private val usbDeviceManager: UsbDeviceManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    @AppScope private val scope: CoroutineScope,
    private val sessionFactory: UsbSessionFactory
) {
    private val _state = MutableStateFlow<UsbExclusiveState>(UsbExclusiveState.Disabled)
    val state: StateFlow<UsbExclusiveState> = _state.asStateFlow()

    /** Fires when an engaged session is lost involuntarily (unplug) — the service pauses. */
    private val _sessionLost = MutableSharedFlow<UsbDeviceInfo>(extraBufferCapacity = 4)
    val sessionLost: SharedFlow<UsbDeviceInfo> = _sessionLost.asSharedFlow()

    /** The established session while in Ready/Active; the engine builds its sink around it. */
    @Volatile
    var activeSession: UsbAudioSession? = null
        private set

    private val mutex = Mutex()
    private var sessionDevice: UsbDeviceInfo? = null
    private var deniedDeviceKeys = mutableSetOf<String>()
    private var requestedDeviceKeys = mutableSetOf<String>()

    init {
        scope.launch {
            combine(
                userPreferencesRepository.usbExclusiveModeEnabledFlow,
                usbDeviceManager.attachedAudioDevices
            ) { enabled, devices -> enabled to devices }
                .collect { (enabled, devices) -> reconcile(enabled, devices) }
        }
        scope.launch {
            usbDeviceManager.permissionEvents.collect { result -> onPermissionResult(result) }
        }
    }

    /** Called from the settings UI to retry after a denial. */
    fun retryPermission(device: UsbDeviceInfo) {
        deniedDeviceKeys.remove(device.key)
        requestedDeviceKeys.remove(device.key)
        scope.launch {
            reconcile(
                userPreferencesRepository.usbExclusiveModeEnabledFlow.first(),
                usbDeviceManager.attachedAudioDevices.value
            )
        }
    }

    /** Sink → controller: the negotiated stream format changed (or ended with null). */
    fun onSinkFormatChanged(format: NegotiatedFormat?, source: SourceFormat?) {
        val device = sessionDevice ?: return
        val session = activeSession ?: return
        _state.value = if (format != null && source != null) {
            UsbExclusiveState.Active(
                device = device,
                capabilities = session.capabilities,
                format = format,
                source = source,
                conversion = format.conversion,
                hardwareVolume = session.capabilities.volume != null
            )
        } else {
            UsbExclusiveState.Ready(device, session.capabilities)
        }
    }

    /** Sink → controller: writes started failing (device unplugged or driver died). */
    fun onSessionDead() {
        scope.launch {
            mutex.withLock {
                val device = sessionDevice ?: return@launch
                Timber.tag(TAG).w("Session died for %s", device.key)
                closeSessionLocked(lost = true)
                _state.value = UsbExclusiveState.NoDevice
            }
            usbDeviceManager.refreshAttachedDevices()
        }
    }

    /** Maps a 0..1 slider fraction onto the DAC's hardware volume range. */
    fun setHardwareVolume(fraction: Float): Boolean {
        val session = activeSession ?: return false
        val range = session.volumeRangeDb256() ?: return false
        val (min, max, resolution) = Triple(range[0], range[1], range[2].coerceAtLeast(1))
        val target = min + ((max - min) * fraction.coerceIn(0f, 1f)).toInt()
        val stepped = min + ((target - min) / resolution) * resolution
        return session.setVolumeDb256(stepped)
    }

    private suspend fun reconcile(enabled: Boolean, devices: List<UsbDeviceInfo>) {
        mutex.withLock {
            if (!enabled) {
                closeSessionLocked(lost = false)
                deniedDeviceKeys.clear()
                requestedDeviceKeys.clear()
                _state.value = UsbExclusiveState.Disabled
                return
            }

            // Keep the current session while its device stays attached.
            val currentDevice = sessionDevice
            if (currentDevice != null) {
                val stillAttached = devices.any { it.deviceName == currentDevice.deviceName }
                val stillAlive = activeSession?.isAlive != false
                if (stillAttached && stillAlive) return
                closeSessionLocked(lost = true)
            }

            val device = devices.firstOrNull()
            if (device == null) {
                _state.value = UsbExclusiveState.NoDevice
                return
            }

            when {
                device.hasPermission -> openSessionLocked(device)
                device.key in deniedDeviceKeys ->
                    _state.value = UsbExclusiveState.PermissionDenied(device)
                device.key in requestedDeviceKeys ->
                    _state.value = UsbExclusiveState.PermissionPending(device)
                else -> {
                    // The user opted into exclusive mode, so ask right away on attach.
                    requestedDeviceKeys.add(device.key)
                    _state.value = UsbExclusiveState.PermissionPending(device)
                    usbDeviceManager.requestPermission(device)
                }
            }
        }
    }

    private suspend fun onPermissionResult(result: UsbPermissionResult) {
        when (result) {
            is UsbPermissionResult.Granted -> {
                deniedDeviceKeys.remove(result.device.key)
                userPreferencesRepository.rememberUsbDevice(
                    result.device.key,
                    com.theveloper.pixelplay.data.usb.UsbRememberedDevice(label = result.device.displayName)
                )
                mutex.withLock {
                    if (sessionDevice == null &&
                        userPreferencesRepository.usbExclusiveModeEnabledFlow.first()
                    ) {
                        openSessionLocked(result.device)
                    }
                }
            }

            is UsbPermissionResult.Denied -> {
                deniedDeviceKeys.add(result.device.key)
                if (sessionDevice == null) {
                    _state.value = UsbExclusiveState.PermissionDenied(result.device)
                }
            }
        }
    }

    private suspend fun openSessionLocked(device: UsbDeviceInfo) {
        val opened = withContext(Dispatchers.IO) {
            val connection = usbDeviceManager.openConnection(device)
                ?: return@withContext OpenOutcome.Failed("Could not open ${device.displayName}", true)

            val raw = connection.rawDescriptors
                ?: return@withContext OpenOutcome.Failed("No descriptors from ${device.displayName}", false)
                    .also { connection.close() }

            val topology = when (val parsed = UsbDescriptorParser.parse(raw)) {
                is ParseResult.Failure -> {
                    connection.close()
                    return@withContext OpenOutcome.Failed(
                        "${device.displayName}: ${parsed.reason}", false
                    )
                }
                is ParseResult.Success -> parsed.topology
            }

            val controlTransfer = UsbControlTransfer { requestType, request, value, index, buffer ->
                connection.controlTransfer(requestType, request, value, index, buffer, buffer.size, 1000)
            }
            val capabilities = UacCapabilityProber.probe(topology, controlTransfer)
            if (capabilities.formats.isEmpty()) {
                connection.close()
                return@withContext OpenOutcome.Failed(
                    "${device.displayName} reports no playable formats", false
                )
            }

            val session = sessionFactory.open(connection, capabilities)
                ?: return@withContext OpenOutcome.Failed(
                    "Driver could not attach to ${device.displayName}", true
                ).also { connection.close() }

            OpenOutcome.Opened(session, capabilities)
        }

        when (opened) {
            is OpenOutcome.Opened -> {
                activeSession = opened.session
                sessionDevice = device
                _state.value = UsbExclusiveState.Ready(device, opened.capabilities)
                Timber.tag(TAG).i(
                    "USB exclusive ready: %s (%s, rates=%s)",
                    device.displayName, opened.capabilities.version,
                    opened.capabilities.allSampleRatesHz
                )
            }

            is OpenOutcome.Failed -> {
                Timber.tag(TAG).w("USB exclusive open failed: %s", opened.message)
                _state.value = UsbExclusiveState.Error(opened.message, opened.recoverable)
            }
        }
    }

    private suspend fun closeSessionLocked(lost: Boolean) {
        val session = activeSession ?: return
        val device = sessionDevice
        activeSession = null
        sessionDevice = null
        withContext(Dispatchers.IO) { runCatching { session.close() } }
        if (lost && device != null) {
            _sessionLost.tryEmit(device)
        }
        Timber.tag(TAG).i("USB session closed (lost=%b)", lost)
    }

    private sealed interface OpenOutcome {
        data class Opened(val session: UsbAudioSession, val capabilities: UacCapabilities) : OpenOutcome
        data class Failed(val message: String, val recoverable: Boolean) : OpenOutcome
    }

    private companion object {
        private const val TAG = "UsbExclusiveMode"
    }
}

/** Indirection over [UsbAudioSession.open] so the state machine is unit-testable. */
fun interface UsbSessionFactory {
    fun open(connection: UsbDeviceConnection, capabilities: UacCapabilities): UsbAudioSession?
}
