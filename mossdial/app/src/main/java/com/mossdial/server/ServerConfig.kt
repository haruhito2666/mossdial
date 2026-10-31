package com.mossdial.server

data class ServerConfig(
    val port: Int = 8080,
    val bindAddress: String = "127.0.0.1",
    val maxConnections: Int = 32,
    val workerThreads: Int = 4,
    val requestTimeoutMillis: Int = 15_000,
    val enableLogging: Boolean = true,
    val enableTls: Boolean = false,
    val rateLimitRequests: Int = 300,
    val rateLimitWindowMillis: Long = 60_000,
    val maxRateLimitClients: Int = 512,
    val requestLogCapacity: Int = 256,
    /**
     * How long a response write may make no progress before the socket is closed.
     *
     * Java has no write timeout, so a client that requests a large file and stops reading can
     * otherwise pin a worker in `write` forever. This is reset by every successful chunk, not a
     * cap on a slow download's total duration.
     */
    val writeTimeoutMillis: Int = 30_000,
    /**
     * Whether an extensionless route that has no file of its own falls back to the root
     * `index.html`, so a client-side React/Vue/Svelte router can own its URLs.
     *
     * Clean URLs (`/about` -> `about.html` or `about/index.html`) are always tried first; this is
     * only the last resort for a single-page app whose routes exist only in JavaScript.
     */
    val spaFallback: Boolean = true,
    /**
     * The prefix a static build expects, for example `repo` for a GitHub Pages project served at
     * `/repo/`.
     *
     * It is stripped before a path is resolved, so a build that hard-coded its repository name
     * still finds its assets when the same folder is hosted at the root. Empty means no prefix.
     */
    val siteBasePath: String = "",
    /**
     * The loopback port a local HTTP backend listens on, or 0 when the listener serves files only.
     *
     * When this is non-zero every request is proxied to it, which is how a Next.js, Node, Bun,
     * Deno, Python or PHP runtime can be front-ended without turning this app into a process
     * manager. The port is the user's choice; the runtime stays their process.
     */
    val backendPort: Int = 0,
    /** The largest request body the proxy will relay, so an upload cannot exhaust the phone. */
    val backendMaxBodyBytes: Long = ReverseProxy.MAX_BODY_BYTES
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
        require(writeTimeoutMillis in 1_000..120_000)
        require(siteBasePath.none { it == '\\' || it.isISOControl() })
        require(siteBasePath.split('/').none { it == "." || it == ".." })
        require(backendPort in 0..65535)
        require(backendMaxBodyBytes in 1..256L * 1024 * 1024)
    }
}
