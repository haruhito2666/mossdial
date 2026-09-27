package com.mossdial.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecretBoxTest {
    private lateinit var key: SecretKey
    private lateinit var other: SecretKey

    @Before
    fun setUp() {
        key = newKey()
        other = newKey()
    }

    @Test
    fun roundTripsAPlaintext() {
        val envelope = SecretBox.seal(key, PLAINTEXT, AAD)

        assertArrayEquals(PLAINTEXT, SecretBox.open(key, envelope, AAD))
    }

    @Test
    fun neverRepeatsAnEnvelopeForTheSamePlaintext() {
        val first = SecretBox.seal(key, PLAINTEXT, AAD)
        val second = SecretBox.seal(key, PLAINTEXT, AAD)

        assertFalse(first.contentEquals(second))
        assertArrayEquals(PLAINTEXT, SecretBox.open(key, first, AAD))
        assertArrayEquals(PLAINTEXT, SecretBox.open(key, second, AAD))
    }

    @Test
    fun headerIsIdentifiableAndVersioned() {
        val envelope = SecretBox.seal(key, PLAINTEXT, AAD)

        assertEquals(0x4E, envelope[0].toInt() and 0xFF)
        assertEquals(1, envelope[1].toInt())
        assertEquals(IV_BYTES_EXPECTED, envelope[2].toInt())
        assertEquals(
            SecretBox.HEADER_BYTES + IV_BYTES_EXPECTED + PLAINTEXT.size + SecretBox.TAG_BITS / 8,
            envelope.size
        )
    }

    @Test
    fun rejectsAnEnvelopeSealedForAnotherName() {
        val envelope = SecretBox.seal(key, PLAINTEXT, "other_slot".toByteArray(StandardCharsets.UTF_8))

        assertThrows(SecretStoreException::class.java) {
            SecretBox.open(key, envelope, AAD)
        }
    }

    @Test
    fun rejectsAnEnvelopeSealedWithAnotherKey() {
        val envelope = SecretBox.seal(other, PLAINTEXT, AAD)

        assertThrows(SecretStoreException::class.java) {
            SecretBox.open(key, envelope, AAD)
        }
    }

    @Test
    fun rejectsEverySingleBitFlip() {
        val envelope = SecretBox.seal(key, PLAINTEXT, AAD)

        for (index in envelope.indices) {
            val tampered = envelope.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertThrows("byte $index was not protected", SecretStoreException::class.java) {
                SecretBox.open(key, tampered, AAD)
            }
        }
    }

    @Test
    fun rejectsTruncatedEnvelopes() {
        val envelope = SecretBox.seal(key, PLAINTEXT, AAD)

        for (length in 0 until SecretBox.HEADER_BYTES + SecretBox.TAG_BITS / 8) {
            val truncated = envelope.copyOf(length)
            assertThrows(SecretStoreException::class.java) {
                SecretBox.open(key, truncated, AAD)
            }
        }
    }

    @Test
    fun rejectsAnUnknownMagicOrVersion() {
        val wrongMagic = SecretBox.seal(key, PLAINTEXT, AAD).also {
            it[0] = 0x00
        }
        val wrongVersion = SecretBox.seal(key, PLAINTEXT, AAD).also {
            it[1] = 99
        }
        val wrongIvLength = SecretBox.seal(key, PLAINTEXT, AAD).also {
            it[2] = 0
        }

        assertThrows(SecretStoreException::class.java) { SecretBox.open(key, wrongMagic, AAD) }
        assertThrows(SecretStoreException::class.java) { SecretBox.open(key, wrongVersion, AAD) }
        assertThrows(SecretStoreException::class.java) { SecretBox.open(key, wrongIvLength, AAD) }
    }

    @Test
    fun rejectsPlaintextThatWouldExceedTheEnvelopeLimit() {
        val oversized = ByteArray(SecretBox.MAX_PLAINTEXT_BYTES + 1)

        assertThrows(SecretStoreException::class.java) {
            SecretBox.seal(key, oversized, AAD)
        }
    }

    @Test
    fun tagFailureIsReportedAsAnIntegrityProblem() {
        val envelope = SecretBox.seal(key, PLAINTEXT, AAD)

        val failure = assertThrows(SecretStoreException::class.java) {
            SecretBox.open(key, envelope, "tunnel_token_copy".toByteArray(StandardCharsets.UTF_8))
        }

        assertTrue(failure.cause is AEADBadTagException)
    }

    @Test
    fun emptyPlaintextRoundTrips() {
        val envelope = SecretBox.seal(key, ByteArray(0), AAD)

        assertEquals(0, SecretBox.open(key, envelope, AAD).size)
    }

    private companion object {
        val PLAINTEXT = "eyJhIjoiTESTTOKENVALUE0123456789abcdefghij".toByteArray(StandardCharsets.UTF_8)
        val AAD = "tunnel_token".toByteArray(StandardCharsets.UTF_8)
        const val IV_BYTES_EXPECTED = 12

        fun newKey(): SecretKey = KeyGenerator.getInstance("AES")
            .apply { init(256, SecureRandom()) }
            .generateKey()
    }
}
