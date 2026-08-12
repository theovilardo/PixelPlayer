package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.theveloper.pixelplay.shared.WearCapabilities
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearTransferProgress
import com.theveloper.pixelplay.shared.WearTransferRequest
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearPhoneTransferSender @Inject constructor(
    private val application: Application,
    private val transferStateStore: PhoneWatchTransferStateStore,
    private val transferCancellationStore: PhoneWatchTransferCancellationStore,
    private val directTransferCoordinator: PhoneDirectWatchTransferCoordinator,
) {
    private val capabilityClient by lazy { Wearable.getCapabilityClient(application) }
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(application) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun isPixelPlayWatchAvailable(): Boolean {
        return runCatching {
            val capability = capabilityClient.getCapability(
                WearCapabilities.PIXELPLAY_WEAR_APP,
                CapabilityClient.FILTER_REACHABLE,
            ).await()
            transferStateStore.retainReachableWatchNodes(capability.nodes.map { it.id }.toSet())
            capability.nodes.isNotEmpty()
        }.getOrElse { error ->
            transferStateStore.retainReachableWatchNodes(emptySet())
            Timber.tag(TAG).w(error, "Failed checking PixelPlay Wear availability")
            false
        }
    }

    /**
     * Distinct from [isPixelPlayWatchAvailable]: `FILTER_ALL` returns every node that has ever
     * advertised the PixelPlay capability, reachable or not, instead of only ones reachable right
     * now — the signal for "has the user ever paired a watch with PixelPlay installed at all",
     * used to hide watch-related UI entirely for someone who never has, as opposed to showing it
     * disabled/"not connected" for a paired watch that's just out of range at the moment.
     */
    suspend fun refreshWatchPairingState(): Boolean {
        return runCatching {
            val capability = capabilityClient.getCapability(
                WearCapabilities.PIXELPLAY_WEAR_APP,
                CapabilityClient.FILTER_ALL,
            ).await()
            val paired = capability.nodes.isNotEmpty()
            transferStateStore.setAnyWatchPaired(paired)
            paired
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Failed checking whether any watch is paired")
            // Deliberately don't clear transferStateStore's flag on failure (unlike
            // isPixelPlayWatchAvailable's reachability reset): a transient error here shouldn't
            // hide UI that a previous successful check already confirmed should be visible.
            transferStateStore.isAnyWatchPaired.value
        }
    }

    suspend fun refreshWatchLibraryState(): Result<Unit> {
        return runCatching {
            val capability = capabilityClient.getCapability(
                WearCapabilities.PIXELPLAY_WEAR_APP,
                CapabilityClient.FILTER_REACHABLE,
            ).await()
            val nodes = capability.nodes
            val nodeIds = nodes.map { it.id }.toSet()
            transferStateStore.retainReachableWatchNodes(nodeIds)
            transferStateStore.beginWatchLibraryRefresh(nodeIds)
            if (nodes.isEmpty()) return@runCatching

            nodes.forEach { node ->
                messageClient.sendMessage(
                    node.id,
                    WearDataPaths.WATCH_LIBRARY_QUERY,
                    ByteArray(0),
                ).await()
            }
        }
    }

    suspend fun requestSongTransfer(songId: String, songTitle: String = ""): Result<Int> {
        var requestId: String? = null
        return runCatching {
            val capability = capabilityClient.getCapability(
                WearCapabilities.PIXELPLAY_WEAR_APP,
                CapabilityClient.FILTER_REACHABLE,
            ).await()

            val nodes = capability.nodes
            transferStateStore.retainReachableWatchNodes(nodes.map { it.id }.toSet())
            if (nodes.isEmpty()) {
                error("No reachable watch with PixelPlay")
            }

            val request = WearTransferRequest(
                requestId = UUID.randomUUID().toString(),
                songId = songId,
            )
            requestId = request.requestId
            val payload = json.encodeToString(request).toByteArray(Charsets.UTF_8)
            transferStateStore.markRequested(
                requestId = request.requestId,
                songId = songId,
                songTitle = songTitle,
            )

            nodes.forEach { node ->
                directTransferCoordinator.startTransferToWatch(
                    nodeId = node.id,
                    requestId = request.requestId,
                    songId = songId,
                )
            }
            nodes.size
        }.onFailure { error ->
            requestId?.let { safeRequestId ->
                transferStateStore.markProgress(
                    requestId = safeRequestId,
                    songId = songId,
                    bytesTransferred = 0L,
                    totalBytes = 0L,
                    status = WearTransferProgress.STATUS_FAILED,
                    error = error.message ?: "Failed to request transfer",
                    songTitle = songTitle,
                )
            }
        }
    }

    suspend fun cancelTransfer(requestId: String) {
        val transfer = transferStateStore.transfers.value[requestId]
        if (transfer == null) {
            Timber.tag(TAG).w("Ignoring cancel for unknown transfer requestId=%s", requestId)
            return
        }

        transferCancellationStore.markCancelled(requestId)
        transferStateStore.markCancelled(requestId)

        runCatching {
            val capability = capabilityClient.getCapability(
                WearCapabilities.PIXELPLAY_WEAR_APP,
                CapabilityClient.FILTER_REACHABLE,
            ).await()

            val nodes = capability.nodes
            transferStateStore.retainReachableWatchNodes(nodes.map { it.id }.toSet())
            if (nodes.isEmpty()) return@runCatching

            val request = WearTransferRequest(
                requestId = requestId,
                songId = transfer.songId,
            )
            val payload = json.encodeToString(request).toByteArray(Charsets.UTF_8)
            val cancelledProgressPayload = json.encodeToString(
                WearTransferProgress(
                    requestId = requestId,
                    songId = transfer.songId,
                    bytesTransferred = transfer.bytesTransferred,
                    totalBytes = transfer.totalBytes,
                    status = WearTransferProgress.STATUS_CANCELLED,
                )
            ).toByteArray(Charsets.UTF_8)
            nodes.forEach { node ->
                messageClient.sendMessage(
                    node.id,
                    WearDataPaths.TRANSFER_PROGRESS,
                    cancelledProgressPayload,
                ).await()
                messageClient.sendMessage(
                    node.id,
                    WearDataPaths.TRANSFER_CANCEL,
                    payload,
                ).await()
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to notify watch about cancelled transfer")
        }
    }

    private companion object {
        const val TAG = "WearPhoneTransfer"
    }
}
