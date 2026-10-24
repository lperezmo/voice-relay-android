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
}
