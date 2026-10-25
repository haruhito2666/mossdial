package com.mossdial.server

import com.mossdial.DeviceTestSupport
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The identity on a real device, where the private key lives in the platform keystore.
 *
 * `TlsIdentity` has two halves and only one of them is reachable from a JVM test: `InMemory`
 * builds a PKCS12 entry in the process and throws it away with it, while `Keystore` generates the
 * key inside AndroidKeyStore, cannot read it back, and keeps the signed certificate beside it. The
 * JVM suite covers the certificate encoding and the TLS context; it cannot cover whether the key
 * is actually unexportable, whether it survives being asked for twice, or whether a handshake
 * works with a key held by the keystore daemon. All three are what the release checklist calls the
 * gate: a certificate that changes between restarts makes a browser exception or a pinned
 * fingerprint useless the next morning, and that failure is invisible on a host JVM.
 */
class TlsIdentityDeviceTest {
    private val context = DeviceTestSupport.context()

    /**
     * The stored certificate, found by listing rather than by name: the file name is an
     * implementation detail of [TlsIdentity], and a test that hardcodes it would keep passing if
     * the file were renamed to something the app no longer reads.
     */
    private val storedCertificate: File?
        get() = context.noBackupFilesDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".cer") }

    @Before
    fun forgetAnyPreviousIdentity() {
        // A key from an earlier run, with the certificate that matched it, is a perfectly valid
        // identity and nothing is wrong with it. It is cleared only so each test starts from the
        // state a fresh install is in, which is what makes the "creates one" assertion meaningful.
        storedCertificate?.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) }
            .let { store ->
                if (store.containsAlias(TlsIdentity.KEY_ALIAS)) store.deleteEntry(TlsIdentity.KEY_ALIAS)
            }
    }

    @Test
    fun createsAUsableIdentityOnFirstUse() {
        val identity = TlsIdentity.forContext(context)

        assertNotNull(identity.keyPair.private)
        assertNotNull(identity.keyPair.public)
        assertNotNull(identity.certificate.certificate)
        assertTrue("the certificate must be stored, not rebuilt on each start", storedCertificate != null)
    }

    /**
     * The whole point of [TlsIdentity], and the one item the release checklist marks as
     * non-optional: ask twice, as two app restarts would, and get the same certificate back.
     *
     * The first call generates a key and signs a certificate for it; the second must find both and
     * reuse them. If this regressed into generating a fresh pair every time, it would fail here
     * rather than in a user's browser the following morning.
     */
    @Test
    fun presentsTheSameCertificateOnASecondRequest() {
        val first = TlsIdentity.forContext(context)
        val second = TlsIdentity.forContext(context)

        assertArrayEquals(
            "the certificate served on the next start must be the one that was stored",
            first.certificate.certificate.encoded,
            second.certificate.certificate.encoded
        )
        assertArrayEquals(
            "the private key must be the same keystore entry rather than a second key",
            first.keyPair.public.encoded,
            second.keyPair.public.encoded
        )
    }

    /**
     * The key material cannot leave the keystore.
     *
     * An `AndroidKeyStore` private key is a handle, and asking it for its encoded form returns null
     * instead of the bytes. That null is the only observable proof the app signs with a key it does
     * not itself hold, so it is asserted rather than assumed.
     */
    @Test
    fun keepsThePrivateKeyInsideTheKeystore() {
        val identity = TlsIdentity.forContext(context)

        assertTrue(
            "a keystore private key must refuse to hand out its encoded form",
            identity.keyPair.private.encoded == null
        )
    }

    @Test
    fun theCertificateIsSelfSignedAndCoversLoopback() {
        val certificate = TlsIdentity.forContext(context).certificate.certificate

        assertEquals(
            "a self-signed certificate must be its own issuer",
            certificate.issuerX500Principal,
            certificate.subjectX500Principal
        )
        val alternativeNames = certificate.subjectAlternativeNames ?: emptyList()
        assertTrue(
            "127.0.0.1 must be in the SAN, or a loopback client has nothing to match against",
            alternativeNames.any { entry -> entry[1] == "127.0.0.1" }
        )
    }

    /**
     * The identity works end to end over a socket.
     *
     * This is the part a JVM test cannot reach: the key manager signs the handshake by calling into
     * the keystore, so a certificate that is structurally sound but signed with a key the daemon
     * will not use for TLS only fails here.
     *
     * Both sides handshake explicitly. `accept()` on an `SSLServerSocket` returns as soon as the TCP
     * connection exists and the TLS handshake only completes when one side reads, so the server side
     * handshakes on its own thread while the client drives it; a server that waited for the client
     * alone would deadlock against itself.
     *
     * Hardware only. The emulator's software keystore completes `Signature` calls but not a TLS
     * server handshake with a keystore key (in-memory identities handshake fine there with the same
     * harness), so this is a gate for real devices, which is exactly where the release checklist
     * says the HTTPS proof belongs.
     */
    @Test
    fun completesARealHandshakeWithTheKeystoreKey() {
        DeviceTestSupport.assumeRealDevice()
        val identity = TlsIdentity.forContext(context)
        val port = DeviceTestSupport.freePort()
        val listener = LocalTlsContext.create(identity)
            .createServerSocket(DeviceTestSupport.LOOPBACK, port, 1)
        listener.use {
            val accepted = CompletableFuture.supplyAsync { it.accept() }
            val client = trustingSocket(port)
            // accept() is typed as Socket because the listener is used through its supertype, but
            // an SSLServerSocket hands back a socket already wrapped for this side of the handshake.
            val serverSide = accepted.get(10, TimeUnit.SECONDS) as SSLSocket
            val serverHandshake = CompletableFuture.runAsync {
                runCatching { serverSide.startHandshake() }
            }
            try {
                client.startHandshake()
                serverHandshake.get(10, TimeUnit.SECONDS)
                val served = client.session.peerCertificates.first() as X509Certificate
                assertArrayEquals(
                    "the certificate presented on the wire must be the stored one",
                    identity.certificate.certificate.encoded,
                    served.encoded
                )
            } finally {
                runCatching { client.close() }
                runCatching { serverSide.close() }
            }
        }
    }

    /**
     * A certificate that no longer matches the key is replaced instead of propagated.
     *
     * [TlsIdentity] states that a broken identity is recoverable while a confusing TLS error the
     * user cannot act on is not, so that recovery branch is exercised: truncate the stored
     * certificate and the next request must come back with a working identity rather than throwing.
     */
    @Test
    fun replacesACertificateThatNoLongerMatchesTheKey() {
        val original = TlsIdentity.forContext(context).certificate.certificate.encoded
        storedCertificate?.writeBytes(original.copyOf(original.size / 2))

        val recovered = TlsIdentity.forContext(context)

        assertNotNull(recovered.certificate.certificate)
        assertTrue(
            "a certificate that cannot be used must be replaced with one that can",
            !recovered.certificate.certificate.encoded.contentEquals(original)
        )
    }

    /** A client that accepts anything, because this certificate is self-signed by design. */
    private fun trustingSocket(port: Int): SSLSocket {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        return context.socketFactory
            .createSocket(DeviceTestSupport.LOOPBACK, port) as SSLSocket
    }

    /**
     * The keystore key can produce every signature TLS asks of it.
     *
     * A key that cannot sign fails the handshake with an error that blames the protocol rather than
     * the key, so the signing is checked directly, by name, where a failure points at its cause.
     */
    @Test
    fun theKeystoreKeyCanSignWhatTlsNeeds() {
        DeviceTestSupport.assumeRealDevice()
        val identity = TlsIdentity.forContext(context)

        listOf(
            "TLS 1.2 signs with PKCS#1" to "SHA256withRSA",
            "TLS 1.3 signs with PSS" to "SHA256withRSAandMGF1"
        ).forEach { (why, algorithm) ->
            val signed = runCatching {
                val signer = java.security.Signature.getInstance(algorithm)
                signer.initSign(identity.keyPair.private)
                signer.update("mossdial".toByteArray())
                signer.sign().size
            }

            assertTrue(
                "$why, and $algorithm could not sign: ${signed.exceptionOrNull()}",
                signed.isSuccess && signed.getOrDefault(0) > 0
            )
        }
    }
}
