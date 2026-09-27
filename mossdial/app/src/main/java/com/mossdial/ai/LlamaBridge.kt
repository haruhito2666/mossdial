package com.mossdial.ai

/**
 * Direct binding to `libmossdial_ai.so`, the JNI bridge in `src/main/cpp`.
 *
 * The library is loaded once, lazily, and a load failure is recorded instead of
 * thrown so the UI can show why GGUF support is unavailable.
 */
object LlamaBridge {

    private val loadFailure: Throwable? = try {
        System.loadLibrary("mossdial_ai")
        null
    } catch (failure: Throwable) {
        failure
    }

    val isLoaded: Boolean get() = loadFailure == null

    fun loadFailureMessage(): String? = loadFailure?.message ?: loadFailure?.javaClass?.simpleName

    external fun nativeInit()

    external fun nativeSystemInfo(): String

    /** Returns an opaque handle, or 0 after throwing a Java exception. */
    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int, batchSize: Int): Long

    external fun nativeModelInfo(handle: Long): String

    external fun nativeUnloadModel(handle: Long)

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int,
        stopStrings: Array<String>,
        tokenSink: Any
    ): String
}
