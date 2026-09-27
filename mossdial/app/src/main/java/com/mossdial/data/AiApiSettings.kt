package com.mossdial.data

import android.content.Context
import java.io.File

/**
 * Settings and the credential for the local AI API.
 *
 * The port and the LAN switch are plain preferences, normalised through [ServerSettingsRules] so
 * a value written by an older build, restored from a backup or forwarded in an intent is always a
 * port the listener can bind. The listener defaults to its own port, 8081, because the API is a
 * separate socket from the file server.
 *
 * The bearer token is the exception. It is only ever written to the Keystore-backed [SecretStore],
 * under [TOKEN_SLOT], and sealed with the key named [KEY_ALIAS]. Both are deliberately different
 * from the tunnel token's slot and alias: the two credentials are separate secrets, so lifting one
 * of them out of its slot, or trying to read one with the other's key, fails the GCM tag instead
 * of handing over a working token.
 */
class AiApiSettings(
    private val store: PreferenceStore,
    private val secretStore: SecretStore
) {
    constructor(context: Context) : this(
        SharedPreferenceStore(context.getSharedPreferences(NAME, Context.MODE_PRIVATE)),
        EncryptedSecretStore(File(context.filesDir, "secrets"), AndroidKeystoreKeyProvider(KEY_ALIAS))
    )

    var port: Int
        get() = ServerSettingsRules.normalizePort(store.getInt(KEY_PORT, DEFAULT_PORT))
        set(value) = store.putInt(KEY_PORT, ServerSettingsRules.normalizePort(value))

    var allowLan: Boolean
        get() = store.getBoolean(KEY_ALLOW_LAN, false)
        set(value) = store.putBoolean(KEY_ALLOW_LAN, value)

    /** The address the API socket binds for the current LAN setting. */
    fun bindAddress(): String = ServerSettingsRules.bindAddress(allowLan)

    fun hasToken(): Boolean = secretStore.contains(TOKEN_SLOT)

    /** The stored token, or null when none is stored or the stored copy is no longer readable. */
    fun token(): String? = secretStore.read(TOKEN_SLOT)

    fun storeToken(token: String) {
        require(token.isNotBlank() && token.length <= MAX_TOKEN_LENGTH) { "Invalid API token" }
        secretStore.write(TOKEN_SLOT, token)
    }

    /** Replaces the stored token, so a leaked one can be retired without touching the model. */
    fun rotateToken(token: String): String {
        storeToken(token)
        return token
    }

    fun clearToken() {
        secretStore.clear(TOKEN_SLOT)
    }

    companion object {
        const val NAME = "ai_api_settings"
        const val DEFAULT_PORT = 8081

        /** Distinct from [TunnelSettings.TOKEN_SLOT] and sealed with [KEY_ALIAS]. */
        const val TOKEN_SLOT = "ai_api_token"

        /** Distinct from [AndroidKeystoreKeyProvider.DEFAULT_ALIAS], which seals the tunnel token. */
        const val KEY_ALIAS = "mossdial.aiapi.token.v1"

        /** The cap the listener's bearer header can be inside, comfortably over a 32-byte token. */
        const val MAX_TOKEN_LENGTH = 128

        private const val KEY_PORT = "port"
        private const val KEY_ALLOW_LAN = "allow_lan"
    }
}
