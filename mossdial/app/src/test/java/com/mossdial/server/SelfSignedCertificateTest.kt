package com.mossdial.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SelfSignedCertificateTest {
    private val keyPair = SelfSignedCertificate.generateKeyPair()

    private fun create() = SelfSignedCertificate.create(
        commonName = "mossdial.local",
        dnsNames = listOf("localhost"),
        ipAddresses = listOf(InetAddress.getByName("127.0.0.1")),
        keyPair = keyPair
    )

    @Test
    fun createsParseableCertificate() {
        val certificate = create().certificate
        assertEquals("CN=mossdial.local", certificate.subjectX500Principal.name)
        certificate.checkValidity()
        assertTrue(certificate.publicKey.algorithm.contains("RSA"))
    }

    @Test
    fun theStoredCertificateComesBackAsTheSameOne() {
        val created = create()
        val restored = SelfSignedCertificate.fromDer(keyPair, created.certificate.encoded)
        assertArrayEquals(created.certificate.encoded, restored.certificate.encoded)
        assertEquals(created.certificate.serialNumber, restored.certificate.serialNumber)
    }

    @Test
    fun aCertificateForAnotherKeyIsRefused() {
        val created = create()
        val otherKey = SelfSignedCertificate.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            SelfSignedCertificate.fromDer(otherKey, created.certificate.encoded)
        }
    }

    @Test
    fun aFileThatIsNotACertificateIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            SelfSignedCertificate.fromDer(keyPair, "not a certificate".toByteArray())
        }
    }

    @Test
    fun twoPairsAreNotTheSameKey() {
        assertNotEquals(
            keyPair.public.encoded.toList(),
            SelfSignedCertificate.generateKeyPair().public.encoded.toList()
        )
    }

    @Test
    fun anInMemoryIdentityBuildsAUsableContext() {
        val context = LocalTlsContext.create(TlsIdentity.inMemory())
        assertTrue(context.createServerSocket("127.0.0.1", 0, 1).use { it.isBound })
    }
}
