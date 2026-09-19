package com.lperezmo.phonewhisper

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
    data class Result(val text: String?, val error: String?, val httpCode: Int = 0) {
        val ok get() = !text.isNullOrBlank()
    }

    /**
     * Transcribe a WAV clip. Vocabulary goes out as `keywords` for models that
     * take them (gpt-transcribe) and as a `prompt` for the rest. If the model
     * rejects the extra hint fields with a 400, the request is retried once
     * with only the model and the file, so an unfamiliar model slug still works.
     */
    fun transcribe(wav: ByteArray, model: String, vocabulary: List<String>, languages: List<String>): Result {
        val first = postTranscription(wav, transcriptionFields(model, vocabulary, languages))
        if (first.httpCode == 400) {
            return postTranscription(wav, listOf("model" to model))
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

    private fun postTranscription(wav: ByteArray, fields: List<Pair<String, String>>): Result {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
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

        private val sharedHttp = OkHttpClient.Builder()
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

        fun cleanupBody(text: String, model: String, instructions: String, reasoningEffort: String?): JSONObject =
            JSONObject().apply {
                put("model", model)
                put("instructions", instructions)
                put("input", text)
                put("store", false)
                if (reasoningEffort != null) put("reasoning", JSONObject().put("effort", reasoningEffort))
            }

        fun parseTranscriptionText(json: String): Result = parse(json) { obj ->
            if (obj.has("text")) Result(obj.getString("text").trim(), null) else null
        }

        /** Reads `output_text` when present, else the first output_text part of the first message. */
        fun parseResponsesText(json: String): Result = parse(json) { obj ->
            obj.optString("output_text").takeIf { it.isNotBlank() }?.let { return@parse Result(it.trim(), null) }
            val output = obj.optJSONArray("output") ?: return@parse null
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.getJSONObject(j)
                    if (part.optString("type") == "output_text") return@parse Result(part.getString("text").trim(), null)
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
