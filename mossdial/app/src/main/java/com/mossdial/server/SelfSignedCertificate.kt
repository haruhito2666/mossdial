package com.mossdial.server

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

class SelfSignedCertificate private constructor(
    val keyPair: KeyPair,
    val certificate: X509Certificate
) {
    companion object {
        private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
        private const val OID_COMMON_NAME = "2.5.4.3"
        private const val OID_SUBJECT_ALT_NAME = "2.5.29.17"
        private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
        private const val OID_KEY_USAGE = "2.5.29.15"

        /**
         * Creates a certificate for [keyPair] and signs it with that pair's private key.
         *
         * The key is a parameter rather than something generated here, because on Android it comes
         * from the platform keystore and must never be created in memory: see [TlsIdentity].
         */
        fun create(
            commonName: String,
            dnsNames: List<String>,
            ipAddresses: List<InetAddress>,
            keyPair: KeyPair
        ): SelfSignedCertificate {
            val now = Date()
            val notAfter = Calendar.getInstance().apply {
                time = now
                add(Calendar.YEAR, 10)
            }.time
            val algorithm = DerEncoder.sequence(DerEncoder.oid(OID_SHA256_RSA), DerEncoder.nullValue())
            val name = DerEncoder.sequence(
                DerEncoder.set(
                    DerEncoder.sequence(DerEncoder.oid(OID_COMMON_NAME), DerEncoder.utf8(commonName))
                )
            )
            val subjectAltName = DerEncoder.sequence(
                *dnsNames.map { DerEncoder.value(0x82, it.toByteArray(Charsets.US_ASCII)) }.toTypedArray(),
                *ipAddresses.map { DerEncoder.value(0x87, it.address) }.toTypedArray()
            )
            val extensions = DerEncoder.value(
                0xA3,
                DerEncoder.sequence(
                    DerEncoder.sequence(
                        DerEncoder.oid(OID_BASIC_CONSTRAINTS),
                        DerEncoder.octetString(DerEncoder.sequence())
                    ),
                    DerEncoder.sequence(
                        DerEncoder.oid(OID_KEY_USAGE),
                        DerEncoder.value(0x01, byteArrayOf(0xFF.toByte())),
                        DerEncoder.octetString(DerEncoder.bitString(byteArrayOf(0xA0.toByte()), 5))
                    ),
                    DerEncoder.sequence(
                        DerEncoder.oid(OID_SUBJECT_ALT_NAME),
                        DerEncoder.octetString(subjectAltName)
                    )
                )
            )
            val tbs = DerEncoder.sequence(
                DerEncoder.value(0xA0, DerEncoder.integer(2)),
                DerEncoder.integer(BigInteger(160, SecureRandom()).add(BigInteger.ONE)),
                algorithm,
                name,
                DerEncoder.sequence(DerEncoder.utcTime(now), DerEncoder.utcTime(notAfter)),
                name,
                keyPair.public.encoded,
                extensions
            )
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(keyPair.private)
            signer.update(tbs)
            val certificateBytes = DerEncoder.sequence(tbs, algorithm, DerEncoder.bitString(signer.sign()))
            return fromDer(keyPair, certificateBytes)
        }

        /**
         * Reads a certificate that was produced by [create] earlier, so the same certificate comes
         * back on the next run instead of a new one.
         *
         * A certificate that does not match the key, or is not a certificate at all, is refused: a
         * mismatched pair would fail the handshake with an error no user can act on, so it is
         * better to say so here and fall back to a fresh pair.
         */
        fun fromDer(keyPair: KeyPair, der: ByteArray): SelfSignedCertificate {
            val certificate = try {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            } catch (failure: java.security.cert.CertificateException) {
                throw IllegalArgumentException("The stored certificate could not be read", failure)
            }
            if (!certificate.publicKey.encoded.contentEquals(keyPair.public.encoded)) {
                throw IllegalArgumentException("The stored certificate does not match the stored key")
            }
            return SelfSignedCertificate(keyPair, certificate)
        }

        /** A fresh 2048 bit RSA pair in memory. Used on a plain JVM, never on a device. */
        fun generateKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            return generator.generateKeyPair()
        }
    }
}
