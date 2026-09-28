package com.lperezmo.voicerelay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Blocking OpenAI calls. Run them off the main thread.
 *
 * Every call returns a [Result] instead of throwing, so the caller can decide
 * between retrying and falling back without try/catch at each site.
 */
class OpenAiClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: OkHttpClient = sharedHttp,
) {
    /** [inputTokens] / [outputTokens] come from the Responses API `usage` block, for the cost estimate. */
    data class Result(
        val text: String?,
        val error: String?,
        val httpCode: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
    ) {
        val ok get() = !text.isNullOrBlank()
    }

    /** An encoded recording: WAV, or AAC in an .m4a container. [seconds] is the clip length, for usage. */
    class Audio(val bytes: ByteArray, val filename: String, val mime: String, val seconds: Double = 0.0) {
        companion object {
            fun wav(bytes: ByteArray, seconds: Double = 0.0) = Audio(bytes, "audio.wav", "audio/wav", seconds)
            fun m4a(bytes: ByteArray, seconds: Double = 0.0) = Audio(bytes, "audio.m4a", "audio/mp4", seconds)
        }
    }

    /**
     * Transcribe a clip. Vocabulary goes out as `keywords` for models that
     * take them (gpt-transcribe) and as a `prompt` for the rest. If the model
     * rejects the extra hint fields with a 400, the request is retried once
     * with only the model and the file, so an unfamiliar model slug still works.
     */
    fun transcribe(audio: Audio, model: String, vocabulary: List<String>, languages: List<String>): Result {
        val first = postTranscription(audio, transcriptionFields(model, vocabulary, languages))
        if (first.httpCode == 400) {
            return postTranscription(audio, listOf("model" to model))
        }
        return first
    }

    /**
     * Clean up a transcript through the Responses API. Reasoning effort is set
     * to the lowest level for speed; models that do not accept the setting get
     * one retry without it.
     */
    fun cleanup(text: String, model: String, instructions: String, reasoningEffort: String? = "none"): Result {
        val first = postJson("responses", cleanupBody(text, model, instructions, reasoningEffort), ::parseResponsesText)
        if (first.httpCode == 400 && reasoningEffort != null) {
            return postJson("responses", cleanupBody(text, model, instructions, null), ::parseResponsesText)
        }
        return first
    }

    /**
     * A JSON answer through the Responses API, shaped by [schema] (strict
     * structured output). A 400 retries without the reasoning setting, then
     * without the schema, so an older model still answers; the instructions
     * ask for the same JSON either way, and the caller checks what comes back.
     */
    fun respondJson(
        input: String,
        model: String,
        instructions: String,
        schemaName: String,
        schema: JSONObject,
        reasoningEffort: String? = "low",
    ): Result {
        val format = JSONObject()
            .put("type", "json_schema")
            .put("name", schemaName)
            .put("schema", schema)
            .put("strict", true)
        val first = postJson("responses", cleanupBody(input, model, instructions, reasoningEffort, format), ::parseResponsesText)
        var r = first
        if (r.httpCode == 400 && reasoningEffort != null) {
            r = postJson("responses", cleanupBody(input, model, instructions, null, format), ::parseResponsesText)
        }
        if (r.httpCode == 400) {
            r = postJson("responses", cleanupBody(input, model, instructions, null), ::parseResponsesText)
        }
        // When every attempt fails, the first error names the real cause (a later one may only
        // be a side effect of what the retry left out).
        return if (!r.ok && first.httpCode == 400 && first.error != null) r.copy(error = first.error) else r
    }

    /**
     * Opens (and pools) the TLS connection to the API while the user is still
     * speaking, so the upload starts without a handshake. Unauthenticated HEAD;
     * the response is ignored.
     */
    fun prewarm() {
        val request = Request.Builder().url(baseUrl).head().build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
        })
    }

    /** Model ids visible to this key, sorted. */
    fun listModels(): List<String>? {
        val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parseModelIds(resp.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun postTranscription(audio: Audio, fields: List<Pair<String, String>>): Result {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("file", audio.filename, audio.bytes.toRequestBody(audio.mime.toMediaType()))
        }.build()
        return execute(Request.Builder().url("$baseUrl/audio/transcriptions").post(body), ::parseTranscriptionText)
    }

    private fun postJson(path: String, json: JSONObject, parse: (String) -> Result): Result {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        return execute(Request.Builder().url("$baseUrl/$path").post(body), parse)
    }

    private fun execute(builder: Request.Builder, parse: (String) -> Result): Result = try {
        http.newCall(builder.header("Authorization", "Bearer $apiKey").build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val parsed = parse(raw)
            when {
                resp.isSuccessful -> parsed
                parsed.error != null -> parsed.copy(text = null, httpCode = resp.code)
                else -> Result(null, "HTTP ${resp.code}", resp.code)
            }
        }
    } catch (e: IOException) {
        Result(null, e.message ?: "Network error")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        internal val sharedHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Form fields for a transcription request, in the shape each model family accepts. */
        fun transcriptionFields(model: String, vocabulary: List<String>, languages: List<String>): List<Pair<String, String>> {
            val fields = mutableListOf("model" to model)
            if (model.startsWith("gpt-transcribe")) {
                vocabulary.forEach { fields += "keywords[]" to it }
                languages.forEach { fields += "languages[]" to it }
            } else {
                if (vocabulary.isNotEmpty()) fields += "prompt" to "Vocabulary: ${vocabulary.joinToString(", ")}."
                languages.singleOrNull()?.let { fields += "language" to it }
            }
            return fields
        }

        fun cleanupBody(
            text: String,
            model: String,
            instructions: String,
            reasoningEffort: String?,
            format: JSONObject? = null,
        ): JSONObject =
            JSONObject().apply {
                put("model", model)
                put("instructions", instructions)
                put("input", text)
                put("store", false)
                if (reasoningEffort != null) put("reasoning", JSONObject().put("effort", reasoningEffort))
                if (format != null) put("text", JSONObject().put("format", format))
            }

        fun parseTranscriptionText(json: String): Result = parse(json) { obj ->
            if (obj.has("text")) Result(obj.getString("text").trim(), null) else null
        }

        /** Reads `output_text` when present, else the first output_text part of the first message, plus token usage. */
        fun parseResponsesText(json: String): Result = parse(json) { obj ->
            val usage = obj.optJSONObject("usage")
            fun withUsage(text: String) = Result(
                text.trim(), null,
                inputTokens = usage?.optInt("input_tokens") ?: 0,
                outputTokens = usage?.optInt("output_tokens") ?: 0,
            )
            obj.optString("output_text").takeIf { it.isNotBlank() }?.let { return@parse withUsage(it) }
            val output = obj.optJSONArray("output") ?: return@parse null
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.getJSONObject(j)
                    if (part.optString("type") == "output_text") return@parse withUsage(part.getString("text"))
                    if (part.optString("type") == "refusal") return@parse Result(null, "Refused: ${part.optString("refusal")}")
                }
            }
            null
        }

        fun parseModelIds(json: String): List<String>? = try {
            val data: JSONArray = JSONObject(json).getJSONArray("data")
            (0 until data.length()).map { data.getJSONObject(it).getString("id") }.sorted()
        } catch (_: Exception) {
            null
        }

        /** Speech-to-text models worth offering in the picker. */
        fun transcriptionModels(ids: List<String>): List<String> = ids.filter { id ->
            (id.contains("transcribe") || id == "whisper-1") &&
                listOf("realtime", "live", "diarize").none { id.contains(it) }
        }

        /** Text models worth offering for cleanup: general gpt-* chat/reasoning models only. */
        fun cleanupModels(ids: List<String>): List<String> = ids.filter { id ->
            id.startsWith("gpt-") &&
                listOf("transcribe", "tts", "realtime", "audio", "image", "search", "codex", "whisper", "live", "embedding")
                    .none { id.contains(it) }
        }

        private inline fun parse(json: String, extract: (JSONObject) -> Result?): Result = try {
            val obj = JSONObject(json)
            extract(obj)
                ?: obj.optJSONObject("error")?.let { Result(null, it.optString("message", "Unknown error")) }
                ?: Result(null, "Unknown response")
        } catch (e: Exception) {
            Result(null, e.message ?: "Parse error")
        }
    }
}
