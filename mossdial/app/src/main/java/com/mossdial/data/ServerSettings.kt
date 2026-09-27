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

    /** The address the server socket binds for the current LAN setting. */
    fun bindAddress(): String = ServerSettingsRules.bindAddress(allowLan)

    companion object {
        const val NAME = "server_settings"
        private const val KEY_PORT = "port"
        private const val KEY_ALLOW_LAN = "allow_lan"
        private const val KEY_ENABLE_SSL = "enable_ssl"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_REQUEST_LOGGING = "request_logging"
        private const val KEY_LOG_LINES = "request_log_lines"

        /** Matches the server's own default, so an unconfigured install logs what the server expects. */
        const val DEFAULT_LOG_LINES = 256
    }
}
