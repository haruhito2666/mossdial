package com.mossdial.data

import com.mossdial.DeviceTestSupport
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web root on a device, in the app's own storage.
 *
 * `WebRoot.ensure` is where the served tree is born: `filesDir/sites/default` with a default page.
 * The JVM suite covers the file store's path policy over a temporary directory, which is the same
 * logic; what it cannot cover is that the directory is created inside the app's private storage
 * with the owner-only permissions and data-extraction rules the platform applies, because those
 * only exist on a device.
 */
class WebRootDeviceTest {
    private val context = DeviceTestSupport.context()
    private val root: File get() = WebRoot.ensure(context)

    @Test
    fun createsTheSiteDirectoryInsideAppPrivateStorage() {
        assertTrue(root.isDirectory)
        assertEquals(File(context.filesDir, "sites/default").canonicalPath, root.canonicalPath)
    }

    @Test
    fun writesADefaultPageThatCanBeServed() {
        val index = File(root, "index.html")

        assertTrue(index.isFile)
        val content = index.readText()
        assertTrue(content.contains("<!doctype html>"))
        assertTrue(content.contains("Mossdial"))
    }

    /**
     * Calling it again, as every app start does, must not disturb what is there.
     *
     * A second call that recreated the directory or rewrote the page would silently discard a
     * user's site, so the identity of the existing file is checked rather than just its presence.
     */
    @Test
    fun leavesAnExistingSiteAlone() {
        val first = WebRoot.ensure(context)
        val marker = File(first, "keep-me.txt").apply { writeText("kept") }

        val second = WebRoot.ensure(context)

        assertTrue("a user file must survive a second call", marker.isFile)
        assertEquals("kept", marker.readText())
        assertTrue(File(second, "index.html").isFile)
    }

    /**
     * The directory is not reachable by other apps, because it is app-private.
     *
     * `filesDir` is what the platform's own storage guarantees rest on. The permissions are
     * readable rather than the point; the assertion is that the path is under app-private storage
     * at all, so nothing here depends on a specific mode a platform may change.
     */
    @Test
    fun theSiteLivesUnderAppPrivateStorageAndNotOnSharedStorage() {
        val rootPath = root.canonicalPath

        assertTrue(rootPath.startsWith(context.filesDir.canonicalPath))
        assertFalse(
            "the web root must not be on external storage",
            rootPath.startsWith(context.getExternalFilesDir(null)?.canonicalPath ?: "\u0000none")
        )
    }

    /**
     * A symlinked web root is refused rather than followed.
     *
     * `WebRoot` documents that a symlink here is a security exception, because following it would
     * let a path outside the served tree be reached through the root itself. The real tree is moved
     * aside for the duration and put back afterwards, so a failure here cannot leave the app
     * without the site it was installed with.
     */
    @Test
    fun refusesASymlinkedSiteDirectory() {
        val site = File(context.filesDir, "sites/default")
        val outside = File(context.filesDir, "outside-${System.nanoTime()}").apply { mkdirs() }
        val backup = File(context.filesDir, "sites-backup-${System.nanoTime()}")
        WebRoot.ensure(context)
        val moved = site.renameTo(backup)
        assertTrue("the real web root must be moved aside for this test", moved)
        try {
            Files.createSymbolicLink(site.toPath(), outside.toPath())
            assertTrue(Files.isSymbolicLink(site.toPath()))

            try {
                WebRoot.ensure(context)
                throw AssertionError("a symlinked web root must be refused")
            } catch (expected: SecurityException) {
                assertTrue(expected.message.orEmpty().isNotEmpty())
            } finally {
                Files.deleteIfExists(site.toPath())
            }
        } finally {
            Files.deleteIfExists(site.toPath())
            if (!backup.renameTo(site)) {
                // The site is unrecoverable here, so recreate it rather than leave nothing to serve.
                WebRoot.ensure(context)
                backup.deleteRecursively()
            }
            outside.deleteRecursively()
        }
        assertTrue("the real web root must be restored", File(site, "index.html").isFile)
    }
}
