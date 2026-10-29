package com.mossdial.service

import com.mossdial.server.ServerStatisticsSnapshot
import java.util.concurrent.CopyOnWriteArrayList

/** Coarse lifecycle phase of the local server, as shown by the app, the notification and the widget. */
enum class ServerPhase {
    Stopped,
    Starting,
    Running,
    Failed
}

data class ServerStatus(
    val phase: ServerPhase,
    val port: Int = 0,
    val allowLan: Boolean = false,
    val enableTls: Boolean = false,
    val detail: String = "",
    /**
     * When this run of the server came up, on the `SystemClock.elapsedRealtime()` scale, so a
     * screen can show how long the server has been serving without keeping its own clock — a
     * clock that would reset every time the tab left composition. Zero means the phase carries no
     * start moment, which is every state but Running.
     */
    val startedAtElapsedMillis: Long = 0L
) {
    val isActive: Boolean
        get() = phase == ServerPhase.Starting || phase == ServerPhase.Running

    companion object {
        val STOPPED = ServerStatus(ServerPhase.Stopped)

        fun starting(port: Int, allowLan: Boolean, enableTls: Boolean) =
            ServerStatus(ServerPhase.Starting, port, allowLan, enableTls, "Starting the server")

        fun running(port: Int, allowLan: Boolean, enableTls: Boolean) =
            ServerStatus(ServerPhase.Running, port, allowLan, enableTls, "Serving on port $port")

        fun failed(detail: String) = ServerStatus(ServerPhase.Failed, detail = detail)
    }
}

/**
 * The published state of the server, shared by everything that needs to know whether it is up.
 *
 * The foreground service owns the socket, but the home-screen widget, the boot receiver and the
 * settings screen all read the same snapshot. Listeners are notified on the thread that publishes,
 * which is why subscribers must only hand work back to their own thread.
 */
object ServerState {
    private val listeners = CopyOnWriteArrayList<(ServerStatus) -> Unit>()

    @Volatile
    private var status: ServerStatus = ServerStatus.STOPPED

    fun snapshot(): ServerStatus = status

    fun isRunning(): Boolean = status.phase == ServerPhase.Running

    fun publish(next: ServerStatus): ServerStatus {
        status = next
        listeners.forEach { listener -> runCatching { listener(next) } }
        return next
    }

    fun addListener(listener: (ServerStatus) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (ServerStatus) -> Unit) {
        listeners.remove(listener)
    }
}

/**
 * The latest traffic snapshot, for the screen that shows it.
 *
 * The server owns the counters, but the screen that displays them is not the service, so the running
 * server publishes here and the tab reads. [EMPTY] is what a stopped server leaves behind, which
 * means the tab shows nothing rather than numbers from a server that is no longer up.
 */
object TrafficState {
    /**
     * How many one-second samples the live graphs on the server tab draw: a rolling minute of
     * traffic, published once per watch tick.
     */
    private const val SAMPLE_CAPACITY = 60

    private val lock = Any()
    private val samples = ArrayDeque<TrafficSample>(SAMPLE_CAPACITY)

    @Volatile
    private var snapshot: ServerStatisticsSnapshot = ServerStatisticsSnapshot.EMPTY

    fun snapshot(): ServerStatisticsSnapshot = snapshot

    /** The rolling window of per-second deltas the live graphs plot, oldest first. */
    fun samples(): List<TrafficSample> = synchronized(lock) { samples.toList() }

    fun publish(next: ServerStatisticsSnapshot): ServerStatisticsSnapshot {
        val previous = snapshot
        synchronized(lock) {
            // Deltas, not totals: the graph of the last minute must show traffic as it happened,
            // survive a counter that never decreases across a restart of the underlying server,
            // and never read a negative dip because a sample landed between publishes.
            samples.addLast(
                TrafficSample(
                    requestsDelta = (next.totalRequests - previous.totalRequests).coerceAtLeast(0),
                    bytesDelta = (next.bytesSent - previous.bytesSent).coerceAtLeast(0)
                )
            )
            while (samples.size > SAMPLE_CAPACITY) {
                samples.removeFirst()
            }
        }
        snapshot = next
        return next
    }

    fun clear(): ServerStatisticsSnapshot {
        synchronized(lock) { samples.clear() }
        snapshot = ServerStatisticsSnapshot.EMPTY
        return snapshot
    }
}

/**
 * One second of observed traffic, as the delta between two published counters. The graphs on the
 * server tab are drawn from a rolling list of these, so what they show is what the server actually
 * served each second rather than a summary of it.
 */
data class TrafficSample(
    val requestsDelta: Long,
    val bytesDelta: Long
)
