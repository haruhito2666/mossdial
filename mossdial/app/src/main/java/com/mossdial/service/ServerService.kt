package com.mossdial.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mossdial.ai.AiControllerRegistry
import com.mossdial.aiapi.AiApiConfig
import com.mossdial.aiapi.AiApiRequest
import com.mossdial.aiapi.AiApiServer
import com.mossdial.aiapi.AiApiState
import com.mossdial.aiapi.AiApiStatus
import com.mossdial.aiapi.AiApiToken
import com.mossdial.aiapi.AiControllerApiBackend
import com.mossdial.data.AiApiSettings
import com.mossdial.data.ServerSettings
import com.mossdial.data.ServerSettingsRules
import com.mossdial.data.WebRoot
import com.mossdial.server.LocalHttpServer
import com.mossdial.server.ServerConfig
import com.mossdial.server.TlsIdentity
import com.mossdial.widget.ServerWidget

class ServerService : Service() {
    companion object {
        const val ACTION_START = "com.mossdial.action.START"
        const val ACTION_STOP = "com.mossdial.action.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_ALLOW_LAN = "allow_lan"
        const val EXTRA_ENABLE_SSL = "enable_ssl"

        private const val TAG = "MossdialAiApi"
        private const val TRAFFIC_PUBLISH_INTERVAL_MILLIS = 1_000L

        fun isRunning(): Boolean = ServerState.isRunning()
    }

    private val lifecycle = Any()
    private var server: LocalHttpServer? = null
    private var aiApi: AiApiServer? = null
    private var accepting = false

    override fun onCreate() {
        super.onCreate()
        ServerNotifications.ensureChannel(this)
    }

    /**
     * Every request comes in through a foreground start, including a stop that arrives when the
     * service is not up, so the notification is posted first no matter which action was asked for.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val starting = if (intent?.action == ACTION_START) requestedStatus(intent) else null
        enterForeground(starting ?: ServerState.snapshot())
        if (starting == null) stopServer() else startServer(starting)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    /**
     * The status the caller asked for, with the port taken from the intent when the intent carries
     * one and normalised either way, so no request can put the server on an unusable port.
     */
    private fun requestedStatus(intent: Intent): ServerStatus {
        val settings = ServerSettings(this)
        val port = ServerSettingsRules.normalizePort(
            intent.getIntExtra(EXTRA_PORT, settings.port)
        )
        val allowLan = intent.getBooleanExtra(EXTRA_ALLOW_LAN, settings.allowLan)
        val enableSsl = intent.getBooleanExtra(EXTRA_ENABLE_SSL, settings.enableSsl)
        return ServerStatus.starting(port, allowLan, enableSsl)
    }

    private fun startServer(requested: ServerStatus) {
        val alreadyRunning = synchronized(lifecycle) {
            if (accepting) return@synchronized true
            accepting = true
            false
        }
        if (alreadyRunning) {
            // A second start while the socket is open only refreshes the notification.
            ServerNotifications.post(this, ServerState.snapshot())
            return
        }
        publish(requested)
        val settings = ServerSettings(this)
        val requestLogging = settings.requestLogging
        Thread({
            val instance = try {
                LocalHttpServer(
                    config = ServerConfig(
                        port = requested.port,
                        bindAddress = ServerSettingsRules.bindAddress(requested.allowLan),
                        enableTls = requested.enableTls,
                        enableLogging = requestLogging,
                        requestLogCapacity = settings.requestLogLines
                    ),
                    root = WebRoot.ensure(this),
                    // Logcat is a request log too: with logging off, a path must not end up there
                    // either, or the setting would only be hiding half of what it promises.
                    onRequest = { request ->
                        if (requestLogging) {
                            Log.d(TAG, "${request.method} ${request.path} -> ${request.status}")
                        }
                    },
                    // The device identity, so the certificate the user trusts is the same one on the
                    // next start instead of a new key every time the app is opened.
                    tlsIdentity = { TlsIdentity.forContext(applicationContext) }
                ).apply { start() }
            } catch (error: Throwable) {
                Log.e(TAG, "Server start failed", error)
                fail(error.message ?: "Unable to start server")
                return@Thread
            }
            val cancelled = synchronized(lifecycle) {
                // A stop that landed while the socket was being opened takes priority, so the port
                // is released immediately instead of staying bound with nothing listening on it.
                if (!accepting) {
                    instance.stop()
                    true
                } else {
                    server = instance
                    false
                }
            }
            if (!cancelled) {
                publish(ServerStatus.running(requested.port, requested.allowLan, requested.enableTls))
                startAiApi()
                watchTraffic(instance)
            }
        }, "MossdialServer").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Publishes the running server's traffic counters while it is up.
     *
     * One second is often enough for a counter to look alive without the loop costing anything when
     * nobody is watching, and the snapshot is only ever read as a value, so a slow poll cannot make
     * the server slower. The counters are the server's; this only carries the latest copy to the tab.
     */
    private fun watchTraffic(instance: LocalHttpServer) {
        Thread({
            while (isRunning()) {
                TrafficState.publish(instance.statisticsSnapshot())
                try {
                    Thread.sleep(TRAFFIC_PUBLISH_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            TrafficState.clear()
        }, "MossdialTraffic").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Brings the local AI API up beside the file server.
     *
     * It is a second listener on its own port, started on its own thread and failing on its own: a
     * model API that cannot bind, or a device whose Keystore refuses to open the token, leaves the
     * site serving rather than taking the whole foreground service down with it. The token is
     * minted on the first start, so there is no window in which the listener is up without one.
     */
    private fun startAiApi() {
        val settings = try {
            AiApiSettings(this)
        } catch (error: Throwable) {
            Log.e(TAG, "AI API settings could not be opened", error)
            AiApiState.publish(AiApiStatus.failed("Secure storage is unavailable on this device"))
            return
        }
        val token = try {
            settings.token()?.takeIf { AiApiToken.isValid(it) }
                ?: AiApiToken.generate().also { settings.storeToken(it) }
        } catch (error: Throwable) {
            Log.e(TAG, "AI API token could not be read", error)
            AiApiState.publish(AiApiStatus.failed("The API token could not be stored securely"))
            return
        }
        val config = try {
            AiApiConfig(port = settings.port, bindAddress = settings.bindAddress())
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "AI API configuration rejected", error)
            AiApiState.publish(AiApiStatus.failed(error.message ?: "The API port is not usable"))
            return
        }
        val instance = try {
            AiApiServer(
                config = config,
                token = token,
                backend = AiControllerApiBackend(),
                onRequest = { request -> Log.d(TAG, aiApiLogLine(request)) }
            ).apply { start() }
        } catch (error: Throwable) {
            Log.e(TAG, "AI API start failed", error)
            AiApiState.publish(
                AiApiStatus.failed(error.message ?: "Unable to start the AI API on port ${config.port}")
            )
            return
        }
        val cancelled = synchronized(lifecycle) {
            // The same rule as the file server: a stop that landed while this socket was opening
            // takes priority, so the port is released instead of staying bound to nothing.
            if (!accepting) {
                instance.stop()
                true
            } else {
                aiApi = instance
                false
            }
        }
        if (!cancelled) {
            AiApiState.publish(AiApiStatus.running(config.port, settings.allowLan))
        }
    }

    /** Method, path, status and duration only: never a header, a body or the token. */
    private fun aiApiLogLine(request: AiApiRequest): String =
        "${request.method} ${request.path} -> ${request.status} in ${request.millis}ms"

    private fun stopServer() {
        synchronized(lifecycle) {
            accepting = false
            server?.stop()
            server = null
            aiApi?.stop()
            aiApi = null
        }
        publish(ServerStatus.STOPPED)
        AiApiState.publish(AiApiStatus.STOPPED)
        leaveForeground()
        stopSelf()
    }

    private fun fail(detail: String) {
        synchronized(lifecycle) {
            accepting = false
            server = null
        }
        val failed = ServerStatus.failed(detail)
        publish(failed)
        leaveForeground()
        ServerNotifications.postFailure(this, failed)
        stopSelf()
    }

    private fun publish(status: ServerStatus) {
        ServerState.publish(status)
        ServerWidget.refresh(this)
    }

    /**
     * The platform allows five seconds between a foreground start and its notification, so the
     * channel is created and the notification posted before the socket work is handed to a thread.
     */
    private fun enterForeground(status: ServerStatus) {
        ServerNotifications.clearFailure(this)
        val notification = ServerNotifications.build(this, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ServerNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Below API 34 the type is taken from the foregroundServiceType in the manifest.
            startForeground(ServerNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun leaveForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
