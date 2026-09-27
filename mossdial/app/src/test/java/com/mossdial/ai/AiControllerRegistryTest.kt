package com.mossdial.ai

import com.mossdial.data.ModelDownloader
import com.mossdial.data.ModelImporter
import com.mossdial.data.ModelStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * The registry the local AI API reads, which is what keeps one loaded model behind one controller
 * instead of a copy per caller.
 */
class AiControllerRegistryTest {
    private lateinit var base: File
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mossdial-registry-").toFile()
        store = ModelStore(File(base, "models"))
    }

    @After
    fun tearDown() {
        AiControllerRegistry.current()?.let { AiControllerRegistry.detach(it) }
        deleteTreeForTest(base)
    }

    @Test
    fun isEmptyUntilATabAttachesItsController() {
        assertNull(AiControllerRegistry.current())

        val controller = controller()

        AiControllerRegistry.attach(controller)
        assertSame(controller, AiControllerRegistry.current())
        assertTrue(AiControllerRegistry.isAttached(controller))

        AiControllerRegistry.detach(controller)
        assertNull(AiControllerRegistry.current())
        assertFalse(AiControllerRegistry.isAttached(controller))
        controller.release()
    }

    @Test
    fun theNewestAttachmentIsTheOneThatAnswers() {
        val first = controller()
        val second = controller()

        AiControllerRegistry.attach(first)
        AiControllerRegistry.attach(second)
        assertSame(second, AiControllerRegistry.current())

        AiControllerRegistry.detach(first)
        assertSame(second, AiControllerRegistry.current())

        AiControllerRegistry.detach(second)
        assertNull(AiControllerRegistry.current())
        first.release()
        second.release()
    }

    @Test
    fun aTabThatWasReplacedDoesNotClearTheLiveEntryOnItsWayOut() {
        val first = controller()
        val second = controller()
        AiControllerRegistry.attach(first)
        AiControllerRegistry.attach(second)

        // The first tab's onDispose runs after the second tab has already taken over.
        AiControllerRegistry.detach(first)

        assertSame(second, AiControllerRegistry.current())
        AiControllerRegistry.detach(second)
        first.release()
        second.release()
    }

    private fun controller(): AiController = AiController(
        store = store,
        importer = ModelImporter(store, EmptySource),
        downloader = ModelDownloader(store) { throw IOException("no network in this test") },
        llama = UnavailableLlama,
        onnx = object : OnnxRuntime {
            override fun isAvailable(): Boolean = false
            override fun unavailableReason(): String = "not needed here"
            override fun version(): String = "unused"
            override fun describe(path: String): OnnxModelInfo = throw IOException("unused")
            override fun runWithZeros(path: String): OnnxModelInfo = throw IOException("unused")
            override fun encode(path: String, batch: OnnxTokenBatch): OnnxEmbedding =
                throw IOException("unused")
            override fun close(path: String) = Unit
        },
        threads = 1,
        post = { action -> action() }
    )

    private object EmptySource : ModelImporter.ModelSource {
        override fun displayName(location: String): String? = null
        override fun openStream(location: String): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private object UnavailableLlama : LlamaRuntime {
        override fun systemInfo(): String = "unused"
        override fun isAvailable(): Boolean = false
        override fun unavailableReason(): String = "unused"
        override fun load(path: String, config: GenerationConfig): Long = 0L
        override fun describe(handle: Long): String = "unused"
        override fun unload(handle: Long) = Unit
        override fun generate(
            handle: Long,
            prompt: String,
            config: GenerationConfig,
            onToken: (String) -> Unit
        ): String = ""
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
