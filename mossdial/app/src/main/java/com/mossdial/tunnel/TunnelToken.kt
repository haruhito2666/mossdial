package com.mossdial.tunnel

/**
 * Reads the token out of what a person actually pastes.
 *
 * Cloudflare shows a full connector command, not a bare token:
 *
 * ```
 * cloudflared tunnel --no-autoupdate run --token eyJ...
 * ```
 *
 * A token field that only accepts the last word makes that command look invalid. This extracts the
 * value after `--token`, or accepts a bare token unchanged.
 */
object TunnelToken {
    private const val FLAG = "--token"
    private const val SERVICE_INSTALL = "service install"

    fun extract(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val flagIndex = trimmed.indexOf(FLAG)
        if (flagIndex >= 0) {
            val afterFlag = trimmed.substring(flagIndex + FLAG.length)
            val afterSeparator = when {
                afterFlag.startsWith("=") -> afterFlag.substring(1)
                afterFlag.startsWith(" ") || afterFlag.startsWith("\t") -> afterFlag.trimStart()
                else -> return null
            }
            val token = afterSeparator.substringBefore(' ')
                .substringBefore('\t')
                .trim()
                .trim('"', '\'')
            return token.takeIf { it.isNotEmpty() }
        }
        // The Windows dashboard command is `cloudflared.exe service install <token>`.
        val installIndex = trimmed.indexOf(SERVICE_INSTALL, ignoreCase = true)
        if (installIndex >= 0) {
            val token = trimmed.substring(installIndex + SERVICE_INSTALL.length)
                .trimStart()
                .substringBefore(' ')
                .substringBefore('\t')
                .trim()
                .trim('"', '\'')
            return token.takeIf { it.isNotEmpty() }
        }
        val token = trimmed.trim('"', '\'').trim()
        return token.takeIf { it.isNotEmpty() && token.none(Char::isWhitespace) }
    }
}
