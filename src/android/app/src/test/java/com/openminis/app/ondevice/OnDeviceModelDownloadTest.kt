package com.openminis.app.ondevice

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 下载器的纯逻辑：镜像 URL 拼接、Content-Range 解析、Range 续传与
 * 「服务器忽略 Range」的重写路径。不涉及 Android Context。
 */
class OnDeviceModelDownloadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun mirrorUrlNormalizesTrailingSlash() {
        assertEquals("https://a/b/file", mirrorUrl("https://a/b/", "file"))
        assertEquals("https://a/b/file", mirrorUrl("https://a/b", "file"))
    }

    @Test
    fun contentRangeStartIsParsed() {
        assertEquals(0L, parseContentRangeStart("bytes 0-4095/1234567"))
        assertEquals(4096L, parseContentRangeStart("Bytes 4096-8191/1234567"))
        assertNull(parseContentRangeStart(null))
        assertNull(parseContentRangeStart("items 1-2/3"))
        assertNull(parseContentRangeStart("bytes */100"))
    }

    @Test
    fun resumeAppendsFromPartialFile() {
        val part = File(temp.root, "model.part")
        part.writeText("01234")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-9/10")
                .setBody("56789"),
        )

        val written = downloadOne(
            client = client,
            url = server.url("/model").toString(),
            expectedBytes = 10,
            part = part,
        )

        assertEquals(10L, written)
        assertEquals("0123456789", part.readText())
        val request = server.takeRequest()
        assertEquals("bytes=5-", request.getHeader("Range"))
    }

    @Test
    fun serverIgnoringRangeRestartsFromZero() {
        val part = File(temp.root, "model.part")
        part.writeText("01234")
        server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))

        val written = downloadOne(
            client = client,
            url = server.url("/model").toString(),
            expectedBytes = 10,
            part = part,
        )

        assertEquals(10L, written)
        assertEquals("0123456789", part.readText())
    }

    @Test(expected = java.io.IOException::class)
    fun truncatedBodyThrows() {
        val part = File(temp.root, "model.part")
        server.enqueue(MockResponse().setResponseCode(200).setBody("01234"))
        downloadOne(
            client = client,
            url = server.url("/model").toString(),
            expectedBytes = 10,
            part = part,
        )
    }

    @Test
    fun sha256MatchesKnownDigest() {
        val file = File(temp.root, "tokens.txt")
        file.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Of(file),
        )
    }

    @Test
    fun catalogSizesArePositiveAndUnique() {
        val names = OnDeviceModelManager.specs.flatMap { spec ->
            assertTrue(spec.totalBytes > 100_000_000L)
            spec.files.map { it.name }
        }
        assertEquals(names.size, names.distinct().size)
        assertTrue(OnDeviceModelManager.specs.any { it.key == OnDeviceModelManager.ASR_SENSEVOICE })
        assertTrue(OnDeviceModelManager.specs.any { it.key == OnDeviceModelManager.FAST_LLM })
    }
}
