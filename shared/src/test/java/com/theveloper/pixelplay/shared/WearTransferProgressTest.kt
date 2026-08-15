package com.theveloper.pixelplay.shared

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class WearTransferProgressTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trips through JSON with the new errorCode field`() {
        val original = WearTransferProgress(
            requestId = "req-1",
            songId = "song-1",
            bytesTransferred = 512L,
            totalBytes = 1024L,
            status = WearTransferProgress.STATUS_FAILED,
            error = "Connection lost",
            errorCode = WearTransferProgress.ERROR_CODE_CONNECTION_LOST,
        )

        val decoded = json.decodeFromString<WearTransferProgress>(json.encodeToString(original))

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decodes a payload from an older sender that predates errorCode as null`() {
        // A phone running an older build won't include errorCode in the payload at all.
        val legacyPayload =
            """{"requestId":"req-1","songId":"song-1","bytesTransferred":0,"totalBytes":1024,"status":"transferring"}"""

        val decoded = json.decodeFromString<WearTransferProgress>(legacyPayload)

        assertThat(decoded.errorCode).isNull()
        assertThat(decoded.error).isNull()
    }

    @Test
    fun `STATUS_TRANSCODING serializes as its raw string value`() {
        val progress = WearTransferProgress(
            requestId = "req-1",
            songId = "song-1",
            bytesTransferred = 0L,
            totalBytes = 1024L,
            status = WearTransferProgress.STATUS_TRANSCODING,
        )

        assertThat(json.encodeToString(progress)).contains("\"status\":\"transcoding\"")
    }

    @Test
    fun `STATUS_AWAITING_WATCH_ACK is a distinct value from every terminal status`() {
        val terminalStatuses = setOf(
            WearTransferProgress.STATUS_COMPLETED,
            WearTransferProgress.STATUS_FAILED,
            WearTransferProgress.STATUS_CANCELLED,
        )

        assertThat(terminalStatuses).doesNotContain(WearTransferProgress.STATUS_AWAITING_WATCH_ACK)
    }
}
