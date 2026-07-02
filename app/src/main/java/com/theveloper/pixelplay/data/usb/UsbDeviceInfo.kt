package com.theveloper.pixelplay.data.usb

import kotlinx.serialization.Serializable

/**
 * Snapshot of an attached USB audio-capable device, safe to hold in UI state.
 *
 * [serialNumber] is only readable once USB permission has been granted (the platform throws
 * [SecurityException] before that on API 29+), so it may be null for a device the user has not
 * yet approved. [key] therefore prefers the serial when present but stays stable without it.
 */
data class UsbDeviceInfo(
    /** Platform device path, e.g. `/dev/bus/usb/001/002`. Unique while attached. */
    val deviceName: String,
    val productName: String?,
    val manufacturerName: String?,
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String?,
    val hasPermission: Boolean
) {
    val key: String get() = usbDeviceKey(vendorId, productId, serialNumber)

    val displayName: String
        get() = productName?.takeIf { it.isNotBlank() }
            ?: "USB Audio (%04x:%04x)".format(vendorId, productId)
}

/**
 * Stable identity for a remembered device across attach/detach cycles.
 * Serial is best-effort (unreadable pre-permission, missing on some hardware).
 */
internal fun usbDeviceKey(vendorId: Int, productId: Int, serialNumber: String?): String {
    val serial = serialNumber?.takeIf { it.isNotBlank() } ?: "?"
    return "%04x:%04x:%s".format(vendorId, productId, serial)
}

/** Per-device settings persisted in DataStore, keyed by [UsbDeviceInfo.key]. */
@Serializable
data class UsbRememberedDevice(
    val label: String,
    val autoResume: Boolean = true
)

sealed interface UsbPermissionResult {
    val device: UsbDeviceInfo

    data class Granted(override val device: UsbDeviceInfo) : UsbPermissionResult
    data class Denied(override val device: UsbDeviceInfo) : UsbPermissionResult
}
