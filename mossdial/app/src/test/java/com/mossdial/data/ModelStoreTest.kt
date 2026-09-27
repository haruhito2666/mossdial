package com.mossdial.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class ModelStoreTest {
    private lateinit var base: File
    private lateinit var models: File
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mossdial-model-store-").toFile()
        models = File(base, "models")
        store = ModelStore(models)
    }

    @After
    fun tearDown() {
        deleteTreeForTest(base)
    }

    @Test
    fun createsTheDirectoryAndKeepsWeightsOutOfTheWebRoot() {
        val webRoot = File(base, "sites/default")

        val directory = store.ensureDirectory()

        assertTrue(directory.isDirectory)
        assertEquals("models", directory.name)
        assertFalse(
            "model weights must never live inside the served web root",
            ModelStore.isInside(directory, webRoot)
        )
        assertTrue(ModelStore.isInside(webRoot, base))
    }

    @Test
    fun keepsTheNoBackupDirectoryOutsideTheWebRoot() {
        val dataDirectory = File(base, "files")
        val noBackupDirectory = File(base, "no_backup")
        val webRoot = File(dataDirectory, "sites/default")
        val modelsDirectory = File(noBackupDirectory, ModelStore.RELATIVE_PATH)

        assertEquals("models", ModelStore.RELATIVE_PATH)
        assertFalse(ModelStore.isInside(modelsDirectory, webRoot))
        assertFalse(webRoot.exists())
    }

    @Test
    fun listsOnlyModelFilesAndSkipsSymlinks() {
        store.ensureDirectory()
        File(models, "a.gguf").writeBytes(byteArrayOf(1, 2, 3))
        File(models, "b.onnx").writeBytes(byteArrayOf(1))
        File(models, "notes.txt").writeText("ignored")
        File(models, "subdir.gguf").mkdirs()
        val outside = Files.createTempDirectory("mossdial-outside-").toFile()
        val secret = File(outside, "secret.gguf").apply { writeText("secret") }
        Files.createSymbolicLink(File(models, "link.gguf").toPath(), secret.toPath())

        val listed = store.list()

        assertEquals(listOf("a.gguf", "b.onnx"), listed.map { it.name })
        assertEquals(listOf(ModelKind.GGUF, ModelKind.ONNX), listed.map { it.kind })
        assertEquals(3L, listed.first { it.name == "a.gguf" }.sizeBytes)
        assertTrue(listed.none { it.name == "link.gguf" })
        assertEquals("secret", secret.readText())
    }

    @Test
    fun filtersByKind() {
        store.ensureDirectory()
        File(models, "a.gguf").writeBytes(byteArrayOf(1))
        File(models, "b.gguf").writeBytes(byteArrayOf(1))
        File(models, "c.onnx").writeBytes(byteArrayOf(1))

        assertEquals(listOf("c.onnx"), store.list(ModelKind.ONNX).map { it.name })
        assertEquals(listOf("a.gguf", "b.gguf"), store.list(ModelKind.GGUF).map { it.name })
    }

    @Test
    fun refusesToResolveOutsideTheDirectory() {
        store.ensureDirectory()

        assertNull(store.resolve("../escape.gguf"))
        assertNull(store.resolve("/etc/passwd"))
        assertNull(store.resolve("nested/model.gguf"))
        assertNull(store.resolve("missing.gguf"))
        assertNull(store.resolve("a.gguf"))
    }

    @Test
    fun deletesOnlyModelFiles() {
        store.ensureDirectory()
        val model = File(models, "a.gguf").apply { writeBytes(byteArrayOf(1)) }

        assertTrue(store.delete("a.gguf"))
        assertFalse(model.exists())
        assertFalse(store.delete("a.gguf"))
        assertFalse(store.delete("../outside.gguf"))
    }

    @Test
    fun sanitisesNames() {
        assertEquals("model.gguf", ModelStore.sanitizeName("model.gguf"))
        assertEquals("model.gguf", ModelStore.sanitizeName("/tmp/evil/model.gguf"))
        assertEquals("model.gguf", ModelStore.sanitizeName("..\\..\\model.gguf"))
        assertEquals("my_model_1.gguf", ModelStore.sanitizeName("my model:1.gguf"))
        assertNull(ModelStore.sanitizeName(".."))
        assertNull(ModelStore.sanitizeName("   "))
        assertEquals(96, ModelStore.sanitizeName("a".repeat(200) + ".gguf")?.length)
    }

    @Test
    fun onlyAcceptsSafeModelNames() {
        assertTrue(ModelStore.isSafeName("model.gguf"))
        assertTrue(ModelStore.isSafeName("Model-1.Q4_K_M.gguf"))
        assertFalse(ModelStore.isSafeName("model.txt"))
        assertFalse(ModelStore.isSafeName("../model.gguf"))
        assertFalse(ModelStore.isSafeName("dir/model.gguf"))
        assertFalse(ModelStore.isSafeName("model.gguf "))
        assertFalse(ModelStore.isSafeName(""))
    }

    @Test
    fun classifiesExtensionsCaseInsensitively() {
        assertEquals(ModelKind.GGUF, ModelKind.of("a.GGUF"))
        assertEquals(ModelKind.ONNX, ModelKind.of("a.Onnx"))
        assertNull(ModelKind.of("a.bin"))
    }

    @Test
    fun refusesASymbolicModelDirectory() {
        val real = File(base, "real-models").apply { mkdirs() }
        val linked = ModelStore(File(base, "models"))
        Files.createSymbolicLink(linked.directory().toPath(), real.toPath())

        assertThrows(SecurityException::class.java) { linked.ensureDirectory() }
    }

    @Test
    fun findsTheVocabularyBesideItsModelFirst() {
        store.ensureDirectory()
        File(models, "encoder.onnx").writeBytes(byteArrayOf(1))
        File(models, "encoder.txt").writeText("[UNK]")
        File(models, ModelStore.SHARED_VOCAB).writeText("shared")

        assertEquals("encoder.txt", store.vocabFor("encoder.onnx")?.name)
    }

    @Test
    fun fallsBackToTheSharedVocabulary() {
        store.ensureDirectory()
        File(models, "encoder.onnx").writeBytes(byteArrayOf(1))
        File(models, ModelStore.SHARED_VOCAB).writeText("shared")

        assertEquals(ModelStore.SHARED_VOCAB, store.vocabFor("encoder.onnx")?.name)
    }

    @Test
    fun hasNoVocabularyWhenNoneWasImported() {
        store.ensureDirectory()
        File(models, "encoder.onnx").writeBytes(byteArrayOf(1))

        assertNull(store.vocabFor("encoder.onnx"))
        assertNull(store.vocabFor("missing.onnx"))
        assertNull(store.vocabFor("../escape.onnx"))
        assertNull(store.vocabFor("notes.txt"))
    }

    @Test
    fun listsVocabulariesAndNotWeights() {
        store.ensureDirectory()
        File(models, "encoder.onnx").writeBytes(byteArrayOf(1))
        File(models, "b.txt").writeText("b")
        File(models, "a.txt").writeText("a")
        File(models, "vocab.TXT").writeText("upper")
        File(models, "nope.bin").writeText("x")
        val outside = Files.createTempDirectory("mossdial-outside-").toFile()
        val secret = File(outside, "secret.txt").apply { writeText("secret") }
        Files.createSymbolicLink(File(models, "link.txt").toPath(), secret.toPath())

        assertEquals(listOf("a.txt", "b.txt", "vocab.TXT"), store.listVocab())
        assertEquals("secret", secret.readText())
    }

    @Test
    fun onlyResolvesVocabulariesInsideTheDirectory() {
        store.ensureDirectory()
        File(models, "a.txt").writeText("a")

        assertEquals("a.txt", store.resolveVocab("a.txt")?.name)
        assertNull(store.resolveVocab("a.gguf"))
        assertNull(store.resolveVocab("../a.txt"))
        assertNull(store.resolveVocab("missing.txt"))
        assertNull(store.resolveVocab("a.txt "))
    }

    @Test
    fun refusesAnOversizeVocabulary() {
        store.ensureDirectory()
        val file = File(models, "big.txt")
        file.writeBytes(ByteArray((ModelStore.MAX_VOCAB_BYTES + 1).toInt()))

        assertNull(store.resolveVocab("big.txt"))
    }

    @Test
    fun refusesASymbolicVocabulary() {
        store.ensureDirectory()
        val outside = Files.createTempDirectory("mossdial-outside-").toFile()
        val secret = File(outside, "secret.txt").apply { writeText("secret") }
        Files.createSymbolicLink(File(models, "link.txt").toPath(), secret.toPath())

        assertNull(store.resolveVocab("link.txt"))
        assertNull(store.vocabFor("link.onnx"))
        assertEquals("secret", secret.readText())
    }

    @Test
    fun deletesVocabulariesOnly() {
        store.ensureDirectory()
        val vocab = File(models, "a.txt").apply { writeText("a") }
        val model = File(models, "a.gguf").apply { writeBytes(byteArrayOf(1)) }

        assertTrue(store.deleteVocab("a.txt"))
        assertFalse(vocab.exists())
        assertFalse(store.deleteVocab("a.txt"))
        assertFalse(store.deleteVocab("a.gguf"))
        assertTrue(model.exists())
    }

    @Test
    fun onlyAcceptsSafeVocabularyNames() {
        assertTrue(ModelStore.isSafeVocabName("vocab.txt"))
        assertTrue(ModelStore.isSafeVocabName("encoder-1.v2.txt"))
        assertFalse(ModelStore.isSafeVocabName("vocab"))
        assertFalse(ModelStore.isSafeVocabName("../vocab.txt"))
        assertFalse(ModelStore.isSafeVocabName("dir/vocab.txt"))
        assertFalse(ModelStore.isSafeVocabName(""))
        assertFalse(ModelStore.isSafeVocabName("x".repeat(97) + ".txt"))
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
