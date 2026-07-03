package com.theveloper.pixelplay.data.service.wear

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
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.theveloper.pixelplay.MainActivity
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.shared.WearTransferProgress
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class WatchTransferForegroundService : Service() {

    @Inject lateinit var transferStateStore: PhoneWatchTransferStateStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transferObserverJob: Job? = null
    private var hasStartedForeground = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        observeTransfers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(
            transferStateStore.transfers.value.values.toList(),
            transferStateStore.batchTransfers.value.values.toList(),
        )
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeTransfers() {
        transferObserverJob?.cancel()
        transferObserverJob = serviceScope.launch {
            combine(
                transferStateStore.transfers,
                transferStateStore.batchTransfers,
            ) { transfers, batches -> transfers.values.toList() to batches.values.toList() }
                .collect { (states, batches) ->
                    if (states.isEmpty() && batches.isEmpty()) {
                        stopForegroundCompat()
                        stopSelf()
                        return@collect
                    }

                    val notification = buildNotification(states, batches)
                    if (!hasStartedForeground) {
                        startInForeground(notification)
                    } else {
                        notificationManager().notify(NOTIFICATION_ID, notification)
                    }
                }
        }
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
            Timber.tag(TAG).e(error, "Failed to start watch transfer foreground service")
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

    private fun buildNotification(
        transfers: List<PhoneWatchTransferState>,
        batches: List<PhoneWatchBatchTransferState>,
    ): Notification {
        val activeBatches = batches.filter {
            it.status == WearTransferProgress.STATUS_TRANSCODING || it.status == WearTransferProgress.STATUS_TRANSFERRING
        }
        val selectedBatch = activeBatches.maxByOrNull { it.updatedAtMillis }
            ?: batches.maxByOrNull { it.updatedAtMillis }

        if (selectedBatch != null) {
            return buildBatchNotification(selectedBatch, isOngoing = activeBatches.isNotEmpty())
        }

        return buildIndividualNotification(transfers)
    }

    private fun buildBatchNotification(batch: PhoneWatchBatchTransferState, isOngoing: Boolean): Notification {
        val title = if (isOngoing) {
            getString(
                R.string.watch_transfer_status_sending_playlist_service,
                batch.playlistName,
                batch.completedSongs,
                batch.totalSongs,
            )
        } else {
            when (batch.status) {
                WearTransferProgress.STATUS_COMPLETED -> getString(R.string.watch_transfer_status_complete_service)
                WearTransferProgress.STATUS_FAILED -> getString(R.string.watch_transfer_status_failed_service)
                WearTransferProgress.STATUS_CANCELLED -> getString(R.string.watch_transfer_status_cancelled_service)
                else -> getString(R.string.watch_transfer_status_preparing_service)
            }
        }

        val statusText = when (batch.status) {
            WearTransferProgress.STATUS_TRANSCODING -> getString(R.string.watch_transfer_status_transcoding)
            WearTransferProgress.STATUS_TRANSFERRING -> getString(R.string.watch_transfer_status_transferring)
            WearTransferProgress.STATUS_COMPLETED -> getString(R.string.watch_transfer_status_completed)
            WearTransferProgress.STATUS_FAILED -> getString(R.string.watch_transfer_status_failed)
            WearTransferProgress.STATUS_CANCELLED -> getString(R.string.watch_transfer_status_cancelled)
            else -> getString(R.string.watch_transfer_status_preparing)
        }
        val contentText = batch.currentSongTitle.ifBlank { statusText }
        val progressPercent = (batch.overallProgress * 100f).toInt().coerceIn(0, 100)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(createOpenAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(isOngoing)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isOngoing) {
            builder.setProgress(100, progressPercent, false)
        } else {
            builder.setProgress(0, 0, false)
        }

        val detailText = listOfNotNull(
            batch.currentSongTitle.ifBlank { null },
            statusText,
            batch.error?.takeIf { it.isNotBlank() },
        ).joinToString(separator = "\n")
        if (detailText.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
        }

        return builder.build()
    }

    private fun buildIndividualNotification(transfers: List<PhoneWatchTransferState>): Notification {
        val activeTransfers = transfers.filter { it.status == WearTransferProgress.STATUS_TRANSFERRING }
        val selectedTransfer = activeTransfers.maxByOrNull { it.updatedAtMillis }
            ?: transfers.maxByOrNull { it.updatedAtMillis }

        val title = when {
            activeTransfers.size > 1 -> getString(R.string.watch_transfer_status_sending_n_to_watch, activeTransfers.size)
            activeTransfers.size == 1 -> getString(R.string.watch_transfer_status_sending_to_watch)
            selectedTransfer?.status == WearTransferProgress.STATUS_COMPLETED -> getString(R.string.watch_transfer_status_complete_service)
            selectedTransfer?.status == WearTransferProgress.STATUS_FAILED -> getString(R.string.watch_transfer_status_failed_service)
            selectedTransfer?.status == WearTransferProgress.STATUS_CANCELLED -> getString(R.string.watch_transfer_status_cancelled_service)
            else -> getString(R.string.watch_transfer_status_preparing_service)
        }

        val contentText = buildContentText(selectedTransfer, activeTransfers.size)
        val progressPercent = (selectedTransfer?.progress?.times(100f) ?: 0f).toInt().coerceIn(0, 100)
        val isIndeterminate = selectedTransfer == null ||
            (selectedTransfer.status == WearTransferProgress.STATUS_TRANSFERRING && selectedTransfer.totalBytes <= 0L)
        val isOngoing = activeTransfers.isNotEmpty()

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(createOpenAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(isOngoing)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isOngoing) {
            builder.setProgress(100, progressPercent, isIndeterminate)
        } else {
            builder.setProgress(0, 0, false)
        }

        if (transfers.size > 1) {
            val style = NotificationCompat.InboxStyle()
                .setSummaryText(getString(R.string.watch_transfer_summary_n, transfers.size))
            transfers
                .sortedByDescending { it.updatedAtMillis }
                .take(MAX_STYLE_LINES)
                .forEach { transfer ->
                    style.addLine(formatTransferLine(transfer))
                }
            builder.setStyle(style)
        } else {
            val detailText = buildDetailedText(selectedTransfer)
            if (detailText.isNotBlank()) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            }
        }

        return builder.build()
    }

    private fun buildContentText(
        transfer: PhoneWatchTransferState?,
        activeTransferCount: Int,
    ): String {
        if (transfer == null) return getString(R.string.watch_transfer_status_starting)

        if (activeTransferCount > 1) {
            return transfer.songTitle.ifBlank { getString(R.string.watch_transfer_status_multiple_active) }
        }

        val songTitle = transfer.songTitle.ifBlank { getString(R.string.watch_transfer_status_preparing_transfer) }
        val bytesText = formatBytesText(transfer)
        return if (bytesText != null) {
            "$songTitle • $bytesText"
        } else {
            songTitle
        }
    }

    private fun buildDetailedText(transfer: PhoneWatchTransferState?): String {
        if (transfer == null) return getString(R.string.watch_transfer_status_starting)

        val statusLine = when (transfer.status) {
            WearTransferProgress.STATUS_TRANSFERRING -> getString(R.string.watch_transfer_status_transferring)
            WearTransferProgress.STATUS_COMPLETED -> getString(R.string.watch_transfer_status_completed)
            WearTransferProgress.STATUS_FAILED -> getString(R.string.watch_transfer_status_failed)
            WearTransferProgress.STATUS_CANCELLED -> getString(R.string.watch_transfer_status_cancelled)
            else -> getString(R.string.watch_transfer_status_preparing)
        }
        val bytesLine = formatBytesText(transfer)
        val errorLine = transfer.error?.takeIf { it.isNotBlank() }

        return listOfNotNull(
            transfer.songTitle.ifBlank { null },
            statusLine,
            bytesLine,
            errorLine,
        ).joinToString(separator = "\n")
    }

    private fun formatTransferLine(transfer: PhoneWatchTransferState): String {
        val title = transfer.songTitle.ifBlank { transfer.songId }
        val status = when (transfer.status) {
            WearTransferProgress.STATUS_TRANSFERRING -> {
                val percent = (transfer.progress * 100f).toInt().coerceIn(0, 100)
                if (transfer.totalBytes > 0L) "$percent%" else getString(R.string.watch_transfer_status_starting_short)
            }
            WearTransferProgress.STATUS_COMPLETED -> getString(R.string.watch_transfer_status_completed)
            WearTransferProgress.STATUS_FAILED -> getString(R.string.watch_transfer_status_failed)
            WearTransferProgress.STATUS_CANCELLED -> getString(R.string.watch_transfer_status_cancelled)
            else -> getString(R.string.watch_transfer_status_preparing)
        }
        return "$title • $status"
    }

    private fun formatBytesText(transfer: PhoneWatchTransferState): String? {
        if (transfer.totalBytes <= 0L) return null
        val sent = Formatter.formatShortFileSize(this, transfer.bytesTransferred)
        val total = Formatter.formatShortFileSize(this, transfer.totalBytes)
        return "$sent / $total"
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
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
            getString(R.string.watch_transfer_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.watch_transfer_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val TAG = "WatchTransferFgSvc"
        private const val NOTIFICATION_CHANNEL_ID = "pixelplay_watch_transfers"
        private const val NOTIFICATION_ID = 1003
        private const val MAX_STYLE_LINES = 5

        fun start(context: Context) {
            val intent = Intent(context, WatchTransferForegroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to start watch transfer foreground service")
            }
        }
    }
}
