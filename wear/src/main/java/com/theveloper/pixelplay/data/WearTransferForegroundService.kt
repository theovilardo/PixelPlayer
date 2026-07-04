package com.theveloper.pixelplay.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.WearMainActivity
import com.theveloper.pixelplay.shared.WearTransferProgress
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the receiving side alive while a transfer from the phone is in flight. Wear OS is more
 * aggressive than phone Android about suspending background processes on screen-off/ambient —
 * without this, a long playlist transfer left running overnight (screen off, watch charging
 * flat) risks the process being paused mid-transfer with nothing to resume it, since receiving
 * is purely reactive (driven by [WearDataListenerService] callbacks, not user-initiated).
 */
@AndroidEntryPoint
class WearTransferForegroundService : Service() {

    @Inject lateinit var transferRepository: WearTransferRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transferObserverJob: Job? = null
    private var hasStartedForeground = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        observeTransfers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(transferRepository.activeTransfers.value.values.toList())
        if (!hasStartedForeground) {
            startInForeground(notification)
        } else {
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        transferObserverJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeTransfers() {
        transferObserverJob?.cancel()
        transferObserverJob = serviceScope.launch {
            transferRepository.activeTransfers.collect { transfers ->
                val states = transfers.values.toList()
                if (states.isEmpty()) {
                    // onCreate() starts collecting before onStartCommand() has had a chance to
                    // run, so this StateFlow's current (often still-empty) value can arrive
                    // before there's any real work yet. Only actually stop once we've genuinely
                    // started foreground at least once — otherwise stopSelf() here races ahead
                    // of the pending startForeground() call and crashes the whole process with
                    // ForegroundServiceDidNotStartInTimeException.
                    if (hasStartedForeground) {
                        releaseWakeLock()
                        stopForegroundCompat()
                        stopSelf()
                    }
                    return@collect
                }

                acquireWakeLock()
                val notification = buildNotification(states)
                if (!hasStartedForeground) {
                    startInForeground(notification)
                } else {
                    notificationManager().notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PixelPlay:watchTransferReceive",
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_DURATION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startInForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasStartedForeground = true
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Failed to start wear transfer foreground service")
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        if (!hasStartedForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        hasStartedForeground = false
    }

    private fun buildNotification(transfers: List<TransferState>): Notification {
        val activeTransfer = transfers
            .filter { it.status == WearTransferProgress.STATUS_TRANSFERRING }
            .maxByOrNull { it.bytesTransferred }
            ?: transfers.firstOrNull()

        val contentText = when {
            activeTransfer == null -> getString(R.string.wear_transfer_notification_starting)
            activeTransfer.songTitle.isNotBlank() -> activeTransfer.songTitle
            else -> getString(R.string.wear_transfer_notification_starting)
        }
        val progressPercent = (activeTransfer?.progress?.times(100f) ?: 0f).toInt().coerceIn(0, 100)
        val isIndeterminate = activeTransfer == null || activeTransfer.totalBytes <= 0L

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.new_monochrome)
            .setContentTitle(getString(R.string.wear_transfer_notification_title))
            .setContentText(contentText)
            .setContentIntent(createOpenAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setProgress(100, progressPercent, isIndeterminate)
            .build()
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, WearMainActivity::class.java).apply {
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.wear_transfer_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val TAG = "WearTransferFgSvc"
        private const val NOTIFICATION_CHANNEL_ID = "pixelplay_wear_transfers"
        private const val NOTIFICATION_ID = 2003
        // Safety net in case release() is ever skipped by a bug: auto-releases well past any
        // legitimately slow whole-playlist transfer instead of draining battery indefinitely.
        private const val MAX_WAKE_LOCK_DURATION_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, WearTransferForegroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to start wear transfer foreground service")
            }
        }
    }
}
