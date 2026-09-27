package com.mossdial.aiapi

import com.mossdial.ai.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The hand-rolled JSON reader and writer, which are the only place a request becomes structured
 * data and the only place model output becomes a body.
 */
class AiApiJsonTest {

    private fun parseRequest(body: String): AiChatRequest =
        AiApiJson.parseChatRequest(body.toByteArray(StandardCharsets.UTF_8))

    private fun refuse(body: String): String {
        val failure = assertThrows(AiApiBadRequest::class.java) { parseRequest(body) }
        return failure.message.orEmpty()
    }

    @Test
    fun readsTheTurnsOfAChatCompletionRequest() {
        val request = parseRequest(
            """
            {"model":"local","max_tokens":64,"stream":false,"messages":[
              {"role":"system","content":"Answer briefly."},
              {"role":"user","content":"Hello \"there\""},
              {"role":"assistant","content":"Hi"},
              {"role":"user","content":"Bye"}
            ]}
            """.trimIndent()
        )

        assertEquals("local", request.model)
        assertEquals(64, request.maxTokens)
        assertEquals(
            listOf(
                ChatTurn(ChatTurn.Role.System, "Answer briefly."),
                ChatTurn(ChatTurn.Role.User, "Hello \"there\""),
                ChatTurn(ChatTurn.Role.Assistant, "Hi"),
                ChatTurn(ChatTurn.Role.User, "Bye")
            ),
            request.messages
        )
    }

    @Test
    fun acceptsARequestWithoutTheOptionalMembers() {
        val request = parseRequest("""{"messages":[{"role":"user","content":"hi"}]}""")

        assertNull(request.model)
        assertNull(request.maxTokens)
        assertEquals(listOf(ChatTurn(ChatTurn.Role.User, "hi")), request.messages)
    }

    @Test
    fun ignoresMembersItDoesNotUnderstand() {
        val request = parseRequest(
            """{"temperature":0.2,"n":1,"user":"someone","messages":[{"role":"user","content":"hi"}]}"""
        )

        assertEquals(listOf(ChatTurn(ChatTurn.Role.User, "hi")), request.messages)
    }

    @Test
    fun decodesEscapesInsideTheBody() {
        val request = parseRequest(
            "{\"messages\":[{\"role\":\"user\",\"content\":\"a\\tb\\nc\\u0041\\/d\"}]}"
        )

        assertEquals("a\tb\ncA/d", request.messages.single().text)
    }

    @Test
    fun refusesARequestThatIsNotAJsonObject() {
        assertTrue(refuse("[]").contains("must be a JSON object"))
        assertTrue(refuse("\"hello\"").contains("must be a JSON object"))
        assertTrue(refuse("").isNotEmpty())
    }

    @Test
    fun refusesMalformedJson() {
        assertTrue(refuse("{\"messages\":[}").isNotEmpty())
        assertTrue(refuse("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}").isNotEmpty())
        assertTrue(refuse("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]} trailing").contains("trailing"))
        assertTrue(refuse("{'messages':[]}").isNotEmpty())
        assertTrue(refuse("{\"messages\":[{\"role\":\"user\",\"content\":\"un\\qterminated\"}]}").isNotEmpty())
        assertTrue(refuse("{\"messages\":[{\"role\":\"user\",\"content\":\"raw\u0001control\"}]}").isNotEmpty())
        assertTrue(refuse("{\"messages\":[{\"role\":\"user\",\"role\":\"user\",\"content\":\"a\"}]}").contains("Duplicate"))
        assertTrue(refuse("{\"messages\":[{\"role\":\"user\",\"content\":\"a\"},]}").isNotEmpty())
    }

    @Test
    fun refusesARequestThatDoesNotDescribeMessages() {
        assertTrue(refuse("{}").contains("messages"))
        assertTrue(refuse("""{"messages":{}}""").contains("messages"))
        assertTrue(refuse("""{"messages":[]}""").contains("at least one"))
        assertTrue(refuse("""{"messages":[{"content":"no role"}]}""").contains("role"))
        assertTrue(refuse("""{"messages":[{"role":"tool","content":"x"}]}""").contains("Unsupported role"))
        assertTrue(refuse("""{"messages":[{"role":"user"}]}""").contains("content"))
        assertTrue(
            refuse("""{"messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}""")
                .contains("content parts")
        )
        assertTrue(refuse("""{"messages":[{"role":"user","content":"   "}]}""").contains("empty"))
        assertTrue(refuse("""{"messages":["hi"]}""").contains("JSON object"))
    }

    @Test
    fun refusesARequestForSomethingTheApiCannotDo() {
        assertTrue(refuse("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""").contains("Streaming"))
        assertTrue(refuse("""{"tool_choice":"auto","messages":[{"role":"user","content":"hi"}]}""").contains("tool_choice"))
        assertTrue(refuse("""{"tools":[],"messages":[{"role":"user","content":"hi"}]}""").contains("tools"))
        assertTrue(
            refuse("""{"functions":[],"messages":[{"role":"user","content":"hi"}]}""").contains("functions")
        )
        assertTrue(refuse("""{"max_tokens":0,"messages":[{"role":"user","content":"hi"}]}""").contains("max_tokens"))
        assertTrue(refuse("""{"max_tokens":-4,"messages":[{"role":"user","content":"hi"}]}""").contains("max_tokens"))
        assertTrue(refuse("""{"max_tokens":"64","messages":[{"role":"user","content":"hi"}]}""").contains("max_tokens"))
        assertTrue(refuse("""{"max_tokens":1.5,"messages":[{"role":"user","content":"hi"}]}""").contains("max_tokens"))
    }

    @Test
    fun refusesARequestThatIsTooLargeInEveryDirection() {
        val tooManyValues = "[" + "1,".repeat(AiApiJson.MAX_VALUES + 1) + "1]"
        assertTrue(refuse("""{"messages":[{"role":"user","content":"hi"}],"extra":$tooManyValues}""").isNotEmpty())

        val tooDeep = "[".repeat(AiApiJson.MAX_DEPTH + 2) + "]".repeat(AiApiJson.MAX_DEPTH + 2)
        assertTrue(refuse("""{"messages":[{"role":"user","content":"hi"}],"deep":$tooDeep}""").isNotEmpty())

        val longString = "x".repeat(AiApiJson.MAX_STRING_CHARS + 1)
        assertTrue(
            refuse("""{"messages":[{"role":"user","content":"$longString"}]}""").isNotEmpty()
        )
        // An escape cannot be used to slip past the same bound.
        val longEscapes = "\\n".repeat(AiApiJson.MAX_STRING_CHARS)
        assertTrue(
            refuse("""{"messages":[{"role":"user","content":"$longEscapes"}]}""").isNotEmpty()
        )

        val tooManyMessages = (0..AiApiJson.MAX_MESSAGES)
            .joinToString(",") { """{"role":"user","content":"hi $it"}""" }
        assertTrue(
            refuse("""{"messages":[$tooManyMessages]}""").contains("At most")
        )
    }

    @Test
    fun refusesABodyThatIsNotUtf8() {
        val failure = assertThrows(AiApiBadRequest::class.java) {
            AiApiJson.parseChatRequest(byteArrayOf(0x7B, 0xFF.toByte(), 0x7D))
        }

        assertEquals("The request body is not valid UTF-8", failure.message)
    }

    @Test
    fun readsTheTreeShapesTheResponsesAreBuiltFrom() {
        val root = AiApiJson.parse("""{"a":[1,-2.5,true,null,"x"],"b":{}}""") as JsonValue.Obj

        val items = (root["a"] as JsonValue.Arr).items
        assertEquals(JsonValue.Num("1"), items[0])
        assertEquals("-2.5", (items[1] as JsonValue.Num).raw)
        assertNull((items[1] as JsonValue.Num).toIntOrNull())
        assertEquals(JsonValue.Bool(true), items[2])
        assertEquals(JsonValue.Null, items[3])
        assertEquals("x", (items[4] as JsonValue.Str).value)
        assertTrue((root["b"] as JsonValue.Obj).members.isEmpty())
    }

    @Test
    fun escapesTheCharactersThatWouldBreakOutOfAJsonString() {
        assertEquals("plain text", AiApiJson.escape("plain text"))
        assertEquals("say \\\"hi\\\"", AiApiJson.escape("say \"hi\""))
        assertEquals("a\\\\b", AiApiJson.escape("a\\b"))
        assertEquals("one\\ntwo\\rthree\\tfour", AiApiJson.escape("one\ntwo\rthree\tfour"))
        assertEquals("\\b\\f", AiApiJson.escape("\b\u000C"))
        assertEquals("\\u0000\\u001f", AiApiJson.escape("\u0000\u001F"))
        assertEquals("é☃", AiApiJson.escape("é☃"))
    }

    @Test
    fun escapesAHalfSurrogatePairSoTheBodyStaysValidUtf8() {
        assertEquals("\\ud83d", AiApiJson.escape("\ud83d"))
        assertEquals("\\udc69", AiApiJson.escape("\udc69"))
        assertEquals("😀", AiApiJson.escape("😀"))
        assertEquals("a\\ud800b", AiApiJson.escape("a\ud800b"))
    }

    @Test
    fun aModelReplySurvivesTheRoundTripThroughTheWire() {
        val reply = buildString {
            append("He said \"stop\"\n")
            append("C:\\path\\to\\file\r\n")
            append("bell:\u0007 tab:\t nul:\u0000 ")
            append("emoji 😀 and a lone \ud83d surrogate")
        }

        val body = AiApiJson.chatCompletion("chatcmpl-abc", 1_700_000_000, "a.gguf", reply, "stop")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val decoded = String(bytes, StandardCharsets.UTF_8)
        val root = AiApiJson.parse(decoded) as JsonValue.Obj
        val choices = (root["choices"] as JsonValue.Arr).items
        val message = (choices.single() as JsonValue.Obj)["message"] as JsonValue.Obj

        assertEquals("chat.completion", root.stringMember("object"))
        assertEquals("a.gguf", root.stringMember("model"))
        assertEquals("stop", (choices.single() as JsonValue.Obj).stringMember("finish_reason"))
        assertEquals(reply, message.stringMember("content"))
        // Nothing that needs escaping survives as a raw byte, so the body decodes to itself.
        assertEquals(body, decoded)
    }

    @Test
    fun writesOnlyEscapesForBytesThatWouldBreakTheBody() {
        val hostile = "\"\\\n\r\t\b\u000C\u0000\u001F"

        val escaped = AiApiJson.escape(hostile)

        assertTrue(escaped.all { it.code in 32..126 || it == '\\' })
        assertEquals("\\\"\\\\\\n\\r\\t\\b\\f\\u0000\\u001f", escaped)
        assertEquals(hostile, (AiApiJson.parse("{\"v\":\"$escaped\"}") as JsonValue.Obj).stringMember("v"))
    }

    @Test
    fun writesTheModelListAndTheHealthDocument() {
        val list = AiApiJson.models(
            listOf(AiApiModel("a.gguf", true), AiApiModel("b \"b\".gguf", false)),
            1_700_000_000
        )
        val parsed = AiApiJson.parse(list) as JsonValue.Obj
        val data = (parsed["data"] as JsonValue.Arr).items.map { it as JsonValue.Obj }

        assertEquals("list", parsed.stringMember("object"))
        assertEquals(listOf("a.gguf", "b \"b\".gguf"), data.map { it.stringMember("id") })
        assertEquals(listOf(true, false), data.map { (it["loaded"] as JsonValue.Bool).value })
        assertEquals(
            emptyList<JsonValue>(),
            ((AiApiJson.parse("""{"data":[]}""") as JsonValue.Obj)["data"] as JsonValue.Arr).items
        )

        val health = AiApiJson.parse(
            AiApiJson.health("ok", "a.gguf", "llama.cpp 0 \"test\"", 12)
        ) as JsonValue.Obj
        assertEquals("ok", health.stringMember("status"))
        assertEquals("a.gguf", health.stringMember("model"))
        assertEquals("llama.cpp 0 \"test\"", health.stringMember("runtime"))
        assertEquals(12, (health["uptime_seconds"] as JsonValue.Num).toIntOrNull())

        val unloaded = AiApiJson.parse(
            AiApiJson.health("ok", null, "nothing loaded", 0)
        ) as JsonValue.Obj
        assertEquals(JsonValue.Null, unloaded["model"])
    }

    @Test
    fun writesAnErrorDocument() {
        val error = AiApiJson.parse(
            AiApiJson.error("token \"required\"", "invalid_request_error", "unauthorized")
        ) as JsonValue.Obj
        val inner = error["error"] as JsonValue.Obj

        assertEquals("token \"required\"", inner.stringMember("message"))
        assertEquals("invalid_request_error", inner.stringMember("type"))
        assertEquals("unauthorized", inner.stringMember("code"))
    }

    @Test
    fun readsTheInputsOfAnEmbeddingsRequest() {
        val single = AiApiJson.parseEmbeddingRequest("""{"model":"local","input":"hello"}""".toByteArray())
        assertEquals("local", single.model)
        assertEquals(listOf("hello"), single.inputs)

        val many = AiApiJson.parseEmbeddingRequest("""{"input":["one","two"]}""".toByteArray())
        assertNull(many.model)
        assertEquals(listOf("one", "two"), many.inputs)
    }

    @Test
    fun refusesAnEmbeddingsRequestItCannotRead() {
        fun refuse(body: String): String {
            val failure = assertThrows(AiApiBadRequest::class.java) {
                AiApiJson.parseEmbeddingRequest(body.toByteArray(StandardCharsets.UTF_8))
            }
            return failure.message.orEmpty()
        }

        assertTrue(refuse("[]").contains("JSON object"))
        assertTrue(refuse("{\"input\":\"\"}").contains("is empty"))
        assertTrue(refuse("""{"input":1}""").contains("string or an array"))
        assertTrue(refuse("""{"input":{}}""").contains("string or an array"))
        assertTrue(refuse("""{"input":[]}""").contains("at least one"))
        assertTrue(refuse("""{"input":" "}""").contains("empty"))
        assertTrue(refuse("""{"input":["ok",3]}""").contains("must be a string"))
        assertTrue(refuse("""{"input":"x","model":7}""").contains("\"model\" must be a string"))
        val overTheStringLimit = "x".repeat(AiApiJson.MAX_STRING_CHARS + 1)
        assertTrue(refuse("""{"input":["$overTheStringLimit"]}""").contains("too long"))
        val third = "x".repeat(AiApiJson.MAX_EMBEDDING_CHARS / 2)
        assertTrue(
            refuse("""{"input":["$third","$third","$third"]}""").contains("characters in total")
        )
        val tooMany = (1..AiApiJson.MAX_EMBEDDING_INPUTS + 1).joinToString(",") { "\"x\"" }
        assertTrue(refuse("""{"input":[$tooMany]}""").contains("At most"))
    }

    @Test
    fun writesAnEmbeddingsDocumentWithReadableNumbers() {
        val body = AiApiJson.parse(
            AiApiJson.embeddings("encoder.onnx", listOf(floatArrayOf(0.5f, -0.25f, 1f, 0f)))
        ) as JsonValue.Obj

        assertEquals("list", body.stringMember("object"))
        assertEquals("encoder.onnx", body.stringMember("model"))
        val entry = (body["data"] as JsonValue.Arr).items.single() as JsonValue.Obj
        assertEquals("embedding", entry.stringMember("object"))
        assertEquals(0, (entry["index"] as JsonValue.Num).toIntOrNull())
        val values = (entry["embedding"] as JsonValue.Arr).items.map { (it as JsonValue.Num).raw }
        assertEquals(listOf("0.5", "-0.25", "1", "0"), values)
        val usage = body["usage"] as JsonValue.Obj
        assertEquals(0, (usage["prompt_tokens"] as JsonValue.Num).toIntOrNull())
        assertEquals(0, (usage["total_tokens"] as JsonValue.Num).toIntOrNull())
    }

    @Test
    fun aVectorOfBrokenNumbersStillProducesAParseableBody() {
        val body = AiApiJson.embeddings("m", listOf(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY)))

        val values = ((AiApiJson.parse(body) as JsonValue.Obj)["data"] as JsonValue.Arr)
            .items.let { (it.single() as JsonValue.Obj)["embedding"] as JsonValue.Arr }.items
        assertEquals(listOf("0", "0"), values.map { (it as JsonValue.Num).raw })
    }

    @Test
    fun healthNamesTheEncoderAndKeepsNullWhenThereIsNone() {
        val named = AiApiJson.parse(
            AiApiJson.health("ok", "a.gguf", "llama.cpp 0", 3, "encoder.onnx")
        ) as JsonValue.Obj
        assertEquals("encoder.onnx", named.stringMember("embedding_model"))

        val empty = AiApiJson.parse(AiApiJson.health("ok", null, "nothing", 0)) as JsonValue.Obj
        assertEquals(JsonValue.Null, empty["embedding_model"])
    }
}
