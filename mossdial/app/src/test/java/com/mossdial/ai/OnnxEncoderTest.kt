package com.mossdial.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxEncoderTest {

    private fun spec(name: String, shape: LongArray, type: String = "FLOAT") =
        OnnxTensorSpec(name, shape.toList(), type, type == "FLOAT" && shape.all { it > 0 })

    private fun model(
        inputs: List<OnnxTensorSpec>,
        outputs: List<OnnxTensorSpec>,
        path: String = "/models/encoder.onnx"
    ) = OnnxModelInfo(path, inputs, outputs)

    private fun encoder(
        ids: LongArray = longArrayOf(1, -1),
        mask: LongArray = longArrayOf(1, -1),
        types: LongArray? = longArrayOf(1, -1),
        idsType: String = "INT64",
        output: OnnxTensorSpec = spec("last_hidden_state", longArrayOf(1, -1, 384)),
        extraInput: OnnxTensorSpec? = null
    ): OnnxModelInfo = model(
        listOfNotNull(
            spec("input_ids", ids, idsType),
            spec("attention_mask", mask, idsType),
            types?.let { spec("token_type_ids", it, idsType) },
            extraInput
        ),
        listOf(output)
    )

    @Test
    fun `a sentence transformer export is recognised`() {
        val support = OnnxEncoder.detect(encoder())
        assertTrue(support.supported)
        assertNull(support.reason)
        assertEquals("input_ids", support.idsInput)
        assertEquals("attention_mask", support.maskInput)
        assertEquals("token_type_ids", support.typeInput)
        assertEquals("last_hidden_state", support.outputName)
        assertFalse(support.pooledOutput)
        assertEquals(384, support.dimensions)
        assertEquals(0, support.sequenceLength)
    }

    @Test
    fun `an export without token types is still usable`() {
        val support = OnnxEncoder.detect(encoder(types = null))
        assertTrue(support.supported)
        assertNull(support.typeInput)
        assertTrue(support.summary.contains("no token type input"))
    }

    @Test
    fun `a fixed sequence length is reported so the input can be padded to it`() {
        val support = OnnxEncoder.detect(encoder(ids = longArrayOf(1, 512), mask = longArrayOf(1, 512)))
        assertTrue(support.supported)
        assertEquals(512, support.sequenceLength)
    }

    @Test
    fun `int32 token inputs are accepted`() {
        val support = OnnxEncoder.detect(encoder(idsType = "INT32"))
        assertTrue(support.supported)
        assertEquals("INT32", support.idsType)
    }

    @Test
    fun `input names are matched without regard to case`() {
        val support = OnnxEncoder.detect(
            model(
                listOf(
                    spec("Input_IDs", longArrayOf(1, -1), "INT64"),
                    spec("Attention_Mask", longArrayOf(1, -1), "INT64")
                ),
                listOf(spec("last_hidden_state", longArrayOf(1, -1, 384)))
            )
        )
        assertTrue(support.supported)
        assertEquals("Input_IDs", support.idsInput)
    }

    @Test
    fun `a model without an attention mask is refused with a reason`() {
        val support = OnnxEncoder.detect(
            model(
                listOf(spec("input_ids", longArrayOf(1, -1), "INT64")),
                listOf(spec("last_hidden_state", longArrayOf(1, -1, 384)))
            )
        )
        assertFalse(support.supported)
        assertTrue(support.reason!!.contains("attention_mask"))
        assertTrue(support.summary.contains("attention_mask"))
    }

    @Test
    fun `an input this app cannot fill is refused rather than guessed`() {
        val support = OnnxEncoder.detect(
            encoder(extraInput = spec("pixel_values", longArrayOf(1, 3, 224, 224), "FLOAT"))
        )
        assertFalse(support.supported)
        assertTrue(support.reason!!.contains("pixel_values"))
    }

    @Test
    fun `float token inputs are refused`() {
        val support = OnnxEncoder.detect(
            model(
                listOf(
                    spec("input_ids", longArrayOf(1, -1), "FLOAT"),
                    spec("attention_mask", longArrayOf(1, -1), "FLOAT")
                ),
                listOf(spec("last_hidden_state", longArrayOf(1, -1, 384)))
            )
        )
        assertFalse(support.supported)
        assertTrue(support.reason!!.contains("not an integer input"))
    }

    @Test
    fun `inputs that are not a batch and a length are refused`() {
        val support = OnnxEncoder.detect(encoder(ids = longArrayOf(-1), mask = longArrayOf(1, -1)))
        assertFalse(support.supported)
        assertTrue(support.reason!!.contains("[batch, length]"))
    }

    @Test
    fun `a batch larger than one is refused`() {
        val support = OnnxEncoder.detect(encoder(ids = longArrayOf(4, -1), mask = longArrayOf(4, -1)))
        assertFalse(support.supported)
        assertTrue(support.reason!!.contains("batch of 4"))
    }

    @Test
    fun `a model with no float output is refused`() {
        val support = OnnxEncoder.detect(encoder(output = spec("logits", longArrayOf(1, -1, 2), "INT64")))
        assertFalse(support.supported)
        assertTrue(support.reason!!.contains("no float output"))
    }

    @Test
    fun `a two dimensional output is taken as an already pooled vector`() {
        val support = OnnxEncoder.detect(
            encoder(output = spec("pooler_output", longArrayOf(1, 384)))
        )
        assertTrue(support.supported)
        assertTrue(support.pooledOutput)
        assertEquals(384, support.dimensions)
    }

    @Test
    fun `an output of an unusual rank is refused`() {
        val support = OnnxEncoder.detect(
            encoder(output = spec("weird", longArrayOf(1, -1, -1, -1)))
        )
        assertFalse(support.supported)
        assertTrue(support.reason!!.contains("unsupported shape"))
    }

    @Test
    fun `a dynamic output width is reported as unknown`() {
        val support = OnnxEncoder.detect(
            encoder(output = spec("last_hidden_state", longArrayOf(1, -1, -1)))
        )
        assertTrue(support.supported)
        assertEquals(0, support.dimensions)
        assertTrue(support.summary.contains("unknown width"))
    }

    @Test
    fun `a preferred output name is chosen over another float output`() {
        val support = OnnxEncoder.detect(
            model(
                listOf(
                    spec("input_ids", longArrayOf(1, -1), "INT64"),
                    spec("attention_mask", longArrayOf(1, -1), "INT64")
                ),
                listOf(
                    spec("other", longArrayOf(1, -1, 384)),
                    spec("sentence_embedding", longArrayOf(1, 384))
                )
            )
        )
        assertTrue(support.supported)
        assertEquals("sentence_embedding", support.outputName)
    }

    @Test
    fun `padding a batch to a fixed length keeps the ids and zeroes the mask`() {
        val batch = OnnxTokenBatch.of(intArrayOf(2, 4, 3), intArrayOf(1, 1, 1), intArrayOf(0, 0, 0))
        val padded = OnnxEncoder.pad(batch, 6)
        assertEquals(6, padded.length)
        assertArrayEquals(longArrayOf(2, 4, 3, 0, 0, 0), padded.inputIds)
        assertArrayEquals(longArrayOf(1, 1, 1, 0, 0, 0), padded.attentionMask)
        assertArrayEquals(longArrayOf(0, 0, 0, 0, 0, 0), padded.tokenTypeIds)
    }

    @Test
    fun `padding to the length it already has changes nothing`() {
        val batch = OnnxTokenBatch.of(intArrayOf(2, 3), intArrayOf(1, 1), intArrayOf(0, 0))
        assertEquals(batch, OnnxEncoder.pad(batch, 2))
        assertEquals(batch, OnnxEncoder.pad(batch, 0))
    }

    @Test
    fun `a sequence longer than a fixed length is refused`() {
        val batch = OnnxTokenBatch.of(intArrayOf(2, 3, 4), intArrayOf(1, 1, 1), intArrayOf(0, 0, 0))
        assertThrows(IllegalArgumentException::class.java) { OnnxEncoder.pad(batch, 2) }
    }

    @Test
    fun `pooling averages the attended rows and normalises the result`() {
        val values = floatArrayOf(3f, 4f, 100f, 100f)
        val reduced = OnnxEncoder.reduce(values, 2, 2, longArrayOf(1, 0), alreadyPooled = false)
        assertEquals(0.6f, reduced[0], 1e-6f)
        assertEquals(0.8f, reduced[1], 1e-6f)
        assertEquals(1.0, OnnxEncoder.norm(reduced), 1e-6)
    }

    @Test
    fun `every attended row counts once`() {
        val reduced = OnnxEncoder.reduce(
            floatArrayOf(3f, 4f, 0f, 0f), 2, 2, longArrayOf(1, 1), alreadyPooled = false
        )
        assertEquals(0.6f, reduced[0], 1e-6f)
        assertEquals(0.8f, reduced[1], 1e-6f)
    }

    @Test
    fun `an already pooled output is only normalised`() {
        val reduced = OnnxEncoder.reduce(
            floatArrayOf(3f, 4f), 1, 2, longArrayOf(1), alreadyPooled = true
        )
        assertEquals(0.6f, reduced[0], 1e-6f)
        assertEquals(0.8f, reduced[1], 1e-6f)
    }

    @Test
    fun `pooling copies the vector rather than normalising the caller's array`() {
        val values = floatArrayOf(3f, 4f, 100f, 100f)
        OnnxEncoder.reduce(values, 2, 2, longArrayOf(1, 0), alreadyPooled = false)
        assertEquals(100f, values[2], 0f)
    }

    @Test
    fun `a mask that selects nothing is refused rather than divided by`() {
        assertThrows(IllegalArgumentException::class.java) {
            OnnxEncoder.reduce(floatArrayOf(1f, 1f, 1f, 1f), 2, 2, longArrayOf(0, 0), alreadyPooled = false)
        }
    }

    @Test
    fun `an output smaller than its declared shape is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            OnnxEncoder.reduce(floatArrayOf(1f), 2, 2, longArrayOf(1, 1), alreadyPooled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnnxEncoder.reduce(floatArrayOf(1f), 0, 2, longArrayOf(1), alreadyPooled = false)
        }
    }

    @Test
    fun `a mask shorter than the output is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            OnnxEncoder.reduce(floatArrayOf(1f, 1f, 1f, 1f), 2, 2, longArrayOf(1), alreadyPooled = false)
        }
    }

    @Test
    fun `a zero vector is left alone instead of becoming not a number`() {
        val zeros = floatArrayOf(0f, 0f, 0f)
        assertArrayEquals(zeros, OnnxEncoder.l2Normalize(zeros), 0f)
        val normalized = OnnxEncoder.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, normalized[0], 1e-6f)
        assertEquals(0.8f, normalized[1], 1e-6f)
        assertEquals(1.0, OnnxEncoder.norm(normalized), 1e-6)
    }

    @Test
    fun `an embedding carries its own shape and compares by value`() {
        val first = OnnxEmbedding(floatArrayOf(1f, 0f), 2, 4, false)
        val second = OnnxEmbedding(floatArrayOf(1f, 0f), 2, 4, false)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(first == second.copy(tokens = 5))
        assertFalse(first == second.copy(vector = floatArrayOf(0f, 1f)))
    }

    @Test
    fun `an embedding whose vector does not match its width is refused`() {
        assertThrows(IllegalArgumentException::class.java) { OnnxEmbedding(floatArrayOf(1f, 2f), 3, 4, false) }
        assertThrows(IllegalArgumentException::class.java) { OnnxEmbedding(floatArrayOf(), 0, 4, false) }
    }
}
