package com.mossdial.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native library is never available to a JVM unit test, so both runtime
 * wrappers have to degrade into a reported error instead of a crash.
 */
class RuntimeAvailabilityTest {

    @Test
    fun llamaBridgeReportsAMissingLibrary() {
        assertFalse("the JNI library must not load in a JVM unit test", LlamaBridge.isLoaded)
        assertNotNull(LlamaBridge.loadFailureMessage())
    }

    @Test
    fun llamaRuntimeTurnsAMissingLibraryIntoAnError() {
        val runtime = JniLlamaRuntime()

        assertFalse(runtime.isAvailable())
        val reason = runtime.unavailableReason()
        assertNotNull(reason)
        assertTrue(reason!!.contains("libmossdial_ai.so"))
        assertThrows(LlamaException::class.java) { runtime.systemInfo() }
        assertThrows(LlamaException::class.java) { runtime.load("/does/not/exist.gguf", GenerationConfig()) }
        assertThrows(LlamaException::class.java) { runtime.describe(1L) }
        assertThrows(LlamaException::class.java) { runtime.generate(1L, "hi", GenerationConfig()) {} }
    }

    @Test
    fun unloadingIsSafeWithoutALibrary() {
        JniLlamaRuntime().unload(42L)
    }

    @Test
    fun onnxRuntimeTurnsAMissingLibraryIntoAnError() {
        val runtime = OrtOnnxRuntime()

        assertFalse(runtime.isAvailable())
        assertNotNull(runtime.unavailableReason())
        assertThrows(OnnxException::class.java) { runtime.version() }
        assertThrows(OnnxException::class.java) { runtime.describe("/does/not/exist.onnx") }
        assertThrows(OnnxException::class.java) { runtime.runWithZeros("/does/not/exist.onnx") }
    }

    @Test
    fun onnxClosingAnUnopenedPathIsSafe() {
        OrtOnnxRuntime().close("/does/not/exist.onnx")
    }

    @Test
    fun anOnnxModelIsOnlyRunnableWithStaticFloatInputs() {
        val info = OnnxModelInfo(
            path = "/models/a.onnx",
            inputs = listOf(OnnxTensorSpec("input", listOf(1L, 3L), "FLOAT", true)),
            outputs = listOf(OnnxTensorSpec("output", listOf(1L, 3L), "FLOAT", true))
        )

        assertTrue(info.isRunnable)
        assertFalse(info.copy(inputs = listOf(OnnxTensorSpec("input", listOf(-1L, 3L), "FLOAT", false))).isRunnable)
        assertFalse(info.copy(inputs = emptyList()).isRunnable)
        assertNull(info.lastRun)
    }
}
