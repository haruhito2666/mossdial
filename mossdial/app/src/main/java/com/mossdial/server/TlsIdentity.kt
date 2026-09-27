package com.mossdial.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.withLock

/**
 * The key and certificate the TLS listener presents, and where the key lives.
 *
 * This exists because a certificate nobody can keep is a certificate nobody can trust. Generated on
 * every start, the key changes each time the app is reopened, so a certificate the user accepted
 * once, a browser exception they stored, or a fingerprint they pinned all stop matching with nothing
 * to point at. So the key is created once and kept, and the certificate signed by it is kept beside
 * it, which is the whole difference between "HTTPS with a warning" and "HTTPS the user trusts once".
 *
 * There are two of these, and they differ only in where the private key sits. On a device it is
 * generated inside the platform keystore and cannot be read out of: the app holds a handle it signs
 * with, and the file on disk holds a public certificate and nothing else. On a host JVM there is no
 * keystore, so the key stays in memory and the identity is not persistent, which is stated rather
 * than faked.
 */
abstract class TlsIdentity(internal val certificate: SelfSignedCertificate) {

    val keyPair: KeyPair get() = certificate.keyPair

    /** A context whose key manager serves [keyPair] and [certificate] and nothing else. */
    abstract fun sslContext(): SSLContext

    /** The JVM identity: a PKCS12 entry built in memory and thrown away with the process. */
    class InMemory(certificate: SelfSignedCertificate) : TlsIdentity(certificate) {
        override fun sslContext(): SSLContext {
            val password = randomPassword()
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, password)
            keyStore.setKeyEntry(
                "server",
                certificate.keyPair.private,
                password,
                arrayOf(certificate.certificate)
            )
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagers.init(keyStore, password)
            return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        }

        private companion object {
            /** PKCS12 refuses a password that is not printable ASCII, so this is drawn from one. */
            const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            const val PASSWORD_LENGTH = 32

            fun randomPassword(): CharArray {
                val random = SecureRandom()
                return CharArray(PASSWORD_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
            }
        }
    }

    /** The device identity: the key is a keystore entry and never leaves it. */
    class Keystore(certificate: SelfSignedCertificate) : TlsIdentity(certificate) {
        override fun sslContext(): SSLContext {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null, null)
            keyStore.setEntry(
                KEY_ALIAS,
                KeyStore.PrivateKeyEntry(certificate.keyPair.private, arrayOf(certificate.certificate)),
                null
            )
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagers.init(keyStore, null)
            return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        }
    }

    companion object {
        /** Distinct from [com.mossdial.data.AndroidKeystoreKeyProvider.DEFAULT_ALIAS]. */
        const val KEY_ALIAS = "mossdial.tls.server.v1"

        const val COMMON_NAME = "mossdial.local"
        val DNS_NAMES = listOf("localhost", COMMON_NAME)
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CERTIFICATE_FILE = "server.cer"
        private const val CERTIFICATE_MAX_BYTES = 8 * 1024
        private val lock = ReentrantLock()

        /**
         * The identity for this install: the stored one while it is still usable, a new one otherwise.
         *
         * Everything is under one lock, because a second caller arriving mid-regeneration would
         * otherwise create a second key and overwrite the first, leaving a certificate that matches
         * neither. A stored certificate that is missing, oversized, unreadable, or does not match the
         * key is replaced rather than propagated: a broken identity is recoverable, and a confusing
         * TLS error the user cannot act on is not.
         */
        fun forContext(context: Context): TlsIdentity = lock.withLock {
            val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null, null) }
            val certificateFile = File(context.noBackupFilesDir, CERTIFICATE_FILE)
            val privateKey = keystore.getKey(KEY_ALIAS, null)
            if (privateKey is java.security.PrivateKey) {
                val stored = readCertificate(certificateFile)
                if (stored != null) {
                    val keyPair = KeyPair(readPublicKey(keystore), privateKey)
                    val identity = runCatching { SelfSignedCertificate.fromDer(keyPair, stored) }.getOrNull()
                    if (identity != null) return@withLock Keystore(identity)
                }
            }
            val created = SelfSignedCertificate.create(COMMON_NAME, DNS_NAMES, listOf(LOOPBACK), createKeyPair())
            writeCertificate(certificateFile, created.certificate.encoded)
            Keystore(created)
        }

        /** A throwaway identity for a host JVM, which has nowhere to keep a key. */
        fun inMemory(): TlsIdentity =
            InMemory(SelfSignedCertificate.create(COMMON_NAME, DNS_NAMES, listOf(LOOPBACK), SelfSignedCertificate.generateKeyPair()))

        private fun createKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            return generator.generateKeyPair()
        }

        private fun readPublicKey(keystore: KeyStore) =
            keystore.getCertificate(KEY_ALIAS)?.publicKey
                ?: throw IOException("The keystore holds no certificate for $KEY_ALIAS")

        private fun readCertificate(file: File): ByteArray? {
            if (!file.isFile || file.length() > CERTIFICATE_MAX_BYTES) {
                return null
            }
            return runCatching { file.readBytes() }.getOrNull()
        }

        private fun writeCertificate(file: File, der: ByteArray) {
            val temporary = File(file.parentFile, file.name + ".part")
            runCatching {
                temporary.writeBytes(der)
                if (!temporary.renameTo(file)) {
                    temporary.delete()
                }
            }
        }
    }
}
