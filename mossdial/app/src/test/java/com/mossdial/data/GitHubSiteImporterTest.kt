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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GitHubSiteImporterTest {
    private lateinit var webRoot: File
    private lateinit var temp: File
    private lateinit var store: WebRootFileStore
    private var requested = mutableListOf<String>()

    @Before
    fun setUp() {
        webRoot = Files.createTempDirectory("mossdial-github-root-").toFile()
        temp = Files.createTempDirectory("mossdial-github-tmp-").toFile()
        store = WebRootFileStore(webRoot)
    }

    @After
    fun tearDown() {
        deleteTree(webRoot)
        deleteTree(temp)
    }

    @Test
    fun importsTheArchiveRootWhenTheSiteSitsAtTheTop() {
        val archive = zipOf(
            "mossdial-main/index.html" to "<p>ok</p>".toByteArray(),
            "mossdial-main/style.css" to "body{}".toByteArray()
        )

        val result = importer(archive).import(store, "", "owner/repo", null)

        assertEquals("repo", result.name)
        assertEquals("archive root", result.siteRoot)
        assertEquals(2, result.files)
        assertTrue(store.resolve("repo/index.html")?.isFile == true)
        assertTrue(store.resolve("repo/style.css")?.isFile == true)
        assertEquals(
            listOf("https://codeload.github.com/owner/repo/zip/HEAD"),
            requested.toList()
        )
    }

    @Test
    fun importsOnlyTheDetectedSiteFolder() {
        val archive = zipOf(
            "mossdial-main/README.md" to "readme".toByteArray(),
            "mossdial-main/docs/index.html" to "<p>docs</p>".toByteArray(),
            "mossdial-main/docs/app.js" to "console.log(1)".toByteArray()
        )

        val result = importer(archive).import(store, "", "owner/repo", null)

        assertEquals("docs", result.siteRoot)
        assertEquals("repo", result.path)
        assertEquals(SiteKind.SPA, result.siteKind)
        assertTrue(store.resolve("repo/index.html")?.isFile == true)
        assertTrue(store.resolve("repo/app.js")?.isFile == true)
        assertFalse(store.resolve("repo/README.md")?.exists() == true)
    }

    @Test
    fun carriesTheBuildBasePathSoTheImportedSiteCanBeHostedAtTheRoot() {
        val archive = zipOf(
            "repo-main/index.html" to (
                """<link rel="stylesheet" href="/repo/assets/app.css">""" +
                    """<script src="/repo/assets/app.js"></script>"""
                ).toByteArray(),
            "repo-main/assets/app.css" to "body{}".toByteArray(),
            "repo-main/assets/app.js" to "render()".toByteArray()
        )

        val result = importer(archive).import(store, "", "owner/repo", null)

        assertEquals("repo", result.path)
        assertEquals("repo", result.basePath)
        assertEquals(SiteKind.SPA, result.siteKind)
    }

    @Test
    fun acceptsARepositoryUrlAndUsesTheRepositoryNameForTheFolder() {
        val archive = zipOf("Repo.Name-main/index.html" to "<p>ok</p>".toByteArray())

        val result = importer(archive).import(store, "", "https://github.com/Owner/Repo.Name/", null)

        assertEquals("Repo.Name", result.name)
        assertTrue(store.resolve("Repo.Name/index.html")?.isFile == true)
    }

    @Test
    fun keepsABranchFromTheRepositoryUrl() {
        val archive = zipOf("repo-feature-x/index.html" to "<p>ok</p>".toByteArray())

        importer(archive).import(store, "", "https://github.com/owner/repo/tree/feature/x", null)

        assertEquals(
            listOf("https://codeload.github.com/owner/repo/zip/refs/heads/feature/x"),
            requested.toList()
        )
    }

    @Test
    fun refusesWhenTheTargetFolderAlreadyExists() {
        val archive = zipOf("repo-main/index.html" to "<p>ok</p>".toByteArray())
        store.createDirectory("", "repo")

        assertThrows(FileAlreadyExistsException::class.java) {
            importer(archive).import(store, "", "owner/repo", null)
        }
    }

    @Test
    fun refusesARepositoryThatIsOnlyFrameworkSource() {
        val archive = zipOf(
            "repo-main/index.html" to
                """<div id="root"></div><script type="module" src="/src/main.tsx"></script>""".toByteArray(),
            "repo-main/src/main.tsx" to "createRoot(document.getElementById('root')!)".toByteArray(),
            "repo-main/package.json" to """{"scripts":{"build":"vite build"}}""".toByteArray()
        )

        assertThrows(IOException::class.java) {
            importer(archive).import(store, "", "owner/repo", null)
        }

        assertFalse("a source-only import leaves no folder behind", store.resolve("repo")?.exists() == true)
    }

    @Test
    fun refusesAnArchiveEntryThatTriesToEscape() {
        val archive = zipOf(
            "repo-main/index.html" to "<p>ok</p>".toByteArray(),
            "repo-main/../escape.txt" to "no".toByteArray()
        )

        assertThrows(IOException::class.java) {
            importer(archive).import(store, "", "owner/repo", null)
        }

        assertFalse(store.resolve("escape.txt")?.exists() == true)
    }

    @Test
    fun cleansUpTheTargetFolderWhenTheSiteIsOverTheLimit() {
        val archive = zipOf(
            "repo-main/index.html" to "<p>ok</p>".toByteArray(),
            "repo-main/data.bin" to ByteArray(256) { it.toByte() }
        )

        assertThrows(IOException::class.java) {
            importer(archive, maxExtractedBytes = 128).import(store, "", "owner/repo", null)
        }

        assertFalse(store.resolve("repo")?.exists() == true)
        assertTrue(
            "no temporary archive is left behind",
            temp.listFiles().orEmpty().none { it.name.startsWith("mossdial-github-") }
        )
    }

    @Test
    fun refusesMoreEntriesThanTheLimitBeforeAnythingIsWritten() {
        val archive = zipOf(
            "repo-main/one.html" to ByteArray(1),
            "repo-main/two.html" to ByteArray(1),
            "repo-main/three.html" to ByteArray(1)
        )

        assertThrows(IOException::class.java) {
            importer(archive, maxEntries = 2).import(store, "", "owner/repo", null)
        }

        assertFalse(store.resolve("repo")?.exists() == true)
    }

    @Test
    fun rejectsUrlsThatAreNotGitHub() {
        listOf(
            "https://example.com/owner/repo",
            "http://github.com/owner/repo",
            "owner/repo/extra"
        ).forEach { repository ->
            assertThrows(IllegalArgumentException::class.java) {
                GitHubSiteSpec.parse(repository)
            }
        }
    }

    private fun importer(
        body: ByteArray,
        maxArchiveBytes: Long = GitHubSiteImporter.MAX_ARCHIVE_BYTES,
        maxExtractedBytes: Long = GitHubSiteImporter.MAX_EXTRACTED_BYTES,
        maxEntries: Int = GitHubSiteImporter.MAX_ENTRIES
    ): GitHubSiteImporter = GitHubSiteImporter(
        tempDirectory = temp,
        connectionFactory = { url ->
            requested += url.toString()
            FakeConnection(url, HttpURLConnection.HTTP_OK, body)
        },
        maxArchiveBytes = maxArchiveBytes,
        maxExtractedBytes = maxExtractedBytes,
        maxEntries = maxEntries
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

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: ByteArray
    ) : HttpURLConnection(url) {
        private var connected = false

        override fun connect() {
            connected = true
        }

        override fun getResponseCode(): Int = status

        override fun getContentLengthLong(): Long = body.size.toLong()

        override fun usingProxy(): Boolean = false

        override fun disconnect() {
        }

        override fun getInputStream(): InputStream {
            if (!connected) {
                connect()
            }
            return ByteArrayInputStream(body)
        }
    }
}
