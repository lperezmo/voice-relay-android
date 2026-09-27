package com.lperezmo.voicerelay

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class LiveTranscriptionTest {
    private val server = MockWebServer()
    private val received: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())

    @After
    fun tearDown() = server.shutdown()

    /** A fake Realtime server: acknowledges the commit and returns [transcript] (or an [error]). */
    private fun fakeServer(transcript: String?, error: String? = null) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = JSONObject(text)
                received += event
                if (event.getString("type") != "input_audio_buffer.commit") return
                if (error != null) {
                    webSocket.send("""{"type":"error","error":{"message":"$error"}}""")
                    return
                }
                webSocket.send("""{"type":"input_audio_buffer.committed","item_id":"item_1"}""")
                webSocket.send("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_1","delta":"Hel"}""")
                webSocket.send(JSONObject().put("type", "conversation.item.input_audio_transcription.completed")
                    .put("item_id", "item_1").put("transcript", transcript).toString())
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }))
        server.start()
    }

    private fun session() = LiveTranscription(
        "sk-test", "gpt-live-transcribe", listOf("Pendleton"), listOf("en", "es"),
        http = OkHttpClient(), url = server.url("/v1/realtime").toString(),
    )

    @Test
    fun `streams audio, commits, and returns the final transcript`() {
        fakeServer("Hello there.")
        val live = session().apply { start() }
        live.append(ByteArray(3200), 3200) // 100 ms at 16 kHz
        val result = live.finish(5000)
        assertEquals("Hello there.", result.text)
        assertNull(result.error)
        val types = received.map { it.getString("type") }
        assertEquals("session.update", types.first())
        assertTrue("input_audio_buffer.append" in types)
        assertEquals("input_audio_buffer.commit", types.last())
    }

    @Test
    fun `server error is reported so the caller can fall back`() {
        fakeServer(null, error = "bad model")
        val live = session().apply { start() }
        live.append(ByteArray(3200), 3200)
        val result = live.finish(5000)
        assertEquals("bad model", result.error)
    }

    @Test
    fun `session update carries model, hints, format and no turn detection`() {
        val input = LiveTranscription.sessionUpdate("gpt-live-transcribe", listOf("Ma\nria", "OK"), listOf("en"))
            .getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertEquals(24000, input.getJSONObject("format").getInt("rate"))
        assertTrue(input.isNull("turn_detection"))
        val t = input.getJSONObject("transcription")
        assertEquals("gpt-live-transcribe", t.getString("model"))
        assertEquals("Ma ria", t.getJSONArray("keywords").getString(0))
        assertEquals("minimal", t.getString("delay"))
    }

    @Test
    fun `resampler turns 16 kHz into 24 kHz across chunk boundaries`() {
        val r = LiveTranscription.Resampler(16000, 24000)
        var out = 0
        repeat(10) { out += r.process(ByteArray(3200)).size } // ten 100 ms chunks
        // 1 s in -> about 1 s out (24000 samples, 48000 bytes), give or take a sample.
        assertTrue("got $out", out in 47_990..48_004)
    }

    @Test
    fun `resampler keeps a constant signal constant`() {
        val r = LiveTranscription.Resampler(16000, 24000)
        val chunk = ByteArray(200) { if (it % 2 == 0) 0x10 else 0x02 } // 0x0210 = 528
        val out = r.process(chunk) + r.process(chunk)
        for (i in out.indices step 2) {
            assertEquals(528, (out[i].toInt() and 0xFF) or (out[i + 1].toInt() shl 8))
        }
    }
}
