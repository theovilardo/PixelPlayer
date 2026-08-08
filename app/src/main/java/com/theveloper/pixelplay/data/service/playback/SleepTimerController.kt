package com.theveloper.pixelplay.data.service.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.service.SleepTimerReceiver
import timber.log.Timber

/**
 * Manages duration-based and end-of-track sleep timers.
 *
 * Extracted from [MusicService] to isolate timer scheduling and
 * cancellation logic from the service's media-session lifecycle.
 */
class SleepTimerController(
    private val context: Context,
    private val alarmManager: AlarmManager,
    private val actionSleepTimerExpired: String,
    private val currentPlayer: () -> Player?,
    private val currentMediaId: () -> String?,
) {

    var endOfTrackTimerSongId: String? = null
        private set

    fun createSleepTimerPendingIntent(): PendingIntent {
        val intent = Intent(context, SleepTimerReceiver::class.java).apply {
            action = actionSleepTimerExpired
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancelDurationSleepTimerInternal() {
        alarmManager.cancel(createSleepTimerPendingIntent())
    }

    fun setDurationSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimers()
            return
        }
        endOfTrackTimerSongId = null
        val triggerAtMillis = System.currentTimeMillis() + (minutes * 60_000L)
        val pendingIntent = createSleepTimerPendingIntent()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent,
                    )
                }
            } else
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            Timber.tag(TAG).d("Sleep timer set for %d minutes", minutes)
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Exact alarm denied; using inexact sleep timer")
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun setEndOfTrackSleepTimer(enabled: Boolean) {
        if (!enabled) {
            endOfTrackTimerSongId = null
            Timber.tag(TAG).d("End-of-track timer disabled")
            return
        }
        cancelDurationSleepTimerInternal()
        val songId = currentMediaId()
        if (songId.isNullOrBlank()) {
            endOfTrackTimerSongId = null
            Timber.tag(TAG).d("End-of-track timer ignored: no active song")
            return
        }
        endOfTrackTimerSongId = songId
        Timber.tag(TAG).d("End-of-track timer set for mediaId=%s", songId)
    }

    fun cancelSleepTimers() {
        cancelDurationSleepTimerInternal()
        endOfTrackTimerSongId = null
        Timber.tag(TAG).d("Sleep timers cancelled")
    }

    /**
     * Checks if the current track matches the end-of-track timer and pauses if so.
     * Returns true if the timer fired, false otherwise.
     */
    fun checkEndOfTrackTimer(): Boolean {
        val timerSongId = endOfTrackTimerSongId ?: return false
        val player = currentPlayer() ?: return false
        val currentId = player.currentMediaItem?.mediaId
        if (currentId == timerSongId && player.isPlaying) {
            player.pause()
            endOfTrackTimerSongId = null
            Timber.tag(TAG).d("End-of-track timer fired: paused playback")
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "SleepTimerController"
    }
}
