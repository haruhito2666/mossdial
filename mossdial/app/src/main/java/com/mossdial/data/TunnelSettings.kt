package com.mossdial.data

import android.content.Context
import com.mossdial.tunnel.BundledCloudflared
import com.mossdial.tunnel.TunnelConfig
import com.mossdial.tunnel.TunnelResolution
import com.mossdial.tunnel.TunnelResolver
import java.io.File

/**
 * Tunnel preferences.
 *
 * Non-secret values live in plain preferences. The tunnel token is the exception: it is only
 * ever written to the Keystore-backed [SecretStore], and [hasToken] reports presence without
 * materialising the value.
 */
class TunnelSettings(context: Context, private val secretStore: SecretStore) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    constructor(context: Context) : this(context, defaultSecretStore(context))

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Whether the packaged `cloudflared` is used instead of a user-supplied executable.
     *
     * On by default: the packaged engine is the difference between "paste a token" and "install
     * ADB, push a binary, chmod it, and type its path".
     */
    var useBundled: Boolean
        get() = preferences.getBoolean(KEY_USE_BUNDLED, true)
        set(value) = preferences.edit().putBoolean(KEY_USE_BUNDLED, value).apply()

    /**
     * Whether the tunnel is a temporary quick tunnel.
     *
     * A quick tunnel needs no token or domain: cloudflared prints a random
     * `https://....trycloudflare.com` URL that lasts until the process stops. It is for testing and
     * sharing a link, not for a stable hostname.
     */
    var quickTunnel: Boolean
        get() = preferences.getBoolean(KEY_QUICK_TUNNEL, false)
        set(value) = preferences.edit().putBoolean(KEY_QUICK_TUNNEL, value).apply()

    var executablePath: String
        get() = preferences.getString(KEY_EXECUTABLE_PATH, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_EXECUTABLE_PATH, value).apply()

    var protocol: String
        get() = preferences.getString(KEY_PROTOCOL, TunnelConfig.PROTOCOL_HTTP2).orEmpty()
            .let { stored -> if (stored in TunnelConfig.ALLOWED_PROTOCOLS) stored else TunnelConfig.PROTOCOL_HTTP2 }
        set(value) {
            TunnelConfig.validateProtocol(value)
            preferences.edit().putString(KEY_PROTOCOL, value).apply()
        }

    fun hasToken(): Boolean = secretStore.contains(TOKEN_SLOT)

    /** The stored token, or null when none is stored or the stored copy is unusable. */
    fun token(): String? = secretStore.read(TOKEN_SLOT)

    fun storeToken(token: String) {
        TunnelConfig.validateToken(token)
        secretStore.write(TOKEN_SLOT, token)
    }

    fun clearToken() {
        secretStore.clear(TOKEN_SLOT)
    }

    /**
     * Assembles a validated configuration, or returns the reason the stored settings cannot be
     * used. The packaged engine is preferred unless the user explicitly chose a custom binary; if
     * this build does not contain it, the stored custom path is used instead.
     */
    fun resolve(): TunnelResolution {
        val bundledPath = BundledCloudflared.path(appContext)
        val path = executableFor(useBundled, bundledPath, executablePath)
        return try {
            TunnelResolver.resolve(path, token(), protocol)
        } catch (error: SecretStoreException) {
            TunnelResolution.Invalid("Secure storage is unavailable on this device")
        }
    }

    /** A quick tunnel needs no token; [port] is the local site port it should publish. */
    fun resolveQuick(port: Int): TunnelResolution {
        val path = executableFor(useBundled, BundledCloudflared.path(appContext), executablePath)
        return try {
            TunnelResolution.Ready(TunnelConfig.quick(path, port, protocol))
        } catch (error: IllegalArgumentException) {
            TunnelResolution.Invalid(error.message ?: "The quick tunnel is not usable")
        }
    }

    companion object {
        const val TOKEN_SLOT = "tunnel_token"
        private const val PREFERENCES = "tunnel_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_USE_BUNDLED = "use_bundled"
        private const val KEY_QUICK_TUNNEL = "quick_tunnel"
        private const val KEY_EXECUTABLE_PATH = "executable_path"
        private const val KEY_PROTOCOL = "protocol"

        /** The engine the tunnel should run: the packaged one when selected and present. */
        internal fun executableFor(
            useBundled: Boolean,
            bundledPath: String?,
            customPath: String
        ): String = if (useBundled && bundledPath != null) bundledPath else customPath

        /**
         * Whether the tunnel is enabled, without opening the Keystore-backed store.
         *
         * The file server asks this on request threads to decide whether a forwarded address may
         * be trusted. That path must not be able to fail because secure storage is unavailable,
         * so it reads the plain preference directly.
         */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun defaultSecretStore(context: Context): SecretStore = EncryptedSecretStore(
            File(context.filesDir, "secrets"),
            AndroidKeystoreKeyProvider()
        )
    }
}
