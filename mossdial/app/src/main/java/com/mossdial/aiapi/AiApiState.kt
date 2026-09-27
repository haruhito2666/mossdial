package com.mossdial.aiapi

import com.mossdial.data.ServerSettingsRules
import java.util.concurrent.CopyOnWriteArrayList

/** Coarse lifecycle phase of the local AI API, as shown by the app. */
enum class AiApiPhase {
    Stopped,
    Running,
    Failed
}

data class AiApiStatus(
    val phase: AiApiPhase,
    val port: Int = 0,
    val allowLan: Boolean = false,
    val detail: String = ""
) {
    val isRunning: Boolean
        get() = phase == AiApiPhase.Running

    /**
     * What a client should connect to.
     *
     * With LAN access the socket is bound to every interface, and `0.0.0.0` is not an address a
     * client can dial, so the bind address is reported with a note instead of a URL that would
     * silently fail.
     */
    fun endpoint(): String = when {
        phase != AiApiPhase.Running || port == 0 -> ""
        allowLan -> "${ServerSettingsRules.ANY_ADDRESS}:$port, reachable at this device's LAN address"
        else -> "http://${ServerSettingsRules.LOOPBACK_ADDRESS}:$port"
    }

    companion object {
        val STOPPED = AiApiStatus(AiApiPhase.Stopped)

        fun running(port: Int, allowLan: Boolean) =
            AiApiStatus(AiApiPhase.Running, port, allowLan, "Serving the local AI API on port $port")

        fun failed(detail: String) = AiApiStatus(AiApiPhase.Failed, detail = detail)
    }
}

/**
 * The published state of the local AI API, shared by the service that owns the socket and the AI
 * tab that describes it. Mirrors [com.mossdial.service.ServerState]: listeners are notified on the
 * publishing thread, so a subscriber must only hand work back to its own thread.
 */
object AiApiState {
    private val listeners = CopyOnWriteArrayList<(AiApiStatus) -> Unit>()

    @Volatile
    private var status: AiApiStatus = AiApiStatus.STOPPED

    fun snapshot(): AiApiStatus = status

    fun isRunning(): Boolean = status.isRunning

    fun publish(next: AiApiStatus): AiApiStatus {
        status = next
        listeners.forEach { listener -> runCatching { listener(next) } }
        return next
    }

    fun addListener(listener: (AiApiStatus) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (AiApiStatus) -> Unit) {
        listeners.remove(listener)
    }
}
