package com.mossdial.tunnel

/**
 * Validated settings for a user-provided `cloudflared` executable.
 *
 * Mossdial never bundles a tunnel binary. The user points the app at an executable they
 * already trust, and the app launches it with an explicit argument vector. Because the
 * argument vector is built without a shell, no quoting or escaping rules apply: every
 * value below becomes exactly one argv entry.
 *
 * Nothing in this class is ever written to the log. [token] is treated as a secret and is
 * redacted by [TunnelCommand.describe] and [TunnelStatus.capture].
 */
data class TunnelConfig(
    val executablePath: String,
    val token: String,
    val protocol: String = PROTOCOL_HTTP2
) {
    init {
        validateExecutablePath(executablePath)
        validateToken(token)
        validateProtocol(protocol)
    }

    companion object {
        const val PROTOCOL_HTTP2 = "http2"
        const val PROTOCOL_QUIC = "quic"

        val ALLOWED_PROTOCOLS: List<String> = listOf(PROTOCOL_HTTP2, PROTOCOL_QUIC)

        const val MAX_EXECUTABLE_PATH_LENGTH = 4096
        const val MAX_TOKEN_LENGTH = 4096
        const val MIN_TOKEN_LENGTH = 16

        /**
         * Deliberately narrow. The argument vector is built without a shell, so metacharacters
         * are harmless, but rejecting them turns a confusing runtime failure into an explicit
         * configuration error and keeps a pasted command from being silently treated as a path.
         */
        private const val EXECUTABLE_PATH_ALPHABET = "abcdefghijklmnopqrstuvwxyz" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/._+-"

        fun validateExecutablePath(path: String) {
            require(path.isNotBlank()) { "Enter the path to your cloudflared executable" }
            require(path.length <= MAX_EXECUTABLE_PATH_LENGTH) { "The executable path is too long" }
            require(path.startsWith("/")) { "Use an absolute path, for example /data/local/tmp/cloudflared" }
            path.forEach { character ->
                require(character in EXECUTABLE_PATH_ALPHABET) {
                    "The executable path may only contain letters, digits, / . _ + -"
                }
            }
        }

        /**
         * A Cloudflare tunnel token is a long opaque credential. The check only rejects values
         * that cannot be a token, so that whitespace or control characters never reach argv.
         */
        fun validateToken(token: String) {
            require(token.isNotBlank()) { "Enter your Cloudflare tunnel token" }
            require(token.length >= MIN_TOKEN_LENGTH) { "That token is too short to be a tunnel token" }
            require(token.length <= MAX_TOKEN_LENGTH) { "That token is too long" }
            token.forEach { character ->
                require(character.code in 0x21..0x7e) {
                    "A tunnel token cannot contain spaces or control characters"
                }
            }
        }

        fun validateProtocol(protocol: String) {
            require(protocol in ALLOWED_PROTOCOLS) {
                "Choose a supported transport protocol"
            }
        }
    }
}
