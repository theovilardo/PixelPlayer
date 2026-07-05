package com.theveloper.pixelplay.utils

import android.os.Build

object PlatformUtils {
    fun isUbuntuTouch(): Boolean {
        return try {
            val properties = System.getProperties()
            properties.getProperty("os.name", "")
                .contains("ubuntu", ignoreCase = true) ||
            properties.getProperty("os.version", "")
                .contains("ubuntu", ignoreCase = true) ||
            Build.DISPLAY.contains("ubuntu", ignoreCase = true) ||
            Build.HOST?.contains("ubuntu", ignoreCase = true) == true ||
            Build.FINGERPRINT?.contains("ubuntu", ignoreCase = true) == true ||
            Build.MODEL?.contains("ubuntu", ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }
    }

    fun isWaydroid(): Boolean {
        return try {
            val properties = System.getProperties()
            properties.getProperty("java.vendor.url", "")
                .contains("waydroid", ignoreCase = true) ||
            Build.HOST?.contains("waydroid", ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }
    }

    fun isRunningOnLinux(): Boolean {
        return try {
            val osName = System.getProperty("os.name") ?: ""
            osName.contains("linux", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    fun isEmulatedStorage(): Boolean {
        return Build.DEVICE?.contains("generic", ignoreCase = true) == true ||
            Build.PRODUCT?.contains("generic", ignoreCase = true) == true ||
            Build.HARDWARE?.contains("goldfish", ignoreCase = true) == true ||
            Build.FINGERPRINT?.contains("generic", ignoreCase = true) == true
    }

    val isLowRamDevice: Boolean get() = runCatching {
        val activityManager = android.app.ActivityManager::class.java
            .getMethod("isLowRamDevice")
        activityManager.invoke(null) as? Boolean ?: false
    }.getOrDefault(false)

    val totalMemoryMb: Long get() = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        android.app.ActivityManager::class.java
            .getMethod("getMemoryInfo", android.app.ActivityManager.MemoryInfo::class.java)
            .invoke(null, info)
        info.totalMem / (1024 * 1024)
    }.getOrDefault(0L)
}
