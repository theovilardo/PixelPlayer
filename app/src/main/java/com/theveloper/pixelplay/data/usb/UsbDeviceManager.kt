package com.theveloper.pixelplay.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Tracks attached USB Audio Class devices and owns the USB permission flow.
 *
 * Detection is broadcast-driven (ATTACHED/DETACHED) plus an enumeration at construction time so
 * a DAC that was already plugged in when the app started is found too. Only devices exposing an
 * AudioStreaming interface (class 0x01 / subclass 0x02) are listed.
 */
@Singleton
class UsbDeviceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _attachedAudioDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val attachedAudioDevices: StateFlow<List<UsbDeviceInfo>> = _attachedAudioDevices.asStateFlow()

    private val _permissionEvents = MutableSharedFlow<UsbPermissionResult>(extraBufferCapacity = 8)
    val permissionEvents: SharedFlow<UsbPermissionResult> = _permissionEvents.asSharedFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshAttachedDevices()

                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    // Refresh first so the emitted snapshot carries hasPermission/serial info.
                    refreshAttachedDevices()
                    if (device != null) {
                        val info = device.toInfo()
                        Timber.tag(TAG).i("USB permission %s for %s", if (granted) "granted" else "denied", info.key)
                        _permissionEvents.tryEmit(
                            if (granted) UsbPermissionResult.Granted(info)
                            else UsbPermissionResult.Denied(info)
                        )
                    }
                }
            }
        }
    }

    init {
        if (usbManager != null) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            // NOT_EXPORTED still receives system broadcasts; the permission action is only ever
            // sent by our own PendingIntent (explicit package below).
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            refreshAttachedDevices()
        } else {
            Timber.tag(TAG).w("UsbManager unavailable; USB exclusive mode disabled")
        }
    }

    /** Shows the system USB permission dialog; result arrives on [permissionEvents]. */
    fun requestPermission(device: UsbDeviceInfo) {
        val manager = usbManager ?: return
        val raw = findRawDevice(device) ?: run {
            Timber.tag(TAG).w("requestPermission: %s no longer attached", device.key)
            _permissionEvents.tryEmit(UsbPermissionResult.Denied(device))
            return
        }
        if (manager.hasPermission(raw)) {
            _permissionEvents.tryEmit(UsbPermissionResult.Granted(raw.toInfo()))
            return
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        // FLAG_MUTABLE: the system fills in EXTRA_DEVICE/EXTRA_PERMISSION_GRANTED.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            device.key.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.requestPermission(raw, pendingIntent)
    }

    fun hasPermission(device: UsbDeviceInfo): Boolean {
        val manager = usbManager ?: return false
        val raw = findRawDevice(device) ?: return false
        return manager.hasPermission(raw)
    }

    /**
     * Opens the device, yielding the file descriptor holder the native driver wraps.
     * Returns null when the device is gone or permission is missing.
     */
    fun openConnection(device: UsbDeviceInfo): UsbDeviceConnection? {
        val manager = usbManager ?: return null
        val raw = findRawDevice(device) ?: return null
        if (!manager.hasPermission(raw)) return null
        return manager.openDevice(raw)
    }

    fun refreshAttachedDevices() {
        val manager = usbManager ?: return
        val devices = manager.deviceList.values
            .filter(::hasAudioStreamingInterface)
            .map { it.toInfo() }
            .sortedBy { it.deviceName }
        _attachedAudioDevices.value = devices
    }

    private fun findRawDevice(device: UsbDeviceInfo): UsbDevice? =
        usbManager?.deviceList?.values?.firstOrNull { it.deviceName == device.deviceName }

    private fun UsbDevice.toInfo(): UsbDeviceInfo {
        val permitted = usbManager?.hasPermission(this) == true
        return UsbDeviceInfo(
            deviceName = deviceName,
            productName = productName,
            manufacturerName = manufacturerName,
            vendorId = vendorId,
            productId = productId,
            // Reading the serial without permission throws on API 29+.
            serialNumber = if (permitted) runCatching { serialNumber }.getOrNull() else null,
            hasPermission = permitted
        )
    }

    companion object {
        private const val TAG = "UsbDeviceManager"
        const val ACTION_USB_PERMISSION = "com.theveloper.pixelplay.USB_PERMISSION"
    }
}

/**
 * True when the device exposes a USB Audio Class AudioStreaming interface — i.e. it can carry
 * an audio stream, as opposed to audio-class control-only or HID/storage composites.
 */
internal fun hasAudioStreamingInterface(device: UsbDevice): Boolean {
    for (i in 0 until device.interfaceCount) {
        val itf = device.getInterface(i)
        if (itf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
            itf.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING
        ) {
            return true
        }
    }
    return false
}

/** USB Audio Class interface subclass code for AudioStreaming (UAC1 §A.2 / UAC2 §A.5). */
internal const val USB_SUBCLASS_AUDIOSTREAMING = 0x02
