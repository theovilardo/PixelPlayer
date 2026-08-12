package com.theveloper.pixelplay.data.service.wear

import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.model.Song

/**
 * Aggregate size/time estimate shown on the send-to-watch confirmation sheet, computed only over
 * the songs that still need to be transferred (already-on-watch songs are skipped by the dedupe
 * in [PlaylistWatchTransferCoordinator], so they don't cost bandwidth or storage).
 */
data class WatchPlaylistTransferEstimate(
    val totalSongCount: Int,
    val pendingSongCount: Int,
    val estimatedBytes: Long,
    val estimatedTransferSeconds: Long,
)

/**
 * Pure size/time heuristics for the whole-playlist watch transfer confirmation UI. Kept separate
 * from [WatchAudioTranscoder] (which does the real encode) so it stays cheap to unit test.
 */
@UnstableApi
object WatchPlaylistTransferEstimator {

    /**
     * Assumed throughput for the single Bluetooth channel used for the transfer (phase 1 — no
     * Wi-Fi transport yet). This is the Wearable Data Layer ChannelClient rate, not raw Bluetooth
     * bandwidth, and is deliberately conservative: an estimate that undershoots the real time
     * erodes trust in the confirmation sheet more than one that's a bit pessimistic. Needs
     * re-measuring against a real phone+watch pair once device testing resumes — see §R-04 of the
     * Wear OS guide for the documented range (~50–150 KB/s) this sits below on purpose.
     */
    private const val ASSUMED_TRANSFER_RATE_BYTES_PER_SEC = 40_000L

    fun estimateBytesForSong(song: Song, transcoder: WatchAudioTranscoder): Long {
        val effectiveBitrateBps = if (transcoder.requiresTranscoding(song)) {
            WatchAudioTranscoder.TARGET_BITRATE_BPS
        } else {
            song.bitrate ?: WatchAudioTranscoder.TARGET_BITRATE_BPS
        }
        val durationSeconds = song.duration / 1000.0
        return (durationSeconds * effectiveBitrateBps / 8.0).toLong().coerceAtLeast(0L)
    }

    fun estimate(
        allSongs: List<Song>,
        pendingSongs: List<Song>,
        transcoder: WatchAudioTranscoder,
    ): WatchPlaylistTransferEstimate {
        val totalBytes = pendingSongs.sumOf { estimateBytesForSong(it, transcoder) }
        return WatchPlaylistTransferEstimate(
            totalSongCount = allSongs.size,
            pendingSongCount = pendingSongs.size,
            estimatedBytes = totalBytes,
            estimatedTransferSeconds = estimateTransferSeconds(totalBytes),
        )
    }

    private fun estimateTransferSeconds(totalBytes: Long): Long {
        if (totalBytes <= 0L) return 0L
        return (totalBytes / ASSUMED_TRANSFER_RATE_BYTES_PER_SEC).coerceAtLeast(1L)
    }
}
