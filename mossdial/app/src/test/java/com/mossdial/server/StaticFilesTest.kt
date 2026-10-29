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
}
