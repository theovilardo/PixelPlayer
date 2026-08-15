package com.theveloper.pixelplay.data

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.theveloper.pixelplay.presentation.WearMainActivity
import com.theveloper.pixelplay.shared.WearBrowseResponse
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearFavoriteSyncResponse
import com.theveloper.pixelplay.shared.WearPlaybackResult
import com.theveloper.pixelplay.shared.WearPlayerState
import com.theveloper.pixelplay.shared.WearPlaylistSync
import com.theveloper.pixelplay.shared.WearTransferMetadata
import com.theveloper.pixelplay.shared.WearTransferProgress
import com.theveloper.pixelplay.shared.WearTransferRequest
import com.theveloper.pixelplay.shared.WearVolumeState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

/**
 * Listens for DataItem changes, MessageClient messages, and ChannelClient events
 * from the phone app via the Wear Data Layer.
 *
 * Handles:
 * - Player state updates (DataItem)
 * - Browse responses (MessageClient)
 * - Transfer metadata and progress (MessageClient)
 * - Audio file streaming (ChannelClient)
 */
@AndroidEntryPoint
class WearDataListenerService : WearableListenerService() {

    @Inject
    lateinit var stateRepository: WearStateRepository

    @Inject
    lateinit var libraryRepository: WearLibraryRepository

    @Inject
    lateinit var transferRepository: WearTransferRepository

    @Inject
    lateinit var favoriteSyncRepository: WearFavoriteSyncRepository

    @Inject
    lateinit var performanceSettingsRepository: WearPerformanceSettingsRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "WearDataListener"
        private const val AUTO_LAUNCH_COOLDOWN_MS = 2_500L
        private const val AUTO_LAUNCH_KEEP_ALIVE_MS = 7_000L

        @Volatile
        private var lastAutoLaunchElapsedMs = 0L

        @Volatile
        private var lastAutoLaunchSongId = ""

        @Volatile
        private var lastKnownPlaying = false
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Timber.tag(TAG).d("onDataChanged: ${dataEvents.count} events")

        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach

            val dataItem = event.dataItem
            Timber.tag(TAG).d("Data event path=%s", dataItem.uri.path)
            when (dataItem.uri.path) {
                WearDataPaths.PLAYER_STATE -> {
                    // Copy DataMap in callback thread; DataEventBuffer is invalid once callback returns.
                    val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
                    scope.launch {
                        try {
                            processPlayerStateUpdate(dataMap)
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to process player state update")
                        }
                    }
                }

                WearDataPaths.WEAR_PERFORMANCE_SETTINGS -> {
                    val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
                    // Written inline rather than through `scope`: onDataChanged already runs on
                    // the Data Layer's own background thread, and this service is torn down as
                    // soon as its callbacks return — onDestroy cancels `scope`, so a launched
                    // DataStore write races that teardown and can be dropped before it lands.
                    // Player state can absorb a dropped update (it streams continuously); this
                    // is a one-shot event, and losing it leaves the toggles stuck at their
                    // defaults with no way for the watch to notice.
                    runBlocking {
                        try {
                            performanceSettingsRepository.save(
                                showAlbumArt = dataMap.getBoolean(WearDataPaths.KEY_SHOW_ALBUM_ART, true),
                                dynamicColorTheming = dataMap.getBoolean(WearDataPaths.KEY_DYNAMIC_COLOR_THEMING, true),
                                playButtonAnimation = dataMap.getBoolean(WearDataPaths.KEY_PLAY_BUTTON_ANIMATION, true),
                            )
                            Timber.tag(TAG).d("Performance settings synced from phone")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to process performance settings sync")
                        }
                    }
                }
            }
        }
    }

    private suspend fun processPlayerStateUpdate(dataMap: DataMap) {
        val stateJson = dataMap.getString(WearDataPaths.KEY_STATE_JSON).orEmpty()

        if (stateJson.isEmpty()) {
            // Empty state means playback stopped / service destroyed
            stateRepository.updatePlayerState(WearPlayerState())
            stateRepository.updateAlbumArt(null)
            Timber.tag(TAG).d("Received empty state (playback stopped)")
            return
        }

        val playerState = json.decodeFromString<WearPlayerState>(stateJson)
        val adjustedPlayerState = playerState.withTransportLatencyApplied(dataMap)
        stateRepository.updatePlayerState(adjustedPlayerState)
        stateRepository.setPhoneConnected(true)
        Timber.tag(TAG).d("Updated state: ${adjustedPlayerState.songTitle} (playing=${adjustedPlayerState.isPlaying})")
        maybeAutoLaunchPlayer(adjustedPlayerState)

        // Extract album art Asset
        if (dataMap.containsKey(WearDataPaths.KEY_ALBUM_ART)) {
            val asset = dataMap.getAsset(WearDataPaths.KEY_ALBUM_ART)
            if (asset != null) {
                try {
                    val dataClient = Wearable.getDataClient(this@WearDataListenerService)
                    val response = dataClient.getFdForAsset(asset).await()
                    val inputStream = response.inputStream
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    stateRepository.updateAlbumArt(bitmap)
                    Timber.tag(TAG).d("Album art updated")
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to load album art asset")
                    stateRepository.updateAlbumArt(null)
                }
            }
        } else {
            stateRepository.updateAlbumArt(null)
        }
    }

    private fun WearPlayerState.withTransportLatencyApplied(dataMap: DataMap): WearPlayerState {
        if (!isPlaying || totalDurationMs <= 0L) return this
        val phonePublishedAtMs = dataMap.getLong(WearDataPaths.KEY_TIMESTAMP, 0L)
        if (phonePublishedAtMs <= 0L) return this

        val transportLatencyMs = (System.currentTimeMillis() - phonePublishedAtMs)
            .coerceIn(0L, 5_000L)
        if (transportLatencyMs <= 0L) return this

        return copy(
            currentPositionMs = (currentPositionMs + transportLatencyMs)
                .coerceIn(0L, totalDurationMs),
        )
    }

    private fun maybeAutoLaunchPlayer(playerState: WearPlayerState) {
        val playerIdentity = playerState.playerIdentity()
        val isNowPlaying = playerState.isPlaying && playerIdentity.isNotEmpty()
        if (!isNowPlaying) {
            lastKnownPlaying = false
            return
        }
        if (WearMainActivity.isForeground) {
            lastKnownPlaying = true
            return
        }

        val playbackJustStarted = !lastKnownPlaying
        val songChangedWhilePlaying = playerIdentity != lastAutoLaunchSongId

        val now = SystemClock.elapsedRealtime()
        val keepAliveExpired = now - lastAutoLaunchElapsedMs >= AUTO_LAUNCH_KEEP_ALIVE_MS
        val shouldAutoOpen = playbackJustStarted || songChangedWhilePlaying || keepAliveExpired
        if (!shouldAutoOpen) {
            lastKnownPlaying = true
            return
        }
        if (now - lastAutoLaunchElapsedMs < AUTO_LAUNCH_COOLDOWN_MS) {
            lastKnownPlaying = true
            return
        }

        val intent = Intent(this, WearMainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra("auto_open_reason", "phone_playback")
        }

        runCatching {
            startActivity(intent)
            lastAutoLaunchElapsedMs = now
            lastAutoLaunchSongId = playerIdentity
            lastKnownPlaying = true
            Timber.tag(TAG).d("Auto-opened Wear player for active phone playback")
        }.onFailure { e ->
            lastKnownPlaying = true
            Timber.tag(TAG).w(e, "Failed to auto-open Wear player")
        }
    }

    private fun WearPlayerState.playerIdentity(): String {
        if (songId.isNotBlank()) return songId
        val title = songTitle.trim()
        if (title.isNotEmpty()) return "$title|${artistName.trim()}"
        return ""
    }

    /**
     * Receives message responses from the phone (browse responses, transfer metadata/progress).
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        Timber.tag(TAG).d(
            "Message received path=%s nodeId=%s bytes=%d",
            messageEvent.path,
            messageEvent.sourceNodeId,
            messageEvent.data.size
        )
        when (messageEvent.path) {
            WearDataPaths.BROWSE_RESPONSE -> {
                try {
                    val responseJson = String(messageEvent.data, Charsets.UTF_8)
                    val response = json.decodeFromString<WearBrowseResponse>(responseJson)
                    libraryRepository.onBrowseResponseReceived(response)
                    Timber.tag(TAG).d(
                        "Received browse response: ${response.items.size} items, " +
                            "requestId=${response.requestId}"
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to process browse response")
                }
            }

            WearDataPaths.TRANSFER_METADATA -> {
                scope.launch {
                    try {
                        val metadataJson = String(messageEvent.data, Charsets.UTF_8)
                        val metadata = json.decodeFromString<WearTransferMetadata>(metadataJson)
                        transferRepository.onMetadataReceived(
                            metadata = metadata,
                            sourceNodeId = messageEvent.sourceNodeId,
                        )
                        Timber.tag(TAG).d(
                            "Received transfer metadata: ${metadata.title}, " +
                                "fileSize=${metadata.fileSize}, requestId=${metadata.requestId}"
                        )
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to process transfer metadata")
                    }
                }
            }

            WearDataPaths.PLAYLIST_SYNC -> {
                scope.launch {
                    try {
                        val syncJson = String(messageEvent.data, Charsets.UTF_8)
                        val sync = json.decodeFromString<WearPlaylistSync>(syncJson)
                        transferRepository.onPlaylistSyncReceived(sync, sourceNodeId = messageEvent.sourceNodeId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to process playlist sync")
                    }
                }
            }

            WearDataPaths.TRANSFER_PROGRESS -> {
                try {
                    val progressJson = String(messageEvent.data, Charsets.UTF_8)
                    val progress = json.decodeFromString<WearTransferProgress>(progressJson)
                    transferRepository.onProgressReceived(progress)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to process transfer progress")
                }
            }

            WearDataPaths.PLAYBACK_RESULT -> {
                try {
                    val resultJson = String(messageEvent.data, Charsets.UTF_8)
                    val result = json.decodeFromString<WearPlaybackResult>(resultJson)
                    stateRepository.setPhoneConnected(true)
                    stateRepository.publishPlaybackResult(result)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to process playback result")
                }
            }

            WearDataPaths.FAVORITES_SYNC_STATE -> {
                scope.launch {
                    try {
                        val responseJson = String(messageEvent.data, Charsets.UTF_8)
                        val response = json.decodeFromString<WearFavoriteSyncResponse>(responseJson)
                        favoriteSyncRepository.applyFavoriteSyncResponse(response)
                        stateRepository.setPhoneConnected(true)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to process favorite sync response")
                    }
                }
            }

            WearDataPaths.TRANSFER_REQUEST -> {
                try {
                    val requestJson = String(messageEvent.data, Charsets.UTF_8)
                    val request = json.decodeFromString<WearTransferRequest>(requestJson)
                    transferRepository.requestTransfer(
                        songId = request.songId,
                        requestId = request.requestId,
                        targetNodeId = messageEvent.sourceNodeId,
                    )
                    Timber.tag(TAG).d("Received phone transfer request for songId=${request.songId}")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to process transfer request")
                }
            }

            WearDataPaths.TRANSFER_CANCEL -> {
                try {
                    val requestJson = String(messageEvent.data, Charsets.UTF_8)
                    val request = json.decodeFromString<WearTransferRequest>(requestJson)
                    transferRepository.cancelTransfer(
                        requestId = request.requestId,
                        notifyPhone = false,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to process transfer cancel")
                }
            }

            WearDataPaths.WATCH_LIBRARY_QUERY -> {
                scope.launch {
                    transferRepository.publishLibraryState(targetNodeId = messageEvent.sourceNodeId)
                }
            }

            WearDataPaths.VOLUME_STATE -> {
                try {
                    val volumeJson = String(messageEvent.data, Charsets.UTF_8)
                    val volumeState = json.decodeFromString<WearVolumeState>(volumeJson)
                    stateRepository.updateVolumeState(
                        level = volumeState.level,
                        max = volumeState.max,
                        routeType = volumeState.routeType,
                        routeName = volumeState.routeName,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to process volume state update")
                }
            }

            else -> {
                Timber.tag(TAG).d("Ignoring message on path: ${messageEvent.path}")
            }
        }
    }

    /**
     * Delegates opened channels to the repository so transfer work survives the listener lifecycle.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        when (channel.path) {
            WearDataPaths.TRANSFER_CHANNEL -> {
                Timber.tag(TAG).d("Audio transfer channel opened")
                transferRepository.receiveAudioChannel(channel)
            }

            WearDataPaths.TRANSFER_ARTWORK_CHANNEL -> {
                Timber.tag(TAG).d("Artwork transfer channel opened")
                transferRepository.receiveArtworkChannel(channel)
            }

            else -> {
                Timber.tag(TAG).d("Ignoring channel on path: ${channel.path}")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
