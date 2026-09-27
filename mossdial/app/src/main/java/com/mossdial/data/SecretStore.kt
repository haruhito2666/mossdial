package com.mossdial.data

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKey

/** Storage for small credentials that must never touch disk in the clear. */
interface SecretStore {
    fun contains(name: String): Boolean

    /** Returns the secret, or null when it is absent or no longer recoverable. */
    fun read(name: String): String?

    fun write(name: String, value: String)

    fun clear(name: String)
}

/** Supplies the key that seals and opens a secret. Backed by the Android Keystore in the app. */
fun interface KeyProvider {
    fun key(): SecretKey
}

/**
 * A [SecretStore] that keeps every secret as a [SecretBox] envelope in a private directory.
 *
 * Written from scratch on `javax.crypto` and `java.nio` so the whole path is exercised by JVM
 * tests; only [KeyProvider] needs a device. On disk a secret is an opaque envelope: a
 * compromised file yields nothing without the Keystore key, and a flipped byte fails the GCM
 * tag instead of decrypting to garbage.
 *
 * Recovery is deliberate. If a blob cannot be opened, for example after the key was invalidated
 * by a lock-screen change, the blob is discarded and [read] reports absence, so the app asks for
 * the credential again rather than crashing or silently returning junk.
 */
class EncryptedSecretStore(
    directory: File,
    private val keyProvider: KeyProvider
) : SecretStore {
    private val root: File = directory.also { target ->
        if (!target.exists() && !target.mkdirs()) {
            throw IOException("Unable to create the secure storage directory")
        }
        if (!Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("The secure storage path is not a directory")
        }
    }

    override fun contains(name: String): Boolean = slot(name).isFile

    override fun read(name: String): String? {
        val file = slot(name)
        if (!file.isFile) return null
        if (file.length() < 4 || file.length() > SecretBox.MAX_ENVELOPE_BYTES) {
            discard(file)
            return null
        }
        val envelope = try {
            Files.readAllBytes(file.toPath())
        } catch (_: IOException) {
            return null
        }
        val key = keyProvider.key()
        return try {
            val plaintext = SecretBox.open(key, envelope, aad(name))
            String(plaintext, StandardCharsets.UTF_8)
        } catch (_: SecretStoreException) {
            discard(file)
            null
        }
    }

    override fun write(name: String, value: String) {
        val plaintext = value.toByteArray(StandardCharsets.UTF_8)
        if (plaintext.size > SecretBox.MAX_PLAINTEXT_BYTES) {
            throw SecretStoreException("The secret is too large to store")
        }
        val key = keyProvider.key()
        // The key generates its own IV. Android Keystore refuses a caller-supplied IV when
        // encrypting, and a software key produces an equally random one this way.
        val envelope = SecretBox.seal(key, plaintext, aad(name))
        val file = slot(name)
        val temporary = slot("$name.pending")
        try {
            Files.write(temporary.toPath(), envelope)
            restrictToOwner(temporary)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            restrictToOwner(file)
        } catch (error: IOException) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            throw SecretStoreException("The secret could not be stored", error)
        }
    }

    override fun clear(name: String) {
        discard(slot(name))
    }

    private fun slot(name: String): File {
        require(name.isNotEmpty() && name.length <= 64) { "Invalid secret name" }
        require(name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) {
            "Invalid secret name"
        }
        require(name != "." && name != "..") { "Invalid secret name" }
        return File(root, name)
    }

    private fun aad(name: String): ByteArray = name.toByteArray(StandardCharsets.UTF_8)

    private fun discard(file: File) {
        runCatching { Files.deleteIfExists(file.toPath()) }
    }

    private fun restrictToOwner(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            )
        }
    }
}
