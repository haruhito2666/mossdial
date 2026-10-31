package com.mossdial.server

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaticFilesTest {

    @Test
    fun `an ordinary index page is served`() {
        val root = Files.createTempDirectory("mossdial-static").toFile()
        try {
            val directory = File(root, "site").apply { mkdirs() }
            val index = File(directory, "index.html").apply { writeText("<p>ok</p>") }

            assertEquals(index.canonicalFile, StaticFiles.resolve(root, "/site"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an htm index page is also a directory index`() {
        val root = Files.createTempDirectory("mossdial-static-htm").toFile()
        try {
            val directory = File(root, "site").apply { mkdirs() }
            val index = File(directory, "index.htm").apply { writeText("<p>ok</p>") }

            assertEquals(index.canonicalFile, StaticFiles.resolve(root, "/site"))
            assertEquals(index.canonicalFile, StaticFiles.resolve(root, "/site/"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an index symlink that leaves the web root is refused`() {
        val parent = Files.createTempDirectory("mossdial-static-link").toFile()
        try {
            val root = File(parent, "root").apply { mkdirs() }
            val directory = File(root, "site").apply { mkdirs() }
            val secret = File(parent, "secret.html").apply { writeText("not for the web") }
            Files.createSymbolicLink(File(directory, "index.html").toPath(), secret.toPath())

            assertNull(StaticFiles.resolve(root, "/site"))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `clean urls find the html that a static exporter emitted`() {
        val root = Files.createTempDirectory("mossdial-clean").toFile()
        try {
            val aboutFile = File(root, "about.html").apply { writeText("<p>about</p>") }
            val teamDirectory = File(root, "team").apply { mkdirs() }
            val teamIndex = File(teamDirectory, "index.html").apply { writeText("<p>team</p>") }

            assertEquals(aboutFile.canonicalFile, StaticFiles.resolveSite(root, "/about", false))
            assertEquals(teamIndex.canonicalFile, StaticFiles.resolveSite(root, "/team", false))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an extensionless route falls back to the app index only when enabled`() {
        val root = Files.createTempDirectory("mossdial-spa").toFile()
        try {
            val index = File(root, "index.html").apply { writeText("<div id=app></div>") }
            File(root, "app.js").writeText("render()")

            assertEquals(index.canonicalFile, StaticFiles.resolveSite(root, "/route", true))
            assertNull(StaticFiles.resolveSite(root, "/route", false))
            assertNull("a missing asset must stay missing", StaticFiles.resolveSite(root, "/missing.js", true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `clean urls find htm pages too`() {
        val root = Files.createTempDirectory("mossdial-clean-htm").toFile()
        try {
            val about = File(root, "about.htm").apply { writeText("<p>about</p>") }
            val team = File(root, "team").apply { mkdirs() }
            val teamIndex = File(team, "index.htm").apply { writeText("<p>team</p>") }

            assertEquals(about.canonicalFile, StaticFiles.resolveSite(root, "/about", false))
            assertEquals(teamIndex.canonicalFile, StaticFiles.resolveSite(root, "/team", false))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a build base path is stripped before a file is resolved`() {
        val root = Files.createTempDirectory("mossdial-base-path").toFile()
        try {
            val index = File(root, "index.html").apply { writeText("<div id=app></div>") }
            val about = File(root, "about.html").apply { writeText("<p>about</p>") }
            val assets = File(root, "assets").apply { mkdirs() }
            val script = File(assets, "app.js").apply { writeText("render()") }

            assertEquals(index.canonicalFile, StaticFiles.resolveSite(root, "/repo/", true, "repo"))
            assertEquals(about.canonicalFile, StaticFiles.resolveSite(root, "/repo/about", true, "repo"))
            assertEquals(script.canonicalFile, StaticFiles.resolveSite(root, "/repo/assets/app.js", true, "repo"))
            assertEquals(index.canonicalFile, StaticFiles.resolveSite(root, "/repo/dashboard", true, "repo"))
            assertNull(StaticFiles.resolveSite(root, "/repo/missing.js", true, "repo"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a path that only looks like the base path is left alone`() {
        val root = Files.createTempDirectory("mossdial-base-path-prefix").toFile()
        try {
            val repository = File(root, "repository.html").apply { writeText("<p>repository</p>") }

            assertEquals(
                repository.canonicalFile,
                StaticFiles.resolveSite(root, "/repository", false, "repo")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `base path stripping leaves query strings and near matches alone`() {
        assertEquals("/", StaticFiles.stripBasePath("/repo", "repo"))
        assertEquals("/", StaticFiles.stripBasePath("/repo/", "repo"))
        assertEquals("/?x=1", StaticFiles.stripBasePath("/repo?x=1", "repo"))
        assertEquals("/assets/app.js", StaticFiles.stripBasePath("/repo/assets/app.js", "repo"))
        assertEquals("/repository", StaticFiles.stripBasePath("/repository", "repo"))
        assertEquals("/repo", StaticFiles.stripBasePath("/repo", "other"))
    }

    @Test
    fun `modern assets carry the types browsers require`() {
        val root = Files.createTempDirectory("mossdial-mime").toFile()
        try {
            assertEquals("application/wasm", StaticFiles.contentType(File(root, "module.wasm")))
            assertEquals("text/javascript; charset=utf-8", StaticFiles.contentType(File(root, "module.mjs")))
            assertEquals("text/javascript; charset=utf-8", StaticFiles.contentType(File(root, "module.cjs")))
            assertEquals("font/woff2", StaticFiles.contentType(File(root, "font.woff2")))
            assertEquals("application/manifest+json", StaticFiles.contentType(File(root, "site.webmanifest")))
        } finally {
            root.deleteRecursively()
        }
    }
}
