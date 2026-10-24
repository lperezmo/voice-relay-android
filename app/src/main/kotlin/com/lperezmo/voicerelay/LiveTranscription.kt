package com.lperezmo.voicerelay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * Live mode: streams the microphone to OpenAI's Realtime transcription API
 * while the user is still talking, so the transcript is nearly finished by the
 * time they tap stop. One session per dictation:
 *
 *   start() at record start -> append() for every mic chunk -> finish() at stop
 *
 * Turn detection is off: the whole dictation is one turn, committed at stop.
 * Any failure makes [finish] return an error, and the caller falls back to the
 * standard upload with the same recording, so live mode can never lose a dictation.
 */
class LiveTranscription(
    private val apiKey: String,
    private val model: String,
    private val vocabulary: List<String>,
    private val languages: List<String>,
    private val http: OkHttpClient = OpenAiClient.sharedHttp,
    private val url: String = URL,
    private val log: (String) -> Unit = {},
) {
    /** Receives transcript pieces as they are recognized, on the socket thread. */
    @Volatile var onDelta: ((String) -> Unit)? = null
    private var socket: WebSocket? = null
    private val resampler = Resampler(Recorder.SAMPLE_RATE, API_RATE)
    private val pending = ByteArrayOutputStream()
    private val done = CountDownLatch(1)
    private val lock = Any()

    // Guarded by [lock].
    private var error: String? = null
    private var committing = false
    private val committedItems = mutableListOf<String>()
    private val transcripts = mutableMapOf<String, String>()
    private var connectedAt = 0L
    private val startedAt = System.currentTimeMillis()

    fun start() {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
        // OkHttp queues sends until the socket opens, so audio captured while connecting is kept.
        socket = http.newWebSocket(request, Listener()).also { it.send(sessionUpdate(model, vocabulary, languages).toString()) }
    }

    /** Mic PCM (16 kHz mono PCM16). Called on the capture thread. */
    fun append(chunk: ByteArray, length: Int) {
        pending.write(resampler.process(chunk, length))
        if (pending.size() >= SEND_BYTES) flush()
    }

    /**
     * Commits the dictation and waits for the final transcript. Call after the
     * recorder has stopped. Blocking; call off the main thread.
     */
    fun finish(timeoutMs: Long = FINISH_TIMEOUT_MS): OpenAiClient.Result {
        val ws = socket ?: return OpenAiClient.Result(null, "Live session not started")
        flush()
        synchronized(lock) {
            error?.let { ws.cancel(); return OpenAiClient.Result(null, it) }
            committing = true
        }
        ws.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
        val completed = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ws.close(1000, null)) ws.cancel()
        synchronized(lock) {
            error?.let { return OpenAiClient.Result(null, it) }
            if (!completed) return OpenAiClient.Result(null, "Live transcript timed out")
            // Empty text with no error is "no speech", like the standard endpoint.
            return OpenAiClient.Result(committedItems.joinToString(" ") { transcripts[it].orEmpty() }.trim(), null)
        }
    }

    /** Drops the session without a transcript (recording too short, discarded). */
    fun cancel() {
        socket?.cancel()
    }

    private fun flush() {
        if (pending.size() == 0) return
        val audio = Base64.getEncoder().encodeToString(pending.toByteArray())
        pending.reset()
        socket?.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", audio).toString())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connectedAt = System.currentTimeMillis()
            log("Live: connected in ${connectedAt - startedAt} ms")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = try { JSONObject(text) } catch (_: Exception) { return }
            synchronized(lock) {
                when (event.optString("type")) {
                    "error" -> fail(event.optJSONObject("error")?.optString("message") ?: "Live transcription error")
                    "input_audio_buffer.committed" -> committedItems += event.optString("item_id")
                    "conversation.item.input_audio_transcription.delta" ->
                        event.optString("delta").takeIf { it.isNotEmpty() }?.let { onDelta?.invoke(it) }
                    "conversation.item.input_audio_transcription.completed" ->
                        transcripts[event.optString("item_id")] = event.optString("transcript")
                    "conversation.item.input_audio_transcription.failed" ->
                        fail(event.optJSONObject("error")?.optString("message") ?: "Live transcription failed")
                }
                if (committing && committedItems.isNotEmpty() && committedItems.all { it in transcripts }) done.countDown()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) { fail(response?.let { "Live connection failed (HTTP ${it.code})" } ?: (t.message ?: "Live connection failed")) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) { if (committing && done.count > 0) fail("Live connection closed") }
        }
    }

    /** Caller holds [lock]. First error wins. */
    private fun fail(message: String) {
        if (error == null) error = message
        done.countDown()
    }

    /**
     * Linear-interpolation resampler for mono PCM16 across chunk boundaries.
     * Good enough for speech going to a recognizer (16 kHz up to 24 kHz).
     */
    class Resampler(inRate: Int, outRate: Int) {
        private val step = inRate.toDouble() / outRate
        /** Position of the next output sample, in input samples, relative to [prev]. */
        private var pos = 0.0
        private var prev: Int? = null

        fun process(bytes: ByteArray, length: Int = bytes.size): ByteArray {
            val count = length / 2
            val samples = IntArray(count + if (prev != null) 1 else 0)
            var k = 0
            prev?.let { samples[k++] = it }
            for (i in 0 until count) {
                samples[k++] = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
            }
            if (samples.size < 2) {
                if (samples.isNotEmpty()) prev = samples.last()
                return ByteArray(0)
            }
            val out = ByteArrayOutputStream()
            while (pos + 1 < samples.size) {
                val i = floor(pos).toInt()
                val frac = pos - i
                val v = (samples[i] * (1 - frac) + samples[i + 1] * frac).toInt()
                out.write(v and 0xFF)
                out.write(v shr 8 and 0xFF)
                pos += step
            }
            pos -= (samples.size - 1)
            prev = samples.last()
            return out.toByteArray()
        }
    }

    companion object {
        const val URL = "wss://api.openai.com/v1/realtime?intent=transcription"
        const val API_RATE = 24_000
        /** About 100 ms of 24 kHz PCM16 per append event. */
        private const val SEND_BYTES = API_RATE * 2 / 10
        private const val FINISH_TIMEOUT_MS = 8_000L

        fun sessionUpdate(model: String, vocabulary: List<String>, languages: List<String>): JSONObject {
            val transcription = JSONObject().put("model", model)
            // Keywords must be single-line; the API rejects line breaks and angle brackets.
            val keywords = Vocabulary.forTranscription(vocabulary).map { it.replace(Regex("[\\r\\n<>]"), " ").trim() }.filter { it.isNotEmpty() }
            if (keywords.isNotEmpty()) transcription.put("keywords", JSONArray(keywords))
            if (languages.isNotEmpty()) transcription.put("languages", JSONArray(languages))
            if (model.startsWith("gpt-live-transcribe")) transcription.put("delay", "minimal")
            return JSONObject().put("type", "session.update").put(
                "session", JSONObject().put("type", "transcription").put(
                    "audio", JSONObject().put(
                        "input", JSONObject()
                            .put("format", JSONObject().put("type", "audio/pcm").put("rate", API_RATE))
                            .put("transcription", transcription)
                            .put("turn_detection", JSONObject.NULL)
                    )
                )
            )
        }
    }
}
