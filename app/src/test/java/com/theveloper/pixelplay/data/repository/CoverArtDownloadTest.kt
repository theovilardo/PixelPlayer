package com.theveloper.pixelplay.data.repository

import android.content.Context
import com.theveloper.pixelplay.data.coverart.CoverArtCandidate
import com.theveloper.pixelplay.data.coverart.CoverArtSource
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Covers what happens between a remote host and a file on disk: the candidate
 * URL is chosen from third-party search results, so this is the one path in the
 * feature where an outside party decides what the app writes.
 *
 * Responses are handed to the client directly rather than served over a socket,
 * which keeps this to the dependencies the project already has.
 */
class CoverArtDownloadTest {

    @TempDir
    lateinit var cacheDir: File

    private val context = mockk<Context>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { context.cacheDir } returns cacheDir
    }

    @Test
    fun `a cleartext url is refused without asking for it`() = runTest {
        val http = FakeHttp { error("no request should be made") }

        val result = repositoryOf(http).downloadCandidate(candidate("http://example.test/a.jpg"))

        assertTrue(result.isFailure)
        assertEquals(0, http.callCount, "the refusal must come before the request")
        assertEquals(emptyList<String>(), cachedFileNames())
    }

    @Test
    fun `a probe refuses a cleartext url too`() = runTest {
        val http = FakeHttp { error("no request should be made") }

        assertNull(repositoryOf(http).probeSize(candidate("http://example.test/a.jpg")))
        assertEquals(0, http.callCount)
    }

    @Test
    fun `an image the host declares as oversized is never read`() = runTest {
        val http = FakeHttp { request ->
            response(request, body = declaredSize(MAX_IMAGE_BYTES + 1))
        }

        val result = repositoryOf(http).downloadCandidate(candidate())

        assertTrue(result.isFailure)
        assertEquals(emptyList<String>(), cachedFileNames())
    }

    @Test
    fun `a body that keeps coming past the cap is cut off`() = runTest {
        // No declared length, so the only defence is the bounded read.
        val http = FakeHttp { request ->
            response(request, body = undeclaredSize(MAX_IMAGE_BYTES + 1))
        }

        val result = repositoryOf(http).downloadCandidate(candidate())

        assertTrue(result.isFailure)
        assertEquals(emptyList<String>(), cachedFileNames())
    }

    @Test
    fun `an error page served as an image is not written to disk`() = runTest {
        val http = FakeHttp { request ->
            response(
                request,
                // The host says image, the bytes say otherwise. A captive portal
                // or a rate-limit page reaches the cropper without this check.
                body = "<html><body>rate limited</body></html>"
                    .toByteArray()
                    .toResponseBody("image/jpeg".toMediaType())
            )
        }

        val result = repositoryOf(http).downloadCandidate(candidate())

        assertTrue(result.isFailure)
        assertEquals(emptyList<String>(), cachedFileNames())
    }

    @Test
    fun `a non-image content type is refused`() = runTest {
        val http = FakeHttp { request ->
            response(request, body = jpegBytes().toResponseBody("text/html".toMediaType()))
        }

        assertTrue(repositoryOf(http).downloadCandidate(candidate()).isFailure)
        assertEquals(emptyList<String>(), cachedFileNames())
    }

    @Test
    fun `a rejected payload is not downloaded again`() = runTest {
        val http = FakeHttp { request ->
            response(request, body = "nope".toByteArray().toResponseBody("image/jpeg".toMediaType()))
        }

        repositoryOf(http).downloadCandidate(candidate())

        // The verdict cannot change on a second read, and the transport retry
        // would spend up to three full downloads reaching it.
        assertEquals(1, http.callCount)
    }

    @Test
    fun `each accepted format lands in the cache`() {
        listOf(
            "jpeg" to jpegBytes(),
            "png" to pngBytes(width = 1000, height = 1000),
            "gif" to "GIF89a".toByteArray() + ByteArray(16),
            "webp" to webpBytes()
        ).forEach { (label, bytes) ->
            runTest {
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                val http = FakeHttp { request ->
                    response(request, body = bytes.toResponseBody("image/jpeg".toMediaType()))
                }

                val result = repositoryOf(http).downloadCandidate(candidate())

                assertTrue(result.isSuccess, "$label should be accepted")
                assertEquals(1, cachedFileNames().size, "$label should have been cached")
            }
        }
    }

    @Test
    fun `the cache does not grow without bound`() = runTest {
        val directory = File(cacheDir, "cover_art_search").apply { mkdirs() }
        repeat(25) { index ->
            File(directory, "stale$index.img").apply {
                writeBytes(ByteArray(4))
                setLastModified(1_000_000L + index * 1_000L)
            }
        }
        val http = FakeHttp { request ->
            response(request, body = jpegBytes().toResponseBody("image/jpeg".toMediaType()))
        }

        repositoryOf(http).downloadCandidate(candidate())

        // These files only live between picking a result and confirming the
        // crop, so the ceiling is what stops a browsing session filling the
        // cache directory.
        assertEquals(20, cachedFileNames().size)
    }

    @Test
    fun `a probe reports the full size of a ranged response`() = runTest {
        val http = FakeHttp { request ->
            response(
                request,
                code = 206,
                body = pngBytes(width = 1400, height = 1400)
                    .toResponseBody("image/png".toMediaType()),
                headers = mapOf("Content-Range" to "bytes 0-1023/450560")
            )
        }

        val size = repositoryOf(http).probeSize(candidate())

        // Only the first bytes were asked for, so the body length is not the
        // image's weight — the range footer is.
        assertEquals(1400, size?.width)
        assertEquals(450_560L, size?.byteCount)
        assertTrue(size?.measured == true)
    }

    @Test
    fun `a probe falls back to the body length when the host ignores the range`() = runTest {
        val png = pngBytes(width = 600, height = 900)
        val http = FakeHttp { request ->
            response(request, body = png.toResponseBody("image/png".toMediaType()))
        }

        val size = repositoryOf(http).probeSize(candidate())

        assertEquals(600 to 900, size?.width to size?.height)
        assertEquals(png.size.toLong(), size?.byteCount)
    }

    @Test
    fun `a probe that cannot read the prefix leaves the reported size alone`() = runTest {
        val http = FakeHttp { request ->
            response(request, body = ByteArray(64).toResponseBody("image/png".toMediaType()))
        }

        assertNull(repositoryOf(http).probeSize(candidate()))
    }

    private fun repositoryOf(http: FakeHttp) = CoverArtSearchRepository(
        context = context,
        providers = emptyList(),
        okHttpClient = http.client
    )

    private fun cachedFileNames(): List<String> =
        File(cacheDir, "cover_art_search").listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun candidate(imageUrl: String = "https://example.test/cover.jpg") = CoverArtCandidate(
        id = "candidate",
        albumTitle = "Discovery",
        artistName = "Daft Punk",
        thumbnailUrl = "https://example.test/thumb.jpg",
        imageUrl = imageUrl,
        source = CoverArtSource.DEEZER
    )

    private fun response(
        request: Request,
        code: Int = 200,
        body: ResponseBody,
        headers: Map<String, String> = emptyMap()
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("OK")
        .body(body)
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()

    /** Claims a length without producing one, the way a host advertises a file. */
    private fun declaredSize(length: Long) = object : ResponseBody() {
        override fun contentType() = "image/jpeg".toMediaType()
        override fun contentLength() = length
        override fun source(): BufferedSource = Buffer().write(jpegBytes())
    }

    /** Produces more than it admits to, which is what the bounded read is for. */
    private fun undeclaredSize(length: Long) = object : ResponseBody() {
        override fun contentType() = "image/jpeg".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource =
            Buffer().write(jpegBytes()).write(ByteArray(length.toInt()))
    }

    private fun jpegBytes() = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(32)

    private fun webpBytes(): ByteArray {
        val bytes = ByteArray(32)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        return bytes
    }

    private fun pngBytes(width: Int, height: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D,
        0x49, 0x48, 0x44, 0x52
    ) + intBe(width) + intBe(height) + ByteArray(8)

    private fun intBe(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    /**
     * Hands back canned responses and counts how often the client was asked,
     * which is how the retry behaviour is observed.
     */
    private class FakeHttp(private val handler: (Request) -> Response) {
        var callCount: Int = 0
            private set

        val client: OkHttpClient = mockk {
            every { newCall(any()) } answers {
                callCount++
                val request = firstArg<Request>()
                mockk<Call> { every { execute() } returns handler(request) }
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
    }
}
