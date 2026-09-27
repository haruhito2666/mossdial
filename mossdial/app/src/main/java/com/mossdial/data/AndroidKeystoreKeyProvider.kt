package com.mossdial.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-256 key held by the platform Android Keystore.
 *
 * The key material never enters the app process: [Cipher] operations are executed inside the
 * keystore daemon, and on devices with a secure element or TEE the key is additionally
 * hardware bound. Only a reference is returned here, which is all [SecretBox] needs.
 *
 * This is a hand-rolled replacement for `EncryptedSharedPreferences`, which would pull in the
 * AndroidX Security library for a single AES key.
 */
class AndroidKeystoreKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
    private val providerName: String = PROVIDER
) : KeyProvider {
    override fun key(): SecretKey = try {
        val keyStore = loadKeyStore()
        (keyStore.getKey(alias, null) as? SecretKey) ?: generateKey()
    } catch (error: GeneralSecurityException) {
        throw SecretStoreException("Secure storage is unavailable on this device", error)
    } catch (error: java.io.IOException) {
        throw SecretStoreException("Secure storage could not be opened", error)
    }

    /** Discards the key, which makes every stored secret permanently unreadable. */
    fun deleteKey() {
        try {
            loadKeyStore().deleteEntry(alias)
        } catch (error: GeneralSecurityException) {
            throw SecretStoreException("Secure storage could not be updated", error)
        } catch (error: java.io.IOException) {
            throw SecretStoreException("Secure storage could not be opened", error)
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(ALGORITHM, providerName)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(providerName).apply { load(null) }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "mossdial.tunnel.secret.v1"
        private const val ALGORITHM = "AES"
        private const val KEY_SIZE_BITS = 256
    }
}
