package com.mossdial.tunnel

/** Outcome of turning stored tunnel preferences into something runnable. */
sealed class TunnelResolution {
    data class Ready(val config: TunnelConfig) : TunnelResolution()

    data class Invalid(val reason: String) : TunnelResolution()
}

/**
 * Validates stored preferences into a runnable [TunnelConfig].
 *
 * Kept free of Android types so the whole decision, including which reason a user sees for an
 * incomplete setup, is covered by JVM tests.
 */
object TunnelResolver {
    fun resolve(executablePath: String, token: String?, protocol: String): TunnelResolution {
        if (executablePath.isBlank()) {
            return TunnelResolution.Invalid("Enter the path to your cloudflared executable")
        }
        if (token.isNullOrBlank()) {
            return TunnelResolution.Invalid("Save a Cloudflare tunnel token first")
        }
        return try {
            TunnelResolution.Ready(TunnelConfig(executablePath, token, protocol))
        } catch (error: IllegalArgumentException) {
            TunnelResolution.Invalid(error.message ?: "Tunnel settings are incomplete")
        }
    }
}
