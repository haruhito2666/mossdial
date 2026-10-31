package com.mossdial.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import com.mossdial.data.SiteInspector
import com.mossdial.data.TunnelSettings
import com.mossdial.data.WebRoot
import com.mossdial.server.LocalHttpServer
import com.mossdial.server.ServerConfig
import com.mossdial.server.TlsIdentity
import com.mossdial.widget.ServerWidget

class ServerService : Service() {
    companion object {
        const val ACTION_START = "com.mossdial.action.START"
        const val ACTION_STOP = "com.mossdial.action.STOP"
        const val ACTION_RESTART = "com.mossdial.action.RESTART"
        const val EXTRA_PORT = "port"
        const val EXTRA_ALLOW_LAN = "allow_lan"
        const val EXTRA_ENABLE_SSL = "enable_ssl"

        private const val TAG = "MossdialAiApi"
        private const val TRAFFIC_PUBLISH_INTERVAL_MILLIS = 1_000L

        /**
         * How long to wait before each attempt to open the socket again.
         *
         * A port can be busy for a moment after a restart, or the identity may still be being
         * written; retrying across about two minutes rides those out without turning a genuinely
         * unusable port into an endless foreground loop.
         */
        private val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 5_000L, 15_000L, 30_000L, 60_000L)

        /**
         * How long a run has to last before a death is treated as new rather than as a failing
         * loop. A server that dies within this window keeps spending the retry budget; one that
         * served for longer gets a fresh one, because an hour of service followed by a crash is
         * not the same failure as five crashes in a minute.
         */
        private const val STABLE_RUN_MILLIS = 60_000L

        fun isRunning(): Boolean = ServerState.isRunning()
    }

    private val lifecycle = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: LocalHttpServer? = null
    private var aiApi: AiApiServer? = null

    /**
     * The start attempt that owns the fields above.
     *
     * Every start increments this, and every completion may only adopt its own instance while the
     * serial still matches. A single `accepting` boolean could not tell an old start that landed
     * after a stop+start from the new one: the old thread saw "accepting" and adopted itself,
     * leaving the real attempt to fail to bind, and a retry then dropped the wrong listener
     * without stopping it.
     */
    private var attemptSerial = 0L
    private var starting = false
    @Volatile
    private var startAttempts = 0
    @Volatile
    private var runningSinceMillis = 0L

    override fun onCreate() {
        super.onCreate()
        ServerNotifications.ensureChannel(this)
    }

    /**
     * Every request comes in through a foreground start, including a stop that arrives when the
     * service is not up, so the notification is posted first no matter which action was asked for.
     *
     * A null intent is the system restarting the service after the process died (`START_STICKY`).
     * There are no extras to read, so the persisted [ServerSettings.desiredRunning] decides: a
     * server the user had running comes back by itself, and one they stopped stays stopped.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = ServerSettings(this)
        val action = intent?.action
        if (action == ACTION_STOP) {
            // One rule for every stop path — the screen, the widget and the notification action:
            // an explicit stop clears both the intent to run and the reboot consent.
            settings.desiredRunning = false
            settings.autoStart = false
            enterForeground(ServerState.snapshot())
            stopServer()
            return START_NOT_STICKY
        }
        if (action == ACTION_RESTART) {
            // A site folder, site type or base path changed while the listener was up. Rebuild the
            // listener with the stored configuration without dropping the foreground service.
            settings.desiredRunning = true
            startAttempts = 0
            val requested = requestedStatus(settings)
            enterForeground(requested)
            restartServer(requested)
            return START_STICKY
        }

        val requested = when {
            intent != null && intent.action == ACTION_START -> requestedStatus(intent)
            intent == null && settings.desiredRunning -> requestedStatus(settings)
            else -> null
        }
        if (requested == null) {
            enterForeground(ServerState.snapshot())
            stopServer()
            return START_NOT_STICKY
        }

        settings.desiredRunning = true
        startAttempts = 0
        enterForeground(requested)
        startServer(requested)
        // If the process is killed — by a crash or by the system reclaiming memory — bring the
        // server back rather than leaving a hosted site down until someone opens the app.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // fail() has already published the reason and posted the failure notification. Stopping
        // the service must not overwrite that with "Offline", or the screen would show a server
        // that merely went quiet instead of the error the user needs to act on.
        stopServer(publishStopped = ServerState.snapshot().phase != ServerPhase.Failed)
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

    /** The stored configuration, for a restart the system initiated without an intent. */
    private fun requestedStatus(settings: ServerSettings): ServerStatus =
        ServerStatus.starting(settings.port, settings.allowLan, settings.enableSsl)

    private fun startServer(requested: ServerStatus) {
        val attempt = synchronized(lifecycle) {
            if (!ServerSettings(this).desiredRunning) {
                null
            } else if (starting || server != null) {
                null
            } else {
                starting = true
                attemptSerial += 1
                // Publish while holding the attempt, so a stop cannot slip in and be overwritten
                // by a stale Starting status from this one.
                publish(requested)
                attemptSerial
            }
        }
        if (attempt == null) {
            // When the server is already starting or running this refreshes the notification; when
            // the user stopped it in the meantime it does nothing at all.
            if (ServerSettings(this).desiredRunning) {
                ServerNotifications.post(this, ServerState.snapshot())
            }
            return
        }
        val attemptId = attempt
        val settings = ServerSettings(this)
        val requestLogging = settings.requestLogging
        Thread({
            val instance = try {
                val siteRoot = WebRoot.serveRoot(this, settings.sitePath)
                // A build made for GitHub Pages asks for /repo/... while the imported folder is
                // served at /. Detect that prefix once at start so the same tree works in both
                // places; an explicit setting always wins.
                val siteBasePath = settings.siteBasePath.ifEmpty {
                    runCatching { SiteInspector.detectBasePath(siteRoot) }.getOrDefault("")
                }
                LocalHttpServer(
                    config = ServerConfig(
                        port = requested.port,
                        bindAddress = ServerSettingsRules.bindAddress(requested.allowLan),
                        enableTls = requested.enableTls,
                        enableLogging = requestLogging,
                        requestLogCapacity = settings.requestLogLines,
                        spaFallback = settings.siteKind.spaFallback,
                        siteBasePath = siteBasePath,
                        backendPort = if (settings.backendEnabled) settings.backendPort else 0
                    ),
                    root = siteRoot,
                    // Logcat is a request log too: with logging off, a path must not end up there
                    // either, or the setting would only be hiding half of what it promises.
                    onRequest = { request ->
                        if (requestLogging) {
                            Log.d(TAG, "${request.method} ${request.path} -> ${request.status}")
                        }
                    },
                    // The device identity, so the certificate the user trusts is the same one on the
                    // next start instead of a new key every time the app is opened.
                    tlsIdentity = { TlsIdentity.forContext(applicationContext) },
                    // cloudflared reaches the site over loopback, so the tunnel's client address
                    // is only meaningful while the user has the tunnel enabled.
                    trustProxyHeaders = { TunnelSettings.isEnabled(applicationContext) }
                ).apply { start() }
            } catch (error: Throwable) {
                Log.e(TAG, "Server start failed", error)
                scheduleRetry(attemptId, requested, error.message ?: "Unable to start server")
                return@Thread
            }
            val adopted = synchronized(lifecycle) {
                if (attemptSerial != attemptId || !ServerSettings(this).desiredRunning) {
                    false
                } else {
                    server = instance
                    starting = false
                    startAttempts = 0
                    runningSinceMillis = SystemClock.elapsedRealtime()
                    // Publish while holding the attempt: a stop cannot land between adoption and
                    // this line and then be overwritten by a stale Running status.
                    publish(
                        ServerStatus.running(requested.port, requested.allowLan, requested.enableTls)
                            .copy(startedAtElapsedMillis = runningSinceMillis)
                    )
                    true
                }
            }
            if (!adopted) {
                runCatching { instance.stop() }
                return@Thread
            }
            startAiApi(attemptId)
            watchTraffic(instance, attemptId)
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
    private fun watchTraffic(instance: LocalHttpServer, attempt: Long) {
        Thread({
            while (isCurrentAttempt(attempt) && isRunning()) {
                // The flag on the server says "started", not "still accepting": if its accept
                // loop dies of something it does not catch, this is what notices and brings the
                // site back instead of leaving a foreground notification over a dead socket.
                if (!instance.isServing) {
                    restartAfterListenerDied(instance, attempt)
                    return@Thread
                }
                restartAiApiIfDead()
                TrafficState.publish(instance.statisticsSnapshot())
                try {
                    Thread.sleep(TRAFFIC_PUBLISH_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            // Do not wipe a newer attempt's counters when this thread is simply the old one
            // finishing after a restart.
            if (!isRunning()) TrafficState.clear()
        }, "MossdialTraffic").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Brings the local AI API back if its listener died while the file server kept serving.
     *
     * Only a listener that exists and has stopped is replaced, so a configuration failure (which
     * publishes a failure and leaves nothing registered) is not retried once a second. The site
     * stays up either way; this just restores the second socket.
     */
    private fun restartAiApiIfDead() {
        val deadAndAttempt = synchronized(lifecycle) {
            val current = aiApi
            if (current == null || current.isServing) {
                null
            } else {
                aiApi = null
                runCatching { current.stop() }
                current to attemptSerial
            }
        } ?: return
        val (_, attempt) = deadAndAttempt
        // A new file-server attempt may have taken over while the dead socket was being closed.
        if (!isCurrentAttempt(attempt)) return
        AiApiState.publish(AiApiStatus.STOPPED)
        startAiApi(attempt)
    }

    /**
     * Opens the socket again after a failed attempt, with a bounded backoff.
     *
     * The service stays in the foreground while it waits, so the retry is not a background start
     * the system can refuse. After the last delay the failure is reported and the service stops
     * with the intent to run cleared, so it cannot retry forever on a port that will never work.
     */
    private fun scheduleRetry(attempt: Long, requested: ServerStatus, reason: String) {
        if (startAttempts >= RETRY_DELAYS_MILLIS.size) {
            fail("$reason (gave up after ${RETRY_DELAYS_MILLIS.size} attempts)", attempt)
            return
        }
        val retrySerial = synchronized(lifecycle) {
            if (attemptSerial != attempt) return
            starting = false
            val serverToStop = server
            server = null
            val aiToStop = aiApi
            aiApi = null
            // Invalidate this attempt so a completion still in flight cannot adopt itself after
            // the retry has taken over.
            attemptSerial += 1
            // Stop the old listeners while this attempt still owns the state, so a new start
            // cannot try to bind the same ports before the old sockets are closed.
            runCatching { serverToStop?.stop() }
            runCatching { aiToStop?.stop() }
            attemptSerial
        }
        // A user may have started the server again while the failed listener was being closed.
        // That new attempt owns the state now; this retry must not publish over it.
        if (!isCurrentAttempt(retrySerial)) return
        AiApiState.publish(AiApiStatus.STOPPED)
        TrafficState.clear()
        val delayIndex = startAttempts.coerceIn(0, RETRY_DELAYS_MILLIS.lastIndex)
        val delay = RETRY_DELAYS_MILLIS[delayIndex]
        startAttempts = (delayIndex + 1).coerceAtMost(RETRY_DELAYS_MILLIS.size)
        val waiting = requested.copy(detail = "Retrying in ${delay / 1_000}s: $reason")
        publish(waiting)
        ServerNotifications.post(this, waiting)
        mainHandler.postDelayed({
            if (ServerSettings(this).desiredRunning) {
                startServer(requested)
            }
        }, delay)
    }

    /**
     * Replaces a server whose accept loop died while the service and its notification stayed up.
     * The restart uses the configuration the dead server was actually serving with, not whatever
     * has been typed into settings since, and goes through the same backoff as a failed start so a
     * server that dies immediately cannot spin.
     */
    private fun restartAfterListenerDied(dead: LocalHttpServer, attempt: Long) {
        val current = ServerState.snapshot()
        val nextAttempt = synchronized(lifecycle) {
            if (server !== dead || attemptSerial != attempt) return
            server = null
            starting = false
            attemptSerial += 1
            runCatching { dead.stop() }
            attemptSerial
        }
        TrafficState.clear()
        if (current.phase != ServerPhase.Running) return
        if (SystemClock.elapsedRealtime() - runningSinceMillis >= STABLE_RUN_MILLIS) {
            startAttempts = 0
        }
        val requested = ServerStatus.starting(current.port, current.allowLan, current.enableTls)
        scheduleRetry(nextAttempt, requested, "The listener stopped")
    }

    /**
     * Brings the local AI API up beside the file server.
     *
     * It is a second listener on its own port, started on its own thread and failing on its own: a
     * model API that cannot bind, or a device whose Keystore refuses to open the token, leaves the
     * site serving rather than taking the whole foreground service down with it. The token is
     * minted on the first start, so there is no window in which the listener is up without one.
     */
    private fun startAiApi(attempt: Long) {
        if (!isCurrentAttempt(attempt)) return
        val settings = try {
            AiApiSettings(this)
        } catch (error: Throwable) {
            Log.e(TAG, "AI API settings could not be opened", error)
            publishAiFailure(attempt, "Secure storage is unavailable on this device")
            return
        }
        val token = try {
            settings.token()?.takeIf { AiApiToken.isValid(it) }
                ?: AiApiToken.generate().also { settings.storeToken(it) }
        } catch (error: Throwable) {
            Log.e(TAG, "AI API token could not be read", error)
            publishAiFailure(attempt, "The API token could not be stored securely")
            return
        }
        val allowLan = settings.allowLan
        val enableTls = settings.enableTls
        val config = try {
            AiApiConfig(
                port = settings.port,
                bindAddress = ServerSettingsRules.bindAddress(allowLan),
                enableTls = enableTls
            )
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "AI API configuration rejected", error)
            publishAiFailure(attempt, error.message ?: "The API port is not usable")
            return
        }
        val instance = try {
            AiApiServer(
                config = config,
                token = token,
                backend = AiControllerApiBackend(),
                onRequest = { request -> Log.d(TAG, aiApiLogLine(request)) },
                tlsIdentity = { TlsIdentity.forContext(applicationContext) }
            ).apply { start() }
        } catch (error: Throwable) {
            Log.e(TAG, "AI API start failed", error)
            publishAiFailure(attempt, error.message ?: "Unable to start the AI API on port ${config.port}")
            return
        }
        val adopted = synchronized(lifecycle) {
            // The same rule as the file server: a stop or a newer attempt that landed while this
            // socket was opening takes priority, so the port is released instead of staying bound
            // to nothing. Publishing under the same lock keeps a stale Running out of the state.
            if (attemptSerial != attempt) {
                runCatching { instance.stop() }
                false
            } else {
                aiApi = instance
                AiApiState.publish(AiApiStatus.running(config.port, allowLan, enableTls))
                true
            }
        }
        if (!adopted) {
            return
        }
    }

    /** Publishes an AI API failure only while the attempt that produced it still owns the state. */
    private fun publishAiFailure(attempt: Long, message: String) {
        synchronized(lifecycle) {
            if (attemptSerial == attempt) {
                AiApiState.publish(AiApiStatus.failed(message))
            }
        }
    }

    private fun isCurrentAttempt(attempt: Long): Boolean = synchronized(lifecycle) {
        attemptSerial == attempt
    }

    /** Method, path, status and duration only: never a header, a body or the token. */
    private fun aiApiLogLine(request: AiApiRequest): String =
        "${request.method} ${request.path} -> ${request.status} in ${request.millis}ms"

    /**
     * Closes the current listeners and opens new ones with the stored settings, without leaving the
     * foreground.
     *
     * This is the same serial dance as a stop followed by a start: the old attempt is invalidated
     * first, so a start that is still opening a socket cannot adopt itself after the restart.
     * Pending retries are cancelled because they describe the old configuration.
     */
    private fun restartServer(requested: ServerStatus) {
        mainHandler.removeCallbacksAndMessages(null)
        startAttempts = 0
        synchronized(lifecycle) {
            attemptSerial += 1
            starting = false
            val serverToStop = server
            server = null
            val aiToStop = aiApi
            aiApi = null
            runCatching { serverToStop?.stop() }
            runCatching { aiToStop?.stop() }
        }
        AiApiState.publish(AiApiStatus.STOPPED)
        TrafficState.clear()
        publish(requested)
        startServer(requested)
    }

    private fun stopServer(publishStopped: Boolean = true) {
        mainHandler.removeCallbacksAndMessages(null)
        startAttempts = 0
        runningSinceMillis = 0L
        synchronized(lifecycle) {
            // Invalidate any start that is still opening its socket. Its completion will see the
            // changed serial, stop its own instance and leave the state alone.
            attemptSerial += 1
            starting = false
            val serverToStop = server
            server = null
            val aiToStop = aiApi
            aiApi = null
            runCatching { serverToStop?.stop() }
            runCatching { aiToStop?.stop() }
        }
        if (publishStopped) {
            publish(ServerStatus.STOPPED)
        } else {
            runCatching { ServerWidget.refresh(this) }
        }
        AiApiState.publish(AiApiStatus.STOPPED)
        TrafficState.clear()
        leaveForeground()
        stopSelf()
    }

    private fun fail(detail: String, attempt: Long) {
        synchronized(lifecycle) {
            // A newer attempt already owns the service; this failure is stale and must not tear it
            // down or clear a state it has just published.
            if (attemptSerial != attempt) return
            attemptSerial += 1
            starting = false
            startAttempts = 0
            ServerSettings(this).desiredRunning = false
            val serverToStop = server
            server = null
            val aiToStop = aiApi
            aiApi = null
            runCatching { serverToStop?.stop() }
            runCatching { aiToStop?.stop() }
        }
        mainHandler.removeCallbacksAndMessages(null)
        runningSinceMillis = 0L
        AiApiState.publish(AiApiStatus.STOPPED)
        TrafficState.clear()
        val failed = ServerStatus.failed(detail)
        publish(failed)
        leaveForeground()
        ServerNotifications.postFailure(this, failed)
        stopSelf()
    }

    private fun publish(status: ServerStatus) {
        ServerState.publish(status)
        runCatching { ServerWidget.refresh(this) }
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
