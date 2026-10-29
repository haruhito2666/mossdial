package com.mossdial.aiapi

import com.mossdial.data.ServerSettingsRules
import com.mossdial.server.LanAddress
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
    val detail: String = "",
    val enableTls: Boolean = false
) {
    val isRunning: Boolean
        get() = phase == AiApiPhase.Running

    /**
     * What a client should connect to.
     *
     * With LAN access the socket listens on every interface, and `0.0.0.0` is not an address a
     * client can dial, so the actual site-local address is reported and the bind address is only a
     * fallback for a device with no network up.
     */
    fun endpoint(): String = when {
        phase != AiApiPhase.Running || port == 0 -> ""
        allowLan -> "$scheme://${LanAddress.siteLocalIPv4() ?: ServerSettingsRules.LOOPBACK_ADDRESS}:$port"
        else -> "$scheme://${ServerSettingsRules.LOOPBACK_ADDRESS}:$port"
    }

    val scheme: String
        get() = if (enableTls) "https" else "http"

    companion object {
        val STOPPED = AiApiStatus(AiApiPhase.Stopped)

        fun running(port: Int, allowLan: Boolean, enableTls: Boolean = false) =
            AiApiStatus(
                AiApiPhase.Running,
                port,
                allowLan,
                "Serving the local AI API on ${if (enableTls) "HTTPS" else "HTTP"} port $port",
                enableTls
            )

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
