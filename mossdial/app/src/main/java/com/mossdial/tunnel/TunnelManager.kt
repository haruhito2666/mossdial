package com.mossdial.tunnel

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a user-provided `cloudflared` executable as a child process.
 *
 * The command is an explicit argument vector handed straight to [ProcessBuilder]. No shell is
 * involved at any point, so the token is passed as exactly one argv entry and cannot be
 * re-interpreted.
 *
 * Guarantees:
 *  - at most one tunnel process is alive at a time;
 *  - only bounded, redacted, single-line status is retained, and the token is never logged;
 *  - [stop] escalates from a polite destroy to a forced destroy after a short grace period.
 */
object TunnelManager {
    private const val TAG = "MossdialTunnel"
    private const val STOP_GRACE_MILLIS = 2_000L
    private const val READ_BUFFER_CHARS = 1024

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(TunnelStatus) -> Unit>()

    private val control: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MossdialTunnelControl").apply { isDaemon = true }
    }

    private var process: Process? = null
    private var token: String = ""
    private var outputTail: String = ""
    private var stopRequested = false

    @Volatile
    private var status: TunnelStatus = TunnelStatus.STOPPED

    fun isRunning(): Boolean = status.isActive

    fun snapshot(): TunnelStatus = status

    fun addListener(listener: (TunnelStatus) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (TunnelStatus) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Launches the tunnel described by [config]. Cheap validation happens on the calling
     * thread so a misconfiguration is reported immediately; the launch itself is asynchronous.
     */
    fun start(config: TunnelConfig): TunnelStatus {
        synchronized(lock) {
            if (process != null) return status
            val executable = File(config.executablePath)
            if (!executable.isFile) {
                return publish(TunnelStatus.failed("No executable at that path"))
            }
            if (!executable.canExecute()) {
                return publish(TunnelStatus.failed("That file is not executable"))
            }
            token = config.token
            outputTail = ""
            stopRequested = false
            publish(TunnelStatus(TunnelPhase.Starting, "Starting tunnel"))
            control.execute { launch(config) }
        }
        return status
    }

    fun stop() {
        val running: Process
        synchronized(lock) {
            running = process ?: return
            stopRequested = true
        }
        running.destroy()
        control.execute { forceStop(running) }
    }

    private fun forceStop(target: Process) {
        try {
            if (!target.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.i(TAG, "Tunnel process ignored destroy, forcing termination")
                target.destroyForcibly()
                target.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            target.destroyForcibly()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Tunnel process could not be terminated: ${error.javaClass.simpleName}")
        }
    }

    private fun launch(config: TunnelConfig) {
        val builder = ProcessBuilder(TunnelCommand.command(config))
        builder.redirectErrorStream(false)
        val started = try {
            builder.start()
        } catch (error: IOException) {
            Log.w(TAG, "Tunnel launch failed: ${error.javaClass.simpleName}")
            finish(TunnelStatus.failed("Tunnel executable could not be run"))
            return
        } catch (error: SecurityException) {
            Log.w(TAG, "Tunnel launch denied: ${error.javaClass.simpleName}")
            finish(TunnelStatus.failed("This device does not allow running that executable"))
            return
        }
        synchronized(lock) {
            process = started
        }
        publish(TunnelStatus(TunnelPhase.Running, "Tunnel running"))
        startReader(started.inputStream, "out")
        startReader(started.errorStream, "err")
        startWatcher(started)
    }

    private fun startReader(stream: InputStream, label: String) {
        Thread({
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val buffer = CharArray(READ_BUFFER_CHARS)
            val line = StringBuilder()
            try {
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    var index = 0
                    while (index < read) {
                        val character = buffer[index]
                        index += 1
                        if (character == '\n') {
                            emit(line)
                            line.setLength(0)
                        } else {
                            line.append(character)
                            if (line.length >= TunnelStatusCapture.MAX_LINE_CHARS) {
                                emit(line)
                                line.setLength(0)
                            }
                        }
                    }
                }
                emit(line)
            } catch (_: IOException) {
            } finally {
                runCatching { reader.close() }
            }
        }, "MossdialTunnel-$label").apply {
            isDaemon = true
            start()
        }
    }

    private fun emit(line: StringBuilder) {
        if (line.isEmpty()) return
        synchronized(lock) {
            val appended = TunnelStatusCapture.append(outputTail, line, listOf(token))
            if (appended != null) outputTail = appended
        }
    }

    private fun startWatcher(target: Process) {
        Thread({
            val exited = try {
                target.waitFor()
                true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!exited) return@Thread
            val owned = synchronized(lock) { process === target }
            if (!owned) return@Thread
            val code = try {
                target.exitValue()
            } catch (_: IllegalThreadStateException) {
                null
            }
            finish(exitStatus(code))
        }, "MossdialTunnelWatch").apply {
            isDaemon = true
            start()
        }
    }

    private fun exitStatus(code: Int?): TunnelStatus {
        val stopped = synchronized(lock) { stopRequested }
        if (stopped) return TunnelStatus.STOPPED
        val summary = if (code == null) "Tunnel process ended unexpectedly" else "Tunnel exited with code $code"
        val diagnostic = synchronized(lock) {
            TunnelStatusCapture.line(outputTail, listOf(token))
        }
        Log.i(TAG, "Tunnel process ended, exit=$code")
        return TunnelStatus.failed(summary, diagnostic)
    }

    /** Publishes the terminal state of a process and clears the retained output. */
    private fun finish(final: TunnelStatus) {
        synchronized(lock) {
            if (process == null && final.phase == TunnelPhase.Stopped) return
            process = null
            token = ""
            outputTail = ""
        }
        publish(final)
    }

    private fun publish(next: TunnelStatus): TunnelStatus {
        status = next
        listeners.forEach { listener ->
            runCatching { listener(next) }
        }
        return next
    }
}
