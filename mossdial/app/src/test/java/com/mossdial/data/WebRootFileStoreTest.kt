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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class WebRootFileStoreTest {
    private lateinit var root: File
    private var outside: File? = null

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mossdial-file-store-").toFile()
    }

    @After
    fun tearDown() {
        deleteTreeForTest(root)
        outside?.let(::deleteTreeForTest)
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        val store = WebRootFileStore(root)

        assertNull(store.resolve("../outside.txt"))
        assertNull(store.resolve("site/../../outside.txt"))
        assertNull(store.resolve("/outside.txt"))
        assertNull(store.resolve("site\\outside.txt"))
        assertNull(store.resolve("site/./outside.txt"))
        assertEquals(root.canonicalFile, store.resolve("")?.canonicalFile)
    }

    @Test
    fun rejectsSymlinkEscapes() {
        val outsideDirectory = Files.createTempDirectory("mossdial-outside-").toFile()
        outside = outsideDirectory
        val secret = File(outsideDirectory, "secret.txt").apply { writeText("secret") }
        Files.createSymbolicLink(File(root, "escape").toPath(), outsideDirectory.toPath())
        val store = WebRootFileStore(root)

        assertNull(store.resolve("escape"))
        assertNull(store.resolve("escape/secret.txt"))
        assertTrue(store.list("").none { it.name == "escape" })
        assertThrows(SecurityException::class.java) {
            store.createTextFile("", "escape", "replacement")
        }
        assertEquals("secret", secret.readText())
    }

    @Test
    fun createsListsRenamesImportsAndDeletesFiles() {
        val store = WebRootFileStore(root)
        val index = store.createTextFile("", "index.html", "<h1>Mossdial</h1>")
        val assets = store.createDirectory("", "assets")
        val script = store.createTextFile(assets.relativePath, "app.js", "console.log(1)")
        val data = store.importFile(
            assets.relativePath,
            "data.json",
            ByteArrayInputStream("{\"ok\":true}".toByteArray(StandardCharsets.UTF_8)),
            "application/json"
        )

        assertEquals("text/html; charset=utf-8", index.type)
        assertEquals("assets/app.js", script.relativePath)
        assertEquals("application/json", data.type)
        assertEquals(listOf("app.js", "data.json"), store.list(assets.relativePath).map { it.name })

        val renamed = store.rename(script.relativePath, "main.js")
        assertEquals("assets/main.js", renamed.relativePath)
        assertTrue(File(root, "assets/main.js").isFile)
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            store.createTextFile("", "index.html")
        }

        store.delete(assets.relativePath)
        assertFalse(File(root, "assets").exists())
        assertTrue(File(root, "index.html").isFile)
        store.delete(index.relativePath)
        assertFalse(File(root, "index.html").exists())
    }

    @Test
    fun refusesUnsafeNames() {
        val store = WebRootFileStore(root)

        assertThrows(IllegalArgumentException::class.java) {
            store.createTextFile("", "../escape.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.rename(store.createTextFile("", "safe.txt").relativePath, "..")
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
