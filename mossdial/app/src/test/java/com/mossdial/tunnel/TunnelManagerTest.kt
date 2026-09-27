package com.mossdial.tunnel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Comparator

/**
 * Exercises the real launch path against a stand-in executable.
 *
 * The stand-in is a small shell script, so these tests cover argument delivery, the bounded
 * capture, token redaction and start/stop without needing an Android device or a tunnel binary.
 */
class TunnelManagerTest {
    private lateinit var directory: File
    private var created = 0

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("mossdial-tunnel-").toFile()
    }

    @After
    fun tearDown() {
        TunnelManager.stop()
        if (TunnelManager.snapshot().isActive) {
            awaitStatus { it.phase == TunnelPhase.Stopped }
        }
        if (!directory.exists()) return
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    @Test
    fun refusesToStartWhenThereIsNoExecutableAtThePath() {
        val missing = File(directory, "absent").absolutePath

        val status = TunnelManager.start(TunnelConfig(missing, TOKEN))

        assertEquals(TunnelPhase.Failed, status.phase)
        assertEquals("No executable at that path", status.detail)
    }

    @Test
    fun refusesToStartAFileThatIsNotExecutable() {
        val plain = File(directory, "plain-file").apply { writeText("not a program") }

        val status = TunnelManager.start(TunnelConfig(plain.absolutePath, TOKEN))

        assertEquals(TunnelPhase.Failed, status.phase)
        assertEquals("That file is not executable", status.detail)
    }

    @Test
    fun launchesNothingForAnInvalidConfiguration() {
        var rejected = false
        try {
            TunnelManager.start(TunnelConfig("relative/path", TOKEN))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue("the configuration must be rejected before launch", rejected)
        assertEquals(TunnelPhase.Stopped, TunnelManager.snapshot().phase)
    }

    @Test
    fun runsTheTunnelAndStopsItOnRequest() {
        val executable = standIn("printf 'edge connection established\\n' >&2\nexec sleep 300")

        val started = TunnelManager.start(TunnelConfig(executable.absolutePath, TOKEN))

        assertEquals(TunnelPhase.Running, awaitStatus { it.phase == TunnelPhase.Running }.phase)
        assertTrue(started.isActive)
        assertTrue(TunnelManager.isRunning())

        TunnelManager.stop()

        assertEquals(TunnelPhase.Stopped, awaitStatus { it.phase == TunnelPhase.Stopped }.phase)
        assertFalse(TunnelManager.isRunning())
    }

    @Test
    fun neverStartsASecondProcessWhileOneIsAlive() {
        val counter = File(directory, "launches")
        val executable = standIn("printf 'x\\n' >> \"${counter.absolutePath}\"\nexec sleep 300")
        val config = TunnelConfig(executable.absolutePath, TOKEN)

        TunnelManager.start(config)
        assertEquals(TunnelPhase.Running, awaitStatus { it.phase == TunnelPhase.Running }.phase)
        waitForFile(counter)
        TunnelManager.start(config)
        Thread.sleep(300)

        assertEquals(1, counter.readLines().size)
    }

    @Test
    fun reportsTheExitCodeAndRedactsTheTokenFromProcessOutput() {
        // A token full of shell metacharacters proves two things at once: it reached the process
        // as a single argument, and nothing interpreted it on the way.
        val token = "tokenprefix1234;\$(id)--suffix"
        val executable = standIn("printf 'connecting argc=%s token=%s\\n' \"\$#\" \"\$5\" >&2\nexit 1")
        TunnelManager.start(TunnelConfig(executable.absolutePath, token))

        val finished = awaitStatus { it.phase == TunnelPhase.Failed }

        assertEquals("Tunnel exited with code 1", finished.detail)
        assertTrue(finished.diagnostic, finished.diagnostic.contains("argc=7"))
        assertTrue(finished.diagnostic, finished.diagnostic.contains("token=***"))
        assertFalse(finished.diagnostic, finished.diagnostic.contains(token))
        assertFalse(finished.diagnostic, finished.diagnostic.contains("uid="))
    }

    @Test
    fun doesNotCarryOutputOverIntoTheNextProcess() {
        val noisy = standIn("printf 'stale line\\n' >&2\nexit 2")
        TunnelManager.start(TunnelConfig(noisy.absolutePath, TOKEN))
        val first = awaitStatus { it.phase == TunnelPhase.Failed }
        assertEquals("stale line", first.diagnostic)

        val quiet = standIn("exit 3")
        TunnelManager.start(TunnelConfig(quiet.absolutePath, TOKEN))
        val second = awaitStatus { it.phase == TunnelPhase.Failed }

        assertEquals("Tunnel exited with code 3", second.detail)
        assertEquals("", second.diagnostic)
    }

    @Test
    fun boundsTheDiagnosticItReports() {
        val noise = TunnelStatusCapture.MAX_CAPTURED_CHARS * 4
        val executable = standIn("i=0; while [ \$i -lt $noise ]; do printf 'x'; i=\$((i+1)); done >&2\nexit 1")
        TunnelManager.start(TunnelConfig(executable.absolutePath, TOKEN))

        val finished = awaitStatus { it.phase == TunnelPhase.Failed }

        assertEquals(TunnelStatusCapture.MAX_CAPTURED_CHARS, finished.diagnostic.length)
        assertTrue(finished.diagnostic, finished.diagnostic.endsWith(TunnelStatusCapture.CLIP_MARKER))
    }

    private fun standIn(body: String): File {
        val script = File(directory, "cloudflared-${created++}")
        script.writeText("#!/bin/sh\n$body\n")
        check(script.setExecutable(true)) { "unable to mark the stand-in executable" }
        return script
    }

    private fun waitForFile(file: File) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !file.isFile) {
            Thread.sleep(25)
        }
    }

    private fun awaitStatus(
        timeoutMillis: Long = 10_000,
        predicate: (TunnelStatus) -> Boolean
    ): TunnelStatus {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val status = TunnelManager.snapshot()
            if (predicate(status)) return status
            Thread.sleep(25)
        }
        return TunnelManager.snapshot()
    }

    private companion object {
        const val TOKEN = "eyJhIjoiTESTTOKENVALUE0123456789abcdefghij"
    }
}
