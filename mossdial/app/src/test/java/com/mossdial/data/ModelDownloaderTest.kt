package com.mossdial.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class ModelDownloaderTest {
    private lateinit var base: File
    private lateinit var store: ModelStore
    private val requested = mutableListOf<String>()

    private val gguf = "GGUF".toByteArray() + ByteArray(60)

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mossdial-download-").toFile()
        store = ModelStore(File(base, "models"))
        requested.clear()
    }

    @After
    fun tearDown() {
        deleteTreeForTest(base)
    }

    @Test
    fun downloadsOverHttpsAndReportsProgress() {
        val progress = mutableListOf<Pair<Long, Long>>()
        val downloader = downloader { FakeConnection(URL("https://example.test/a.gguf"), 200, gguf) }

        val stored = downloader.download("https://example.test/models/tiny.gguf") { read, total ->
            progress += read to total
        }

        assertEquals("tiny.gguf", stored.name)
        assertEquals(64L, stored.sizeBytes)
        assertEquals(listOf("https://example.test/models/tiny.gguf"), requested)
        assertEquals(64L to 64L, progress.last())
        assertTrue(progress.any { it.first < 64L })
        assertEquals(listOf("tiny.gguf"), store.list().map { it.name })
    }

    @Test
    fun keepsACollidingDownloadUnderANewName() {
        File(base, "models").mkdirs()
        File(base, "models/tiny.gguf").writeBytes(ByteArray(4))
        val downloader = downloader { FakeConnection(URL("https://example.test/tiny.gguf"), 200, gguf) }

        val stored = downloader.download("https://example.test/tiny.gguf")

        assertEquals("tiny-2.gguf", stored.name)
    }

    @Test
    fun rejectsPlainHttpBeforeConnecting() {
        val downloader = downloader { FakeConnection(URL("http://example.test/a.gguf"), 200, gguf) }

        val failure = assertThrows(ModelDownloader.DownloadException::class.java) {
            downloader.download("http://example.test/a.gguf")
        }

        assertTrue(failure.message!!.contains("https"))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun rejectsARedirectThatLeavesHttps() {
        val downloader = downloader {
            FakeConnection(URL("http://elsewhere.test/a.gguf"), 200, gguf)
        }

        val failure = assertThrows(ModelDownloader.DownloadException::class.java) {
            downloader.download("https://example.test/a.gguf")
        }

        assertTrue(failure.message!!.contains("redirected off HTTPS"))
    }

    @Test
    fun rejectsANonSuccessStatus() {
        val downloader = downloader { FakeConnection(URL("https://example.test/a.gguf"), 404, ByteArray(0)) }

        val failure = assertThrows(ModelDownloader.DownloadException::class.java) {
            downloader.download("https://example.test/a.gguf")
        }

        assertTrue(failure.message!!.contains("404"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun rejectsAUrlThatIsNotAModel() {
        val downloader = downloader { FakeConnection(URL("https://example.test/a.bin"), 200, ByteArray(4)) }

        assertThrows(ModelDownloader.DownloadException::class.java) {
            downloader.download("https://example.test/a.bin")
        }
        assertTrue(requested.isEmpty())
    }

    @Test
    fun rejectsAResponseThatIsNotGguf() {
        val downloader = downloader { FakeConnection(URL("https://example.test/a.gguf"), 200, "nope".toByteArray()) }

        val failure = assertThrows(ModelDownloader.DownloadException::class.java) {
            downloader.download("https://example.test/a.gguf")
        }

        assertTrue(failure.message!!.contains("not a GGUF file"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun rejectsATruncatedResponse() {
        val downloader = downloader {
            FakeConnection(URL("https://example.test/a.gguf"), 200, gguf, declaredLength = 4096L)
        }

        val failure = assertThrows(ModelDownloader.DownloadException::class.java) {
            downloader.download("https://example.test/a.gguf")
        }

        assertTrue(failure.message!!.contains("ended early"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun acceptsAnUnknownContentLength() {
        val downloader = downloader {
            FakeConnection(URL("https://example.test/a.gguf"), 200, gguf, declaredLength = -1L)
        }

        val stored = downloader.download("https://example.test/a.gguf")

        assertEquals(64L, stored.sizeBytes)
    }

    @Test
    fun leavesNoPartialFileWhenTheConnectionBreaks() {
        val downloader = downloader {
            FakeConnection(URL("https://example.test/a.gguf"), 200, gguf, failAfter = 16)
        }

        assertThrows(IOException::class.java) { downloader.download("https://example.test/a.gguf") }

        assertTrue(store.list().isEmpty())
        assertNull(File(base, "models").listFiles()?.firstOrNull { it.name.endsWith(".part") })
    }

    @Test
    fun rejectsAnOversizedDeclaredLength() {
        val downloader = downloader {
            FakeConnection(
                URL("https://example.test/a.gguf"),
                200,
                gguf,
                declaredLength = ModelStore.MAX_MODEL_BYTES + 1
            )
        }

        assertThrows(ModelDownloader.DownloadException::class.java) {
            downloader.download("https://example.test/a.gguf")
        }
    }

    @Test
    fun rejectsAnEmptyResponse() {
        val downloader = downloader { FakeConnection(URL("https://example.test/a.gguf"), 200, ByteArray(0)) }

        assertThrows(ModelDownloader.DownloadException::class.java) {
            downloader.download("https://example.test/a.gguf")
        }
    }

    @Test
    fun rejectsAMalformedUrl() {
        val downloader = downloader { FakeConnection(URL("https://example.test/a.gguf"), 200, gguf) }

        assertThrows(ModelDownloader.DownloadException::class.java) { downloader.download("not a url") }
        assertThrows(ModelDownloader.DownloadException::class.java) { downloader.download("https://") }
    }

    private fun downloader(connection: (URL) -> FakeConnection) = ModelDownloader(store) { url ->
        requested += url.toString()
        connection(url)
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: ByteArray,
        private val declaredLength: Long = body.size.toLong(),
        private val failAfter: Int = -1
    ) : HttpURLConnection(url) {
        private var connected = false

        override fun connect() {
            connected = true
        }

        override fun getResponseCode(): Int = status

        override fun getContentLengthLong(): Long = declaredLength

        override fun usingProxy(): Boolean = false

        override fun disconnect() {
        }

        override fun getInputStream(): InputStream {
            if (!connected) {
                connect()
            }
            return object : ByteArrayInputStream(body) {
                private var read = 0

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (failAfter >= 0 && read >= failAfter) {
                        throw IOException("connection reset")
                    }
                    return super.read(buffer, offset, length).also { if (it > 0) read += it }
                }
            }
        }
    }

    private fun deleteTreeForTest(directory: File) {
        if (!directory.exists() && !Files.isSymbolicLink(directory.toPath())) return
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder<Path>()).forEach { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: Exception) {
                }
            }
        }
    }
}
