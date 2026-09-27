package com.mossdial.ai

import java.io.IOException

/**
 * GGUF inference, isolated behind an interface so the controller and the UI can be
 * tested without the native library.
 */
interface LlamaRuntime {

    /** Human readable runtime status, for example the llama.cpp version and the ggml devices. */
    fun systemInfo(): String

    /** True when the native library could be loaded at all. */
    fun isAvailable(): Boolean

    /** Why the runtime is unavailable, or null when it is available. */
    fun unavailableReason(): String?

    /** Loads a GGUF file and returns an opaque handle for the other calls. */
    fun load(path: String, config: GenerationConfig): Long

    fun describe(handle: Long): String

    fun unload(handle: Long)

    /**
     * Generates a completion for [prompt]. [onToken] is called from the calling
     * thread for every decoded token. Returns the full completion.
     */
    fun generate(handle: Long, prompt: String, config: GenerationConfig, onToken: (String) -> Unit): String
}

/** Raised for every GGUF failure so callers never see a raw JNI or file error. */
class LlamaException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * [LlamaRuntime] on top of [LlamaBridge]. All failures, including a missing native
 * library, become [LlamaException].
 */
class JniLlamaRuntime : LlamaRuntime {

    override fun isAvailable(): Boolean = LlamaBridge.isLoaded

    override fun unavailableReason(): String? = when {
        LlamaBridge.isLoaded -> null
        else -> "libmossdial_ai.so could not be loaded: " +
            (LlamaBridge.loadFailureMessage() ?: "unknown error")
    }

    override fun systemInfo(): String {
        if (!LlamaBridge.isLoaded) {
            throw LlamaException(unavailableReason() ?: "llama.cpp is unavailable")
        }
        return guarded("Reading the runtime status failed") { LlamaBridge.nativeSystemInfo() }
    }

    override fun load(path: String, config: GenerationConfig): Long {
        requireRuntime()
        return guarded("Loading $path failed") {
            LlamaBridge.nativeLoadModel(
                path,
                config.contextSize,
                config.threads,
                config.batchSize
            )
        }
    }

    override fun describe(handle: Long): String {
        requireRuntime()
        return guarded("Reading the model description failed") { LlamaBridge.nativeModelInfo(handle) }
    }

    override fun unload(handle: Long) {
        if (!LlamaBridge.isLoaded || handle == 0L) {
            return
        }
        LlamaBridge.nativeUnloadModel(handle)
    }

    override fun generate(
        handle: Long,
        prompt: String,
        config: GenerationConfig,
        onToken: (String) -> Unit
    ): String {
        requireRuntime()
        val sink = TokenSink(onToken)
        return guarded("Generation failed") {
            LlamaBridge.nativeGenerate(
                handle,
                prompt,
                config.maxTokens,
                config.temperature,
                config.topK,
                config.topP,
                config.seed,
                config.stopStrings.toTypedArray(),
                sink
            )
        }
    }

    private fun requireRuntime() {
        if (!LlamaBridge.isLoaded) {
            throw LlamaException(unavailableReason() ?: "llama.cpp is unavailable")
        }
    }

    private fun <T> guarded(context: String, block: () -> T): T = try {
        block()
    } catch (failure: LlamaException) {
        throw failure
    } catch (failure: Throwable) {
        throw LlamaException("$context: ${failure.message ?: failure.javaClass.simpleName}", failure)
    }

    /**
     * The native side looks up `onToken(String)` by name, so this deliberately is a
     * named method rather than a lambda.
     */
    private class TokenSink(private val sink: (String) -> Unit) {
        fun onToken(token: String) = sink.invoke(token)
    }
}
