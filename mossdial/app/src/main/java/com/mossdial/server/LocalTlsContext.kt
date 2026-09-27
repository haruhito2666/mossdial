package com.mossdial.server

import java.net.InetAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * The server side of TLS: the socket factory for the listener, over an identity that already exists.
 *
 * There is no key generation here. The key pair and certificate come from [TlsIdentity], which is
 * what keeps them the same across restarts; a context built from a throwaway certificate would defeat
 * the entire point of having one.
 */
class LocalTlsContext private constructor(
    private val context: SSLContext
) {
    fun createServerSocket(bindAddress: String, port: Int, backlog: Int): SSLServerSocket {
        val socket = context.serverSocketFactory.createServerSocket(
            port,
            backlog,
            InetAddress.getByName(bindAddress)
        ) as SSLServerSocket
        socket.useClientMode = false
        socket.needClientAuth = false
        val supported = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (supported.isNotEmpty()) socket.enabledProtocols = supported.toTypedArray()
        return socket
    }

    companion object {
        fun create(identity: TlsIdentity): LocalTlsContext = LocalTlsContext(identity.sslContext())

        /** A context for a host JVM, where there is no keystore to keep a key in. */
        fun inMemory(): LocalTlsContext = create(TlsIdentity.inMemory())
    }
}
