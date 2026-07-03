package com.theveloper.pixelplay.shared

import kotlinx.serialization.Serializable

/**
 * Snapshot of songs currently saved on the watch.
 */
@Serializable
data class WearLibraryState(
    val songIds: List<String> = emptyList(),
    /** Free space available on the watch's internal storage, used to warn before large transfers. */
    val freeStorageBytes: Long = 0L,
)
