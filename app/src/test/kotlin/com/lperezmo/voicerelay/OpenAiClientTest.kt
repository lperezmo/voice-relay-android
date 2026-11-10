package com.lperezmo.voicerelay

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OpenAiClient("sk-test", server.url("/v1").toString().trimEnd('/'), OkHttpClient())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `gpt-transcribe gets keywords and languages`() {
        val fields = OpenAiClient.transcriptionFields("gpt-transcribe", listOf("sherpa-onnx", "Galaxy"), listOf("en", "es"))
        assertEquals(
            listOf("model" to "gpt-transcribe", "keywords[]" to "sherpa-onnx", "keywords[]" to "Galaxy",
                "languages[]" to "en", "languages[]" to "es"),
            fields,
        )
    }

    @Test fun `other models get vocabulary as a prompt and a single language`() {
        val fields = OpenAiClient.transcriptionFields("whisper-1", listOf("Galaxy"), listOf("es"))
        assertEquals(listOf("model" to "whisper-1", "prompt" to "Vocabulary: Galaxy.", "language" to "es"), fields)
        val multi = OpenAiClient.transcriptionFields("gpt-4o-transcribe", emptyList(), listOf("en", "es"))
        assertEquals(listOf("model" to "gpt-4o-transcribe"), multi)
    }

    @Test fun `transcribe returns text and sends auth`() {
        server.enqueue(MockResponse().setBody("""{"text": " Hello there. "}"""))
        val r = client.transcribe(OpenAiClient.Audio.wav(ByteArray(10)), "gpt-transcribe", emptyList(), listOf("en"))
        assertEquals("Hello there.", r.text)
        val req = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req.path)
        assertTrue(req.body.readUtf8().contains("filename=\"audio.wav\""))
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
    }

    @Test fun `m4a audio is sent with its own filename and type`() {
        server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
        client.transcribe(OpenAiClient.Audio.m4a(ByteArray(10)), "gpt-transcribe", emptyList(), emptyList())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("filename=\"audio.m4a\""))
        assertTrue(body.contains("Content-Type: audio/mp4"))
    }

    @Test fun `transcribe retries once without hints on 400`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unknown parameter: keywords"}}"""))
        server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
        val r = client.transcribe(OpenAiClient.Audio.wav(ByteArray(10)), "gpt-transcribe", listOf("x"), listOf("en"))
        assertEquals("ok", r.text)
        server.takeRequest()
        val retry = server.takeRequest().body.readUtf8()
        assertFalse(retry.contains("keywords"))
        assertTrue(retry.contains("gpt-transcribe"))
    }

    @Test fun `auth error surfaces message and code`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided."}}"""))
        val r = client.transcribe(OpenAiClient.Audio.wav(ByteArray(10)), "gpt-transcribe", emptyList(), emptyList())
        assertNull(r.text)
        assertEquals("Incorrect API key provided.", r.error)
        assertEquals(401, r.httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun `cleanup sends reasoning effort and store false`() {
        server.enqueue(MockResponse().setBody("""{"output_text":"Clean."}"""))
        val r = client.cleanup("clean", "gpt-5.6-luna", "Fix it")
        assertEquals("Clean.", r.text)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"effort\":\"none\""))
        assertTrue(body.contains("\"store\":false"))
    }

    @Test fun `cleanup retries without reasoning on 400`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"reasoning not supported"}}"""))
        server.enqueue(MockResponse().setBody("""{"output_text":"Fine."}"""))
        assertEquals("Fine.", client.cleanup("fine", "gpt-4.1", "Fix it").text)
        server.takeRequest()
        assertFalse(server.takeRequest().body.readUtf8().contains("reasoning"))
    }

    @Test fun `respondJson sends a strict schema with low reasoning`() {
        server.enqueue(MockResponse().setBody("""{"output_text":"{\"pronunciations\":[]}"}"""))
        val r = client.respondJson("hey", "gpt-5.6-luna", "Say it", WakeWordAi.SCHEMA_NAME, WakeWordAi.SCHEMA)
        assertEquals("""{"pronunciations":[]}""", r.text)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"json_schema\""))
        assertTrue(body.contains("\"strict\":true"))
        assertTrue(body.contains("\"effort\":\"low\""))
    }

    @Test fun `respondJson drops reasoning, then the schema, on 400s`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"reasoning not supported"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"text.format not supported"}}"""))
        server.enqueue(MockResponse().setBody("""{"output_text":"{}"}"""))
        assertEquals("{}", client.respondJson("hey", "gpt-4", "Say it", "x", WakeWordAi.SCHEMA).text)
        assertTrue(server.takeRequest().body.readUtf8().contains("reasoning"))
        val second = server.takeRequest().body.readUtf8()
        assertFalse(second.contains("reasoning"))
        assertTrue(second.contains("json_schema"))
        val third = server.takeRequest().body.readUtf8()
        assertFalse(third.contains("reasoning"))
        assertFalse(third.contains("json_schema"))
    }

    @Test fun `respondJson reports the first error when every attempt fails`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Model needs verification"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"second"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"third"}}"""))
        val r = client.respondJson("hey", "gpt-5.6-luna", "Say it", "x", WakeWordAi.SCHEMA)
        assertEquals("Model needs verification", r.error)
        assertFalse(r.ok)
    }

    @Test fun `a refusal is an error, not an unknown response`() {
        val json = """{"output":[{"type":"message","content":[{"type":"refusal","refusal":"No."}]}]}"""
        assertEquals("Refused: No.", OpenAiClient.parseResponsesText(json).error)
    }

    @Test fun `parses responses output array when output_text is absent`() {
        val json = """{"output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":" Hi. "}]}]}"""
        assertEquals("Hi.", OpenAiClient.parseResponsesText(json).text)
    }

    @Test fun `parse handles malformed json`() {
        val r = OpenAiClient.parseTranscriptionText("not json")
        assertNull(r.text)
        assertTrue(r.error != null)
    }

    @Test fun `model filters keep only usable models`() {
        val ids = listOf("gpt-transcribe", "gpt-4o-mini-transcribe", "gpt-4o-transcribe-diarize", "gpt-live-transcribe",
            "whisper-1", "gpt-realtime-whisper", "gpt-5.6-luna", "gpt-5-codex", "gpt-4o-mini-tts", "dall-e-3", "gpt-5-search-api")
        assertEquals(listOf("gpt-transcribe", "gpt-4o-mini-transcribe", "whisper-1"), OpenAiClient.transcriptionModels(ids))
        assertEquals(listOf("gpt-5.6-luna"), OpenAiClient.cleanupModels(ids))
    }

    @Test fun `a key with a line break inside is an error, not a crash`() {
        val bad = OpenAiClient("sk-te\nst", server.url("/v1").toString().trimEnd('/'), OkHttpClient())
        val result = bad.transcribe(OpenAiClient.Audio.wav(ByteArray(8)), "gpt-transcribe", emptyList(), emptyList())
        assertNull(result.text)
        assertEquals(OpenAiClient.BAD_KEY, result.error)
        assertNull(bad.listModels())
        assertEquals(0, server.requestCount)
    }
}
