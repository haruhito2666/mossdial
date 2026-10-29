package com.mossdial.tunnel

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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

    /** How many times an unexpected exit is retried before the failure is final. */
    private const val MAX_AUTO_RESTARTS = 2
    private const val AUTO_RESTART_DELAY_MILLIS = 3_000L

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(TunnelStatus) -> Unit>()

    private val control: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MossdialTunnelControl").apply { isDaemon = true }
    }
    private val retries: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MossdialTunnelRetry").apply { isDaemon = true }
    }

    private var process: Process? = null
    private var launchPending = false
    private var autoRestartPending = false
    private var autoRestartTask: ScheduledFuture<*>? = null
    private var autoRestarts = 0
    private var lastConfig: TunnelConfig? = null
    private var launchSerial = 0L
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
            if (process != null || launchPending) return status
            // An explicit Start supersedes a queued auto-restart, even when the new configuration
            // turns out to be invalid: the user has taken over.
            if (autoRestartPending) {
                autoRestartPending = false
                autoRestartTask?.cancel(false)
                autoRestartTask = null
                launchSerial += 1
            }
            val executable = File(config.executablePath)
            if (!executable.isFile) {
                return publish(TunnelStatus.failed("No executable at that path"))
            }
            if (!executable.canExecute()) {
                return publish(TunnelStatus.failed("That file is not executable"))
            }
            lastConfig = config
            autoRestarts = 0
            token = config.token
            outputTail = ""
            stopRequested = false
            launchPending = true
            launchSerial += 1
            val serial = launchSerial
            publish(TunnelStatus(TunnelPhase.Starting, "Starting tunnel"))
            control.execute { launch(config, serial) }
        }
        return status
    }

    fun stop() {
        val running: Process?
        synchronized(lock) {
            stopRequested = true
            launchSerial += 1
            val cancelledLaunch = launchPending || autoRestartPending
            launchPending = false
            autoRestartPending = false
            autoRestartTask?.cancel(false)
            autoRestartTask = null
            running = process
            if (running == null) {
                token = ""
                outputTail = ""
                if (cancelledLaunch || status.phase != TunnelPhase.Stopped) {
                    publish(TunnelStatus.STOPPED)
                }
            }
        }
        if (running != null) {
            running.destroy()
            control.execute { forceStop(running) }
        }
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

    private fun launch(config: TunnelConfig, serial: Long) {
        if (!isCurrentLaunch(serial)) {
            cancelLaunchIfCurrent(serial)
            return
        }
        val builder = ProcessBuilder(TunnelCommand.command(config))
        builder.redirectErrorStream(false)
        val started = try {
            builder.start()
        } catch (error: IOException) {
            Log.w(TAG, "Tunnel launch failed: ${error.javaClass.simpleName}")
            failLaunch(serial, "Tunnel executable could not be run")
            return
        } catch (error: SecurityException) {
            Log.w(TAG, "Tunnel launch denied: ${error.javaClass.simpleName}")
            failLaunch(serial, "This device does not allow running that executable")
            return
        }
        // Stop can win the race between the check above and the process actually starting. The
        // process is only adopted while this launch is still the current one; otherwise it is
        // destroyed instead of becoming an untracked child.
        val adopted = synchronized(lock) {
            if (launchSerial != serial || stopRequested) {
                false
            } else {
                process = started
                launchPending = false
                publish(TunnelStatus(TunnelPhase.Running, "Tunnel running"))
                true
            }
        }
        if (!adopted) {
            runCatching { started.destroy() }
            return
        }
        startReader(started.inputStream, "out")
        startReader(started.errorStream, "err")
        startWatcher(started)
    }

    private fun isCurrentLaunch(serial: Long): Boolean = synchronized(lock) {
        launchSerial == serial && launchPending && !stopRequested
    }

    /** Clears the pending marker only if this launch is still the one being waited for. */
    private fun cancelLaunchIfCurrent(serial: Long) {
        synchronized(lock) {
            if (launchSerial == serial) launchPending = false
        }
    }

    private fun failLaunch(serial: Long, detail: String) {
        synchronized(lock) {
            if (launchSerial != serial || !launchPending) return
            launchPending = false
            process = null
            token = ""
            outputTail = ""
            stopRequested = false
            publish(TunnelStatus.failed(detail))
        }
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
            val final = exitStatus(code)
            if (finish(final, target) && final.phase == TunnelPhase.Failed) {
                scheduleAutoRestart()
            }
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

    /**
     * Publishes the terminal state of a process and clears the retained output.
     *
     * [expected] is the process the caller observed; the state is only published while that
     * process is still the current one, so a watcher left over from an earlier launch cannot
     * clear a newer process. Returns true when the state was actually published.
     */
    private fun finish(final: TunnelStatus, expected: Process? = null): Boolean {
        synchronized(lock) {
            if (expected != null && process !== expected) return false
            if (expected == null && process != null) return false
            process = null
            launchPending = false
            token = ""
            outputTail = ""
            stopRequested = false
            publish(final)
            return true
        }
    }

    /**
     * Schedules one reconnect after the process ended without a Stop.
     *
     * A tunnel that dies is the one case where doing nothing leaves the site unreachable while the
     * app still says it is configured, so a bounded number of attempts are made with a pause that
     * keeps a binary that exits immediately from becoming a spin. Stop, or an explicit Start,
     * invalidates the pending attempt through the launch serial.
     */
    private fun scheduleAutoRestart() {
        val pending = synchronized(lock) {
            if (stopRequested || process != null || launchPending || autoRestartPending) {
                return@synchronized null
            }
            if (autoRestarts >= MAX_AUTO_RESTARTS) return@synchronized null
            val stored = lastConfig ?: return@synchronized null
            autoRestarts += 1
            autoRestartPending = true
            launchSerial += 1
            stored to launchSerial
        } ?: return
        val (config, serial) = pending
        val task = retries.schedule(
            { runAutoRestart(config, serial) },
            AUTO_RESTART_DELAY_MILLIS,
            TimeUnit.MILLISECONDS
        )
        val retain = synchronized(lock) {
            if (stopRequested || !autoRestartPending || launchSerial != serial) {
                false
            } else {
                autoRestartTask = task
                true
            }
        }
        if (!retain) task.cancel(false)
    }

    private fun runAutoRestart(config: TunnelConfig, serial: Long) {
        val ready = synchronized(lock) {
            autoRestartTask = null
            if (launchSerial != serial || !autoRestartPending || stopRequested ||
                process != null || launchPending
            ) {
                if (launchSerial == serial) autoRestartPending = false
                false
            } else {
                autoRestartPending = false
                launchPending = true
                token = config.token
                outputTail = ""
                stopRequested = false
                // The failure stays visible during the pause; the phase only changes when a
                // new process is actually about to start.
                publish(TunnelStatus(TunnelPhase.Starting, "Reconnecting after an unexpected exit"))
                true
            }
        }
        if (ready) launch(config, serial)
    }

    private fun publish(next: TunnelStatus): TunnelStatus {
        status = next
        listeners.forEach { listener ->
            runCatching { listener(next) }
        }
        return next
    }
}
