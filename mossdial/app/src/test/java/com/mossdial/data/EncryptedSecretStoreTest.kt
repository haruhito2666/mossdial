package com.mossdial.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Comparator
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class EncryptedSecretStoreTest {
    private lateinit var directory: File
    private lateinit var key: SecretKey
    private lateinit var store: EncryptedSecretStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("mossdial-secrets-").toFile()
        key = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
        store = EncryptedSecretStore(directory, { key })
    }

    @After
    fun tearDown() {
        if (!directory.exists()) return
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    @Test
    fun storesReadsAndClearsASecret() {
        assertFalse(store.contains(SLOT))
        assertNull(store.read(SLOT))

        store.write(SLOT, TOKEN)

        assertTrue(store.contains(SLOT))
        assertEquals(TOKEN, store.read(SLOT))

        store.clear(SLOT)

        assertFalse(store.contains(SLOT))
        assertNull(store.read(SLOT))
    }

    @Test
    fun overwritesAnExistingSecret() {
        store.write(SLOT, TOKEN)
        store.write(SLOT, "second-value-that-is-long-enough")

        assertEquals(
            "a pending write must not be left behind",
            1,
            directory.listFiles()?.size ?: 0
        )
        assertEquals("second-value-that-is-long-enough", store.read(SLOT))
    }

    @Test
    fun neverWritesThePlaintextToDisk() {
        store.write(SLOT, TOKEN)

        val onDisk = String(Files.readAllBytes(File(directory, SLOT).toPath()), StandardCharsets.ISO_8859_1)

        assertFalse(onDisk.contains(TOKEN))
    }

    @Test
    fun keepsTheStoredFileOwnerOnly() {
        store.write(SLOT, TOKEN)

        val permissions = Files.getPosixFilePermissions(File(directory, SLOT).toPath())

        assertEquals(PosixFilePermissions.fromString("rw-------"), permissions)
    }

    @Test
    fun bindsASecretToItsSlot() {
        store.write(SLOT, TOKEN)

        val moved = File(directory, OTHER_SLOT)
        Files.move(File(directory, SLOT).toPath(), moved.toPath())

        assertNull(store.read(OTHER_SLOT))
    }

    @Test
    fun reportsAbsenceWhenTheStoredBlobIsTampered() {
        store.write(SLOT, TOKEN)
        val file = File(directory, SLOT)
        val bytes = Files.readAllBytes(file.toPath())
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x40).toByte()
        Files.write(file.toPath(), bytes)

        assertNull(store.read(SLOT))
        assertFalse("an unrecoverable blob must be discarded", file.exists())
    }

    @Test
    fun reportsAbsenceWhenTheKeyIsLost() {
        store.write(SLOT, TOKEN)
        val rotated = EncryptedSecretStore(directory, { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() })

        assertNull(rotated.read(SLOT))
    }

    @Test
    fun reportsAbsenceWhenTheBlobIsTruncated() {
        store.write(SLOT, TOKEN)
        val file = File(directory, SLOT)
        Files.write(file.toPath(), Files.readAllBytes(file.toPath()).copyOf(2))

        assertNull(store.read(SLOT))
    }

    @Test
    fun refusesSlotNamesThatCouldEscapeTheDirectory() {
        listOf("", "..", ".", "../escape", "nested/slot", "slot with space", "a".repeat(65)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { store.write(name, TOKEN) }
        }
    }

    @Test
    fun refusesASecretLargerThanTheEnvelopeLimit() {
        val oversized = "a".repeat(SecretBox.MAX_PLAINTEXT_BYTES + 1)

        assertThrows(SecretStoreException::class.java) { store.write(SLOT, oversized) }
    }

    @Test
    fun refusesToReadFromASymlinkedDirectory() {
        val outside = Files.createTempDirectory("mossdial-outside-").toFile()
        val linked = File(directory.parentFile, "linked-secrets")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())

        assertThrows(java.io.IOException::class.java) { EncryptedSecretStore(linked, { key }) }
        Files.deleteIfExists(linked.toPath())
    }

    private companion object {
        const val SLOT = "tunnel_token"
        const val OTHER_SLOT = "tunnel_token_copy"
        const val TOKEN = "eyJhIjoiTESTTOKENVALUE0123456789abcdefghij"
    }
}
