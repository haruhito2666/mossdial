package com.mossdial.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteInspectorTest {

    @Test
    fun `detects a GitHub Pages base path and checks the assets behind it`() {
        val root = Files.createTempDirectory("mossdial-inspect-base").toFile()
        try {
            write(
                root,
                "index.html",
                """
                <!doctype html>
                <link rel="stylesheet" href="/repo/_next/static/app.css">
                <script src="/repo/assets/app.js"></script>
                <img src="./logo.png">
                """.trimIndent()
            )
            write(root, "_next/static/app.css", "body{}")
            write(root, "assets/app.js", "render()")
            write(root, "logo.png", "png")

            val inspection = SiteInspector.inspect(root)

            assertEquals("index.html", inspection.indexFile)
            assertEquals("repo", inspection.basePath)
            assertTrue(inspection.hasIndex)
            assertFalse(inspection.indexIsEmpty)
            assertTrue(inspection.missingAssets.toString(), inspection.missingAssets.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reports an asset the page asks for but the folder does not contain`() {
        val root = Files.createTempDirectory("mossdial-inspect-missing").toFile()
        try {
            write(
                root,
                "index.html",
                """<script src="/assets/app.js"></script><link href="/assets/missing.css" rel="stylesheet">"""
            )
            write(root, "assets/app.js", "render()")

            val inspection = SiteInspector.inspect(root)

            assertEquals("", inspection.basePath)
            assertEquals(listOf("/assets/missing.css"), inspection.missingAssets)
            assertFalse(inspection.isHealthy)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an index without a file is reported as missing`() {
        val root = Files.createTempDirectory("mossdial-inspect-no-index").toFile()
        try {
            write(root, "app.js", "render()")

            val inspection = SiteInspector.inspect(root)

            assertFalse(inspection.hasIndex)
            assertEquals(null, inspection.indexFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an empty index is distinguished from a missing one`() {
        val root = Files.createTempDirectory("mossdial-inspect-empty").toFile()
        try {
            write(root, "index.html", "")

            val inspection = SiteInspector.inspect(root)

            assertTrue(inspection.hasIndex)
            assertTrue(inspection.indexIsEmpty)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an htm index is accepted and external references are ignored`() {
        val root = Files.createTempDirectory("mossdial-inspect-ext").toFile()
        try {
            write(
                root,
                "index.htm",
                """
                <link href="https://fonts.example/site.css" rel="stylesheet">
                <img src="data:image/png;base64,AAAA">
                <a href="mailto:hi@example.com">mail</a>
                <a href="#top">top</a>
                """.trimIndent()
            )

            val inspection = SiteInspector.inspect(root)

            assertTrue(inspection.hasIndex)
            assertEquals("index.htm", inspection.indexFile)
            assertTrue(inspection.missingAssets.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a single reference under a known asset folder still reveals the base path`() {
        val root = Files.createTempDirectory("mossdial-inspect-single").toFile()
        try {
            write(
                root,
                "index.html",
                """<script src="/repo/_next/static/app.js"></script>"""
            )
            write(root, "_next/static/app.js", "render()")

            val inspection = SiteInspector.inspect(root)

            assertEquals("repo", inspection.basePath)
            assertTrue(inspection.missingAssets.toString(), inspection.missingAssets.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a base tag is enough to identify the prefix`() {
        val root = Files.createTempDirectory("mossdial-inspect-base-tag").toFile()
        try {
            write(root, "index.html", """<base href="/repo/"><div id=app></div>""")

            assertEquals("repo", SiteInspector.inspect(root).basePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a page that loads source instead of a build is flagged`() {
        val root = Files.createTempDirectory("mossdial-inspect-source").toFile()
        try {
            write(root, "index.html", """<script type="module" src="/src/main.tsx"></script>""")
            write(root, "src/main.tsx", "createRoot(document.body)")

            val inspection = SiteInspector.inspect(root)

            assertTrue(inspection.looksUnbuilt)
            assertTrue(inspection.missingAssets.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `detects the preset from the output tree`() {
        val next = Files.createTempDirectory("mossdial-inspect-next").toFile()
        val static = Files.createTempDirectory("mossdial-inspect-static").toFile()
        val spa = Files.createTempDirectory("mossdial-inspect-spa").toFile()
        val custom = Files.createTempDirectory("mossdial-inspect-custom").toFile()
        try {
            write(next, "_next/app.js", "render()")
            write(next, "index.html", "<div id=app></div>")

            write(static, "index.html", "<div>home</div>")
            write(static, "about/index.html", "<div>about</div>")

            write(spa, "index.html", "<div id=app></div>")
            write(spa, "assets/app.js", "render()")

            write(custom, "index.html", "<div>hello</div>")

            assertEquals(SiteKind.NEXT_EXPORT, SiteInspector.detectKind(next))
            assertEquals(SiteKind.STATIC_EXPORT, SiteInspector.detectKind(static))
            assertEquals(SiteKind.SPA, SiteInspector.detectKind(spa))
            assertEquals(SiteKind.CUSTOM, SiteInspector.detectKind(custom))
        } finally {
            next.deleteRecursively()
            static.deleteRecursively()
            spa.deleteRecursively()
            custom.deleteRecursively()
        }
    }

    private fun write(root: File, path: String, content: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
