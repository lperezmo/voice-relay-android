package com.lperezmo.voicerelay

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AnthropicClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = AnthropicClient("sk-ant-test", server.url("/v1").toString().trimEnd('/'), OkHttpClient())
    }

    @After fun tearDown() = server.shutdown()

    private fun reply(text: String, stop: String = "end_turn") =
        """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"$text"}],""" +
            """"stop_reason":"$stop","usage":{"input_tokens":120,"output_tokens":9}}"""

    @Test fun `cleanup sends the key, the version, the prompt as system, and low effort`() {
        server.enqueue(MockResponse().setBody(reply("Use bun, not npm.")))
        val r = client.cleanup("use bon not npm", "claude-haiku-5-5", "Fix it")
        assertEquals("Use bun, not npm.", r.text)
        assertEquals(120, r.inputTokens)
        assertEquals(9, r.outputTokens)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertEquals(AnthropicClient.API_VERSION, req.getHeader("anthropic-version"))
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("anthropic-beta"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertEquals("Fix it", body.getString("system"))
        assertEquals("use bon not npm", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertFalse(body.has("fallbacks"))
        assertFalse(body.has("temperature"))
    }

    @Test fun `models with server-side fallback opt in to it`() {
        server.enqueue(MockResponse().setBody(reply("Ok.")))
        client.cleanup("ok", "claude-opus-5-5", "Fix it")
        val req = server.takeRequest()
        assertEquals("server-side-fallback-2026-07-01", req.getHeader("anthropic-beta"))
        assertEquals("default", JSONObject(req.body.readUtf8()).getString("fallbacks"))
    }

    @Test fun `a 400 retries once without effort and fallback`() {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"effort not supported"}}"""))
        server.enqueue(MockResponse().setBody(reply("Fine.")))
        assertEquals("Fine.", client.cleanup("fine", "claude-opus-5-5", "Fix it").text)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val retry = server.takeRequest()
        val body = JSONObject(retry.body.readUtf8())
        assertFalse(body.has("output_config"))
        assertFalse(body.has("fallbacks"))
        assertNull(retry.getHeader("anthropic-beta"))
    }

    @Test fun `auth error surfaces message and code, without a retry`() {
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val r = client.cleanup("x", "claude-haiku-5-5", "Fix it")
        assertNull(r.text)
        assertEquals("invalid x-api-key", r.error)
        assertEquals(401, r.httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a refusal or a cut-off answer is a failure, so the raw text is kept`() {
        val refused = AnthropicClient.parseMessageText("""{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal"}}""")
        assertFalse(refused.ok)
        assertEquals("Refused by Claude", refused.error)
        assertFalse(AnthropicClient.parseMessageText(reply("Half a sent", stop = "max_tokens")).ok)
        assertFalse(AnthropicClient.parseMessageText("""{"content":[],"stop_reason":"end_turn"}""").ok)
    }

    @Test fun `after a server-side fallback only the final answer is read`() {
        val json = """{"content":[{"type":"text","text":"partial"},{"type":"fallback","model":"claude-opus-5"},""" +
            """{"type":"text","text":"Final."}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        assertEquals("Final.", AnthropicClient.parseMessageText(json).text)
    }

    @Test fun `text split over several blocks is joined`() {
        val json = """{"content":[{"type":"text","text":"One, "},{"type":"text","text":"two."}],"stop_reason":"end_turn"}"""
        assertEquals("One, two.", AnthropicClient.parseMessageText(json).text)
    }

    @Test fun `the picker offers Claude models only`() {
        assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5"),
            AnthropicClient.cleanupModels(listOf("claude-haiku-5-5", "claude-opus-5-5", "gpt-6-luna")))
    }

    @Test fun `listModels reads ids with the Anthropic headers`() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"claude-opus-5-5"},{"id":"claude-haiku-5-5"}],"has_more":false}"""))
        assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5"), client.listModels())
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/v1/models"))
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
    }

    @Test fun `the picker offers Haiku Sonnet and Opus with Sonnet as the default`() {
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"), AnthropicClient.TIERS.map { it.id })
        assertEquals("claude-sonnet-5-5", AppSettings.DEFAULT_CLAUDE_MODEL)
        assertEquals("Sonnet 5.5 (claude-sonnet-5-5)", AnthropicClient.displayName("claude-sonnet-5-5"))
        assertEquals("claude-fable-5-1", AnthropicClient.displayName("claude-fable-5-1"))
    }

    @Test fun `ask sends history as text turns, then the images and the text in one user turn`() {
        server.enqueue(MockResponse().setBody(reply("Q: And this one?\\nIt is a beaver.")))
        val ink = byteArrayOf(1, 2, 3)
        val lens = byteArrayOf(4, 5, 6, 7)
        val history = listOf(Exchange("What is this?", "A dam."))
        val r = client.ask(listOf(ink, lens), history, "claude-sonnet-5-5", "Answer on the page", "Read image 1")
        assertEquals("Q: And this one?\nIt is a beaver.", r.text)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertEquals(AnthropicClient.API_VERSION, req.getHeader("anthropic-version"))
        assertEquals("server-side-fallback-2026-07-01", req.getHeader("anthropic-beta"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("claude-sonnet-5-5", body.getString("model"))
        assertEquals("Answer on the page", body.getString("system"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        assertTrue(body.getInt("max_tokens") >= 16_000)
        assertFalse(body.has("temperature"))
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("What is this?", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("A dam.", messages.getJSONObject(1).getString("content"))
        val last = messages.getJSONObject(2)
        assertEquals("user", last.getString("role"))
        val content = last.getJSONArray("content")
        assertEquals(3, content.length())
        for ((i, png) in listOf(ink, lens).withIndex()) {
            val block = content.getJSONObject(i)
            assertEquals("image", block.getString("type"))
            val source = block.getJSONObject("source")
            assertEquals("base64", source.getString("type"))
            assertEquals("image/png", source.getString("media_type"))
            assertTrue(png.contentEquals(java.util.Base64.getDecoder().decode(source.getString("data"))))
        }
        assertEquals("text", content.getJSONObject(2).getString("type"))
        assertEquals("Read image 1", content.getJSONObject(2).getString("text"))
    }

    @Test fun `each image is named by its bytes, jpeg or png`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("image/jpeg", AnthropicClient.mediaTypeOf(jpeg))
        assertEquals("image/png", AnthropicClient.mediaTypeOf(png))
        assertEquals("image/png", AnthropicClient.mediaTypeOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        val body = AnthropicClient.askBody(listOf(png, jpeg), emptyList(), "claude-haiku-5-5", "S", "T", null, false)
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image/png", content.getJSONObject(0).getJSONObject("source").getString("media_type"))
        assertEquals("image/jpeg", content.getJSONObject(1).getJSONObject("source").getString("media_type"))
    }

    @Test fun `ask with no history sends a single user turn and skips empty exchanges`() {
        val body = AnthropicClient.askBody(listOf(byteArrayOf(9)), listOf(Exchange("", "x")), "claude-haiku-5-5", "S", "T", null, false)
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals(2, messages.getJSONObject(0).getJSONArray("content").length())
        assertFalse(body.has("output_config"))
        assertFalse(body.has("fallbacks"))
    }

    @Test fun `ask with no images sends a text-only user turn`() {
        val body = AnthropicClient.askBody(emptyList(), listOf(Exchange("Why?", "Because.")), "claude-haiku-5-5", "S", "Typed question: Why not?", null, false)
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        val content = messages.getJSONObject(2).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("Typed question: Why not?", content.getJSONObject(0).getString("text"))

        server.enqueue(MockResponse().setBody(reply("Q: Why not?\\nNo reason.")))
        val r = client.ask(emptyList(), emptyList(), "claude-haiku-5-5", "S", "Typed question: Why not?")
        assertEquals("Q: Why not?\nNo reason.", r.text)
        val sent = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals(1, sent.length())
        assertFalse(sent.getJSONObject(0).getJSONArray("content").toString().contains("\"image\""))
    }

    @Test fun `ask retries a 400 once without effort and fallback, keeping the images`() {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"effort not supported"}}"""))
        server.enqueue(MockResponse().setBody(reply("Q: Hi?\\nHello.")))
        val r = client.ask(listOf(byteArrayOf(1)), emptyList(), "claude-opus-5-5", "S", "T")
        assertEquals("Q: Hi?\nHello.", r.text)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val retry = server.takeRequest()
        val body = JSONObject(retry.body.readUtf8())
        assertFalse(body.has("output_config"))
        assertFalse(body.has("fallbacks"))
        assertNull(retry.getHeader("anthropic-beta"))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image", content.getJSONObject(0).getString("type"))
    }

    @Test fun `ask does not re-send the images for a 400 that is not about effort or fallback`() {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"image exceeds 5 MB maximum"}}"""))
        val r = client.ask(listOf(byteArrayOf(1)), emptyList(), "claude-opus-5-5", "S", "T")
        assertNull(r.text)
        assertEquals(400, r.httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun `ask surfaces API errors with their code and does not retry them`() {
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val auth = client.ask(listOf(byteArrayOf(1)), emptyList(), "claude-haiku-5-5", "S", "T")
        assertNull(auth.text)
        assertEquals("invalid x-api-key", auth.error)
        assertEquals(401, auth.httpCode)
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(529).setBody("overloaded"))
        val busy = client.ask(listOf(byteArrayOf(1)), emptyList(), "claude-haiku-5-5", "S", "T")
        assertFalse(busy.ok)
        assertTrue(busy.error != null)
        assertEquals(529, busy.httpCode)

        server.enqueue(MockResponse().setBody("""{"content":[],"stop_reason":"refusal"}"""))
        assertEquals("Refused by Claude", client.ask(listOf(byteArrayOf(1)), emptyList(), "claude-haiku-5-5", "S", "T").error)
        assertEquals(3, server.requestCount)
    }

    @Test fun `a key with a stray line break fails the call instead of throwing`() {
        val broken = AnthropicClient("sk-ant-te\nst", server.url("/v1").toString().trimEnd('/'), OkHttpClient())
        val result = broken.cleanup("text", "claude-haiku-5-5", "Fix it")
        assertNull(result.text)
        assertTrue(result.error!!.contains("Anthropic API key"))
        assertNull(broken.listModels())
        assertEquals(0, server.requestCount)
    }

    // ---- conversations with tools ----

    private val toolUseJson =
        """{"id":"msg_1","type":"message","role":"assistant","content":[""" +
            """{"type":"thinking","thinking":"","signature":"sig"},""" +
            """{"type":"tool_use","id":"toolu_1","name":"set_timer","input":{"seconds":600,"label":"Pasta"}}],""" +
            """"stop_reason":"tool_use","usage":{"input_tokens":321,"output_tokens":45}}"""

    private fun sampleTools() = JSONArray().put(
        AnthropicClient.tool("get_time", "The time.", JSONObject().put("type", "object").put("properties", JSONObject())),
    )

    private fun sampleMessages() = JSONArray().put(JSONObject().put("role", "user").put("content", "10 minutes"))

    private fun block(type: String, vararg pairs: Pair<String, Any>) =
        JSONObject().put("type", type).apply { pairs.forEach { (k, v) -> put(k, v) } }

    private fun array(vararg blocks: JSONObject) = JSONArray().apply { blocks.forEach { put(it) } }

    @Test fun `toolsBody carries model, tokens, system, tools and messages, and never forces a tool`() {
        val messages = sampleMessages()
        val tools = sampleTools()
        val body = AnthropicClient.toolsBody(messages, "claude-sonnet-5-5", "Sys", tools, "low", true)
        assertEquals("claude-sonnet-5-5", body.getString("model"))
        assertTrue(body.getInt("max_tokens") >= 16_000)
        assertEquals("Sys", body.getString("system"))
        assertEquals(tools.toString(), body.getJSONArray("tools").toString())
        assertEquals(messages.toString(), body.getJSONArray("messages").toString())
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        assertFalse(body.has("tool_choice"))
        assertFalse(body.has("temperature"))

        val plain = AnthropicClient.toolsBody(messages, "claude-haiku-5-5", "Sys", tools, null, false)
        assertFalse(plain.has("output_config"))
        assertFalse(plain.has("fallbacks"))
        assertFalse(plain.has("tool_choice"))
        assertTrue(plain.has("tools"))
    }

    @Test fun `tool has a name, a description and an input schema`() {
        val schema = JSONObject().put("type", "object")
        val tool = AnthropicClient.tool("get_time", "The time.", schema)
        assertEquals("get_time", tool.getString("name"))
        assertEquals("The time.", tool.getString("description"))
        assertEquals(schema.toString(), tool.getJSONObject("input_schema").toString())
        assertEquals(3, tool.length())
    }

    @Test fun `parseTurn keeps the content as sent, the stop reason and the usage`() {
        val turn = AnthropicClient.parseTurn(toolUseJson)
        assertNull(turn.error)
        assertEquals("tool_use", turn.stopReason)
        assertEquals(321, turn.inputTokens)
        assertEquals(45, turn.outputTokens)
        assertEquals(JSONObject(toolUseJson).getJSONArray("content").toString(), turn.content.toString())
        assertEquals("sig", turn.content.getJSONObject(0).getString("signature"))
        val uses = AnthropicClient.toolUses(turn.content)
        assertEquals(1, uses.size)
        assertEquals("toolu_1", uses[0].id)
        assertEquals("set_timer", uses[0].name)
        assertEquals(600, uses[0].input.getInt("seconds"))
        assertEquals("Pasta", uses[0].input.getString("label"))
    }

    @Test fun `parseTurn reads an API error and survives garbage`() {
        val error = AnthropicClient.parseTurn("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        assertEquals("Overloaded", error.error)
        assertEquals(0, error.content.length())

        assertEquals("Unknown response", AnthropicClient.parseTurn("""{"type":"message"}""").error)

        val garbage = AnthropicClient.parseTurn("<html>bad gateway</html>")
        assertNotNull(garbage.error)
        assertEquals(0, garbage.content.length())
        assertNotNull(AnthropicClient.parseTurn("").error)
    }

    @Test fun `turnText and toolUses read only what follows the last fallback block`() {
        val content = array(
            block("text", "text" to "partial"),
            block("tool_use", "id" to "toolu_0", "name" to "set_timer", "input" to JSONObject().put("seconds", 1)),
            block("fallback", "model" to "claude-opus-5"),
            block("thinking", "thinking" to "", "signature" to "sig2"),
            block("text", "text" to " Final "),
            block("text", "text" to "answer. "),
            block("tool_use", "id" to "toolu_1", "name" to "get_time"),
        )
        assertEquals("Final answer.", AnthropicClient.turnText(content))
        val uses = AnthropicClient.toolUses(content)
        assertEquals(listOf("toolu_1"), uses.map { it.id })
        assertEquals(0, uses[0].input.length())

        val plain = array(
            block("thinking", "thinking" to ""),
            block("text", "text" to "One "),
            block("tool_use", "id" to "a", "name" to "x", "input" to JSONObject()),
            block("tool_use", "id" to "b", "name" to "y", "input" to JSONObject()),
        )
        assertEquals("One", AnthropicClient.turnText(plain))
        assertEquals(listOf("a", "b"), AnthropicClient.toolUses(plain).map { it.id })
        assertEquals("", AnthropicClient.turnText(JSONArray()))
        assertTrue(AnthropicClient.toolUses(JSONArray()).isEmpty())
    }

    @Test fun `echoTurn sends the turn back unchanged when no fallback ran`() {
        val content = JSONObject(toolUseJson).getJSONArray("content")
        val echo = AnthropicClient.echoTurn(content)
        assertEquals("assistant", echo.getString("role"))
        assertEquals(content.toString(), echo.getJSONArray("content").toString())
    }

    @Test fun `echoTurn after a fallback drops the declined model's thinking and tool calls`() {
        val content = array(
            block("thinking", "thinking" to "", "signature" to "sig1"),
            block("redacted_thinking", "data" to "xyz"),
            block("text", "text" to "before"),
            block("tool_use", "id" to "toolu_0", "name" to "set_timer", "input" to JSONObject()),
            block("fallback", "model" to "claude-opus-5"),
            block("thinking", "thinking" to "", "signature" to "sig2"),
            block("text", "text" to "after"),
            block("tool_use", "id" to "toolu_1", "name" to "get_time", "input" to JSONObject()),
        )
        val echoed = AnthropicClient.echoTurn(content).getJSONArray("content")
        val expected = array(
            content.getJSONObject(2),
            content.getJSONObject(4),
            content.getJSONObject(5),
            content.getJSONObject(6),
            content.getJSONObject(7),
        )
        assertEquals(expected.toString(), echoed.toString())
    }

    @Test fun `toolResult names the call and marks errors only when asked`() {
        val ok = AnthropicClient.toolResult("toolu_1", "done")
        assertEquals("tool_result", ok.getString("type"))
        assertEquals("toolu_1", ok.getString("tool_use_id"))
        assertEquals("done", ok.getString("content"))
        assertFalse(ok.has("is_error"))

        val bad = AnthropicClient.toolResult("toolu_2", "nope", isError = true)
        assertEquals("toolu_2", bad.getString("tool_use_id"))
        assertEquals("nope", bad.getString("content"))
        assertTrue(bad.getBoolean("is_error"))
    }

    @Test fun `toolTurn sends the key, the version, the tools and the conversation`() {
        server.enqueue(MockResponse().setBody(toolUseJson))
        val messages = sampleMessages()
        val tools = sampleTools()
        val turn = client.toolTurn(messages, "claude-opus-5-5", "Sys", tools)
        assertNull(turn.error)
        assertEquals("tool_use", turn.stopReason)
        assertEquals(321, turn.inputTokens)
        assertEquals("toolu_1", AnthropicClient.toolUses(turn.content).single().id)

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertEquals(AnthropicClient.API_VERSION, req.getHeader("anthropic-version"))
        assertEquals("server-side-fallback-2026-07-01", req.getHeader("anthropic-beta"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("Sys", body.getString("system"))
        assertEquals(tools.toString(), body.getJSONArray("tools").toString())
        assertEquals(messages.toString(), body.getJSONArray("messages").toString())
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        assertFalse(body.has("tool_choice"))
    }

    @Test fun `toolTurn does not opt Haiku in to the fallback`() {
        server.enqueue(MockResponse().setBody(toolUseJson))
        client.toolTurn(sampleMessages(), "claude-haiku-5-5", "Sys", sampleTools())
        val req = server.takeRequest()
        assertNull(req.getHeader("anthropic-beta"))
        val body = JSONObject(req.body.readUtf8())
        assertFalse(body.has("fallbacks"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
    }

    @Test fun `toolTurn retries a 400 about effort once without effort and fallback`() {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"output_config.effort: not supported"}}"""))
        server.enqueue(MockResponse().setBody(toolUseJson))
        val turn = client.toolTurn(sampleMessages(), "claude-opus-5-5", "Sys", sampleTools())
        assertNull(turn.error)
        assertEquals("tool_use", turn.stopReason)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val retry = server.takeRequest()
        assertNull(retry.getHeader("anthropic-beta"))
        val body = JSONObject(retry.body.readUtf8())
        assertFalse(body.has("output_config"))
        assertFalse(body.has("fallbacks"))
        assertTrue(body.has("tools"))
        assertEquals(1, body.getJSONArray("messages").length())
    }

    @Test fun `toolTurn does not retry a 400 about something else`() {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"messages: tool_result block missing"}}"""))
        val turn = client.toolTurn(sampleMessages(), "claude-opus-5-5", "Sys", sampleTools())
        assertEquals(400, turn.httpCode)
        assertEquals("messages: tool_result block missing", turn.error)
        assertEquals(1, server.requestCount)
    }

    @Test fun `toolTurn surfaces a 401 without a retry`() {
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val turn = client.toolTurn(sampleMessages(), "claude-haiku-5-5", "Sys", sampleTools())
        assertEquals("invalid x-api-key", turn.error)
        assertEquals(401, turn.httpCode)
        assertEquals(0, turn.content.length())
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(529).setBody("overloaded"))
        val busy = client.toolTurn(sampleMessages(), "claude-haiku-5-5", "Sys", sampleTools())
        assertEquals("HTTP 529", busy.error)
        assertEquals(529, busy.httpCode)
    }

    @Test fun `toolTurn with a dropped connection has no HTTP code`() {
        val noRetry = AnthropicClient(
            "sk-ant-test", server.url("/v1").toString().trimEnd('/'),
            OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val turn = noRetry.toolTurn(sampleMessages(), "claude-haiku-5-5", "Sys", sampleTools())
        assertNotNull(turn.error)
        assertEquals(0, turn.httpCode)
        assertEquals(0, turn.content.length())
    }

    @Test fun `toolTurn with a broken key fails instead of throwing`() {
        val broken = AnthropicClient("sk-ant-te\nst", server.url("/v1").toString().trimEnd('/'), OkHttpClient())
        val turn = broken.toolTurn(sampleMessages(), "claude-haiku-5-5", "Sys", sampleTools())
        assertTrue(turn.error!!.contains("Anthropic API key"))
        assertEquals(0, turn.httpCode)
        assertEquals(0, server.requestCount)
    }
}
