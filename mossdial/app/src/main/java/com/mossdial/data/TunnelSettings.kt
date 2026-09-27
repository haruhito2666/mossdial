package com.mossdial.data

import android.content.Context
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
    private val preferences = context.getSharedPreferences("tunnel_settings", Context.MODE_PRIVATE)

    constructor(context: Context) : this(context, defaultSecretStore(context))

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

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
     * used. Callers get either a ready-to-run config or a message meant for the UI.
     */
    fun resolve(): TunnelResolution = try {
        TunnelResolver.resolve(executablePath, token(), protocol)
    } catch (error: SecretStoreException) {
        TunnelResolution.Invalid("Secure storage is unavailable on this device")
    }

    companion object {
        const val TOKEN_SLOT = "tunnel_token"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_EXECUTABLE_PATH = "executable_path"
        private const val KEY_PROTOCOL = "protocol"

        fun defaultSecretStore(context: Context): SecretStore = EncryptedSecretStore(
            File(context.filesDir, "secrets"),
            AndroidKeystoreKeyProvider()
        )
    }
}
