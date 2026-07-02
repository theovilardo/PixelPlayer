package com.theveloper.pixelplay.usbaudio

import java.nio.ByteBuffer

/**
 * Thin JNI surface over the native USB Audio Class driver (libusb-based).
 *
 * The native library is loaded lazily via [ensureLoaded] so that the normal
 * (non-exclusive) playback path never pays for it — callers must invoke
 * [ensureLoaded] before the first native call. [UsbAudioSession] is the only
 * intended caller; keep these externals free of business logic.
 *
 * All methods taking a `handle` expect the value returned by [nativeCreate];
 * a zero handle means creation failed. Return convention: 0 = success,
 * negative = failure (details via [nativeGetLastError]).
 */
object UsbAudioNative {

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("usbaudio_jni")
                    loaded = true
                }
            }
        }
    }

    /** Returns the version string of the bundled libusb, e.g. "libusb 1.0.30". */
    external fun nativeGetVersion(): String

    /** Wraps the UsbDeviceConnection fd; returns an opaque session handle or 0. */
    external fun nativeCreate(fd: Int): Long

    external fun nativeDestroy(handle: Long)

    /** Detaches the kernel driver and claims the AudioControl + AudioStreaming interfaces. */
    external fun nativeClaim(handle: Long, acInterface: Int, asInterface: Int): Int

    /** Selects the alt setting and (re)builds the iso pipeline; call [nativeStart] after. */
    external fun nativeConfigureStream(
        handle: Long,
        asInterface: Int,
        altSetting: Int,
        endpointAddress: Int,
        feedbackEndpointAddress: Int,
        rateHz: Int,
        channels: Int,
        subslotBytes: Int,
        intervalCode: Int,
        maxPacketSize: Int,
        ringBufferMs: Int
    ): Int

    external fun nativeSetSampleRate(
        handle: Long,
        uacVersion: Int,
        clockId: Int,
        acInterface: Int,
        endpointAddress: Int,
        rateHz: Int
    ): Int

    external fun nativeStart(handle: Long): Int
    external fun nativePause(handle: Long): Int
    external fun nativeResume(handle: Long): Int
    external fun nativeFlush(handle: Long): Int

    /** Stops streaming and selects alt setting 0, releasing the iso bandwidth. */
    external fun nativeStop(handle: Long, asInterface: Int): Int

    /**
     * Queues PCM from a direct [ByteBuffer]. Returns bytes accepted (0 = ring full,
     * caller retries later), or negative on error/dead stream. Never blocks.
     */
    external fun nativeWrite(handle: Long, buffer: ByteBuffer, offset: Int, size: Int): Int

    /** Frames of real audio in completed transfers — the playback position. */
    external fun nativeGetPlayedFrames(handle: Long): Long

    /** Frames consumed from the ring (played + in flight) — the flush rebase point. */
    external fun nativeGetConsumedFrames(handle: Long): Long

    /** Frames still queued (ring + in flight). */
    external fun nativeGetBufferedFrames(handle: Long): Long

    external fun nativeGetXrunCount(handle: Long): Int

    /** False once the device vanished or the pipeline died. */
    external fun nativeIsAlive(handle: Long): Boolean

    /** [min, max, res] in 1/256 dB, or null when the unit doesn't answer. */
    external fun nativeGetVolumeRangeDb256(
        handle: Long,
        uacVersion: Int,
        unitId: Int,
        acInterface: Int
    ): IntArray?

    external fun nativeSetVolumeDb256(
        handle: Long,
        uacVersion: Int,
        unitId: Int,
        acInterface: Int,
        valueDb256: Int
    ): Int

    external fun nativeSetMute(
        handle: Long,
        uacVersion: Int,
        unitId: Int,
        acInterface: Int,
        mute: Boolean
    ): Int

    external fun nativeGetLastError(handle: Long): String?
}
