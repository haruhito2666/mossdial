package com.mossdial.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.ValueInfo
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * One tensor the model expects or produces.
 *
 * [shape] is the declared shape with a negative number for a dynamic axis, which is how ONNX
 * Runtime reports one. [shapeText] is that shape for display.
 */
data class OnnxTensorSpec(
    val name: String,
    val shape: List<Long>,
    val type: String,
    val runnable: Boolean
) {
    val shapeText: String get() = shape.joinToString(", ", "[", "]")

    /** The batch the tensor is fixed to, or 0 when the axis is dynamic. */
    val fixedBatch: Int get() = if (shape.size >= 1 && shape[0] > 0) shape[0].toInt() else 0

    /** The width the tensor is fixed to, or 0 when the axis is dynamic. */
    val fixedWidth: Int get() = if (shape.isNotEmpty() && shape.last() > 0) shape.last().toInt() else 0

    companion object {
        /** The shape of a tensor that is not a tensor at all. */
        fun unknown() = listOf(0L)
    }
}

/** What the ONNX Runtime could tell us about a loaded model. */
data class OnnxModelInfo(
    val path: String,
    val inputs: List<OnnxTensorSpec>,
    val outputs: List<OnnxTensorSpec>,
    val lastRun: String? = null
) {
    val isRunnable: Boolean get() = inputs.isNotEmpty() && inputs.all { it.runnable }
}

/**
 * ONNX Runtime, isolated behind an interface for the same reason as [LlamaRuntime].
 *
 * The interface is intentionally narrow: open a session, describe it, run it with
 * zero filled inputs, close it. The app does not claim to be a general ONNX tool.
 */
interface OnnxRuntime {

    fun isAvailable(): Boolean

    fun unavailableReason(): String?

    fun version(): String

    /** Opens a session and returns its description. [close] must be called for every open. */
    fun describe(path: String): OnnxModelInfo

    /**
     * Runs the session with zeros for every input and returns one line per output.
     * Only supported when every input has a fully static float shape.
     */
    fun runWithZeros(path: String): OnnxModelInfo

    /**
     * Runs the session as a text encoder and returns one pooled, unit length sentence vector.
     *
     * [batch] must already hold the ids, mask and token types of the encoded text. The graph is
     * checked against [OnnxEncoder] first, and a model that is not a BERT shaped encoder is
     * refused with the reason rather than run with guessed tensors.
     */
    fun encode(path: String, batch: OnnxTokenBatch): OnnxEmbedding

    fun close(path: String)
}

/** Raised for every ONNX Runtime failure. */
class OnnxException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * [OnnxRuntime] on top of the official `com.microsoft.onnxruntime:onnxruntime-android`
 * AAR. Every failure mode, including the native library missing on a host JVM, is
 * reported as [OnnxException] instead of crashing.
 */
class OrtOnnxRuntime : OnnxRuntime {

    private val environment: OrtEnvironment? = try {
        OrtEnvironment.getEnvironment()
    } catch (_: Throwable) {
        null
    }

    private val sessions = HashMap<String, OrtSession>()

    override fun isAvailable(): Boolean = environment != null

    override fun unavailableReason(): String? =
        if (environment != null) {
            null
        } else {
            "The ONNX Runtime native library could not be loaded"
        }

    override fun version(): String {
        val env = requireEnvironment()
        return guarded("Reading the ONNX Runtime version failed") { env.version }
    }

    @Synchronized
    override fun describe(path: String): OnnxModelInfo = infoOf(path, sessionOf(path))

    @Synchronized
    override fun runWithZeros(path: String): OnnxModelInfo {
        val env = requireEnvironment()
        val session = sessionOf(path)

        val inputs = session.inputInfo.map { (name, node) ->
            val tensor = node.info as? TensorInfo
                ?: throw OnnxException("Input $name is not a tensor")
            if (tensor.type != OnnxJavaType.FLOAT) {
                throw OnnxException("Input $name is ${tensor.type}, only FLOAT is supported")
            }
            if (tensor.shape.any { it < 0 }) {
                throw OnnxException("Input $name has a dynamic shape ${tensor.shape.toList()}")
            }
            name to tensor.shape
        }.toMap()

        val results = ArrayList<String>(inputs.size)
        guarded("Running $path failed") {
            val tensors = inputs.map { (name, shape) ->
                val count = shape.fold(1L) { total, dimension -> total * dimension }.toInt()
                val buffer = FloatBuffer.allocate(count)
                repeat(count) { buffer.put(0f) }
                buffer.rewind()
                name to OnnxTensor.createTensor(env, buffer, shape)
            }
            try {
                session.run(tensors.toMap()).use { output ->
                    for (entry in output) {
                        results += "${entry.key} ${describe(entry.value)}"
                    }
                }
            } finally {
                tensors.forEach { it.second.close() }
            }
        }
        return infoOf(path, session).copy(lastRun = results.joinToString("\n"))
    }

    @Synchronized
    override fun close(path: String) {
        sessions.remove(path)?.close()
    }

    @Synchronized
    override fun encode(path: String, batch: OnnxTokenBatch): OnnxEmbedding {
        val env = requireEnvironment()
        val session = sessionOf(path)
        val support = OnnxEncoder.detect(infoOf(path, session))
        if (!support.supported) {
            throw OnnxException("${path.substringAfterLast('/')} is not a usable text encoder: ${support.reason}")
        }
        val input = OnnxEncoder.pad(batch, support.sequenceLength)
        val columns = outputWidth(support)
        val rows = if (support.pooledOutput) 1 else input.length
        val values = FloatArray(rows * columns)
        val tensors = buildList {
            add(idTensor(env, support.idsInput, support.idsType, input.inputIds))
            add(idTensor(env, support.maskInput, support.maskType, input.attentionMask))
            if (support.typeInput != null) {
                add(idTensor(env, support.typeInput, support.typeType!!, input.tokenTypeIds))
            }
        }
        try {
            val named = LinkedHashMap<String, OnnxTensor>(tensors.size)
            tensors.forEachIndexed { index, tensor -> named[inputNameFor(support, index)] = tensor }
            guarded("Encoding with $path failed") {
                session.run(named, setOf(support.outputName)).use { result ->
                    val tensor = result[0] as? OnnxTensor
                        ?: throw OnnxException("The encoder produced no ${support.outputName} output")
                    val buffer = tensor.floatBuffer
                    buffer.get(values)
                }
            }
        } finally {
            tensors.forEach { it.close() }
        }
        return OnnxEmbedding(
            vector = OnnxEncoder.reduce(
                values = values,
                rows = rows,
                columns = columns,
                mask = input.attentionMask,
                alreadyPooled = support.pooledOutput
            ),
            dimensions = columns,
            tokens = batch.length,
            pooledOutput = support.pooledOutput
        )
    }

    private fun inputNameFor(support: OnnxEncoderSupport, index: Int): String = when (index) {
        0 -> support.idsInput
        1 -> support.maskInput
        else -> support.typeInput ?: support.maskInput
    }

    private fun outputWidth(support: OnnxEncoderSupport): Int {
        if (support.dimensions > 0) return support.dimensions
        throw OnnxException("Output ${support.outputName} has a dynamic width, which is not supported")
    }

    /** An INT64 or INT32 tensor of shape [1, length], built from the encoded ids. */
    private fun idTensor(env: OrtEnvironment, name: String, type: String, ids: LongArray): OnnxTensor {
        val shape = longArrayOf(1L, ids.size.toLong())
        return when (type) {
            "INT32" -> {
                val buffer = IntBuffer.allocate(ids.size)
                ids.forEach { buffer.put(it.toInt()) }
                buffer.rewind()
                OnnxTensor.createTensor(env, buffer, shape)
            }
            else -> {
                val buffer = LongBuffer.allocate(ids.size)
                buffer.put(ids)
                buffer.rewind()
                OnnxTensor.createTensor(env, buffer, shape)
            }
        }
    }

    @Synchronized
    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private fun infoOf(path: String, session: OrtSession): OnnxModelInfo = OnnxModelInfo(
        path = path,
        inputs = session.inputInfo.map { (name, node) -> specOf(name, node.info) },
        outputs = session.outputInfo.map { (name, node) -> specOf(name, node.info) }
    )

    private fun describe(value: OnnxValue): String {
        val tensor = value.info as? TensorInfo
            ?: return value.type.toString()
        return "${tensor.type} shape=${tensor.shape.toList()}"
    }

    private fun specOf(name: String, info: ValueInfo): OnnxTensorSpec {
        val tensor = info as? TensorInfo
        return OnnxTensorSpec(
            name = name,
            shape = tensor?.shape?.toList() ?: OnnxTensorSpec.unknown(),
            type = tensor?.type?.toString() ?: "non-tensor",
            runnable = tensor != null && tensor.type == OnnxJavaType.FLOAT &&
                tensor.shape.all { it > 0 }
        )
    }

    /** The one open session for [path], created on first use. */
    private fun sessionOf(path: String): OrtSession = sessions.getOrPut(path) {
        guarded("Opening $path failed") {
            val options = OrtSession.SessionOptions()
            try {
                requireEnvironment().createSession(path, options)
            } finally {
                options.close()
            }
        }
    }

    private fun requireEnvironment(): OrtEnvironment = environment
        ?: throw OnnxException(unavailableReason() ?: "ONNX Runtime is unavailable")

    private fun <T> guarded(context: String, block: () -> T): T = try {
        block()
    } catch (failure: OnnxException) {
        throw failure
    } catch (failure: Throwable) {
        val message = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
        throw OnnxException("$context: $message", failure)
    }
}
