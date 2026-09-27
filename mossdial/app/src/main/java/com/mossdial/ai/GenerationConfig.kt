package com.mossdial.ai

/**
 * Knobs for a GGUF load and for one generation call.
 *
 * [contextSize] is capped by the context the model was trained with, and [maxTokens]
 * is an upper bound: the native bridge keeps at least half of the context for the
 * prompt.
 */
data class GenerationConfig(
    val contextSize: Int = 2048,
    val threads: Int = 4,
    val batchSize: Int = 512,
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val seed: Int = 0,
    val stopStrings: List<String> = listOf("\nUser:", "\nAssistant:")
)

/** One turn in the on-device chat. */
data class ChatTurn(val role: Role, val text: String) {
    enum class Role { User, Assistant, System }
}

/**
 * Formats the prompt for one generation call.
 *
 * The native bridge keeps no KV cache between calls, so the whole conversation is
 * re-sent every turn. Models carry their own chat template in the GGUF metadata,
 * but reading it would need the upstream template engine, so a fixed plain
 * transcript is used instead: good enough for instruct-tuned GGUF chat, and honest
 * about not being a general template renderer.
 */
object ChatPrompt {

    fun format(turns: List<ChatTurn>): String {
        val builder = StringBuilder()
        for (turn in turns) {
            val text = turn.text.trim()
            if (text.isEmpty()) {
                continue
            }
            when (turn.role) {
                ChatTurn.Role.User -> builder.append("User: ").append(text).append('\n')
                ChatTurn.Role.Assistant -> builder.append("Assistant: ").append(text).append("\n\n")
                ChatTurn.Role.System -> builder.append("System: ").append(text).append('\n')
            }
        }
        builder.append("Assistant:")
        return builder.toString()
    }
}
