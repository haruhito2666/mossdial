package com.mossdial.aiapi

import com.mossdial.ai.AiController
import com.mossdial.ai.AiControllerRegistry
import com.mossdial.ai.AiGenerationBusyException
import com.mossdial.ai.AiGenerationTimeoutException
import com.mossdial.ai.AiGenerationUnavailableException
import com.mossdial.ai.ChatTurn
import com.mossdial.ai.LlamaException
import java.io.IOException

/**
 * What the HTTP layer needs from the model, with no HTTP in it.
 *
 * The listener is then testable end to end with a fake backend, and the process-wide AI tab is only
 * one implementation of this seam. Failures are typed because the status a client should see
 * depends on why generation did not happen, not on which runtime raised it.
 */
interface AiApiBackend {

    /** A human readable runtime state, shown by `GET /health`. */
    fun runtimeStatus(): String

    /** The model that is loaded right now, or null when nothing is loaded. */
    fun activeModel(): String?

    /** The ONNX encoder that could answer an embedding request, or null when there is none. */
    fun activeEmbeddingModel(): String?

    /** Every model the API can name, with the loaded one marked. */
    fun models(): List<AiApiModel>

    /** Generates a reply, blocking the caller until it is finished or [timeoutMillis] is up. */
    fun generate(turns: List<ChatTurn>, maxTokens: Int, timeoutMillis: Long): String

    /**
     * Embeds [text], blocking the caller until it is finished or [timeoutMillis] is up.
     *
     * The same failure types as [generate] mean the same status, so a client cannot tell a busy
     * model from a missing one by the code it gets.
     */
    fun embed(text: String, timeoutMillis: Long): FloatArray
}

/** Why a generation did not produce a reply, and so which status the API answers with. */
sealed class AiApiFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The model, or the tab holding it, is already generating: 409. */
    class Busy(message: String) : AiApiFailure(message)

    /** Nothing can answer yet, for example because no model is loaded: 503. */
    class Unavailable(message: String) : AiApiFailure(message)

    /** Generation started and failed: 500. */
    class Failed(message: String, cause: Throwable? = null) : AiApiFailure(message, cause)

    /** Generation ran past the request's budget: 504. */
    class TimedOut(message: String) : AiApiFailure(message)
}

/**
 * The API backed by the AI tab's own controller.
 *
 * The controller is read through [AiControllerRegistry] on every call rather than captured once, so
 * a request that arrives after the tab is closed gets an honest "no model" answer instead of a
 * reference to a released controller. There is no second model load anywhere on this path: the
 * reply comes from the same loaded handle the AI tab is showing.
 */
class AiControllerApiBackend(
    private val controller: () -> AiController? = { AiControllerRegistry.current() }
) : AiApiBackend {

    override fun runtimeStatus(): String = controller()?.llamaStatus
        ?: "The AI tab has not been opened in this process, so no model is loaded"

    override fun activeModel(): String? = controller()?.loadedModel

    override fun activeEmbeddingModel(): String? = controller()?.takeIf { it.canEmbed }?.selectedOnnx

    override fun models(): List<AiApiModel> {
        val active = controller() ?: return emptyList()
        val loaded = active.loadedModel
        return active.ggufModels.map { AiApiModel(it.name, it.name == loaded) }
    }

    override fun generate(turns: List<ChatTurn>, maxTokens: Int, timeoutMillis: Long): String {
        val active = controller()
            ?: throw AiApiFailure.Unavailable(
                "Open the AI tab and load a GGUF model before calling the local API"
            )
        return try {
            active.chatNow(turns, maxTokens, timeoutMillis)
        } catch (busy: AiGenerationBusyException) {
            throw AiApiFailure.Busy(busy.message ?: "A reply is already being generated")
        } catch (timeout: AiGenerationTimeoutException) {
            throw AiApiFailure.TimedOut(timeout.message ?: "Generation ran out of time")
        } catch (unavailable: AiGenerationUnavailableException) {
            throw AiApiFailure.Unavailable(unavailable.message ?: "The local model cannot answer yet")
        } catch (failure: LlamaException) {
            throw AiApiFailure.Failed(failure.message ?: "Generation failed", failure)
        } catch (failure: IOException) {
            throw AiApiFailure.Failed(failure.message ?: "Generation failed", failure)
        }
    }

    override fun embed(text: String, timeoutMillis: Long): FloatArray {
        val active = controller()
            ?: throw AiApiFailure.Unavailable(
                "Open the AI tab and select an ONNX encoder before calling the local API"
            )
        return try {
            active.embedNow(text, timeoutMillis)
        } catch (busy: AiGenerationBusyException) {
            throw AiApiFailure.Busy(busy.message ?: "The model is already working")
        } catch (timeout: AiGenerationTimeoutException) {
            throw AiApiFailure.TimedOut(timeout.message ?: "Embedding ran out of time")
        } catch (unavailable: AiGenerationUnavailableException) {
            throw AiApiFailure.Unavailable(unavailable.message ?: "The local encoder cannot answer yet")
        } catch (failure: IOException) {
            throw AiApiFailure.Failed(failure.message ?: "Embedding failed", failure)
        }
    }
}
