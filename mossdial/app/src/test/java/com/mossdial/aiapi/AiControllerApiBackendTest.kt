package com.mossdial.aiapi

import com.mossdial.ai.AiController
import com.mossdial.ai.AiControllerRegistry
import com.mossdial.ai.ChatTurn
import com.mossdial.ai.GenerationConfig
import com.mossdial.ai.LlamaException
import com.mossdial.ai.LlamaRuntime
import com.mossdial.ai.OnnxEmbedding
import com.mossdial.ai.OnnxModelInfo
import com.mossdial.ai.OnnxRuntime
import com.mossdial.ai.OnnxTokenBatch
import com.mossdial.data.ModelDownloader
import com.mossdial.data.ModelImporter
import com.mossdial.data.ModelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The seam between the AI tab's controller and the HTTP layer: which controller answers, and how a
 * refusal from it becomes the failure the listener turns into a status.
 */
class AiControllerApiBackendTest {
    private lateinit var base: File
    private lateinit var store: ModelStore
    private lateinit var llama: FakeLlama
    private var controller: AiController? = null

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mossdial-backend-").toFile()
        store = ModelStore(File(base, "models"))
        llama = FakeLlama()
    }

    @After
    fun tearDown() {
        controller?.let { active ->
            AiControllerRegistry.detach(active)
            active.release()
        }
        deleteTreeForTest(base)
    }

    @Test
    fun saysSoWhenNoAiTabIsOpen() {
        val backend = AiControllerApiBackend()

        assertNull(backend.activeModel())
        assertTrue(backend.models().isEmpty())
        assertTrue(backend.runtimeStatus().contains("AI tab"))
        val failure = assertThrows(AiApiFailure.Unavailable::class.java) {
            backend.generate(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 64, 5_000)
        }
        assertTrue(failure.message!!.contains("Open the AI tab"))
    }

    @Test
    fun answersFromTheModelTheTabHasLoaded() {
        writeModel("a.gguf")
        val tab = tab()
        tab.selectGguf("a.gguf")
        val backend = AiControllerApiBackend()

        assertEquals("a.gguf", backend.activeModel())
        assertEquals(
            listOf(AiApiModel("a.gguf", loaded = true)),
            backend.models()
        )
        assertEquals("Hi there", backend.generate(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 64, 5_000))
        assertEquals(listOf("a.gguf"), llama.loaded)
        assertEquals("User: hi\nAssistant:", llama.prompts.last())
    }

    @Test
    fun stopsNamingTheModelOnceTheTabIsGone() {
        writeModel("a.gguf")
        val tab = tab()
        tab.selectGguf("a.gguf")
        AiControllerRegistry.detach(tab)

        val backend = AiControllerApiBackend()
        assertNull(backend.activeModel())
        assertTrue(backend.models().isEmpty())
    }

    @Test
    fun reportsAModelThatIsNotLoadedYetAsUnavailable() {
        writeModel("a.gguf")
        tab()

        val failure = assertThrows(AiApiFailure.Unavailable::class.java) {
            AiControllerApiBackend().generate(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 64, 5_000)
        }
        assertEquals("No GGUF model is loaded", failure.message)
    }

    @Test
    fun reportsAFailedGenerationAsAFailure() {
        writeModel("a.gguf")
        val tab = tab()
        tab.selectGguf("a.gguf")
        llama.failure = "context is too small"

        val failure = assertThrows(AiApiFailure.Failed::class.java) {
            AiControllerApiBackend().generate(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 64, 5_000)
        }
        assertEquals("context is too small", failure.message)
    }

    @Test
    fun reportsAGenerationThatIsAlreadyRunningAsBusy() {
        writeModel("a.gguf")
        val tab = tab()
        tab.selectGguf("a.gguf")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        llama.beforeGenerate = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val backend = AiControllerApiBackend()
        val failure = AtomicReference<Throwable?>()
        val caller = Thread {
            failure.set(
                runCatching { backend.generate(listOf(ChatTurn(ChatTurn.Role.User, "api")), 64, 5_000) }
                    .exceptionOrNull()
            )
        }
        caller.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val busy = assertThrows(AiApiFailure.Busy::class.java) {
            backend.generate(listOf(ChatTurn(ChatTurn.Role.User, "second")), 64, 5_000)
        }
        assertTrue(busy.message!!.contains("already being generated"))

        release.countDown()
        caller.join(5_000)
        assertNull(failure.get())
    }

    @Test
    fun aPublishedStatusDescribesWhatIsRunning() {
        assertEquals("", AiApiStatus.STOPPED.endpoint())
        assertEquals("http://127.0.0.1:8081", AiApiStatus.running(8081, allowLan = false).endpoint())
        assertTrue(AiApiStatus.running(8081, allowLan = true).endpoint().contains("0.0.0.0:8081"))
        assertTrue(AiApiStatus.failed("port in use").detail.isNotEmpty())
        assertTrue(AiApiState.publish(AiApiStatus.running(9000, allowLan = false)).isRunning)
        assertSame(AiApiState.snapshot().phase, AiApiPhase.Running)
        AiApiState.publish(AiApiStatus.STOPPED)
    }

    private fun tab(): AiController {
        val tab = AiController(
            store = store,
            importer = ModelImporter(store, object : ModelImporter.ModelSource {
                override fun displayName(location: String): String? = null
                override fun openStream(location: String) = throw IOException("unused")
            }),
            downloader = ModelDownloader(store) { throw IOException("no network in this test") },
            llama = llama,
            onnx = object : OnnxRuntime {
                override fun isAvailable(): Boolean = false
                override fun unavailableReason(): String = "unused"
                override fun version(): String = "unused"
                override fun describe(path: String): OnnxModelInfo = throw IOException("unused")
                override fun runWithZeros(path: String): OnnxModelInfo = throw IOException("unused")
                override fun encode(path: String, batch: OnnxTokenBatch): OnnxEmbedding =
                    throw IOException("unused")
                override fun close(path: String) = Unit
            },
            threads = 1,
            post = { action -> action() },
            executor = InlineExecutor
        )
        controller = tab
        AiControllerRegistry.attach(tab)
        return tab
    }

    private fun writeModel(name: String) {
        val directory = File(base, "models")
        directory.mkdirs()
        File(directory, name).writeBytes("GGUF".toByteArray() + ByteArray(8))
    }

    private class FakeLlama : LlamaRuntime {
        var failure: String? = null
        var beforeGenerate: (() -> Unit)? = null
        val loaded = mutableListOf<String>()
        val prompts = mutableListOf<String>()

        override fun systemInfo(): String = "llama 0 test"
        override fun isAvailable(): Boolean = true
        override fun unavailableReason(): String? = null
        override fun load(path: String, config: GenerationConfig): Long {
            loaded += File(path).name
            return loaded.size.toLong()
        }

        override fun describe(handle: Long): String = "model ${loaded[handle.toInt() - 1]}"
        override fun unload(handle: Long) = Unit

        override fun generate(
            handle: Long,
            prompt: String,
            config: GenerationConfig,
            onToken: (String) -> Unit
        ): String {
            beforeGenerate?.invoke()
            prompts += prompt
            failure?.let { throw LlamaException(it) }
            onToken("Hi there")
            return "Hi there"
        }
    }

    private object InlineExecutor : AbstractExecutorService() {
        private var stopped = false

        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
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
