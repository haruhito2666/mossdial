package com.mossdial.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mossdial.data.ContentResolverModelSource
import com.mossdial.data.ModelDownloader
import com.mossdial.data.ModelImporter
import com.mossdial.data.ModelKind
import com.mossdial.data.ModelStore
import com.mossdial.data.StoredModel
import com.mossdial.data.WebRoot
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One turn in the on-device chat, and why a generation may not start. */
class AiGenerationBusyException(message: String) : IllegalStateException(message)

class AiGenerationTimeoutException(message: String) : IllegalStateException(message)

class AiGenerationUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * One sentence vector, and how it was made, for the tab's display.
 *
 * The vector itself is not held: a few hundred floats are of no use to read on a phone, and keeping
 * the last one alive across the tab would hold a large float array for nothing.
 */
data class EmbeddingSummary(
    val model: String,
    val dimensions: Int,
    val tokens: Int,
    val norm: Double,
    val firstValues: List<Float>
)

/** Why an embedding could not be produced, and so which status the API answers with. */
class AiEmbeddingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Everything the AI tab shows and does, held as Compose state.
 *
 * File and inference work runs on one background thread. Every state write happens
 * through [post] so the UI thread only ever sees a finished update. There are no
 * coroutines: an [ExecutorService] plus the Compose snapshot state are the whole
 * concurrency story.
 */
class AiController(
    private val store: ModelStore,
    private val importer: ModelImporter,
    private val downloader: ModelDownloader,
    val llama: LlamaRuntime,
    val onnx: OnnxRuntime,
    private val threads: Int,
    private val post: (() -> Unit) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mossdial-ai").apply { isDaemon = true }
    }
) {

    var models by mutableStateOf(emptyList<StoredModel>())
        private set

    var selectedGguf by mutableStateOf<String?>(null)
    var selectedOnnx by mutableStateOf<String?>(null)

    var config by mutableStateOf(GenerationConfig(threads = threads))
        private set

    var chatTurns by mutableStateOf(emptyList<ChatTurn>())
        private set

    var draft by mutableStateOf("")

    /** The reply being streamed in, or the last completed reply. */
    var reply by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    var busyLabel by mutableStateOf("")
        private set

    var llamaStatus by mutableStateOf("Checking llama.cpp…")
        private set

    var onnxStatus by mutableStateOf("Checking ONNX Runtime…")
        private set

    var loadedModel by mutableStateOf<String?>(null)
        private set

    var onnxModel by mutableStateOf<OnnxModelInfo?>(null)
        private set

    /** What the selected ONNX model looks like to the embedding path, or null when none is selected. */
    var encoder by mutableStateOf<OnnxEncoderSupport?>(null)
        private set

    /** The vocabulary file beside the selected model, or null when none was imported. */
    var vocabularyName by mutableStateOf<String?>(null)
        private set

    /** Why the vocabulary is or is not loaded, in one line. */
    var vocabularyStatus by mutableStateOf("No vocabulary imported")
        private set

    var embeddingInput by mutableStateOf("")

    /** The last embedding's shape and norm, or null before the first one. */
    var embedding by mutableStateOf<EmbeddingSummary?>(null)
        private set

    var downloadUrl by mutableStateOf("")
        private set

    var downloadProgress by mutableStateOf<Pair<Long, Long>?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    val modelDirectory: String get() = store.directory().absolutePath

    val ggufModels: List<StoredModel> get() = models.filter { it.kind == ModelKind.GGUF }
    val onnxModels: List<StoredModel> get() = models.filter { it.kind == ModelKind.ONNX }

    val canChat: Boolean get() = loadedModel != null && !busy && llama.isAvailable()

    /** True when a selected encoder, its vocabulary and a free slot make an embedding possible. */
    val canEmbed: Boolean get() = encoder?.supported == true && tokenizer != null && !busy

    private val generationGuard = AtomicBoolean(false)

    /**
     * The WordPiece vocabulary of the selected encoder.
     *
     * It is written only on the worker thread, and read only there too: [embedNow] hands the work to
     * the same single threaded executor that loads and clears it, so the tab's Embed button and an
     * API request can never encode against a vocabulary that is being replaced.
     */
    private var tokenizer: WordPieceTokenizer? = null

    /**
     * The loaded model's handle, or zero when nothing is loaded.
     *
     * Volatile because [chatNow] reads it from a socket worker thread rather than from the worker
     * thread that loads and unloads the model.
     */
    @Volatile
    private var handle = 0L
    private val cancelled = AtomicBoolean(false)
    private var onnxPath: String? = null

    init {
        refreshRuntimeStatus()
        refreshModels()
    }

    fun refreshRuntimeStatus() {
        llamaStatus = llama.unavailableReason()
            ?: runCatching { llama.systemInfo() }
                .getOrElse { "llama.cpp is loaded but could not describe itself: ${it.message}" }
        onnxStatus = onnx.unavailableReason()
            ?: runCatching { onnx.version() }
                .getOrElse { "ONNX Runtime could not report its version: ${it.message}" }
    }

    fun refreshModels() = launch("Reading the model directory") { readModels() }

    fun importModel(location: String) = launch("Importing the model") {
        val stored = importer.import(location)
        readModels()
        if (stored.kind == ModelKind.GGUF) {
            selectedGguf = stored.name
        }
        "Imported ${stored.name}"
    }

    fun deleteModel(name: String) = launch("Deleting $name") {
        if (loadedModel == name) {
            unloadSession()
            loadedModel = null
            chatTurns = emptyList()
            reply = ""
        }
        val openOnnx = onnxPath
        if (openOnnx != null && openOnnx.endsWith(File.separator + name)) {
            closeOnnxSession()
            onnxModel = null
            encoder = null
            tokenizer = null
            embedding = null
        }
        if (!store.delete(name)) {
            throw IOException("$name could not be deleted")
        }
        if (selectedGguf == name) {
            selectedGguf = null
        }
        if (selectedOnnx == name) {
            selectedOnnx = null
            readVocabularyState()
        }
        readModels()
        "Deleted $name"
    }

    fun updateDownloadUrl(url: String) {
        downloadUrl = url
    }

    fun startDownload() {
        val url = downloadUrl.trim()
        if (url.isEmpty()) {
            message = "Enter an https:// URL first"
            return
        }
        launch("Downloading the model") {
            post { downloadProgress = 0L to -1L }
            val stored = try {
                downloader.download(url) { read, total -> post { downloadProgress = read to total } }
            } catch (failure: Throwable) {
                post { downloadProgress = null }
                throw failure
            }
            readModels()
            if (stored.kind == ModelKind.GGUF) {
                selectedGguf = stored.name
            }
            post { downloadProgress = null }
            "Downloaded ${stored.name} (${stored.sizeBytes} bytes)"
        }
    }

    fun selectGguf(name: String) {
        if (name == selectedGguf && loadedModel == name) {
            return
        }
        val stored = models.firstOrNull { it.name == name }
        if (stored == null) {
            message = "$name is no longer in the model directory"
            return
        }
        selectedGguf = name
        val requested = config
        launch("Loading ${stored.name}") {
            unloadSession()
            loadedModel = null
            chatTurns = emptyList()
            reply = ""
            val opened = llama.load(stored.file.absolutePath, requested)
            handle = opened
            loadedModel = stored.name
            llama.describe(opened)
        }
    }

    fun unloadModel() = launch("Unloading the model") {
        unloadSession()
        loadedModel = null
        chatTurns = emptyList()
        reply = ""
        "Model unloaded"
    }

    fun updateConfig(next: GenerationConfig) {
        config = next
    }

    fun updateDraft(text: String) {
        draft = text
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) {
            return
        }
        if (!canChat) {
            message = "Load a GGUF model before chatting"
            return
        }
        val turns = chatTurns + ChatTurn(ChatTurn.Role.User, text)
        val request = config
        chatTurns = turns
        draft = ""
        reply = ""
        cancelled.set(false)

        launch("Generating a reply") {
            // The same slot the API path takes, so a chat request from the network and a turn typed
            // here can never be in llama.cpp at the same time.
            requireGenerationSlot()
            try {
                val finalText = generate(handle, turns, request, honourCancellation = true) { snapshot ->
                    post { reply = snapshot }
                }
                post { reply = finalText }
                post { chatTurns = chatTurns + ChatTurn(ChatTurn.Role.Assistant, finalText) }
            } finally {
                releaseGenerationSlot()
            }
            ""
        }
    }

    /**
     * Generates one reply and blocks the calling thread until it is finished.
     *
     * This is the entry point for the local AI API, so a request from a socket worker ends up in
     * the same single-threaded worker as the AI tab. That is deliberate: llama.cpp, the ONNX
     * sessions and the loaded model are all touched on that one thread, so an API request can
     * never sit next to a tab turn in native code, and no second model is ever loaded for it.
     *
     * [generationGuard] is what keeps two generations apart. It is taken before the work is queued
     * and released by the worker that runs it, so a caller that gives up on [timeoutMillis] does
     * not free the guard while the model is still busy, and the next caller is told 409 rather
     * than joining the queue.
     *
     * @param maxTokens an upper bound only: the tab's own reply length is the ceiling.
     * @throws AiGenerationBusyException when a generation is already running.
     * @throws AiGenerationTimeoutException when the reply is not ready in time.
     * @throws AiGenerationUnavailableException when no model is loaded.
     */
    fun chatNow(turns: List<ChatTurn>, maxTokens: Int, timeoutMillis: Long): String {
        val activeHandle = handle
        if (!llama.isAvailable()) {
            throw AiGenerationUnavailableException(llama.unavailableReason() ?: "llama.cpp is unavailable")
        }
        if (activeHandle == 0L) {
            throw AiGenerationUnavailableException("No GGUF model is loaded")
        }
        if (turns.isEmpty() || turns.all { it.text.isBlank() }) {
            throw IllegalArgumentException("At least one message is required")
        }
        if (maxTokens < 1) {
            throw IllegalArgumentException("maxTokens must be positive")
        }
        if (timeoutMillis < 1_000L) {
            throw IllegalArgumentException("The generation timeout must be at least a second")
        }
        requireGenerationSlot()
        val outcome = GenerationOutcome()
        val finished = CountDownLatch(1)
        try {
            executor.execute {
                try {
                    val request = config.copy(maxTokens = maxTokens.coerceAtMost(config.maxTokens))
                    outcome.reply = generate(activeHandle, turns, request, honourCancellation = false) { }
                } catch (failure: Throwable) {
                    outcome.failure = failure
                } finally {
                    releaseGenerationSlot()
                    finished.countDown()
                }
            }
        } catch (rejected: RejectedExecutionException) {
            // The tab was released while this request was on its way in.
            releaseGenerationSlot()
            throw AiGenerationUnavailableException("The AI tab has been closed", rejected)
        }
        val completed = try {
            finished.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            throw AiGenerationTimeoutException("The model did not answer within ${timeoutMillis / 1_000L}s")
        }
        outcome.failure?.let { failure -> throw failure }
        return outcome.reply.orEmpty()
    }

    fun stopGeneration() {
        cancelled.set(true)
    }

    fun selectOnnx(name: String) {
        val stored = models.firstOrNull { it.name == name }
        if (stored == null) {
            message = "$name is no longer in the model directory"
            return
        }
        selectedOnnx = name
        launch("Reading ${stored.name}") {
            closeOnnxSession()
            val path = stored.file.absolutePath
            onnxPath = path
            val info = onnx.describe(path)
            onnxModel = info
            encoder = OnnxEncoder.detect(info)
            loadTokenizerFor(stored.name)
            "${info.inputs.size} inputs, ${info.outputs.size} outputs"
        }
    }

    /** Imports the `vocab.txt` that goes with the selected encoder. */
    fun importVocabulary(location: String) = launch("Importing the vocabulary") {
        val stored = importer.importVocabulary(location)
        val selected = selectedOnnx
        if (selected != null) {
            loadTokenizerFor(selected)
        }
        "Imported ${stored.name}"
    }

    fun deleteVocabulary(name: String) = launch("Deleting $name") {
        if (!store.deleteVocab(name)) {
            throw IOException("$name could not be deleted")
        }
        tokenizer = null
        readVocabularyState()
        readModels()
        "Deleted $name"
    }

    /**
     * Finds and parses the vocabulary of [modelName], or leaves the tab without one.
     *
     * A missing vocabulary is not an error here: the model is still usable for the graph inspection
     * the ONNX card already offers, so the state simply says what is missing and Embed stays off.
     */
    private fun loadTokenizerFor(modelName: String) {
        tokenizer = null
        val file = store.vocabFor(modelName)
        if (file == null) {
            vocabularyName = null
            vocabularyStatus = "No vocab.txt beside $modelName, so text cannot be encoded"
            embedding = null
            return
        }
        val loaded = WordPieceVocabulary.load(file)
        tokenizer = WordPieceTokenizer(loaded)
        vocabularyName = file.name
        vocabularyStatus = "${file.name}: ${loaded.size} tokens"
        embedding = null
    }

    private fun readVocabularyState() {
        val selected = selectedOnnx
        vocabularyName = selected?.let { store.vocabFor(it)?.name }
        vocabularyStatus = when {
            selected == null -> "Select an ONNX model first"
            vocabularyName == null -> "No vocab.txt beside $selected, so text cannot be encoded"
            else -> vocabularyStatus
        }
    }

    fun updateEmbeddingInput(text: String) {
        embeddingInput = text
    }

    /** Encodes the text in the tab and publishes what came out. */
    fun embedText() {
        val text = embeddingInput
        if (text.isBlank()) {
            message = "Type something to embed first"
            return
        }
        val name = selectedOnnx
        if (name == null) {
            message = "Select an ONNX encoder first"
            return
        }
        launch("Embedding the text") {
            val result = encodeNow(name, text)
            post { embedding = summarize(name, result) }
            "${result.dimensions} dimensions from ${result.tokens} tokens"
        }
    }

    /**
     * Embeds [text] and blocks the calling thread until it is finished or [timeoutMillis] is up.
     *
     * This is the entry point for the local AI API, and it is shaped like [chatNow] for the same
     * reasons: the work is queued on the one worker that owns the sessions, and [generationGuard]
     * is taken before it is queued so an embedding and a chat turn can never be inside ONNX Runtime
     * and llama.cpp at the same time.
     *
     * @throws AiGenerationBusyException when the model is already generating.
     * @throws AiGenerationTimeoutException when the encoder does not answer in time.
     * @throws AiGenerationUnavailableException when no usable encoder or vocabulary is loaded.
     */
    fun embedNow(text: String, timeoutMillis: Long): FloatArray {
        val name = selectedOnnx
            ?: throw AiGenerationUnavailableException("Select an ONNX encoder before asking for an embedding")
        if (text.isBlank()) {
            throw IllegalArgumentException("The text to embed cannot be empty")
        }
        if (timeoutMillis < 1_000L) {
            throw IllegalArgumentException("The embedding timeout must be at least a second")
        }
        requireGenerationSlot()
        val outcome = EmbeddingOutcome()
        val finished = CountDownLatch(1)
        try {
            executor.execute {
                try {
                    outcome.embedding = encodeNow(name, text).vector
                } catch (failure: Throwable) {
                    outcome.failure = failure
                } finally {
                    releaseGenerationSlot()
                    finished.countDown()
                }
            }
        } catch (rejected: RejectedExecutionException) {
            releaseGenerationSlot()
            throw AiGenerationUnavailableException("The AI tab has been closed", rejected)
        }
        val completed = try {
            finished.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            throw AiGenerationTimeoutException("The encoder did not answer within ${timeoutMillis / 1_000L}s")
        }
        outcome.failure?.let { failure -> throw failure }
        return outcome.embedding ?: FloatArray(0)
    }

    /** The encoding itself. Worker thread only. */
    private fun encodeNow(modelName: String, text: String): OnnxEmbedding {
        val path = onnxPath
            ?: throw AiGenerationUnavailableException("No ONNX session is open")
        val active = tokenizer
            ?: throw AiGenerationUnavailableException(
                "Import the vocab.txt that goes with $modelName before embedding"
            )
        val support = encoder
            ?: throw AiGenerationUnavailableException("$modelName has not been described yet")
        if (!support.supported) {
            throw AiGenerationUnavailableException("${support.reason ?: "This model is not a text encoder"}")
        }
        if (!path.endsWith(File.separator + modelName)) {
            throw AiGenerationUnavailableException("The open ONNX session is not $modelName")
        }
        val batch = try {
            active.encode(text)
        } catch (failure: IllegalArgumentException) {
            throw AiEmbeddingException("The text could not be encoded: ${failure.message}", failure)
        }
        return try {
            onnx.encode(path, batch)
        } catch (failure: OnnxException) {
            throw AiEmbeddingException(failure.message ?: "Encoding failed", failure)
        }
    }

    /** The displayable shape of [result]; the vector itself is not kept. */
    private fun summarize(model: String, result: OnnxEmbedding) = EmbeddingSummary(
        model = model,
        dimensions = result.dimensions,
        tokens = result.tokens,
        norm = OnnxEncoder.norm(result.vector),
        firstValues = result.vector.take(PREVIEW_VALUES)
    )

    fun runOnnx() {
        val info = onnxModel ?: run {
            message = "Select an ONNX model first"
            return
        }
        launch("Running ${info.path.substringAfterLast('/')}") {
            val updated = onnx.runWithZeros(info.path)
            onnxModel = updated
            encoder = OnnxEncoder.detect(updated)
            updated.lastRun ?: "The session produced no output"
        }
    }

    fun closeOnnx() = launch("Closing the ONNX session") {
        closeOnnxSession()
        onnxModel = null
        encoder = null
        tokenizer = null
        embedding = null
        readVocabularyState()
        "ONNX session closed"
    }

    fun clearMessage() {
        message = null
    }

    /**
     * Releases the model, the ONNX sessions and the worker thread.
     *
     * The teardown is queued rather than run here, so it happens on the same worker that owns the
     * native handles. A generation in flight when the tab closes, which is how a socket worker
     * calling [chatNow] looks from here, then finishes first instead of having the model freed
     * underneath it. [shutdown] is graceful for the same reason: the queued teardown still runs.
     */
    fun release() {
        cancelled.set(true)
        runCatching {
            executor.execute {
                runCatching { unloadSession() }
                runCatching { closeOnnxSession() }
                runCatching { tokenizer = null }
            }
        }
        executor.shutdown()
    }

    /**
     * Takes the one generation slot.
     *
     * @throws AiGenerationBusyException when the model is already generating, so the two callers
     * of [generate] can never be inside llama.cpp together.
     */
    private fun requireGenerationSlot() {
        if (!generationGuard.compareAndSet(false, true)) {
            throw AiGenerationBusyException("A reply is already being generated")
        }
    }

    private fun releaseGenerationSlot() {
        generationGuard.set(false)
    }

    /**
     * Runs one generation and reports the whole text streamed so far through [onProgress].
     *
     * The streamed copy is also kept here rather than by the caller, because a runtime that
     * reports no final answer still streamed every token, and the tab should not lose a reply that
     * is already visible.
     *
     * [honourCancellation] is false for an API call: the tab's Stop button is about the tab's own
     * turn, and the reply behind an API request belongs to the client that asked for it, which has
     * the request timeout instead.
     */
    private fun generate(
        activeHandle: Long,
        turns: List<ChatTurn>,
        request: GenerationConfig,
        honourCancellation: Boolean,
        onProgress: (String) -> Unit
    ): String {
        val streamed = StringBuilder()
        val answer = llama.generate(activeHandle, ChatPrompt.format(turns), request) { token ->
            if (honourCancellation && cancelled.get()) {
                throw StopGeneration()
            }
            streamed.append(token)
            onProgress(streamed.toString())
        }
        return answer.ifEmpty { streamed.toString() }
    }

    /** Re-reads the directory on the worker thread, publishes on the UI thread. */
    private fun readModels(): String {
        val listed = store.list()
        post {
            models = listed
            if (selectedGguf != null && listed.none { it.name == selectedGguf }) {
                selectedGguf = null
            }
            if (selectedOnnx != null && listed.none { it.name == selectedOnnx }) {
                selectedOnnx = null
                encoder = null
                tokenizer = null
                embedding = null
                readVocabularyState()
            }
        }
        return "${listed.size} model file(s)"
    }

    /** Native teardown only. Callers own the matching state updates. */
    private fun unloadSession() {
        llama.unload(handle)
        handle = 0L
    }

    private fun closeOnnxSession() {
        val path = onnxPath ?: return
        onnxPath = null
        runCatching { onnx.close(path) }
    }

    /**
     * Runs [block] on the worker thread and reports the outcome. Every state write
     * inside [block] goes through [post].
     */
    private fun launch(label: String, block: () -> String) {
        if (busy) {
            message = "$label is already running"
            return
        }
        busy = true
        busyLabel = label
        message = null
        executor.execute {
            val outcome = try {
                block()
            } catch (_: StopGeneration) {
                CANCELLED
            } catch (busy: AiGenerationBusyException) {
                busy.message ?: "A reply is already being generated"
            } catch (failure: Throwable) {
                if (cancelled.get()) CANCELLED else "Error: ${failure.message ?: failure.javaClass.simpleName}"
            }
            post {
                busy = false
                busyLabel = ""
                if (outcome.isNotEmpty()) {
                    message = outcome
                }
            }
        }
    }

    /** Thrown from the token callback to end a generation early. */
    private class StopGeneration : RuntimeException()

    /** How a queued generation hands its result back to the thread waiting in [chatNow]. */
    private class GenerationOutcome {
        var reply: String? = null
        var failure: Throwable? = null
    }

    /** How a queued embedding hands its result back to the thread waiting in [embedNow]. */
    private class EmbeddingOutcome {
        var embedding: FloatArray? = null
        var failure: Throwable? = null
    }

    companion object {
        private const val CANCELLED = "Stopped"

        /** How much of a vector the tab shows, because all of it does not fit on a phone. */
        private const val PREVIEW_VALUES = 8

        /**
         * Wires the controller against the real platform pieces. Refuses to start when
         * the model directory would sit inside the served web root.
         */
        fun forContext(context: Context): AiController {
            val models = ModelStore.forContext(context)
            val webRoot = WebRoot.ensure(context.applicationContext)
            if (ModelStore.isInside(models.directory(), webRoot)) {
                throw SecurityException("The model directory must live outside the web root")
            }
            val handler = Handler(Looper.getMainLooper())
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            return AiController(
                store = models,
                importer = ModelImporter(models, ContentResolverModelSource(context.applicationContext)),
                downloader = ModelDownloader(models),
                llama = JniLlamaRuntime(),
                onnx = OrtOnnxRuntime(),
                threads = threads,
                post = { action -> handler.post(action) }
            )
        }
    }
}
