package com.theveloper.pixelplay.usbaudio

/**
 * Thin JNI surface over the native USB Audio Class driver (libusb-based).
 *
 * The native library is loaded lazily via [ensureLoaded] so that the normal
 * (non-exclusive) playback path never pays for it — callers must invoke
 * [ensureLoaded] before the first native call.
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
}
