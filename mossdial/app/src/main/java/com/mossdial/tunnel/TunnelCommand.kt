package com.mossdial.tunnel

/**
 * Builds the argument vector handed to `ProcessBuilder`.
 *
 * There is no shell anywhere in this path, so the vector is passed through verbatim: no
 * quoting, no globbing, no variable expansion. Each element is validated by [TunnelConfig]
 * before it can reach [command].
 */
object TunnelCommand {
    private const val REDACTED = "***"

    /** Subcommand sequence, token included. Never log the result of this function. */
    fun arguments(config: TunnelConfig): List<String> = listOf(
        "tunnel",
        "--no-autoupdate",
        "run",
        "--token",
        config.token,
        "--protocol",
        config.protocol
    )

    /** Full command, executable first. Never log the result of this function. */
    fun command(config: TunnelConfig): List<String> =
        listOf(config.executablePath) + arguments(config)

    /**
     * Human-readable form of [command] with the token replaced, safe for the UI and the log.
     * The executable path is user supplied, so it is quoted for readability only.
     */
    fun describe(config: TunnelConfig): String =
        (listOf("\"${config.executablePath}\"") + arguments(config).map { argument ->
            if (argument == config.token) REDACTED else argument
        }).joinToString(" ")
}
