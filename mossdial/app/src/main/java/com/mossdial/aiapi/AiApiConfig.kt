package com.mossdial.aiapi

/**
 * The bounds the local AI API runs under.
 *
 * The port is separate from the web host's port on purpose: the site and the model API are
 * independent listeners, so a model request can never be routed into the file server and a busy
 * inference cannot stall the site. Every limit here is a hard ceiling the listener enforces per
 * connection, so a single client cannot make the process grow without bound.
 */
data class AiApiConfig(
    val port: Int = 8081,
    val bindAddress: String = "127.0.0.1",
    val maxConnections: Int = 8,
    val workerThreads: Int = 2,
    val readTimeoutMillis: Int = 15_000,
    val writeTimeoutMillis: Int = 15_000,
    val generationTimeoutMillis: Long = 120_000,
    val maxRequestLineBytes: Int = 8 * 1024,
    val maxHeaders: Int = 32,
    val maxHeaderBytes: Int = 16 * 1024,
    val maxBodyBytes: Int = 64 * 1024,
    val defaultMaxTokens: Int = 256,
    val rateLimitRequests: Int = 60,
    val rateLimitWindowMillis: Long = 60_000,
    val maxRateLimitClients: Int = 128
) {
    init {
        require(port in 1024..65535)
        require(bindAddress.isNotBlank())
        require(maxConnections in 1..128)
        require(workerThreads in 1..16)
        require(readTimeoutMillis in 1_000..120_000)
        require(writeTimeoutMillis in 1_000..120_000)
        require(generationTimeoutMillis in 1_000..600_000)
        require(maxRequestLineBytes in 256..64 * 1024)
        require(maxHeaders in 1..256)
        require(maxHeaderBytes in 256..256 * 1024)
        require(maxBodyBytes in 1_024..1024 * 1024)
        require(defaultMaxTokens in 1..8_192)
        require(rateLimitRequests in 1..100_000)
        require(rateLimitWindowMillis in 1_000..86_400_000)
        require(maxRateLimitClients in 1..4_096)
    }
}
