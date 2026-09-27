package com.mossdial.tunnel

/** Coarse lifecycle phase. Deliberately does not describe the tunnel's state in detail. */
enum class TunnelPhase {
    Stopped,
    Starting,
    Running,
    Failed
}

data class TunnelStatus(
    val phase: TunnelPhase,
    val detail: String = "",
    val diagnostic: String = ""
) {
    val isActive: Boolean
        get() = phase == TunnelPhase.Starting || phase == TunnelPhase.Running

    companion object {
        val STOPPED = TunnelStatus(TunnelPhase.Stopped)

        fun failed(detail: String, diagnostic: String = "") =
            TunnelStatus(TunnelPhase.Failed, detail, diagnostic = diagnostic)
    }
}

/**
 * Bounded, redacted, single-line capture of whatever the tunnel process prints.
 *
 * The app keeps a short tail of process output for diagnostics only. Three limits apply to
 * everything that is retained:
 *
 *  - bounded length, so a chatty process cannot grow memory or the status line;
 *  - the tunnel token is replaced with `***` in every captured fragment;
 *  - control characters are folded to single spaces, so a fragment can never forge a
 *    multi-line log entry or a terminal escape sequence.
 *
 * The phase itself stays generic; [TunnelStatus.detail] is a fixed sentence per transition.
 */
object TunnelStatusCapture {
    const val MAX_CAPTURED_CHARS = 200
    const val MAX_KEPT_CHARS = 2 * MAX_CAPTURED_CHARS
    const val MAX_LINE_CHARS = 8192
    const val REDACTED = "***"
    const val CLIP_MARKER = "…"

    /**
     * Normalises one raw chunk and returns the new bounded tail, or null when the chunk
     * carries no displayable content. [secrets] is removed from the whole retained tail, not
     * just from the new chunk, so a token split across two captures is still redacted.
     */
    fun append(previous: String, raw: CharSequence, secrets: List<String>): String? {
        val flattened = flatten(raw)
        if (flattened.isEmpty()) return null
        val combined = redact(previous + flattened, secrets)
        return if (combined.length <= MAX_KEPT_CHARS) {
            combined
        } else {
            combined.substring(combined.length - MAX_KEPT_CHARS)
        }
    }

    /**
     * The retained tail as a single clipped line. Control characters are folded and [secrets]
     * scrubbed again here, so the value is safe to display no matter what reached it.
     */
    fun line(tail: String, secrets: List<String> = emptyList()): String {
        val collapsed = redact(flatten(tail), secrets).trim()
        return if (collapsed.length <= MAX_CAPTURED_CHARS) {
            collapsed
        } else {
            collapsed.take(MAX_CAPTURED_CHARS - 1) + CLIP_MARKER
        }
    }

    /** Folds control characters to spaces so a fragment can never forge a second line. */
    private fun flatten(raw: CharSequence): String {
        val folded = buildString(raw.length) {
            raw.forEach { character ->
                append(if (character.isWhitespace() || character.isISOControl()) ' ' else character)
            }
        }
        val collapsed = folded.replace(WHITESPACE_RUN, " ")
        return if (collapsed.isBlank()) "" else collapsed
    }

    private fun redact(text: String, secrets: List<String>): String {
        var result = text
        secrets.forEach { secret ->
            if (secret.isNotEmpty()) {
                result = result.replace(secret, REDACTED)
            }
        }
        return result
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
