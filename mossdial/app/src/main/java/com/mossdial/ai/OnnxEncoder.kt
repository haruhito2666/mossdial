package com.mossdial.ai

/**
 * What a described ONNX model looks like to the embedding path, and why it cannot be used when it
 * cannot.
 *
 * This is a value type over [OnnxModelInfo] rather than a session call, so the decision can be made
 * and tested without a runtime: the AI tab uses it to explain what is missing before a request is
 * ever sent, and [OrtOnnxRuntime.encode] re-checks it against the live session before running.
 *
 * [sequenceLength] is the static sequence length the graph was exported with, or 0 when the axis is
 * dynamic. Many exports fix it at 512, and a request of a different length then has to be padded to
 * it rather than refused.
 */
data class OnnxEncoderSupport(
    val supported: Boolean,
    val reason: String?,
    val idsInput: String,
    val maskInput: String,
    val typeInput: String?,
    val idsType: String,
    val maskType: String,
    val typeType: String?,
    val outputName: String,
    val pooledOutput: Boolean,
    val dimensions: Int,
    val sequenceLength: Int
) {
    /** One line for the tab, and the wording of the refusal when [supported] is false. */
    val summary: String
        get() = if (supported) {
            val width = if (dimensions > 0) "$dimensions dimensions" else "unknown width"
            val length = if (sequenceLength > 0) ", fixed length $sequenceLength" else ""
            buildString {
                append("Text encoder: $outputName, $width$length")
                if (typeInput == null) append(", no token type input") else append(", with token types")
            }
        } else {
            reason ?: "This ONNX model is not a supported text encoder"
        }
}

/**
 * The ONNX half of on-device text embeddings: deciding whether a graph can be driven as a
 * BERT-shaped encoder, and turning its output into one sentence vector.
 *
 * The supported shape is the one every `sentence-transformers` text encoder exports to: three
 * integer inputs (`input_ids`, `attention_mask`, and optionally `token_type_ids`) and a float
 * output. Nothing here is family specific, because the tensor names, the dtypes and the output rank
 * are the whole of the contract, and a graph outside it is refused with a reason rather than fed
 * guessed tensors.
 *
 * The reduction is mean pooling over the attention mask followed by L2 normalisation, which is what
 * `sentence-transformers` does for a model with no pooling layer of its own. The mask is used rather
 * than the token count alone so that a padded position cannot move the vector.
 */
object OnnxEncoder {

    const val IDS_INPUT = "input_ids"
    const val MASK_INPUT = "attention_mask"
    const val TYPE_INPUT = "token_type_ids"

    /** The float outputs checked in order; the first that exists is used. */
    private val PREFERRED_OUTPUTS = listOf("last_hidden_state", "sentence_embedding", "pooler_output", "output")

    private val INTEGER_TYPES = setOf("INT64", "INT32")

    fun detect(info: OnnxModelInfo): OnnxEncoderSupport = try {
        detectOrThrow(info)
    } catch (refusal: UnsupportedEncoderException) {
        unsupported(refusal.message ?: "This ONNX model is not a supported text encoder")
    }

    private fun detectOrThrow(info: OnnxModelInfo): OnnxEncoderSupport {
        val byName = info.inputs.associateBy { it.name.lowercase() }
        val ids = byName[IDS_INPUT] ?: throw UnsupportedEncoderException("It has no $IDS_INPUT input")
        val mask = byName[MASK_INPUT] ?: throw UnsupportedEncoderException("It has no $MASK_INPUT input")
        val types = byName[TYPE_INPUT]
        val known = setOfNotNull(IDS_INPUT, MASK_INPUT, TYPE_INPUT)
        val extra = info.inputs.map { it.name }.filterNot { it.lowercase() in known }
        if (extra.isNotEmpty()) {
            throw UnsupportedEncoderException(
                "It needs inputs this app cannot fill: ${extra.joinToString(", ")}"
            )
        }
        for (input in listOfNotNull(ids, mask, types)) {
            if (input.type !in INTEGER_TYPES) {
                throw UnsupportedEncoderException("Input ${input.name} is ${input.type}, not an integer input")
            }
            if (input.shape.size != 2) {
                throw UnsupportedEncoderException("Input ${input.name} is not a [batch, length] tensor")
            }
            if (input.fixedBatch !in BATCH_SIZES) {
                throw UnsupportedEncoderException("Input ${input.name} expects a batch of ${input.fixedBatch}")
            }
        }
        val length = staticLengthOf(ids) ?: staticLengthOf(mask) ?: 0
        val output = info.outputs.firstOrNull { it.name.lowercase() in PREFERRED_OUTPUTS && it.type == "FLOAT" }
            ?: info.outputs.firstOrNull { it.type == "FLOAT" }
            ?: throw UnsupportedEncoderException("It has no float output to pool")
        val rank = output.shape.size
        val pooled = when (rank) {
            3 -> false
            2 -> true
            else -> throw UnsupportedEncoderException("Output ${output.name} has an unsupported shape")
        }
        if (output.fixedBatch !in BATCH_SIZES) {
            throw UnsupportedEncoderException("Output ${output.name} expects a batch of ${output.fixedBatch}")
        }
        return OnnxEncoderSupport(
            supported = true,
            reason = null,
            idsInput = ids.name,
            maskInput = mask.name,
            typeInput = types?.name,
            idsType = ids.type,
            maskType = mask.type,
            typeType = types?.type,
            outputName = output.name,
            pooledOutput = pooled,
            dimensions = staticWidthOf(output),
            sequenceLength = length
        )
    }

    private fun unsupported(reason: String) = OnnxEncoderSupport(
        supported = false,
        reason = reason,
        idsInput = "",
        maskInput = "",
        typeInput = null,
        idsType = "",
        maskType = "",
        typeType = null,
        outputName = "",
        pooledOutput = false,
        dimensions = 0,
        sequenceLength = 0
    )

    /** The static sequence length of a rank two `[batch, length]` input, or 0 when it is dynamic. */
    private fun staticLengthOf(input: OnnxTensorSpec): Int? = input.fixedWidth.takeIf { it > 0 }

    private fun staticWidthOf(output: OnnxTensorSpec): Int = output.fixedWidth

    /**
     * Pads [batch] to [length] with a zero id and a zero mask, or refuses a batch that is already
     * longer. A model exported with a fixed sequence length is fed that length, so the tensor shape
     * always matches the graph.
     */
    fun pad(batch: OnnxTokenBatch, length: Int): OnnxTokenBatch {
        if (length <= 0 || batch.length == length) return batch
        if (batch.length > length) {
            throw IllegalArgumentException("A sequence of ${batch.length} does not fit a fixed length of $length")
        }
        val ids = LongArray(length)
        val mask = LongArray(length)
        val types = LongArray(length)
        batch.inputIds.copyInto(ids)
        batch.attentionMask.copyInto(mask)
        batch.tokenTypeIds.copyInto(types)
        return OnnxTokenBatch(ids, mask, types)
    }

    /**
     * Averages the rows of [rows] x [columns] selected by [mask] and scales the result to unit
     * length, which is the sentence vector a `sentence-transformers` model is compared with.
     *
     * [values] is the flat output of the graph in row major order. A zero length mask row count
     * would mean nothing to average, so it is refused rather than divided by.
     */
    fun reduce(values: FloatArray, rows: Int, columns: Int, mask: LongArray, alreadyPooled: Boolean): FloatArray {
        require(rows > 0 && columns > 0) { "A hidden state needs a positive row count and width" }
        require(values.size >= rows * columns) {
            "The output holds ${values.size} values, not the declared ${rows * columns}"
        }
        if (alreadyPooled) {
            return l2Normalize(values.copyOf(columns))
        }
        require(mask.size >= rows) { "The mask is shorter than the output" }
        val pooled = FloatArray(columns)
        var counted = 0
        for (row in 0 until rows) {
            if (mask[row] == 0L) continue
            val base = row * columns
            for (column in 0 until columns) {
                pooled[column] += values[base + column]
            }
            counted++
        }
        if (counted == 0) {
            throw IllegalArgumentException("The attention mask selects no token to average")
        }
        for (column in 0 until columns) {
            pooled[column] /= counted
        }
        return l2Normalize(pooled)
    }

    /** Divides by the Euclidean norm, leaving a zero vector alone rather than producing NaN. */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) {
            sum += (value.toDouble() * value.toDouble())
        }
        val norm = Math.sqrt(sum)
        if (norm <= 0.0 || !norm.isFinite()) {
            return vector.copyOf()
        }
        val out = FloatArray(vector.size)
        for (index in vector.indices) {
            out[index] = (vector[index] / norm).toFloat()
        }
        return out
    }

    /** The norm of a vector, for the tab's display and for tests. */
    fun norm(vector: FloatArray): Double {
        var sum = 0.0
        for (value in vector) {
            sum += (value.toDouble() * value.toDouble())
        }
        return Math.sqrt(sum)
    }

    private val BATCH_SIZES = setOf(0, 1)

    /** Raised inside detection, turned into a [OnnxEncoderSupport] that is not supported. */
    private class UnsupportedEncoderException(message: String) : IllegalArgumentException(message)
}

/** One pooled sentence vector and what it came from. */
data class OnnxEmbedding(
    val vector: FloatArray,
    val dimensions: Int,
    val tokens: Int,
    val pooledOutput: Boolean
) {
    init {
        require(vector.size == dimensions) { "A $dimensions wide vector was given ${vector.size} values" }
        require(dimensions > 0) { "An embedding needs at least one dimension" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is OnnxEmbedding &&
            dimensions == other.dimensions &&
            tokens == other.tokens &&
            pooledOutput == other.pooledOutput &&
            vector.contentEquals(other.vector))

    override fun hashCode(): Int =
        ((vector.contentHashCode() * 31 + dimensions) * 31 + tokens) * 31 + pooledOutput.hashCode()
}
