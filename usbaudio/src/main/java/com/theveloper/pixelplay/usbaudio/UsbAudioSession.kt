package com.theveloper.pixelplay.usbaudio

import android.hardware.usb.UsbDeviceConnection
import com.theveloper.pixelplay.usbaudio.descriptor.UacCapabilities
import com.theveloper.pixelplay.usbaudio.descriptor.UacVersion
import com.theveloper.pixelplay.usbaudio.negotiation.NegotiatedFormat
import java.nio.ByteBuffer

/**
 * One exclusive streaming session with a USB DAC.
 *
 * Owns the native driver handle and the [UsbDeviceConnection] whose file descriptor the
 * driver wraps — the connection must stay open for the session's whole life, so the session
 * takes ownership and closes it in [close].
 *
 * Single-owner: created by the exclusive-mode controller, handed to the audio sink, closed
 * exactly once. All methods are safe to call after [close] (they become no-ops / failures)
 * so a racing detach can't crash the playback thread.
 */
class UsbAudioSession private constructor(
    private val connection: UsbDeviceConnection,
    private val handle: Long,
    val capabilities: UacCapabilities
) : AutoCloseable {

    private val lock = Any()
    private var closed = false
    private var claimed = false

    /** The stream configuration currently programmed into the DAC, if any. */
    @Volatile
    var currentFormat: NegotiatedFormat? = null
        private set

    private val uacVersionCode: Int
        get() = if (capabilities.version == UacVersion.UAC2) 2 else 1

    /**
     * Programs [format]: claims interfaces on first use, selects the alt setting, sets the
     * sample rate and starts the iso pipeline. Returns false (with [lastError] populated)
     * on any failure; the session stays usable for a retry with another format.
     */
    fun configure(format: NegotiatedFormat, ringBufferMs: Int = DEFAULT_RING_BUFFER_MS): Boolean {
        synchronized(lock) {
            if (closed) return false
            val candidate = format.candidate

            if (!claimed) {
                if (UsbAudioNative.nativeClaim(
                        handle, capabilities.controlInterfaceNumber, candidate.interfaceNumber
                    ) != 0
                ) {
                    return false
                }
                claimed = true
            }

            if (UsbAudioNative.nativeConfigureStream(
                    handle = handle,
                    asInterface = candidate.interfaceNumber,
                    altSetting = candidate.altSetting,
                    endpointAddress = candidate.endpointAddress,
                    feedbackEndpointAddress = candidate.feedbackEndpointAddress ?: 0,
                    rateHz = format.sampleRateHz,
                    channels = candidate.channels,
                    subslotBytes = candidate.subslotBytes,
                    intervalCode = candidate.intervalCode,
                    maxPacketSize = candidate.maxPacketSize,
                    ringBufferMs = ringBufferMs
                ) != 0
            ) {
                return false
            }

            if (UsbAudioNative.nativeSetSampleRate(
                    handle = handle,
                    uacVersion = uacVersionCode,
                    clockId = candidate.clockSourceId ?: 0,
                    acInterface = capabilities.controlInterfaceNumber,
                    endpointAddress = candidate.endpointAddress,
                    rateHz = format.sampleRateHz
                ) != 0
            ) {
                // UAC1 devices without the sampling-frequency control run at a fixed or
                // auto-detected rate; treat the failure as non-fatal for them.
                if (uacVersionCode == 2 || candidate.uac1SampleRateControl) return false
            }

            if (UsbAudioNative.nativeStart(handle) != 0) return false
            currentFormat = format
            return true
        }
    }

    /** Queues PCM. Returns bytes accepted (0 = backpressure), negative when the stream died. */
    fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
        if (closed) return -1
        return UsbAudioNative.nativeWrite(handle, buffer, offset, size)
    }

    fun pause() {
        synchronized(lock) { if (!closed) UsbAudioNative.nativePause(handle) }
    }

    fun resume() {
        synchronized(lock) { if (!closed) UsbAudioNative.nativeResume(handle) }
    }

    fun flush() {
        synchronized(lock) { if (!closed) UsbAudioNative.nativeFlush(handle) }
    }

    /** Stops streaming and releases iso bandwidth (alt setting 0); format must be re-configured. */
    fun stopStream() {
        synchronized(lock) {
            if (closed) return
            currentFormat?.let {
                UsbAudioNative.nativeStop(handle, it.candidate.interfaceNumber)
            }
            currentFormat = null
        }
    }

    val playedFrames: Long
        get() = if (closed) 0 else UsbAudioNative.nativeGetPlayedFrames(handle)

    val consumedFrames: Long
        get() = if (closed) 0 else UsbAudioNative.nativeGetConsumedFrames(handle)

    val bufferedFrames: Long
        get() = if (closed) 0 else UsbAudioNative.nativeGetBufferedFrames(handle)

    val xrunCount: Int
        get() = if (closed) 0 else UsbAudioNative.nativeGetXrunCount(handle)

    val isAlive: Boolean
        get() = !closed && UsbAudioNative.nativeIsAlive(handle)

    val lastError: String?
        get() = if (closed) null else UsbAudioNative.nativeGetLastError(handle)

    /** [min, max, res] in 1/256 dB, or null when the DAC has no usable feature unit. */
    fun volumeRangeDb256(): IntArray? {
        synchronized(lock) {
            if (closed) return null
            val unit = capabilities.volume ?: return null
            return UsbAudioNative.nativeGetVolumeRangeDb256(
                handle, uacVersionCode, unit.featureUnitId, capabilities.controlInterfaceNumber
            )
        }
    }

    fun setVolumeDb256(valueDb256: Int): Boolean {
        synchronized(lock) {
            if (closed) return false
            val unit = capabilities.volume ?: return false
            return UsbAudioNative.nativeSetVolumeDb256(
                handle, uacVersionCode, unit.featureUnitId,
                capabilities.controlInterfaceNumber, valueDb256
            ) == 0
        }
    }

    fun setMute(mute: Boolean): Boolean {
        synchronized(lock) {
            if (closed) return false
            val unit = capabilities.volume ?: return false
            if (!unit.hasMasterMute) return false
            return UsbAudioNative.nativeSetMute(
                handle, uacVersionCode, unit.featureUnitId,
                capabilities.controlInterfaceNumber, mute
            ) == 0
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            stopStream()
            closed = true
            UsbAudioNative.nativeDestroy(handle)
            runCatching { connection.close() }
        }
    }

    companion object {
        const val DEFAULT_RING_BUFFER_MS = 250

        /**
         * Wraps an open, permission-granted connection. [capabilities] must come from
         * [com.theveloper.pixelplay.usbaudio.descriptor.UacCapabilityProber] on the same device.
         * On failure the connection is left untouched (caller keeps ownership).
         */
        fun open(connection: UsbDeviceConnection, capabilities: UacCapabilities): UsbAudioSession? {
            UsbAudioNative.ensureLoaded()
            val handle = UsbAudioNative.nativeCreate(connection.fileDescriptor)
            if (handle == 0L) return null
            return UsbAudioSession(connection, handle, capabilities)
        }
    }
}
