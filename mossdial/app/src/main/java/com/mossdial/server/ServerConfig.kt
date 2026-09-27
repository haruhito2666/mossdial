package com.mossdial.server

data class ServerConfig(
    val port: Int = 8080,
    val bindAddress: String = "127.0.0.0",
    val maxConnections: Int = 32,
    val workerThreads: Int = 4,
    val requestTimeoutMillis: Int = 15_000,
    val enableLogging: Boolean = true,
    val enableTls: Boolean = false,
    val rateLimitRequests: Int = 300,
    val rateLimitWindowMillis: Long = 60_000,
    val maxRateLimitClients: Int = 512,
    val requestLogCapacity: Int = 256
) {
    init {
        require(port in 1024..65535)
        require(bindAddress.isNotBlank())
        require(maxConnections in 1..512)
        require(workerThreads in 1..64)
        require(requestTimeoutMillis in 1_000..120_000)
        require(rateLimitRequests in 1..100_000)
        require(rateLimitWindowMillis in 1_000..86_400_000)
        require(maxRateLimitClients in 1..4_096)
        require(requestLogCapacity in 1..4_096)
    }
}
