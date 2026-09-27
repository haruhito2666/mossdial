package com.mossdial.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class ModelImporterTest {
    private lateinit var base: File
    private lateinit var store: ModelStore
    private lateinit var importer: ModelImporter
    private val source = FakeSource()

    private val gguf = "GGUF".toByteArray() + ByteArray(12)
    private val vocabulary = "[UNK]\n[CLS]\n[SEP]\nhello\nworld\n".toByteArray()

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mossdial-import-").toFile()
        store = ModelStore(File(base, "models"))
        importer = ModelImporter(store, source)
    }

    @After
    fun tearDown() {
        deleteTreeForTest(base)
    }

    @Test
    fun importsThroughTheSourceAndListsTheModel() {
        source.documents["content://models/1"] = FakeDocument("tiny.gguf", gguf)

        val stored = importer.import("content://models/1")

        assertEquals("tiny.gguf", stored.name)
        assertEquals(ModelKind.GGUF, stored.kind)
        assertEquals(16L, stored.sizeBytes)
        assertTrue(File(base, "models/tiny.gguf").isFile)
        assertEquals(listOf("tiny.gguf"), store.list().map { it.name })
        assertEquals(listOf("content://models/1"), source.opened)
    }

    @Test
    fun keepsACollidingImportUnderANewName() {
        source.documents["content://models/1"] = FakeDocument("tiny.gguf", gguf)
        source.documents["content://models/2"] = FakeDocument("tiny.gguf", gguf)

        val first = importer.import("content://models/1")
        val second = importer.import("content://models/2")

        assertEquals("tiny.gguf", first.name)
        assertEquals("tiny-2.gguf", second.name)
        assertEquals(2, store.list().size)
    }

    @Test
    fun stripsDirectoryPartsFromTheName() {
        source.documents["content://models/1"] = FakeDocument("../../escape.gguf", gguf)

        val stored = importer.import("content://models/1")

        assertEquals("escape.gguf", stored.name)
        assertEquals(File(base, "models").canonicalFile, stored.file.canonicalFile.parentFile)
    }

    @Test
    fun rejectsNamesThatAreNotModels() {
        source.documents["content://models/1"] = FakeDocument("notes.txt", ByteArray(4))

        assertThrows(ModelImporter.ImportException::class.java) {
            importer.import("content://models/1")
        }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun rejectsAFileWithoutAGgufMagicHeader() {
        source.documents["content://models/1"] = FakeDocument("fake.gguf", "nope".toByteArray())

        val failure = assertThrows(ModelImporter.ImportException::class.java) {
            importer.import("content://models/1")
        }

        assertTrue(failure.message!!.contains("not a GGUF file"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun acceptsAnOnnxFileWithoutLookingAtItsContents() {
        source.documents["content://models/1"] = FakeDocument("model.onnx", ByteArray(24) { it.toByte() })

        val stored = importer.import("content://models/1")

        assertEquals(ModelKind.ONNX, stored.kind)
        assertEquals(24L, stored.sizeBytes)
    }

    @Test
    fun rejectsAnEmptyDocument() {
        source.documents["content://models/1"] = FakeDocument("empty.gguf", ByteArray(0))

        assertThrows(ModelImporter.ImportException::class.java) {
            importer.import("content://models/1")
        }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun leavesNoPartialFileWhenTheStreamFails() {
        source.documents["content://models/1"] = FakeDocument("broken.gguf", gguf, failAfter = 8)

        assertThrows(IOException::class.java) { importer.import("content://models/1") }

        assertTrue(store.list().isEmpty())
        assertNull(File(base, "models/broken.gguf.part").takeIf { it.exists() })
    }

    @Test
    fun rejectsADocumentWithoutAName() {
        source.documents["content://models/1"] = null

        assertThrows(ModelImporter.ImportException::class.java) {
            importer.import("content://models/1")
        }
    }

    @Test
    fun doesNotOverwriteAnExistingTemporaryFile() {
        store.ensureDirectory()
        File(base, "models/tiny.gguf.part").writeText("stale")
        source.documents["content://models/1"] = FakeDocument("tiny.gguf", gguf)

        val stored = importer.import("content://models/1")

        assertEquals("tiny-2.gguf", stored.name)
        assertFalse(store.list().any { it.name.endsWith(".part") })
    }

    @Test
    fun importsAVocabularyAndOffersItToTheMatchingModel() {
        File(base, "models").mkdirs()
        File(base, "models/encoder.onnx").writeBytes(byteArrayOf(1))
        source.documents["content://vocab/1"] = FakeDocument("vocab.txt", vocabulary)

        val stored = importer.importVocabulary("content://vocab/1")

        assertEquals(ModelStore.SHARED_VOCAB, stored.name)
        assertEquals(listOf("vocab.txt"), store.listVocab())
        assertEquals("vocab.txt", store.vocabFor("encoder.onnx")?.name)
        assertEquals(listOf("encoder.onnx"), store.list().map { it.name })
    }

    @Test
    fun keepsACollidingVocabularyUnderANewName() {
        source.documents["content://vocab/1"] = FakeDocument("vocab.txt", vocabulary)
        source.documents["content://vocab/2"] = FakeDocument("vocab.txt", vocabulary)

        importer.importVocabulary("content://vocab/1")
        val second = importer.importVocabulary("content://vocab/2")

        assertEquals("vocab-2.txt", second.name)
        assertEquals(listOf("vocab-2.txt", "vocab.txt"), store.listVocab())
    }

    @Test
    fun refusesADocumentThatIsNotAVocabulary() {
        source.documents["content://vocab/1"] = FakeDocument("notes.txt", "just some notes".toByteArray())
        source.documents["content://vocab/2"] = FakeDocument(
            "words.txt",
            "[UNK]\n[CLS]\nhello".toByteArray()
        )
        source.documents["content://vocab/3"] = FakeDocument(
            "binary.txt",
            "[UNK]\n[CLS]\n[SEP]\n".toByteArray() + byteArrayOf(0)
        )

        assertThrows(ModelImporter.ImportException::class.java) {
            importer.importVocabulary("content://vocab/1")
        }
        assertThrows(ModelImporter.ImportException::class.java) {
            importer.importVocabulary("content://vocab/2")
        }
        assertThrows(ModelImporter.ImportException::class.java) {
            importer.importVocabulary("content://vocab/3")
        }
        assertTrue(store.listVocab().isEmpty())
    }

    @Test
    fun refusesAVocabularyThatIsNotATextFile() {
        source.documents["content://vocab/1"] = FakeDocument("model.gguf", vocabulary)

        assertThrows(ModelImporter.ImportException::class.java) {
            importer.importVocabulary("content://vocab/1")
        }
    }

    @Test
    fun refusesAnEmptyVocabulary() {
        source.documents["content://vocab/1"] = FakeDocument("vocab.txt", ByteArray(0))

        assertThrows(ModelImporter.ImportException::class.java) {
            importer.importVocabulary("content://vocab/1")
        }
        assertTrue(store.listVocab().isEmpty())
    }

    @Test
    fun acceptsAVocabularyWithCarriageReturns() {
        source.documents["content://vocab/1"] = FakeDocument("vocab.txt", "[UNK]\r\n[CLS]\r\n[SEP]\r\nhello\r\n".toByteArray())

        val stored = importer.importVocabulary("content://vocab/1")

        assertEquals(ModelStore.SHARED_VOCAB, stored.name)
    }

    @Test
    fun leavesNoPartialVocabularyWhenTheStreamFails() {
        source.documents["content://vocab/1"] = FakeDocument("vocab.txt", vocabulary, failAfter = 6)

        assertThrows(IOException::class.java) { importer.importVocabulary("content://vocab/1") }

        assertTrue(store.listVocab().isEmpty())
        assertNull(File(base, "models/vocab.txt.part").takeIf { it.exists() })
    }

    private class FakeDocument(val name: String, private val bytes: ByteArray, private val failAfter: Int = -1) {
        fun open(): InputStream = object : ByteArrayInputStream(bytes) {
            private var read = 0

            override fun read(): Int {
                if (failAfter >= 0 && read >= failAfter) {
                    throw IOException("stream broke")
                }
                return super.read().also { if (it >= 0) read++ }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (failAfter >= 0 && read >= failAfter) {
                    throw IOException("stream broke")
                }
                return super.read(buffer, offset, length).also { if (it > 0) read += it }
            }
        }
    }

    private class FakeSource : ModelImporter.ModelSource {
        val documents = HashMap<String, FakeDocument?>()
        val opened = mutableListOf<String>()

        override fun displayName(location: String): String? = documents[location]?.name

        override fun openStream(location: String): InputStream {
            opened += location
            return documents[location]?.open() ?: throw IOException("unknown document $location")
        }
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
