package com.mossdial.ai

import com.mossdial.data.ModelDownloader
import com.mossdial.data.ModelImporter
import com.mossdial.data.ModelStore
import com.mossdial.data.StoredModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AiControllerTest {
    private lateinit var base: File
    private lateinit var store: ModelStore
    private lateinit var llama: FakeLlamaRuntime
    private lateinit var onnx: FakeOnnxRuntime
    private lateinit var source: FakeSource

    private val gguf = "GGUF".toByteArray() + ByteArray(12)

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mossdial-controller-").toFile()
        store = ModelStore(File(base, "models"))
        llama = FakeLlamaRuntime()
        onnx = FakeOnnxRuntime()
        source = FakeSource()
    }

    @After
    fun tearDown() {
        deleteTreeForTest(base)
    }

    @Test
    fun startsWithTheModelsOnDiskAndBothRuntimeStatuses() {
        writeModel("a.gguf")
        writeModel("b.onnx", "not really an onnx file")

        val controller = controller()

        assertEquals(listOf("a.gguf", "b.onnx"), controller.models.map { it.name })
        assertEquals(listOf("a.gguf"), controller.ggufModels.map { it.name })
        assertEquals(listOf("b.onnx"), controller.onnxModels.map { it.name })
        assertEquals("llama 0 test", controller.llamaStatus)
        assertEquals("onnx 1 test", controller.onnxStatus)
        assertFalse(controller.busy)
        assertNull(controller.loadedModel)
    }

    @Test
    fun reportsAnUnavailableRuntimeInsteadOfHidingIt() {
        llama.available = false
        llama.reason = "libmossdial_ai.so is missing"

        val controller = controller()

        assertEquals("libmossdial_ai.so is missing", controller.llamaStatus)
        assertFalse(controller.canChat)
    }

    @Test
    fun loadingAModelUnloadsThePreviousOneAndReportsItsDescription() {
        writeModel("a.gguf")
        writeModel("b.gguf")
        val controller = controller()

        controller.selectGguf("a.gguf")
        controller.selectGguf("b.gguf")

        assertEquals(listOf("a.gguf", "b.gguf"), llama.loaded)
        assertEquals(listOf("a.gguf"), llama.unloaded)
        assertEquals("b.gguf", controller.loadedModel)
        assertTrue(controller.message!!.startsWith("model b"))
        assertTrue(controller.canChat)
    }

    @Test
    fun aFailedLoadIsReportedAndLeavesNothingLoaded() {
        writeModel("a.gguf")
        llama.loadFailure = "not a GGUF file"
        val controller = controller()

        controller.selectGguf("a.gguf")

        assertEquals("Error: not a GGUF file", controller.message)
        assertNull(controller.loadedModel)
        assertFalse(controller.busy)
    }

    @Test
    fun sendingBeforeALoadIsRefused() {
        writeModel("a.gguf")
        val controller = controller()
        controller.updateDraft("hello")

        controller.send()

        assertTrue(controller.message!!.contains("Load a GGUF model"))
        assertTrue(controller.chatTurns.isEmpty())
    }

    @Test
    fun aTurnBecomesAUserAndAnAssistantEntry() {
        writeModel("a.gguf")
        val controller = controller()
        controller.selectGguf("a.gguf")
        controller.updateDraft("hello")

        controller.send()

        assertEquals(2, controller.chatTurns.size)
        assertEquals(ChatTurn.Role.User, controller.chatTurns[0].role)
        assertEquals("hello", controller.chatTurns[0].text)
        assertEquals(ChatTurn.Role.Assistant, controller.chatTurns[1].role)
        assertEquals("Hi there", controller.chatTurns[1].text)
        assertEquals("Hi there", controller.reply)
        assertEquals("User: hello\nAssistant:", llama.lastPrompt)
        assertEquals("", controller.draft)
    }

    @Test
    fun anEmptyDraftIsIgnored() {
        writeModel("a.gguf")
        val controller = controller()
        controller.selectGguf("a.gguf")
        controller.updateDraft("   ")

        controller.send()

        assertTrue(controller.chatTurns.isEmpty())
        assertNull(llama.lastPrompt)
    }

    @Test
    fun stoppingMidGenerationKeepsThePartialReply() {
        writeModel("a.gguf")
        llama.tokens = listOf("one ", "two ", "three")
        lateinit var controller: AiController
        llama.afterFirstToken = { controller.stopGeneration() }
        controller = controller()
        controller.selectGguf("a.gguf")
        controller.updateDraft("hello")

        controller.send()

        assertEquals("Stopped", controller.message)
        assertEquals("one ", controller.reply)
        assertEquals(1, controller.chatTurns.size)
    }

    @Test
    fun aGenerationFailureIsReportedWithTheRuntimeMessage() {
        writeModel("a.gguf")
        val controller = controller()
        controller.selectGguf("a.gguf")
        llama.generateFailure = "context is too small"
        controller.updateDraft("hello")

        controller.send()

        assertEquals("Error: context is too small", controller.message)
        assertFalse(controller.busy)
    }

    @Test
    fun unloadingClearsTheModelAndTheTranscript() {
        writeModel("a.gguf")
        val controller = controller()
        controller.selectGguf("a.gguf")
        controller.updateDraft("hello")
        controller.send()

        controller.unloadModel()

        assertNull(controller.loadedModel)
        assertTrue(controller.chatTurns.isEmpty())
        assertEquals("", controller.reply)
        assertEquals("a.gguf", llama.unloaded.last())
    }

    @Test
    fun importingSelectsTheImportedModel() {
        source.documents["content://models/1"] = FakeDocument("imported.gguf", gguf)
        val controller = controller()

        controller.importModel("content://models/1")

        assertEquals("imported.gguf", controller.selectedGguf)
        assertTrue(controller.message!!.startsWith("Imported imported.gguf"))
        assertTrue(File(base, "models/imported.gguf").isFile)
    }

    @Test
    fun aFailedImportIsReportedAndStoresNothing() {
        source.documents["content://models/1"] = FakeDocument("broken.gguf", "nope".toByteArray())
        val controller = controller()

        controller.importModel("content://models/1")

        assertTrue(controller.message!!.contains("not a GGUF file"))
        assertTrue(controller.models.isEmpty())
    }

    @Test
    fun deletingAFileRemovesItFromTheList() {
        writeModel("a.gguf")
        writeModel("b.gguf", "onnx")
        val controller = controller()
        controller.selectGguf("a.gguf")

        controller.deleteModel("a.gguf")

        assertEquals(listOf("b.gguf"), controller.models.map { it.name })
        assertNull(controller.selectedGguf)
        assertNull(controller.loadedModel)
        assertEquals(listOf("a.gguf"), llama.unloaded)
    }

    @Test
    fun deletingAnUnrelatedFileKeepsTheLoadedModel() {
        writeModel("a.gguf")
        writeModel("b.gguf")
        val controller = controller()
        controller.selectGguf("a.gguf")

        controller.deleteModel("b.gguf")

        assertEquals("a.gguf", controller.loadedModel)
        assertEquals("a.gguf", controller.selectedGguf)
        assertTrue(llama.unloaded.isEmpty())
        assertEquals(listOf("a.gguf"), controller.models.map { it.name })
    }

    @Test
    fun aDownloadSelectsTheStoredModelAndClearsItsProgress() {
        val controller = controller()

        controller.updateDownloadUrl("https://example.test/tiny.gguf")
        controller.startDownload()

        assertEquals("tiny.gguf", controller.selectedGguf)
        assertNull(controller.downloadProgress)
        assertTrue(controller.message!!.startsWith("Downloaded tiny.gguf"))
        assertTrue(File(base, "models/tiny.gguf").isFile)
    }

    @Test
    fun aDownloadWithoutAnUrlIsRefusedBeforeAnyWork() {
        val controller = controller()

        controller.updateDownloadUrl("   ")
        controller.startDownload()

        assertTrue(controller.message!!.contains("https:// URL"))
    }

    @Test
    fun aFailedDownloadClearsItsProgressAndReportsTheReason() {
        val controller = controller()
        controller.updateDownloadUrl("http://example.test/tiny.gguf")

        controller.startDownload()

        assertNull(controller.downloadProgress)
        assertTrue(controller.message!!.contains("https"))
    }

    @Test
    fun selectingAnOnnxModelDescribesIt() {
        writeModel("a.onnx", "onnx")
        val controller = controller()

        controller.selectOnnx("a.onnx")

        assertEquals("a.onnx", controller.selectedOnnx)
        assertNotNull(controller.onnxModel)
        assertEquals("1 inputs, 1 outputs", controller.message)
        assertEquals(listOf("a.onnx"), onnx.opened)
    }

    @Test
    fun runningAnOnnxModelStoresTheOutputDescription() {
        writeModel("a.onnx", "onnx")
        val controller = controller()
        controller.selectOnnx("a.onnx")

        controller.runOnnx()

        assertEquals("output FLOAT shape=[1]", controller.onnxModel?.lastRun)
        assertEquals(listOf("a.onnx"), onnx.ran)
    }

    @Test
    fun runningWithoutASelectionIsRefused() {
        val controller = controller()

        controller.runOnnx()

        assertTrue(controller.message!!.contains("Select an ONNX model"))
    }

    @Test
    fun closingAnOnnxSessionDropsTheDescription() {
        writeModel("a.onnx", "onnx")
        val controller = controller()
        controller.selectOnnx("a.onnx")

        controller.closeOnnx()

        assertNull(controller.onnxModel)
        assertEquals(listOf("a.onnx"), onnx.closed)
    }

    @Test
    fun anUnreadableOnnxModelIsReported() {
        writeModel("a.onnx", "onnx")
        onnx.describeFailure = "invalid protobuf"
        val controller = controller()

        controller.selectOnnx("a.onnx")

        assertTrue(controller.message!!.contains("invalid protobuf"))
        assertNull(controller.onnxModel)
    }

    @Test
    fun releaseUnloadsEverything() {
        writeModel("a.gguf")
        writeModel("b.onnx", "onnx")
        val controller = controller()
        controller.selectGguf("a.gguf")
        controller.selectOnnx("b.onnx")

        controller.release()

        assertEquals(listOf("a.gguf"), llama.unloaded)
        assertEquals(listOf("b.onnx"), onnx.closed)
        assertFalse(controller.busy)
    }

    @Test
    fun chatNowBlocksTheCallerAndReturnsTheReply() {
        writeModel("a.gguf")
        llama.tokens = listOf("one ", "two")
        val controller = controller()
        controller.selectGguf("a.gguf")

        val reply = controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "hello")), 64, 5_000)

        assertEquals("one two", reply)
        assertEquals("User: hello\nAssistant:", llama.lastPrompt)
        assertTrue(controller.chatTurns.isEmpty())
    }

    @Test
    fun chatNowKeepsTheTabTranscriptToItself() {
        writeModel("a.gguf")
        val controller = controller()
        controller.selectGguf("a.gguf")
        controller.updateDraft("hello")
        controller.send()

        controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "unrelated")), 64, 5_000)

        assertEquals(2, controller.chatTurns.size)
        assertEquals("Hi there", controller.chatTurns[1].text)
    }

    @Test
    fun chatNowIsRefusedWhileAnotherGenerationIsRunning() {
        writeModel("a.gguf")
        llama.tokens = listOf("one ", "two")
        val controller = controller()
        controller.selectGguf("a.gguf")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        llama.beforeGenerate = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val failure = AtomicReference<Throwable?>()

        val caller = Thread {
            failure.set(
                runCatching {
                    controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "api")), 64, 5_000)
                }.exceptionOrNull()
            )
        }
        caller.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val busy = assertThrows(AiGenerationBusyException::class.java) {
            controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "second")), 64, 5_000)
        }
        assertTrue(busy.message!!.contains("already being generated"))

        release.countDown()
        caller.join(5_000)
        assertNull(failure.get())
    }

    @Test
    fun chatNowClampsTheTokenBudgetToTheTabSetting() {
        writeModel("a.gguf")
        val controller = controller()
        controller.selectGguf("a.gguf")
        controller.updateConfig(GenerationConfig(maxTokens = 32))

        controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 4_096, 5_000)

        assertEquals(32, llama.lastMaxTokens)
    }

    @Test
    fun chatNowWithoutAModelSaysSo() {
        writeModel("a.gguf")
        val controller = controller()

        val failure = assertThrows(AiGenerationUnavailableException::class.java) {
            controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 64, 5_000)
        }

        assertEquals("No GGUF model is loaded", failure.message)
    }

    @Test
    fun chatNowWithAnUnavailableRuntimeSaysSo() {
        writeModel("a.gguf")
        llama.available = false
        llama.reason = "libmossdial_ai.so is missing"
        val controller = controller()
        controller.selectGguf("a.gguf")

        val failure = assertThrows(AiGenerationUnavailableException::class.java) {
            controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 64, 5_000)
        }

        assertEquals("libmossdial_ai.so is missing", failure.message)
    }

    @Test
    fun chatNowPassesOnARuntimeFailure() {
        writeModel("a.gguf")
        llama.generateFailure = "context is too small"
        val controller = controller()
        controller.selectGguf("a.gguf")

        val failure = assertThrows(LlamaException::class.java) {
            controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 64, 5_000)
        }

        assertEquals("context is too small", failure.message)
    }

    @Test
    fun chatNowRejectsAnEmptyConversation() {
        writeModel("a.gguf")
        val controller = controller()
        controller.selectGguf("a.gguf")

        assertThrows(IllegalArgumentException::class.java) {
            controller.chatNow(emptyList(), 64, 5_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 0, 5_000)
        }
        assertNull(llama.lastPrompt)
    }

    @Test
    fun chatNowIsRefusedOnceTheTabIsReleased() {
        writeModel("a.gguf")
        val controller = controller()
        controller.selectGguf("a.gguf")

        controller.release()

        // Either the queued teardown has already cleared the handle, or this request is turned away
        // at the queue. Both are the same answer for a client: nothing is loaded to answer with.
        val failure = assertThrows(AiGenerationUnavailableException::class.java) {
            controller.chatNow(listOf(ChatTurn(ChatTurn.Role.User, "hi")), 64, 5_000)
        }
        assertTrue(failure.message!!.isNotEmpty())
    }

    @Test
    fun selectsATextEncoderAndLoadsTheVocabularyBesideIt() {
        writeModel("encoder.onnx")
        writeVocab("encoder.txt", "[UNK]\n[CLS]\n[SEP]\nhello\nworld\n")
        onnx.isEncoder = true
        val controller = controller()

        controller.selectOnnx("encoder.onnx")

        assertNotNull(controller.encoder)
        assertTrue(controller.encoder!!.supported)
        assertEquals("last_hidden_state", controller.encoder!!.outputName)
        assertEquals(384, controller.encoder!!.dimensions)
        assertEquals("encoder.txt", controller.vocabularyName)
        assertTrue(controller.vocabularyStatus.contains("5 tokens"))
        assertTrue(controller.canEmbed)
    }

    @Test
    fun anEncoderWithoutItsVocabularyCannotEmbed() {
        writeModel("encoder.onnx")
        onnx.isEncoder = true
        val controller = controller()

        controller.selectOnnx("encoder.onnx")

        assertTrue(controller.encoder!!.supported)
        assertNull(controller.vocabularyName)
        assertTrue(controller.vocabularyStatus.contains("No vocab.txt"))
        assertFalse(controller.canEmbed)
        val failure = assertThrows(AiGenerationUnavailableException::class.java) {
            controller.embedNow("hello", 5_000)
        }
        assertTrue(failure.message!!.contains("vocab.txt"))
        assertTrue(onnx.encoded.isEmpty())
    }

    @Test
    fun embedsTextThroughTheSelectedEncoder() {
        writeModel("encoder.onnx")
        writeVocab("encoder.txt", "[UNK]\n[CLS]\n[SEP]\nhello\nworld\n")
        onnx.isEncoder = true
        val controller = controller()
        controller.selectOnnx("encoder.onnx")

        val vector = controller.embedNow("hello world", 5_000)

        assertEquals(4, vector.size)
        assertEquals(0.5f, vector[0], 0f)
        assertEquals(listOf("encoder.onnx" to 4), onnx.encoded)
    }

    @Test
    fun theTabEmbedsAndPublishesTheShapeOfTheVector() {
        writeModel("encoder.onnx")
        writeVocab("vocab.txt", "[UNK]\n[CLS]\n[SEP]\nhello\n")
        onnx.isEncoder = true
        val controller = controller()
        controller.selectOnnx("encoder.onnx")
        controller.updateEmbeddingInput("hello")

        controller.embedText()

        val summary = controller.embedding!!
        assertEquals("encoder.onnx", summary.model)
        assertEquals(4, summary.dimensions)
        assertEquals(3, summary.tokens)
        assertEquals(1.0, summary.norm, 1e-6)
        assertEquals(4, summary.firstValues.size)
    }

    @Test
    fun embeddingNeedsText() {
        writeModel("encoder.onnx")
        writeVocab("encoder.txt", "[UNK]\n[CLS]\n[SEP]\nhello\n")
        onnx.isEncoder = true
        val controller = controller()
        controller.selectOnnx("encoder.onnx")

        controller.updateEmbeddingInput("   ")
        controller.embedText()

        assertNull(controller.embedding)
        assertTrue(controller.message!!.contains("Type something to embed"))
        assertThrows(IllegalArgumentException::class.java) { controller.embedNow("  ", 5_000) }
    }

    @Test
    fun embeddingNeedsASelectedEncoder() {
        writeModel("encoder.onnx")
        writeVocab("encoder.txt", "[UNK]\n[CLS]\n[SEP]\nhello\n")
        val controller = controller()
        controller.updateEmbeddingInput("hello")

        controller.embedText()

        assertTrue(controller.message!!.contains("Select an ONNX encoder"))
        assertThrows(AiGenerationUnavailableException::class.java) { controller.embedNow("hello", 5_000) }
    }

    @Test
    fun aModelThatIsNotATextEncoderIsRefusedWithItsReason() {
        writeModel("plain.onnx")
        writeVocab("plain.txt", "[UNK]\n[CLS]\n[SEP]\n")
        val controller = controller()

        controller.selectOnnx("plain.onnx")

        assertFalse(controller.encoder!!.supported)
        assertTrue(controller.encoder!!.reason!!.contains("input_ids"))
        assertFalse(controller.canEmbed)
        assertTrue(controller.encoder!!.summary.contains("input_ids"))
    }

    @Test
    fun importingAVocabularyEnablesEmbedding() {
        writeModel("encoder.onnx")
        onnx.isEncoder = true
        val controller = controller()
        controller.selectOnnx("encoder.onnx")
        assertFalse(controller.canEmbed)
        source.documents["content://vocab/1"] = FakeDocument(
            "vocab.txt",
            "[UNK]\n[CLS]\n[SEP]\nhello\n".toByteArray()
        )

        controller.importVocabulary("content://vocab/1")

        assertEquals("vocab.txt", controller.vocabularyName)
        assertTrue(controller.canEmbed)
        assertEquals(4, controller.embedNow("hello", 5_000).size)
    }

    @Test
    fun aDocumentThatIsNotAVocabularyIsReportedAndNothingIsLoaded() {
        writeModel("encoder.onnx")
        onnx.isEncoder = true
        val controller = controller()
        controller.selectOnnx("encoder.onnx")
        source.documents["content://vocab/1"] = FakeDocument("vocab.txt", "no tokens here".toByteArray())

        controller.importVocabulary("content://vocab/1")

        assertNull(controller.vocabularyName)
        assertFalse(controller.canEmbed)
        assertTrue(controller.message!!.contains("[UNK]"))
    }

    @Test
    fun deletingTheVocabularyTurnsEmbeddingOff() {
        writeModel("encoder.onnx")
        writeVocab("encoder.txt", "[UNK]\n[CLS]\n[SEP]\nhello\n")
        onnx.isEncoder = true
        val controller = controller()
        controller.selectOnnx("encoder.onnx")

        controller.deleteVocabulary("encoder.txt")

        assertNull(controller.vocabularyName)
        assertFalse(controller.canEmbed)
        assertTrue(controller.vocabularyStatus.contains("No vocab.txt"))
    }

    @Test
    fun deletingTheSelectedModelClosesTheEncoder() {
        writeModel("encoder.onnx")
        writeVocab("encoder.txt", "[UNK]\n[CLS]\n[SEP]\nhello\n")
        onnx.isEncoder = true
        val controller = controller()
        controller.selectOnnx("encoder.onnx")
        controller.embedText()

        controller.deleteModel("encoder.onnx")

        assertNull(controller.onnxModel)
        assertNull(controller.encoder)
        assertNull(controller.embedding)
        assertNull(controller.vocabularyName)
        assertEquals(listOf("encoder.onnx"), onnx.closed)
    }

    @Test
    fun closingTheOnnxSessionAlsoDropsTheVocabulary() {
        writeModel("encoder.onnx")
        writeVocab("encoder.txt", "[UNK]\n[CLS]\n[SEP]\nhello\n")
        onnx.isEncoder = true
        val controller = controller()
        controller.selectOnnx("encoder.onnx")

        controller.closeOnnx()

        assertNull(controller.encoder)
        assertNull(controller.embedding)
        assertFalse(controller.canEmbed)
    }

    @Test
    fun anEncoderFailureIsReportedAsAnEmbeddingFailure() {
        writeModel("encoder.onnx")
        writeVocab("encoder.txt", "[UNK]\n[CLS]\n[SEP]\nhello\n")
        onnx.isEncoder = true
        onnx.encodeFailure = "the graph rejected the input"
        val controller = controller()
        controller.selectOnnx("encoder.onnx")

        val failure = assertThrows(AiEmbeddingException::class.java) {
            controller.embedNow("hello", 5_000)
        }

        assertTrue(failure.message!!.contains("rejected"))
        assertFalse(controller.busy)
    }

    @Test
    fun theGenerationSlotIsReleasedAfterAFailedEmbedding() {
        writeModel("encoder.onnx")
        writeVocab("encoder.txt", "[UNK]\n[CLS]\n[SEP]\nhello\n")
        onnx.isEncoder = true
        onnx.encodeFailure = "boom"
        val controller = controller()
        controller.selectOnnx("encoder.onnx")
        assertThrows(AiEmbeddingException::class.java) { controller.embedNow("hello", 5_000) }

        onnx.encodeFailure = null

        assertEquals(4, controller.embedNow("hello", 5_000).size)
    }

    private fun controller(): AiController = AiController(
        store = store,
        importer = ModelImporter(store, source),
        downloader = ModelDownloader(store) { url -> FakeConnection(url) },
        llama = llama,
        onnx = onnx,
        threads = 2,
        post = { action -> action() },
        executor = InlineExecutor
    )

    private fun writeVocab(name: String, body: String) {
        val directory = File(base, "models")
        directory.mkdirs()
        File(directory, name).writeText(body)
    }

    private fun writeModel(name: String, body: String = "GGUF") {
        val directory = File(base, "models")
        directory.mkdirs()
        File(directory, name).writeBytes(if (name.endsWith(".gguf")) body.toByteArray() else ByteArray(8))
    }

    private class FakeLlamaRuntime : LlamaRuntime {
        var available = true
        var reason: String? = null
        var loadFailure: String? = null
        var generateFailure: String? = null
        var tokens: List<String>? = null
        var afterFirstToken: (() -> Unit)? = null
        var beforeGenerate: (() -> Unit)? = null
        val loaded = mutableListOf<String>()
        val unloaded = mutableListOf<String>()
        var lastPrompt: String? = null
        var lastMaxTokens: Int = 0

        override fun systemInfo(): String = "llama 0 test"
        override fun isAvailable(): Boolean = available
        override fun unavailableReason(): String? = reason
        override fun load(path: String, config: GenerationConfig): Long {
            loadFailure?.let { throw LlamaException(it) }
            loaded += File(path).name
            return loaded.size.toLong()
        }

        override fun describe(handle: Long): String = "model ${loaded[handle.toInt() - 1]}"

        override fun unload(handle: Long) {
            if (handle != 0L) {
                unloaded += loaded[handle.toInt() - 1]
            }
        }

        override fun generate(
            handle: Long,
            prompt: String,
            config: GenerationConfig,
            onToken: (String) -> Unit
        ): String {
            generateFailure?.let { throw LlamaException(it) }
            beforeGenerate?.invoke()
            lastPrompt = prompt
            lastMaxTokens = config.maxTokens
            val pieces = tokens ?: listOf("Hi there")
            val builder = StringBuilder()
            pieces.forEachIndexed { index, piece ->
                if (index == 1) {
                    afterFirstToken?.invoke()
                }
                builder.append(piece)
                onToken(piece)
            }
            return builder.toString()
        }
    }

    private class FakeOnnxRuntime : OnnxRuntime {
        var describeFailure: String? = null
        var isEncoder = false
        val opened = mutableListOf<String>()
        val ran = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val encoded = mutableListOf<Pair<String, Int>>()
        var encodeFailure: String? = null

        override fun isAvailable(): Boolean = true
        override fun unavailableReason(): String? = null
        override fun version(): String = "onnx 1 test"

        override fun describe(path: String): OnnxModelInfo {
            describeFailure?.let { throw OnnxException(it) }
            opened += File(path).name
            return if (isEncoder) {
                OnnxModelInfo(
                    path = path,
                    inputs = listOf(
                        OnnxTensorSpec("input_ids", listOf(1L, -1L), "INT64", false),
                        OnnxTensorSpec("attention_mask", listOf(1L, -1L), "INT64", false),
                        OnnxTensorSpec("token_type_ids", listOf(1L, -1L), "INT64", false)
                    ),
                    outputs = listOf(
                        OnnxTensorSpec("last_hidden_state", listOf(1L, -1L, 384L), "FLOAT", false)
                    )
                )
            } else {
                OnnxModelInfo(
                    path = path,
                    inputs = listOf(OnnxTensorSpec("input", listOf(1L), "FLOAT", true)),
                    outputs = listOf(OnnxTensorSpec("output", listOf(1L), "FLOAT", true))
                )
            }
        }

        override fun runWithZeros(path: String): OnnxModelInfo {
            ran += File(path).name
            return describe(path).copy(lastRun = "output FLOAT shape=[1]")
        }

        override fun encode(path: String, batch: OnnxTokenBatch): OnnxEmbedding {
            encoded += File(path).name to batch.length
            encodeFailure?.let { throw OnnxException(it) }
            return OnnxEmbedding(FloatArray(4) { 0.5f }, 4, batch.length, false)
        }

        override fun close(path: String) {
            closed += File(path).name
        }
    }

    private class FakeDocument(val name: String, private val bytes: ByteArray) {
        fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    private class FakeSource : ModelImporter.ModelSource {
        val documents = HashMap<String, FakeDocument?>()

        override fun displayName(location: String): String? = documents[location]?.name

        override fun openStream(location: String): InputStream =
            documents[location]?.open() ?: throw IOException("unknown document $location")
    }

    private class FakeConnection(url: URL) : HttpURLConnection(url) {
        private val body = ("GGUF" + "x".repeat(60)).toByteArray()
        private var connected = false

        override fun connect() {
            connected = true
        }

        override fun getResponseCode(): Int = 200

        override fun getContentLengthLong(): Long = body.size.toLong()

        override fun usingProxy(): Boolean = false

        override fun disconnect() = Unit

        override fun getInputStream(): InputStream {
            if (!connected) {
                connect()
            }
            return ByteArrayInputStream(body)
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
