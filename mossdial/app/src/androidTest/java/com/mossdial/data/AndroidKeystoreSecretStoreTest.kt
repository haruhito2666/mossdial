package com.mossdial.data

import com.mossdial.DeviceTestSupport
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The secret store with a real Android Keystore key behind it.
 *
 * [EncryptedSecretStoreTest] drives the same envelope format with a software key, which is the
 * right place to test the format. It cannot test the thing the project actually depends on: that
 * `AndroidKeystoreKeyProvider` hands back a key it does not hold, that the key is stable across
 * calls, and that a ciphertext produced on this device cannot be opened by a key generated
 * anywhere else. Those are the properties that make the tunnel token and the AI API token
 * unreadable if the file is lifted off the device.
 */
class AndroidKeystoreSecretStoreTest {
    private val context = DeviceTestSupport.context()
    private val provider = AndroidKeystoreKeyProvider("mossdial.test.secret.v1")

    /**
     * A directory of its own, created once per test.
     *
     * This has to be a fixed field rather than a computed one: a fresh path per access would put
     * the write in one directory and the read in another, which looks exactly like a store that
     * silently discards secrets.
     */
    private lateinit var directory: File

    @Before
    fun createTheDirectory() {
        directory = File(context.filesDir, "android-test-secrets-${System.nanoTime()}")
    }

    @After
    fun removeTheDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun handsBackAKeyItDoesNotHold() {
        val key = provider.key()

        assertEquals("AES", key.algorithm)
        assertNull(
            "a keystore key must refuse to export its material",
            key.encoded
        )
    }

    @Test
    fun handsBackAKeyOfTheSizeItDocuments() {
        // A keystore key will not export its bytes, so the size is read from the key spec the
        // keystore itself exposes. If the spec ever asked for 128 bits, nothing else in the app
        // would notice and the security default would quietly have changed. A symmetric key is
        // described by a SecretKeyFactory; KeyFactory only handles asymmetric ones.
        val key = provider.key()
        val info = SecretKeyFactory.getInstance(key.algorithm, AndroidKeystoreKeyProvider.PROVIDER)
            .getKeySpec(key, android.security.keystore.KeyInfo::class.java)
            as android.security.keystore.KeyInfo

        assertEquals(256, info.keySize)
    }

    @Test
    fun returnsTheSameKeyOnEveryCall() {
        // The store calls key() once per read and once per write. If each call produced a new key,
        // a secret written a moment ago would be undecryptable, which is the failure this guards.
        val first = provider.key()
        val second = provider.key()

        assertTrue("the same keystore alias must resolve to the same key", first == second)
    }

    @Test
    fun roundTripsASecretThroughTheKeystoreKey() {
        val store = EncryptedSecretStore(directory, provider)

        store.write(SLOT, SECRET)

        assertEquals(SECRET, store.read(SLOT))
        assertTrue(store.contains(SLOT))

        store.clear(SLOT)

        assertFalse(store.contains(SLOT))
        assertNull(store.read(SLOT))
    }

    @Test
    fun keepsTheStoredSecretOwnerOnly() {
        val store = EncryptedSecretStore(directory, provider)

        store.write(SLOT, SECRET)

        val permissions = Files.getPosixFilePermissions(File(directory, SLOT).toPath())

        assertEquals(PosixFilePermissions.fromString("rw-------"), permissions)
    }

    /**
     * The envelope is only openable by the keystore key that sealed it.
     *
     * A software key generated in the test cannot decrypt what the device's keystore sealed. This
     * is the property that makes the on-disk file useless to anyone who copies it, so it is checked
     * rather than argued.
     */
    @Test
    fun refusesAnEnvelopeOpenedWithADifferentKey() {
        val store = EncryptedSecretStore(directory, provider)
        val otherDirectory = File(context.filesDir, "wrong-key-${System.nanoTime()}")
        val software: SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        store.write(SLOT, SECRET)
        val onDisk = Files.readAllBytes(File(directory, SLOT).toPath())

        val wrong = EncryptedSecretStore(otherDirectory, { software })
        Files.write(File(otherDirectory, SLOT).toPath(), onDisk)

        assertNull("an envelope sealed by the keystore must not open with another key", wrong.read(SLOT))
        otherDirectory.deleteRecursively()
    }

    /**
     * The tunnel token and the AI API token are separate secrets under separate aliases.
     *
     * `AiApiSettings` documents that the two credentials are deliberately sealed with different
     * keys so lifting one out of its slot cannot yield the other. That is only true if the aliases
     * really are different and really do resolve to different keys, which is a device question.
     */
    @Test
    fun keepsTheApiTokenUnderItsOwnKeyAlias() {
        assertNotEquals(
            "the tunnel token's alias must not be reused for the API token",
            AndroidKeystoreKeyProvider.DEFAULT_ALIAS,
            AiApiSettings.KEY_ALIAS
        )

        val apiKey = AndroidKeystoreKeyProvider(AiApiSettings.KEY_ALIAS).key()

        assertTrue(
            "distinct aliases must resolve to distinct keystore keys",
            apiKey != provider.key()
        )
    }

    @Test
    fun neverWritesTheSecretToDiskInTheClear() {
        val store = EncryptedSecretStore(directory, provider)

        store.write(SLOT, SECRET)

        val onDisk = String(Files.readAllBytes(File(directory, SLOT).toPath()), StandardCharsets.ISO_8859_1)

        assertFalse(onDisk.contains(SECRET))
    }

    private companion object {
        const val SLOT = "android_test_token"
        const val SECRET = "device-keystore-secret-value-0123456789"
    }
}
