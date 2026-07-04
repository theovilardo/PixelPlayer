package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.core.graphics.get
import androidx.core.net.toUri
import com.theveloper.pixelplay.data.gdrive.GDriveStreamProxy
import com.google.android.gms.wearable.Wearable
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.NavidromeStreamProxy
import com.theveloper.pixelplay.data.netease.NeteaseStreamProxy
import com.theveloper.pixelplay.data.preferences.AlbumArtPaletteStyle
import com.theveloper.pixelplay.data.preferences.AlbumArtColorAccuracy
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.data.preferences.ThemePreference
import com.theveloper.pixelplay.data.qqmusic.QqMusicStreamProxy
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.telegram.TelegramRepository
import com.theveloper.pixelplay.data.telegram.TelegramStreamProxy
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemeProcessor
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearThemePalette
import com.theveloper.pixelplay.shared.WearTransferMetadata
import com.theveloper.pixelplay.shared.WearTransferProgress
import com.theveloper.pixelplay.shared.WearTransferRequest
import com.theveloper.pixelplay.utils.AlbumArtUtils
import javax.inject.Singleton
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@Singleton
class PhoneDirectWatchTransferCoordinator @Inject constructor(
    private val application: Application,
    private val musicRepository: MusicRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val colorSchemeProcessor: ColorSchemeProcessor,
    private val transferStateStore: PhoneWatchTransferStateStore,
    private val transferCancellationStore: PhoneWatchTransferCancellationStore,
    private val telegramRepository: TelegramRepository,
    private val telegramStreamProxy: Lazy<TelegramStreamProxy>,
    private val neteaseStreamProxy: NeteaseStreamProxy,
    private val qqMusicStreamProxy: QqMusicStreamProxy,
    private val navidromeStreamProxy: NavidromeStreamProxy,
    private val jellyfinStreamProxy: com.theveloper.pixelplay.data.jellyfin.JellyfinStreamProxy,
    private val gDriveStreamProxy: GDriveStreamProxy,
    private val okHttpClient: OkHttpClient,
) {
    private val contentResolver by lazy { application.contentResolver }
    private val messageClient by lazy { Wearable.getMessageClient(application) }
    private val channelClient by lazy { Wearable.getChannelClient(application) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val albumPaletteSeedCache = ConcurrentHashMap<Long, Int>()
    private val albumArtworkTransferCache = ConcurrentHashMap<Long, ByteArray>()

    private data class OpenedSongSource(
        val inputStream: InputStream,
        val fileSize: Long,
        private val closeable: Closeable? = null,
    ) : Closeable {
        override fun close() {
            if (closeable != null) {
                closeable.close()
            } else {
                inputStream.close()
            }
        }
    }

    fun startTransferToWatch(
        nodeId: String,
        requestId: String,
        songId: String,
        transferMode: String = WearTransferRequest.MODE_SAVE_TO_LIBRARY,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = false,
        overrideAudioFile: File? = null,
    ) {
        transferStateStore.markRequested(
            requestId = requestId,
            songId = songId,
        )
        WatchTransferForegroundService.start(application)
        scope.launch {
            performTransfer(
                nodeId = nodeId,
                requestId = requestId,
                songId = songId,
                transferMode = transferMode,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
                overrideAudioFile = overrideAudioFile,
            )
        }
    }

    private suspend fun performTransfer(
        nodeId: String,
        requestId: String,
        songId: String,
        transferMode: String,
        startPositionMs: Long,
        autoPlay: Boolean,
        overrideAudioFile: File? = null,
    ) {
        var openedSongSource: OpenedSongSource? = null
        try {
            val song = musicRepository.getSongsByIds(listOf(songId)).first().firstOrNull()
            if (song == null) {
                sendTransferMetadataError(
                    nodeId = nodeId,
                    requestId = requestId,
                    songId = songId,
                    errorMessage = "Song not found",
                )
                return
            }

            if (
                transferMode == WearTransferRequest.MODE_SAVE_TO_LIBRARY &&
                !isSongTransferEligible(song)
            ) {
                sendTransferMetadataError(
                    nodeId = nodeId,
                    requestId = requestId,
                    songId = song.id,
                    errorMessage = "Song must be downloaded locally on phone before saving to watch",
                )
                return
            }

            val songSource = if (overrideAudioFile != null) {
                runCatching {
                    OpenedSongSource(
                        inputStream = overrideAudioFile.inputStream(),
                        fileSize = overrideAudioFile.length(),
                    )
                }.getOrNull()
            } else {
                openSongSource(
                    song = song,
                    allowProxyStreaming = transferMode == WearTransferRequest.MODE_TEMPORARY_PLAYBACK,
                )
            }
            if (songSource == null) {
                sendTransferMetadataError(
                    nodeId = nodeId,
                    requestId = requestId,
                    songId = song.id,
                    errorMessage = if (transferMode == WearTransferRequest.MODE_TEMPORARY_PLAYBACK) {
                        "Cannot stream audio source to watch"
                    } else {
                        "Cannot read audio file"
                    },
                )
                return
            }
            openedSongSource = songSource

            val fileSize = songSource.fileSize
            val paletteSeedArgb = resolvePaletteSeedArgb(song)
            val transferThemePalette = resolveTransferThemePalette(song)
            val transferArtworkBytes = resolveTransferArtworkBytes(song)

            val metadata = WearTransferMetadata(
                requestId = requestId,
                songId = song.id,
                title = song.title,
                artist = song.displayArtist,
                album = song.album,
                albumId = song.albumId,
                duration = song.duration,
                mimeType = if (overrideAudioFile != null) TRANSCODED_MIME_TYPE else (song.mimeType ?: "audio/mpeg"),
                fileSize = fileSize,
                bitrate = if (overrideAudioFile != null) TRANSCODED_BITRATE_BPS else (song.bitrate ?: 0),
                sampleRate = song.sampleRate ?: 0,
                isFavorite = song.isFavorite,
                paletteSeedArgb = paletteSeedArgb,
                themePalette = transferThemePalette,
                transferMode = transferMode,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
            )
            transferStateStore.markMetadata(
                requestId = requestId,
                songId = song.id,
                songTitle = song.title,
                totalBytes = fileSize,
            )
            if (transferCancellationStore.consumeCancellation(requestId)) {
                sendTransferProgress(
                    nodeId = nodeId,
                    requestId = requestId,
                    songId = song.id,
                    bytesTransferred = 0L,
                    totalBytes = fileSize,
                    status = WearTransferProgress.STATUS_CANCELLED,
                )
                return
            }

            messageClient.sendMessage(
                nodeId,
                WearDataPaths.TRANSFER_METADATA,
                json.encodeToString(metadata).toByteArray(Charsets.UTF_8),
            ).await()

            // Give the watch a brief window to reject duplicates before the audio stream starts.
            delay(METADATA_GUARD_DELAY_MS)
            val duplicateRejected = transferStateStore.transfers.value[requestId]?.let { transfer ->
                transfer.status == WearTransferProgress.STATUS_FAILED &&
                    transfer.error == WearTransferProgress.ERROR_ALREADY_ON_WATCH
            } == true
            if (duplicateRejected) {
                runCatching { openedSongSource.close() }
                openedSongSource = null
                return
            }

            if (transferArtworkBytes != null) {
                runCatching {
                    streamArtworkToWatch(
                        nodeId = nodeId,
                        requestId = requestId,
                        songId = song.id,
                        artworkBytes = transferArtworkBytes,
                    )
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Artwork transfer failed for songId=%s", song.id)
                }
            }

            if (transferCancellationStore.consumeCancellation(requestId)) {
                sendTransferProgress(
                    nodeId = nodeId,
                    requestId = requestId,
                    songId = song.id,
                    bytesTransferred = 0L,
                    totalBytes = fileSize,
                    status = WearTransferProgress.STATUS_CANCELLED,
                )
                runCatching { openedSongSource.close() }
                openedSongSource = null
                return
            }

            streamFileToWatch(
                nodeId = nodeId,
                requestId = requestId,
                songId = song.id,
                inputStream = songSource.inputStream,
                fileSize = fileSize,
            )
            runCatching { songSource.close() }
            openedSongSource = null
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Direct transfer failed for songId=%s", songId)
            sendTransferProgress(
                nodeId = nodeId,
                requestId = requestId,
                songId = songId,
                bytesTransferred = 0L,
                totalBytes = 0L,
                status = WearTransferProgress.STATUS_FAILED,
                error = error.message,
            )
            runCatching { openedSongSource?.close() }
        }
    }

    private fun isSongTransferEligible(song: Song): Boolean {
        val contentUri = song.contentUriString
        if (
            contentUri.startsWith("telegram://") ||
            contentUri.startsWith("netease://") ||
            contentUri.startsWith("gdrive://")
        ) {
            return false
        }

        val localFile = song.path
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }
        if (localFile != null && localFile.isFile && localFile.canRead() && localFile.length() > 0L) {
            return true
        }

        val uri = runCatching { song.contentUriString.toUri() }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme == "file") {
            val uriFile = uri.path?.let { File(it) }
            return uriFile != null && uriFile.isFile && uriFile.canRead() && uriFile.length() > 0L
        }
        if (scheme != "content") return false

        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length != 0L
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun openSongSource(
        song: Song,
        allowProxyStreaming: Boolean,
    ): OpenedSongSource? {
        openDirectSongSource(song)?.let { return it }
        if (!allowProxyStreaming) return null

        val streamUrl = resolveStreamUrl(song) ?: return null
        return openHttpSongSource(streamUrl)
    }

    private fun openDirectSongSource(song: Song): OpenedSongSource? {
        val directFile = song.path
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile && it.canRead() && it.length() > 0L }
        if (directFile != null) {
            return runCatching {
                OpenedSongSource(
                    inputStream = directFile.inputStream(),
                    fileSize = directFile.length(),
                )
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to open direct file for songId=%s", song.id)
            }.getOrNull()
        }

        val rawUri = song.contentUriString
        if (rawUri.isBlank()) return null
        if (rawUri.startsWith("/")) {
            val rawFile = File(rawUri)
            if (rawFile.isFile && rawFile.canRead() && rawFile.length() > 0L) {
                return runCatching {
                    OpenedSongSource(
                        inputStream = rawFile.inputStream(),
                        fileSize = rawFile.length(),
                    )
                }.getOrNull()
            }
        }

        val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "file" -> {
                val uriFile = uri.path?.let(::File)
                    ?.takeIf { it.isFile && it.canRead() && it.length() > 0L }
                    ?: return null
                runCatching {
                    OpenedSongSource(
                        inputStream = uriFile.inputStream(),
                        fileSize = uriFile.length(),
                    )
                }.getOrNull()
            }

            "content" -> {
                runCatching {
                    val inputStream = contentResolver.openInputStream(uri) ?: return@runCatching null
                    val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        afd.length.takeIf { it > 0L } ?: afd.declaredLength.takeIf { it > 0L }
                    } ?: 0L
                    OpenedSongSource(
                        inputStream = inputStream,
                        fileSize = size.coerceAtLeast(0L),
                    )
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to open content source for songId=%s", song.id)
                }.getOrNull()
            }

            else -> null
        }
    }

    private suspend fun resolveStreamUrl(song: Song): String? {
        val rawUri = song.contentUriString
        val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> rawUri
            "telegram" -> resolveTelegramStreamUrl(song, uri, rawUri)
            "netease" -> {
                ensureCloudProxyReady(neteaseStreamProxy) || return null
                neteaseStreamProxy.resolveNeteaseUri(rawUri)
            }
            "qqmusic" -> {
                ensureCloudProxyReady(qqMusicStreamProxy) || return null
                qqMusicStreamProxy.warmUpStreamUrl(rawUri)
                qqMusicStreamProxy.resolveQqMusicUri(rawUri)
            }
            "navidrome" -> {
                ensureCloudProxyReady(navidromeStreamProxy) || return null
                navidromeStreamProxy.warmUpStreamUrl(rawUri)
                navidromeStreamProxy.resolveNavidromeUri(rawUri)
            }
            "jellyfin" -> {
                ensureCloudProxyReady(jellyfinStreamProxy) || return null
                jellyfinStreamProxy.warmUpStreamUrl(rawUri)
                jellyfinStreamProxy.resolveJellyfinUri(rawUri)
            }
            "gdrive" -> {
                ensureGDriveProxyReady() || return null
                gDriveStreamProxy.resolveGDriveUri(rawUri)
            }
            else -> null
        }
    }

    private suspend fun resolveTelegramStreamUrl(song: Song, uri: android.net.Uri, rawUri: String): String? {
        if (!telegramRepository.isReady()) {
            val ready = telegramRepository.awaitReady(10_000L)
            if (!ready) {
                Timber.tag(TAG).w("Telegram repository not ready for watch handoff")
                return null
            }
        }

        val resolved = telegramRepository.resolveTelegramUri(rawUri)
        val fileId = resolved?.first
            ?: song.telegramFileId
            ?: uri.host?.toIntOrNull()
            ?: uri.pathSegments.firstOrNull()?.toIntOrNull()
            ?: return null
        val knownSize = (resolved?.second ?: 0L).coerceAtLeast(0L)

        val proxy = telegramStreamProxy.get()
        val ready = proxy.ensureReady(5_000L)
        if (!ready) {
            Timber.tag(TAG).w("Telegram stream proxy not ready for watch handoff")
            return null
        }
        return proxy.getProxyUrl(fileId, knownSize)
    }

    private suspend fun ensureCloudProxyReady(proxy: Any): Boolean {
        return when (proxy) {
            is NeteaseStreamProxy -> proxy.ensureReady(5_000L)
            is QqMusicStreamProxy -> proxy.ensureReady(5_000L)
            is NavidromeStreamProxy -> proxy.ensureReady(5_000L)
            is com.theveloper.pixelplay.data.jellyfin.JellyfinStreamProxy -> proxy.ensureReady(5_000L)
            else -> false
        }
    }

    private suspend fun ensureGDriveProxyReady(): Boolean {
        if (gDriveStreamProxy.isReady()) return true
        gDriveStreamProxy.start()
        repeat(50) {
            if (gDriveStreamProxy.isReady()) return true
            delay(100L)
        }
        Timber.tag(TAG).w("GDrive stream proxy not ready for watch handoff")
        return false
    }

    private suspend fun openHttpSongSource(url: String): OpenedSongSource? {
        val response = withContext(Dispatchers.IO) {
            okHttpClient.newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .build()
            ).execute()
        }
        if (!response.isSuccessful) {
            Timber.tag(TAG).w("Watch handoff stream request failed: code=%d url=%s", response.code, url)
            response.close()
            return null
        }

        val body = response.body

        return OpenedSongSource(
            inputStream = body.byteStream(),
            fileSize = body.contentLength().coerceAtLeast(0L),
            closeable = response,
        )
    }

    private fun resolvePaletteSeedArgb(song: Song): Int? {
        if (song.albumId > 0L) {
            albumPaletteSeedCache[song.albumId]?.let { return it }
        }

        val bitmap = loadSongAlbumArtBitmap(song) ?: return null
        return try {
            extractSeedColorArgb(bitmap)?.also { seed ->
                if (song.albumId > 0L) {
                    albumPaletteSeedCache[song.albumId] = seed
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun resolveTransferArtworkBytes(song: Song): ByteArray? {
        if (song.albumId > 0L) {
            albumArtworkTransferCache[song.albumId]?.let { return it }
        }

        val bitmap = loadSongAlbumArtBitmapForTransfer(song) ?: return null
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, TRANSFER_ARTWORK_QUALITY, stream)
            val bytes = stream.toByteArray()
            if (bytes.isEmpty() || bytes.size > TRANSFER_ARTWORK_MAX_BYTES) {
                null
            } else {
                if (song.albumId > 0L) {
                    albumArtworkTransferCache[song.albumId] = bytes
                }
                bytes
            }
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to encode transfer artwork for songId=%s", song.id)
            null
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun resolveTransferThemePalette(song: Song): WearThemePalette? {
        val playerTheme = themePreferencesRepository.playerThemePreferenceFlow.first()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && playerTheme == ThemePreference.DYNAMIC) {
            return buildWearThemePalette(dynamicDarkColorScheme(application))
        }

        val artUriString = song.albumArtUriString?.takeIf { it.isNotBlank() }
        if (artUriString != null) {
            val paletteStyle = AlbumArtPaletteStyle.fromStorageKey(
                themePreferencesRepository.albumArtPaletteStyleFlow.first().storageKey
            )
            val colorAccuracyLevel = AlbumArtColorAccuracy.clamp(
                themePreferencesRepository.albumArtColorAccuracyFlow.first()
            )
            val schemePair = colorSchemeProcessor.getOrGenerateColorScheme(
                albumArtUri = artUriString,
                paletteStyle = paletteStyle,
                colorAccuracyLevel = colorAccuracyLevel
            )
            if (schemePair != null) {
                return buildWearThemePalette(schemePair.dark)
            }
        }

        val fallbackBitmap = loadSongAlbumArtBitmapForTransfer(song) ?: return null
        return try {
            buildWearThemePalette(fallbackBitmap)
        } finally {
            fallbackBitmap.recycle()
        }
    }

    private fun loadSongAlbumArtBitmapForTransfer(song: Song): Bitmap? {
        val fromUri = song.albumArtUriString
            ?.takeIf { it.isNotBlank() }
            ?.let { uriString -> decodeBoundedBitmapFromUri(uriString) }
        if (fromUri != null) return fromUri

        val retriever = MediaMetadataRetriever()
        return try {
            val file = File(song.path)
            if (file.exists() && file.canRead()) {
                retriever.setDataSource(song.path)
            } else {
                retriever.setDataSource(application, song.contentUriString.toUri())
            }
            val embedded = retriever.embeddedPicture ?: return null
            decodeBoundedBitmap(embedded)
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to load transfer artwork for songId=%s", song.id)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeBoundedBitmapFromUri(uriString: String): Bitmap? {
        val uri = uriString.toUri()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        AlbumArtUtils.openArtworkInputStream(application, uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        var sampleSize = 1
        while (
            (srcWidth / sampleSize) > TRANSFER_ARTWORK_MAX_DIMENSION * 2 ||
            (srcHeight / sampleSize) > TRANSFER_ARTWORK_MAX_DIMENSION * 2
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }

        return AlbumArtUtils.openArtworkInputStream(application, uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }

    private fun decodeBoundedBitmap(data: ByteArray): Bitmap? {
        if (data.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        var sampleSize = 1
        while (
            (srcWidth / sampleSize) > TRANSFER_ARTWORK_MAX_DIMENSION * 2 ||
            (srcHeight / sampleSize) > TRANSFER_ARTWORK_MAX_DIMENSION * 2
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeByteArray(
            data,
            0,
            data.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            },
        )
    }

    private fun loadSongAlbumArtBitmap(song: Song): Bitmap? {
        val artFromUri = song.albumArtUriString
            ?.takeIf { it.isNotBlank() }
            ?.let { uriString ->
                runCatching {
                    AlbumArtUtils.openArtworkInputStream(application, uriString.toUri())?.use { input ->
                        BitmapFactory.decodeStream(
                            input,
                            null,
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.RGB_565
                                inSampleSize = 4
                            },
                        )
                    }
                }.getOrNull()
            }
        if (artFromUri != null) return artFromUri

        val retriever = MediaMetadataRetriever()
        return try {
            val file = File(song.path)
            if (file.exists() && file.canRead()) {
                retriever.setDataSource(song.path)
            } else {
                retriever.setDataSource(application, song.contentUriString.toUri())
            }
            val embedded = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(
                embedded,
                0,
                embedded.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = 2
                },
            )
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to extract album art for palette seed: songId=%s", song.id)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractSeedColorArgb(bitmap: Bitmap): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        val step = (minOf(bitmap.width, bitmap.height) / 24).coerceAtLeast(1)
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var count = 0L

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap[x, y]
                if (Color.alpha(pixel) >= 28) {
                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)
                    if (red + green + blue > 36) {
                        redSum += red
                        greenSum += green
                        blueSum += blue
                        count++
                    }
                }
                x += step
            }
            y += step
        }

        if (count == 0L) return null
        return Color.rgb(
            (redSum / count).toInt(),
            (greenSum / count).toInt(),
            (blueSum / count).toInt(),
        )
    }

    private suspend fun streamFileToWatch(
        nodeId: String,
        requestId: String,
        songId: String,
        inputStream: InputStream,
        fileSize: Long,
    ) {
        if (transferCancellationStore.consumeCancellation(requestId)) {
            sendTransferProgress(
                nodeId = nodeId,
                requestId = requestId,
                songId = songId,
                bytesTransferred = 0L,
                totalBytes = fileSize,
                status = WearTransferProgress.STATUS_CANCELLED,
            )
            return
        }
        val channel = channelClient.openChannel(nodeId, WearDataPaths.TRANSFER_CHANNEL).await()
        var shouldForceCloseChannel = true

        try {
            var totalSent = 0L
            var lastProgressUpdate = 0L
            var cancelled = false

            withContext(Dispatchers.IO) {
                channelClient.getOutputStream(channel).await().use { outputStream ->
                    val requestIdBytes = requestId.toByteArray(Charsets.UTF_8)
                    outputStream.write(ByteBuffer.allocate(4).putInt(requestIdBytes.size).array())
                    outputStream.write(requestIdBytes)

                    val buffer = ByteArray(TRANSFER_CHUNK_SIZE)
                    inputStream.use { input ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (transferCancellationStore.consumeCancellation(requestId)) {
                                cancelled = true
                                break
                            }

                            outputStream.write(buffer, 0, bytesRead)
                            totalSent += bytesRead

                            if (totalSent - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_BYTES) {
                                sendTransferProgress(
                                    nodeId = nodeId,
                                    requestId = requestId,
                                    songId = songId,
                                    bytesTransferred = totalSent,
                                    totalBytes = fileSize,
                                    status = WearTransferProgress.STATUS_TRANSFERRING,
                                )
                                lastProgressUpdate = totalSent
                            }
                        }
                    }

                    if (!cancelled) {
                        outputStream.flush()
                    }
                }
            }

            if (cancelled) {
                sendTransferProgress(
                    nodeId = nodeId,
                    requestId = requestId,
                    songId = songId,
                    bytesTransferred = totalSent,
                    totalBytes = fileSize,
                    status = WearTransferProgress.STATUS_CANCELLED,
                )
                return
            }

            shouldForceCloseChannel = false

            sendTransferProgress(
                nodeId = nodeId,
                requestId = requestId,
                songId = songId,
                bytesTransferred = fileSize,
                totalBytes = fileSize,
                status = WearTransferProgress.STATUS_COMPLETED,
            )
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Failed to stream file to watch")
            sendTransferProgress(
                nodeId = nodeId,
                requestId = requestId,
                songId = songId,
                bytesTransferred = 0L,
                totalBytes = fileSize,
                status = WearTransferProgress.STATUS_FAILED,
                error = error.message,
            )
        } finally {
            if (shouldForceCloseChannel) {
                runCatching { channelClient.close(channel).await() }
            }
            transferCancellationStore.clear(requestId)
        }
    }

    private suspend fun streamArtworkToWatch(
        nodeId: String,
        requestId: String,
        songId: String,
        artworkBytes: ByteArray,
    ) {
        if (artworkBytes.isEmpty()) return
        val channel = channelClient.openChannel(nodeId, WearDataPaths.TRANSFER_ARTWORK_CHANNEL).await()
        var shouldForceCloseChannel = true
        try {
            withContext(Dispatchers.IO) {
                channelClient.getOutputStream(channel).await().use { outputStream ->
                    val requestIdBytes = requestId.toByteArray(Charsets.UTF_8)
                    val songIdBytes = songId.toByteArray(Charsets.UTF_8)

                    outputStream.write(ByteBuffer.allocate(4).putInt(requestIdBytes.size).array())
                    outputStream.write(requestIdBytes)
                    outputStream.write(ByteBuffer.allocate(4).putInt(songIdBytes.size).array())
                    outputStream.write(songIdBytes)
                    outputStream.write(artworkBytes)
                    outputStream.flush()
                }
            }
            shouldForceCloseChannel = false
        } finally {
            if (shouldForceCloseChannel) {
                runCatching { channelClient.close(channel).await() }
            }
        }
    }

    private suspend fun sendTransferMetadataError(
        nodeId: String,
        requestId: String,
        songId: String,
        errorMessage: String,
    ) {
        val metadata = WearTransferMetadata(
            requestId = requestId,
            songId = songId,
            title = "",
            artist = "",
            album = "",
            albumId = 0L,
            duration = 0L,
            mimeType = "",
            fileSize = 0L,
            bitrate = 0,
            sampleRate = 0,
            isFavorite = false,
            error = errorMessage,
        )
        runCatching {
            messageClient.sendMessage(
                nodeId,
                WearDataPaths.TRANSFER_METADATA,
                json.encodeToString(metadata).toByteArray(Charsets.UTF_8),
            ).await()
        }.onFailure { error ->
            Timber.tag(TAG).e(error, "Failed to send transfer error metadata")
        }

        sendTransferProgress(
            nodeId = nodeId,
            requestId = requestId,
            songId = songId,
            bytesTransferred = 0L,
            totalBytes = 0L,
            status = WearTransferProgress.STATUS_FAILED,
            error = errorMessage,
        )
    }

    private suspend fun sendTransferProgress(
        nodeId: String,
        requestId: String,
        songId: String,
        bytesTransferred: Long,
        totalBytes: Long,
        status: String,
        error: String? = null,
    ) {
        // Local bookkeeping only: a phone-side "completed" just means the phone finished
        // writing its own stream. The watch still has to validate the file and ack it before
        // this song is trustworthy as "present on watch" — see WearCommandReceiver's handling
        // of the watch's own TRANSFER_PROGRESS reply for where STATUS_COMPLETED actually lands.
        val localStatus = if (status == WearTransferProgress.STATUS_COMPLETED) {
            WearTransferProgress.STATUS_AWAITING_WATCH_ACK
        } else {
            status
        }
        transferStateStore.markProgress(
            requestId = requestId,
            songId = songId,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            status = localStatus,
            error = error,
        )
        val progress = WearTransferProgress(
            requestId = requestId,
            songId = songId,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            status = status,
            error = error,
        )
        runCatching {
            messageClient.sendMessage(
                nodeId,
                WearDataPaths.TRANSFER_PROGRESS,
                json.encodeToString(progress).toByteArray(Charsets.UTF_8),
            ).await()
        }.onFailure { errorMsg ->
            Timber.tag(TAG).w(errorMsg, "Failed to send transfer progress")
        }
    }

    private companion object {
        const val TAG = "PhoneDirectTransfer"
        const val TRANSFER_CHUNK_SIZE = 8192
        const val PROGRESS_UPDATE_INTERVAL_BYTES = 65536L
        const val TRANSFER_ARTWORK_MAX_DIMENSION = 1024
        const val TRANSFER_ARTWORK_QUALITY = 95
        const val TRANSFER_ARTWORK_MAX_BYTES = 1_500_000
        const val METADATA_GUARD_DELAY_MS = 250L
        // Kept in sync with WatchAudioTranscoder's AAC-LC output.
        const val TRANSCODED_MIME_TYPE = "audio/mp4"
        const val TRANSCODED_BITRATE_BPS = 256_000
    }
}
