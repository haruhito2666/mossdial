package com.mossdial.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SiteArchiveImporterTest {
    private lateinit var webRoot: File
    private lateinit var temp: File
    private lateinit var store: WebRootFileStore

    @Before
    fun setUp() {
        webRoot = Files.createTempDirectory("mossdial-zip-root-").toFile()
        temp = Files.createTempDirectory("mossdial-zip-tmp-").toFile()
        store = WebRootFileStore(webRoot)
    }

    @After
    fun tearDown() {
        deleteTree(webRoot)
        deleteTree(temp)
    }

    @Test
    fun importsAWrappedDistFolder() {
        val archive = zipOf(
            "dist/index.html" to """<script src="/assets/app.js"></script>""".toByteArray(),
            "dist/assets/app.js" to "render()".toByteArray()
        )

        val result = importer().import(store, "", "my-site.zip", ByteArrayInputStream(archive))

        assertEquals("my-site", result.name)
        assertEquals("my-site", result.path)
        assertEquals("", result.basePath)
        assertEquals(SiteKind.SPA, result.siteKind)
        assertTrue(store.resolve("my-site/index.html")?.isFile == true)
        assertTrue(store.resolve("my-site/assets/app.js")?.isFile == true)
    }

    @Test
    fun importsAFlatArchive() {
        val archive = zipOf(
            "index.html" to "<div id=app></div>".toByteArray(),
            "assets/app.css" to "body{}".toByteArray()
        )

        val result = importer().import(store, "", "site.zip", ByteArrayInputStream(archive))

        assertEquals("site", result.path)
        assertEquals(SiteKind.CUSTOM, result.siteKind)
        assertTrue(store.resolve("site/index.html")?.isFile == true)
        assertTrue(store.resolve("site/assets/app.css")?.isFile == true)
    }

    @Test
    fun refusesAZipThatContainsOnlySource() {
        val archive = zipOf(
            "index.html" to
                """<div id="root"></div><script type="module" src="/src/main.tsx"></script>""".toByteArray(),
            "src/main.tsx" to "createRoot(document.getElementById('root')!)".toByteArray()
        )

        assertThrows(IOException::class.java) {
            importer().import(store, "", "source.zip", ByteArrayInputStream(archive))
        }

        assertFalse(store.resolve("source")?.exists() == true)
    }

    @Test
    fun refusesAnEntryThatTriesToEscape() {
        val archive = zipOf(
            "site/index.html" to "<div>ok</div>".toByteArray(),
            "site/../escape.txt" to "no".toByteArray()
        )

        assertThrows(IOException::class.java) {
            importer().import(store, "", "safe.zip", ByteArrayInputStream(archive))
        }

        assertFalse(store.resolve("escape.txt")?.exists() == true)
    }

    @Test
    fun refusesWhenTheTargetFolderAlreadyExists() {
        store.createDirectory("", "site")
        val archive = zipOf("site/index.html" to "<div>ok</div>".toByteArray())

        assertThrows(FileAlreadyExistsException::class.java) {
            importer().import(store, "", "site.zip", ByteArrayInputStream(archive))
        }
    }

    private fun importer(): SiteArchiveImporter = SiteArchiveImporter(
        tempDirectory = temp,
        maxArchiveBytes = SiteArchiveImporter.MAX_ARCHIVE_BYTES,
        maxExtractedBytes = SiteArchiveImporter.MAX_EXTRACTED_BYTES,
        maxEntries = SiteArchiveImporter.MAX_ENTRIES
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun deleteTree(directory: File) {
        if (!directory.exists()) return
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }
}
