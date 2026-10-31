package com.mossdial.data

import android.content.Context

/**
 * Server preferences: the port, where the socket binds, whether TLS is used, and whether the
 * server should come back on its own after a restart.
 *
 * Values are normalised on the way in and on the way out, so a value written by an older build,
 * restored from a backup or handed over in a service intent is always usable. The tunnel settings
 * stay separate in [TunnelSettings]: the tunnel token is a secret and never passes through here.
 */
class ServerSettings(private val store: PreferenceStore) {
    constructor(context: Context) : this(
        SharedPreferenceStore(context.getSharedPreferences(NAME, Context.MODE_PRIVATE))
    )

    var port: Int
        get() = ServerSettingsRules.normalizePort(store.getInt(KEY_PORT, ServerSettingsRules.DEFAULT_PORT))
        set(value) = store.putInt(KEY_PORT, ServerSettingsRules.normalizePort(value))

    var allowLan: Boolean
        get() = store.getBoolean(KEY_ALLOW_LAN, false)
        set(value) = store.putBoolean(KEY_ALLOW_LAN, value)

    var enableSsl: Boolean
        get() = store.getBoolean(KEY_ENABLE_SSL, false)
        set(value) = store.putBoolean(KEY_ENABLE_SSL, value)

    /**
     * Whether the user wants the server up right now.
     *
     * This is the immediate intent, not the reboot consent of [autoStart]. It is set when a start
     * is accepted and cleared by an explicit stop, and it survives process death so a service the
     * system restarted (`START_STICKY`, which delivers a null intent) knows to bring the server
     * back rather than shut down. A crash therefore restores the server without any user action.
     */
    var desiredRunning: Boolean
        get() = store.getBoolean(KEY_DESIRED_RUNNING, false)
        set(value) = store.putBoolean(KEY_DESIRED_RUNNING, value)

    /**
     * Whether a boot receiver may bring the server back without the user asking.
     *
     * Defaults to off and is only ever turned on from the settings screen, which is what
     * [com.mossdial.service.AutoStartPolicy] checks before starting anything.
     */
    var autoStart: Boolean
        get() = store.getBoolean(KEY_AUTO_START, false)
        set(value) = store.putBoolean(KEY_AUTO_START, value)

    /**
     * Whether requests are recorded in memory at all.
     *
     * Off by default, and the reason is the privacy policy: nothing about who visited a site the
     * user hosts on their own phone is recorded unless they asked for it. The aggregate counters in
     * the statistics are not request logs and are unaffected, so turning this off stops paths from
     * being remembered without blinding the status screen.
     */
    var requestLogging: Boolean
        get() = store.getBoolean(KEY_REQUEST_LOGGING, false)
        set(value) = store.putBoolean(KEY_REQUEST_LOGGING, value)

    /** How many requests the in-memory log keeps, oldest dropped first. */
    var requestLogLines: Int
        get() = ServerSettingsRules.normalizeLogLines(store.getInt(KEY_LOG_LINES, DEFAULT_LOG_LINES))
        set(value) = store.putInt(KEY_LOG_LINES, ServerSettingsRules.normalizeLogLines(value))

    /**
     * The folder inside the web root the server serves, or an empty string for the root itself.
     *
     * This is what lets a person keep several sites in the web root and pick one without moving
     * files around. It is stored as a relative path and validated again when the server starts, so
     * a stale or tampered value can never widen what is served.
     */
    var sitePath: String
        get() = store.getString(KEY_SITE_PATH, "").orEmpty().trim().trim('/')
        set(value) = store.putString(KEY_SITE_PATH, value.trim().trim('/'))

    /**
     * What kind of site the hosted folder holds.
     *
     * This is the user's one-word description of the build output, and it decides how routes are
     * resolved: a SPA needs the `index.html` fallback, while a static export or plain site should
     * 404 a route that has no file. Anything unknown falls back to the plain-static default.
     */
    var siteKind: SiteKind
        get() = SiteKind.fromId(store.getString(KEY_SITE_KIND, SiteKind.CUSTOM.id))
        set(value) = store.putString(KEY_SITE_KIND, value.id)

    /**
     * The prefix a build expects in front of every path, for example `repo` for a GitHub Pages
     * project. The server strips it before resolving a file, so a build made for `/repo/` still
     * finds its assets when the folder is hosted at `/`.
     *
     * Empty means no prefix. Invalid values are stored as empty rather than making the server
     * unable to start.
     */
    var siteBasePath: String
        get() = SiteInspector.normalizeBasePath(store.getString(KEY_SITE_BASE_PATH, ""))
        set(value) = store.putString(KEY_SITE_BASE_PATH, SiteInspector.normalizeBasePath(value))

    /**
     * Whether the public listener proxies to a local backend instead of serving files.
     *
     * The backend itself is the user's own runtime — a Next.js server, Node, Bun, Deno, Python, PHP,
     * anything that speaks HTTP on this device. Mossdial only points the public port at it, so the
     * runtime stays the user's process and the proxy stays transport-only.
     */
    var backendEnabled: Boolean
        get() = store.getBoolean(KEY_BACKEND_ENABLED, false)
        set(value) = store.putBoolean(KEY_BACKEND_ENABLED, value)

    /** The loopback port the local backend listens on. */
    var backendPort: Int
        get() = ServerSettingsRules.normalizePort(store.getInt(KEY_BACKEND_PORT, DEFAULT_BACKEND_PORT))
        set(value) = store.putInt(KEY_BACKEND_PORT, ServerSettingsRules.normalizePort(value))

    /** The address the server socket binds for the current LAN setting. */
    fun bindAddress(): String = ServerSettingsRules.bindAddress(allowLan)

    companion object {
        const val NAME = "server_settings"
        private const val KEY_PORT = "port"
        private const val KEY_ALLOW_LAN = "allow_lan"
        private const val KEY_ENABLE_SSL = "enable_ssl"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_DESIRED_RUNNING = "desired_running"
        private const val KEY_REQUEST_LOGGING = "request_logging"
        private const val KEY_LOG_LINES = "request_log_lines"
        private const val KEY_SITE_PATH = "site_path"
        private const val KEY_SITE_KIND = "site_kind"
        private const val KEY_SITE_BASE_PATH = "site_base_path"
        private const val KEY_BACKEND_ENABLED = "backend_enabled"
        private const val KEY_BACKEND_PORT = "backend_port"

        /** The port a Next.js dev server uses by default, because that is the common case. */
        const val DEFAULT_BACKEND_PORT = 3000

        /** Matches the server's own default, so an unconfigured install logs what the server expects. */
        const val DEFAULT_LOG_LINES = 256
    }
}
