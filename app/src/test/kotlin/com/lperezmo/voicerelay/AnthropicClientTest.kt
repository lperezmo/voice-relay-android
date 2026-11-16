package com.lperezmo.voicerelay

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
