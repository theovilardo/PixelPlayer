package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.di.IoDispatcher
import com.theveloper.pixelplay.di.MainDispatcher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Decides whether a song needs to be re-encoded before it is sent to the watch, and performs
 * that re-encoding with [Transformer].
 *
 * Lossless/high-bitrate sources are re-encoded to AAC-LC at [TARGET_BITRATE_BPS]: watches decode
 * AAC in hardware but most FLAC decoding on Wear OS SoCs is software-only, and lossless files are
 * also far larger to transfer and store on a watch's very limited flash. Sources that are already
 * a compressed lossy format at or below the passthrough bitrate are sent through untouched (see
 * [PhoneDirectWatchTransferCoordinator]) — re-encoding an already-small MP3 down to
 * [TARGET_BITRATE_BPS] would only cost CPU and quality for no size benefit worth the transfer
 * time saved.
 */
@UnstableApi
@Singleton
class WatchAudioTranscoder @Inject constructor(
    private val application: Application,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    sealed class TranscodeResult {
        /** The source is already an acceptable lossy format; send it as-is. */
        data object Passthrough : TranscodeResult()
        data class Transcoded(val outputFile: File) : TranscodeResult()
        data class Failed(val error: Throwable) : TranscodeResult()
    }

    /**
     * Pure decision function, kept separate from the actual encode so it's cheap to unit test —
     * and free of I/O, since [WatchPlaylistTransferEstimator] calls it once per song straight
     * from composition to size the confirmation sheet.
     *
     * Uses only the bitrate the library already knows. That's frequently null (a normal
     * MediaStore scan doesn't read per-file bitrate — only `SyncWorker`'s deep scan does), and an
     * unknown bitrate is treated as "not eligible" here purely because this overload has no way
     * to find out; [transcodeIfNeeded] probes the file itself before deciding, so a null here
     * doesn't condemn the song to a needless re-encode.
     */
    fun requiresTranscoding(song: Song): Boolean =
        requiresTranscoding(song.mimeType, song.bitrate?.takeIf { it > 0 })

    internal fun requiresTranscoding(mimeType: String?, bitrateBps: Int?): Boolean {
        val normalizedMimeType = mimeType?.lowercase(Locale.ROOT)
        val isPassthroughEligible = normalizedMimeType != null &&
            PASSTHROUGH_MIME_TYPES.contains(normalizedMimeType) &&
            bitrateBps != null &&
            bitrateBps <= MAX_PASSTHROUGH_BITRATE_BPS
        return !isPassthroughEligible
    }

    /**
     * Bitrate to assume when sizing a transfer up front, without touching the file — see
     * [WatchPlaylistTransferEstimator].
     *
     * A lossy source with no known bitrate is the common case for a plain MediaStore-scanned
     * library, and [transcodeIfNeeded] will probe it and most likely send it through untouched at
     * whatever its real bitrate is. Assuming [TARGET_BITRATE_BPS] for those would systematically
     * undershoot the estimate; [ASSUMED_UNKNOWN_LOSSY_BITRATE_BPS] sits between the typical real
     * value and the passthrough ceiling instead.
     */
    fun estimatedTransferBitrateBps(song: Song): Int {
        val knownBitrate = song.bitrate?.takeIf { it > 0 }
        val isPassthroughMimeType = song.mimeType?.lowercase(Locale.ROOT)
            ?.let(PASSTHROUGH_MIME_TYPES::contains) == true
        return when {
            knownBitrate == null && isPassthroughMimeType -> ASSUMED_UNKNOWN_LOSSY_BITRATE_BPS
            requiresTranscoding(song.mimeType, knownBitrate) -> TARGET_BITRATE_BPS
            else -> knownBitrate ?: TARGET_BITRATE_BPS
        }
    }

    /**
     * Runs the transcode if [requiresTranscoding] says it's needed, reporting encode progress
     * as a 0f..1f fraction via [onProgress]. Callers own [TranscodeResult.Transcoded.outputFile]
     * and must delete it (via [cleanup]) once it has been sent or the transfer is abandoned.
     */
    suspend fun transcodeIfNeeded(
        song: Song,
        requestId: String,
        onProgress: (Float) -> Unit = {},
    ): TranscodeResult {
        if (!requiresTranscoding(song.mimeType, resolveBitrateBps(song))) return TranscodeResult.Passthrough

        val inputMediaItem = buildInputMediaItem(song)
            ?: return TranscodeResult.Failed(IllegalStateException("No readable local audio source for songId=${song.id}"))

        val outputFile = outputFileFor(song.id, requestId)
        outputFile.parentFile?.mkdirs()

        return runTransform(inputMediaItem, outputFile, onProgress)
    }

    /**
     * The library's bitrate for [song] when the scan captured one, otherwise read from the file
     * itself. Without this probe every song in a plainly-scanned library looks like "unknown
     * bitrate" and gets re-encoded to [TARGET_BITRATE_BPS] — quality lost on an already-lossy
     * source, and minutes of needless CPU per playlist — because only `SyncWorker`'s deep scan
     * populates `Song.bitrate` at all.
     *
     * Never fails a transfer: anything unreadable falls back to null, which keeps the old
     * conservative "re-encode it" answer.
     */
    private suspend fun resolveBitrateBps(song: Song): Int? {
        song.bitrate?.takeIf { it > 0 }?.let { return it }
        return withContext(ioDispatcher) { probeBitrateBps(song) }
    }

    private fun probeBitrateBps(song: Song): Int? {
        val source = buildInputMediaItem(song)?.localConfiguration?.uri ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            when (source.scheme?.lowercase(Locale.ROOT)) {
                "content" -> retriever.setDataSource(application, source)
                else -> retriever.setDataSource(source.path ?: return null)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to probe bitrate for songId=%s", song.id)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun cleanup(result: TranscodeResult) {
        if (result is TranscodeResult.Transcoded) {
            runCatching { result.outputFile.delete() }
                .onFailure { error -> Timber.tag(TAG).w(error, "Failed to delete transcoded temp file") }
        }
    }

    // Transformer must be built and started on a thread that has a Looper — the main thread is
    // the one Android guarantees has one, so this can't move to an injected background dispatcher.
    private suspend fun runTransform(
        inputMediaItem: MediaItem,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): TranscodeResult = withContext(mainDispatcher) {
        // Every other await in this pipeline has a backstop; this one used to have none, and it
        // resumes only from Transformer's own onCompleted/onError. A wedged encoder (a malformed
        // or DRM'd source) therefore stalled the whole batch forever, with the notification frozen
        // at its last value and a cancel unable to take effect until the transcode returned.
        // Timing out here rather than at the call site keeps the cancellation — and so
        // transformer.cancel() via invokeOnCancellation — on the Looper thread Media3 requires.
        withTimeoutOrNull(TRANSCODE_TIMEOUT_MS) {
            runTransformUntilTerminal(inputMediaItem, outputFile, onProgress)
        } ?: TranscodeResult.Failed(
            IllegalStateException("Transcode timed out after ${TRANSCODE_TIMEOUT_MS}ms")
        )
    }

    private suspend fun runTransformUntilTerminal(
        inputMediaItem: MediaItem,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): TranscodeResult {
        return suspendCancellableCoroutine { continuation ->
            val encoderFactory = DefaultEncoderFactory.Builder(application)
                .setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder().setBitrate(TARGET_BITRATE_BPS).build()
                )
                .build()

            val transformer = Transformer.Builder(application)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) {
                            continuation.resume(TranscodeResult.Transcoded(outputFile))
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        // Transformer does not delete partial output on failure — see its Listener docs.
                        runCatching { outputFile.delete() }
                        if (continuation.isActive) {
                            continuation.resume(TranscodeResult.Failed(exportException))
                        }
                    }
                })
                .build()

            continuation.invokeOnCancellation {
                transformer.cancel()
                runCatching { outputFile.delete() }
            }

            pollProgress(transformer, continuation, onProgress)

            transformer.start(inputMediaItem, outputFile.absolutePath)
        }
    }

    private fun pollProgress(
        transformer: Transformer,
        continuation: CancellableContinuation<TranscodeResult>,
        onProgress: (Float) -> Unit,
    ) {
        val progressHolder = ProgressHolder()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                if (!continuation.isActive) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f)
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
                }
            }
        }
        handler.postDelayed(poll, PROGRESS_POLL_INTERVAL_MS)
    }

    private fun buildInputMediaItem(song: Song): MediaItem? {
        val directFile = song.path.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.isFile && it.canRead() && it.length() > 0L }
        if (directFile != null) {
            return MediaItem.fromUri(Uri.fromFile(directFile))
        }

        val rawUri = song.contentUriString
        if (rawUri.isBlank()) return null
        if (rawUri.startsWith("/")) {
            val rawFile = File(rawUri)
            if (rawFile.isFile && rawFile.canRead() && rawFile.length() > 0L) {
                return MediaItem.fromUri(Uri.fromFile(rawFile))
            }
        }

        val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file", "content" -> MediaItem.fromUri(uri)
            else -> null
        }
    }

    private fun outputFileFor(songId: String, requestId: String): File {
        val dir = File(application.cacheDir, "watch_transfer")
        return File(dir, "${songId}_$requestId.m4a")
    }

    companion object {
        /** Also used by [WatchPlaylistTransferEstimator] to size-estimate songs that will be transcoded. */
        const val TARGET_BITRATE_BPS = 128_000

        /**
         * Container mime type of [transcodeIfNeeded]'s output file (an .m4a produced by
         * [Transformer]'s default muxer) — used by callers reporting [WatchAudioOverride][
         * PhoneDirectWatchTransferCoordinator.WatchAudioOverride] metadata to the watch.
         */
        const val TRANSCODED_OUTPUT_MIME_TYPE = "audio/mp4"

        private const val TAG = "WatchAudioTranscoder"
        private const val MAX_PASSTHROUGH_BITRATE_BPS = 256_000
        private const val PROGRESS_POLL_INTERVAL_MS = 250L

        /** See [estimatedTransferBitrateBps] — a mid-range guess for an unprobed lossy source. */
        private const val ASSUMED_UNKNOWN_LOSSY_BITRATE_BPS = 192_000

        // Generous relative to a real encode (a few seconds for a normal song on a modern phone,
        // and Media3 reports progress throughout): this only exists to unstick an encoder that
        // has stopped making progress entirely, not to cap a slow-but-working one.
        private const val TRANSCODE_TIMEOUT_MS = 180_000L
        private val PASSTHROUGH_MIME_TYPES = setOf(
            "audio/mpeg",
            "audio/mp4",
            "audio/aac",
            "audio/mp4a-latm",
            "audio/ogg",
            "audio/opus",
        )
    }
}
