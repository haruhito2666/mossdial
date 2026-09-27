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
    val detail: String = ""
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
    @Volatile
    private var snapshot: ServerStatisticsSnapshot = ServerStatisticsSnapshot.EMPTY

    fun snapshot(): ServerStatisticsSnapshot = snapshot

    fun publish(next: ServerStatisticsSnapshot): ServerStatisticsSnapshot {
        snapshot = next
        return next
    }

    fun clear(): ServerStatisticsSnapshot = publish(ServerStatisticsSnapshot.EMPTY)
}
