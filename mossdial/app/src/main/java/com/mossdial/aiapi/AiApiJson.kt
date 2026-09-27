package com.mossdial.aiapi

import com.mossdial.ai.ChatTurn
import com.mossdial.server.HttpRequestLines

/**
 * The request shapes the API understands and the responses it writes, hand rolled.
 *
 * There is no JSON library in this project and none is being added, so both directions are done
 * here. Reading is a small recursive-descent reader with three bounds: [MAX_DEPTH] levels,
 * [MAX_VALUES] values and [MAX_STRING_CHARS] characters per string. On top of that, the listener
 * has already capped the body, so the reader's own work is bounded by a constant as well.
 *
 * Writing escapes every interpolated value with [escape], which is the only place model output
 * becomes JSON. A reply is the one string in this app that is neither the app's own text nor the
 * user's, so it is escaped rather than trusted: a model that emits a quote, a backslash, a control
 * byte or half of a surrogate pair must still produce a body a client can parse.
 *
 * The reader is strict about the requests it accepts rather than general purpose. A malformed
 * request is refused with [AiApiBadRequest] instead of being guessed at, while unknown members are
 * ignored, so a client that sends more than this understands still works.
 */
object AiApiJson {
    const val MAX_DEPTH = 12
    const val MAX_VALUES = 4_096
    const val MAX_STRING_CHARS = 32 * 1024
    const val MAX_MESSAGES = 64
    const val MAX_EMBEDDING_INPUTS = 16

    /** One embedding run is a full encoder pass, so a batch of inputs has to stay small. */
    const val MAX_EMBEDDING_CHARS = 64 * 1024

    private val UNSUPPORTED_MEMBERS = setOf("tools", "functions", "tool_choice", "function_call")

    /** Parses a chat completion request into the turns the local model is prompted with. */
    fun parseChatRequest(body: ByteArray): AiChatRequest {
        val text = try {
            HttpRequestLines.decode(body)
        } catch (_: java.io.IOException) {
            throw AiApiBadRequest("The request body is not valid UTF-8")
        }
        val root = parse(text) as? JsonValue.Obj
            ?: throw AiApiBadRequest("The request body must be a JSON object")
        UNSUPPORTED_MEMBERS.firstOrNull { root.contains(it) }?.let {
            throw AiApiBadRequest("\"$it\" is not supported by this local API")
        }
        // Clients send "stream": false by default, so only a request for a stream is refused.
        if ((root["stream"] as? JsonValue.Bool)?.value == true) {
            throw AiApiBadRequest("Streaming responses are not supported by this local API")
        }
        val messages = root["messages"] as? JsonValue.Arr
            ?: throw AiApiBadRequest("\"messages\" is required and must be an array")
        if (messages.items.isEmpty()) throw AiApiBadRequest("\"messages\" must hold at least one message")
        if (messages.items.size > MAX_MESSAGES) {
            throw AiApiBadRequest("At most $MAX_MESSAGES messages are accepted in one request")
        }
        val turns = messages.items.map { message ->
            val entry = message as? JsonValue.Obj ?: throw AiApiBadRequest("Every message must be a JSON object")
            ChatTurn(roleOf(entry), contentOf(entry))
        }
        if (turns.all { it.text.isBlank() }) throw AiApiBadRequest("Every message in \"messages\" is empty")
        return AiChatRequest(
            model = root.optionalString("model"),
            messages = turns,
            maxTokens = root.optionalNumber("max_tokens")?.let { tokens ->
                tokens.toIntOrNull()?.takeIf { it > 0 }
                    ?: throw AiApiBadRequest("\"max_tokens\" must be a positive whole number")
            }
        )
    }

    /** Parses an embeddings request into the texts to encode. */
    fun parseEmbeddingRequest(body: ByteArray): AiEmbeddingRequest {
        val text = try {
            HttpRequestLines.decode(body)
        } catch (_: java.io.IOException) {
            throw AiApiBadRequest("The request body is not valid UTF-8")
        }
        val root = parse(text) as? JsonValue.Obj
            ?: throw AiApiBadRequest("The request body must be a JSON object")
        val inputs = when (val input = root["input"]) {
            is JsonValue.Str -> listOf(input.value)
            is JsonValue.Arr -> input.items.map { item ->
                (item as? JsonValue.Str)?.value
                    ?: throw AiApiBadRequest("Every item of \"input\" must be a string")
            }
            else -> throw AiApiBadRequest("\"input\" is required and must be a string or an array of strings")
        }
        if (inputs.isEmpty()) throw AiApiBadRequest("\"input\" must hold at least one string")
        if (inputs.size > MAX_EMBEDDING_INPUTS) {
            throw AiApiBadRequest("At most $MAX_EMBEDDING_INPUTS inputs are embedded in one request")
        }
        if (inputs.any { it.isBlank() }) throw AiApiBadRequest("Every item of \"input\" is empty")
        val total = inputs.sumOf { it.length }
        if (total > MAX_EMBEDDING_CHARS) {
            throw AiApiBadRequest("The inputs hold more than $MAX_EMBEDDING_CHARS characters in total")
        }
        return AiEmbeddingRequest(model = root.optionalString("model"), inputs = inputs)
    }

    private fun roleOf(entry: JsonValue.Obj): ChatTurn.Role {
        val role = entry.stringMember("role") ?: throw AiApiBadRequest("Every message needs a \"role\"")
        return when (role) {
            "user" -> ChatTurn.Role.User
            "assistant" -> ChatTurn.Role.Assistant
            "system" -> ChatTurn.Role.System
            else -> throw AiApiBadRequest("Unsupported role \"$role\"; use system, user or assistant")
        }
    }

    private fun contentOf(entry: JsonValue.Obj): String {
        val content = entry["content"] ?: throw AiApiBadRequest("Every message needs a \"content\"")
        return (content as? JsonValue.Str)?.value
            ?: throw AiApiBadRequest("\"content\" must be a string; this local API does not read content parts")
    }

    /** Parses one JSON document. Throws [AiApiBadRequest] for anything malformed or over a bound. */
    fun parse(text: String): JsonValue = Reader(text).parseDocument()

    /**
     * Escapes [value] for use inside a JSON string, without the surrounding quotes.
     *
     * Control bytes are written as escapes, and a surrogate with no partner is written as
     * `\uXXXX` too: leaving it raw would produce a sequence no UTF-8 encoder can represent, so the
     * body would be corrupt UTF-8 rather than valid JSON with a hole in it.
     */
    fun escape(value: String): String = buildString(value.length + 8) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character == '\b' -> append("\\b")
                character == FORM_FEED -> append("\\f")
                character.code < 0x20 -> appendUnicodeEscape(character.code)
                character.isHighSurrogate() -> {
                    val partner = value.getOrNull(index + 1)
                    if (partner != null && partner.isLowSurrogate()) {
                        append(character).append(partner)
                        index++
                    } else {
                        appendUnicodeEscape(character.code)
                    }
                }
                character.isLowSurrogate() -> appendUnicodeEscape(character.code)
                else -> append(character)
            }
            index++
        }
    }

    fun health(
        status: String,
        model: String?,
        runtimeStatus: String,
        uptimeSeconds: Long,
        embeddingModel: String? = null
    ): String =
        buildString {
            append("{\"status\":").append(quoted(status))
            append(",\"model\":").append(model?.let { quoted(it) } ?: "null")
            append(",\"embedding_model\":").append(embeddingModel?.let { quoted(it) } ?: "null")
            append(",\"runtime\":").append(quoted(runtimeStatus))
            append(",\"uptime_seconds\":").append(uptimeSeconds.coerceAtLeast(0L))
            append('}')
        }

    fun models(models: List<AiApiModel>, created: Long): String = buildString {
        append("{\"object\":\"list\",\"data\":[")
        models.forEachIndexed { index, model ->
            if (index > 0) append(',')
            append("{\"id\":").append(quoted(model.id))
            append(",\"object\":\"model\",\"created\":").append(created)
            append(",\"owned_by\":\"mossdial\",\"loaded\":").append(model.loaded)
            append('}')
        }
        append("]}")
    }

    /**
     * One chat completion. A token count is left out on purpose: this API cannot count tokens for
     * an arbitrary GGUF file without that model's own tokenizer, and an invented count is worse
     * than none.
     */
    fun chatCompletion(id: String, created: Long, model: String, content: String, finishReason: String): String =
        buildString {
            append("{\"id\":").append(quoted(id))
            append(",\"object\":\"chat.completion\",\"created\":").append(created)
            append(",\"model\":").append(quoted(model))
            append(",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":")
            append(quoted(content))
            append("},\"finish_reason\":").append(quoted(finishReason))
            append("}]}")
        }

    fun error(message: String, type: String, code: String): String = buildString {
        append("{\"error\":{\"message\":").append(quoted(message))
        append(",\"type\":").append(quoted(type))
        append(",\"code\":").append(quoted(code))
        append("}}")
    }

    /**
     * One embeddings response.
     *
     * `usage` reports a token count of zero rather than an invented one, for the same reason
     * [chatCompletion] leaves its counts out: this API cannot count tokens for a model it does not
     * have the tokenizer of, and a wrong number is worse than an honest zero. A float is written
     * through [float] so a value can never come out as something a JSON reader would reject.
     */
    fun embeddings(model: String, vectors: List<FloatArray>): String = buildString {
        append("{\"object\":\"list\",\"data\":[")
        vectors.forEachIndexed { index, vector ->
            if (index > 0) append(',')
            append("{\"object\":\"embedding\",\"index\":").append(index)
            append(",\"embedding\":[")
            vector.forEachIndexed { position, value ->
                if (position > 0) append(',')
                append(float(value))
            }
            append("]}")
        }
        append("],\"model\":").append(quoted(model))
        append(",\"usage\":{\"prompt_tokens\":0,\"total_tokens\":0}}")
    }

    /** A JSON number for a float, with a whole value still written as a number. */
    private fun float(value: Float): String {
        if (value.isNaN() || value.isInfinite()) {
            // A model that produced one of these is already broken; writing null keeps the body
            // parseable instead of emitting a bare NaN that no client can read.
            return "0"
        }
        val text = value.toString()
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }

    private fun quoted(value: String): String = "\"" + escape(value) + "\""

    private fun StringBuilder.appendUnicodeEscape(code: Int) {
        append("\\u")
        for (shift in intArrayOf(12, 8, 4, 0)) {
            append(HEX[(code shr shift) and 0xF])
        }
    }

    private const val FORM_FEED = '\u000C'
    private const val HEX = "0123456789abcdef"

    /** The bounded reader. One instance per document; it never looks past [MAX_VALUES] values. */
    private class Reader(private val source: String) {
        private var index = 0
        private var values = 0

        fun parseDocument(): JsonValue {
            val value = readValue(0)
            skipWhitespace()
            if (index != source.length) throw AiApiBadRequest("The request body has trailing content")
            return value
        }

        private fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw AiApiBadRequest("The request body is nested too deeply")
            if (++values > MAX_VALUES) throw AiApiBadRequest("The request body holds too many values")
            skipWhitespace()
            return when (val character = peek()) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> JsonValue.Str(readString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (character == '-' || character in '0'..'9') {
                    readNumber()
                } else {
                    throw AiApiBadRequest("Unexpected character in the request body")
                }
            }
        }

        private fun readObject(depth: Int): JsonValue.Obj {
            expect('{')
            val members = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.Obj(members)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw AiApiBadRequest("A member name must be a string")
                val name = readString()
                if (members.containsKey(name)) throw AiApiBadRequest("Duplicate member \"$name\"")
                skipWhitespace()
                expect(':')
                members[name] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.Obj(members)
                    }
                    else -> throw AiApiBadRequest("A member must be followed by \",\" or \"}\"")
                }
            }
        }

        private fun readArray(depth: Int): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.Arr(items)
                    }
                    else -> throw AiApiBadRequest("An array item must be followed by \",\" or \"]\"")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (index >= source.length) throw AiApiBadRequest("A string is not terminated")
                when (val character = source[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(readEscape())
                    else -> {
                        if (character.code < 0x20) {
                            throw AiApiBadRequest("A raw control character is not allowed in a string")
                        }
                        // Counted as characters are kept, so a string of exactly the limit is allowed
                        // and one more is not.
                        if (builder.length >= MAX_STRING_CHARS) throw AiApiBadRequest("A string is too long")
                        builder.append(character)
                    }
                }
            }
        }

        private fun readEscape(): Char {
            if (index >= source.length) throw AiApiBadRequest("An escape sequence is not terminated")
            return when (val escape = source[index++]) {
                '"', '\\', '/' -> escape
                'b' -> '\b'
                'f' -> FORM_FEED
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> throw AiApiBadRequest("Unsupported escape \\$escape")
            }
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > source.length) throw AiApiBadRequest("A \\u escape is not complete")
            val digits = source.substring(index, index + 4)
            index += 4
            return digits.toIntOrNull(16)?.toChar()
                ?: throw AiApiBadRequest("A \\u escape is not hexadecimal")
        }

        private fun readNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') index++
            readDigits()
            if (index < source.length && source[index] == '.') {
                index++
                readDigits()
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                readDigits()
            }
            return JsonValue.Num(source.substring(start, index))
        }

        private fun readDigits() {
            val start = index
            while (index < source.length && source[index] in '0'..'9') index++
            if (index == start) throw AiApiBadRequest("A number is malformed")
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!source.startsWith(word, index)) throw AiApiBadRequest("Unexpected token in the request body")
            index += word.length
            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isJsonWhitespace()) index++
        }

        private fun peek(): Char {
            if (index >= source.length) throw AiApiBadRequest("The request body ended early")
            return source[index]
        }

        private fun expect(character: Char) {
            if (index >= source.length || source[index] != character) {
                throw AiApiBadRequest("Expected '$character' in the request body")
            }
            index++
        }
    }
}

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

/** The one request shape this API reads. */
data class AiChatRequest(
    val model: String?,
    val messages: List<ChatTurn>,
    val maxTokens: Int?
)

/** A model the API can name, and whether that one is the one currently loaded. */
data class AiApiModel(val id: String, val loaded: Boolean)

/** An embeddings request: the named model, and the texts to encode. */
data class AiEmbeddingRequest(
    val model: String?,
    val inputs: List<String>
)

/** A request the API refuses with 400 because it could not be read. */
class AiApiBadRequest(message: String) : Exception(message)

/** The minimal JSON tree the reader produces. */
sealed class JsonValue {
    object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    /** Numbers keep their literal text, so a whole number never goes through a double. */
    data class Num(val raw: String) : JsonValue() {
        fun toIntOrNull(): Int? {
            raw.toIntOrNull()?.let { return it }
            val decimal = raw.toDoubleOrNull() ?: return null
            if (decimal.isNaN() || decimal.isInfinite() || decimal % 1.0 != 0.0) return null
            if (decimal < Int.MIN_VALUE.toDouble() || decimal > Int.MAX_VALUE.toDouble()) return null
            return decimal.toInt()
        }
    }

    data class Str(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Obj(val members: Map<String, JsonValue>) : JsonValue() {
        operator fun get(name: String): JsonValue? = members[name]

        fun contains(name: String): Boolean = members.containsKey(name)

        fun stringMember(name: String): String? = (members[name] as? Str)?.value

        fun numberMember(name: String): JsonValue.Num? = members[name] as? Num

        /** A member that is absent, or that must be a string when it is there at all. */
        fun optionalString(name: String): String? = when (val member = members[name]) {
            null -> null
            is Str -> member.value
            else -> throw AiApiBadRequest("\"$name\" must be a string")
        }

        /** A member that is absent, or that must be a number when it is there at all. */
        fun optionalNumber(name: String): JsonValue.Num? = when (val member = members[name]) {
            null -> null
            is Num -> member
            else -> throw AiApiBadRequest("\"$name\" must be a number")
        }
    }
}
