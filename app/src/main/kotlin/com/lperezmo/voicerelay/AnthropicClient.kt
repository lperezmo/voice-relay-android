package com.lperezmo.voicerelay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/** One finished Ask the Page exchange, sent back as plain text so a follow-up keeps the conversation. */
class Exchange(val question: String, val answer: String)

/**
 * Blocking Claude (Anthropic Messages API) calls, for the optional cleanup and
 * grammar fix. Speech to text stays on [OpenAiClient]. Run off the main thread.
 *
 * Plain OkHttp like [OpenAiClient]: the Anthropic Java SDK would bring Jackson,
 * Apache HttpClient and a JSON-schema generator into an app that otherwise
 * ships OkHttp alone. Results use [OpenAiClient.Result], so the pipeline, the
 * fallbacks and the usage count treat both services the same way.
 */
class AnthropicClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: OkHttpClient = OpenAiClient.sharedHttp,
) {
    /**
     * Clean up [text] with [instructions] as the system prompt. Effort is set
     * low for speed; a model that rejects the setting, or the server-side
     * fallback, with a 400 gets one retry without both.
     */
    fun cleanup(text: String, model: String, instructions: String, effort: String? = "low"): OpenAiClient.Result {
        val fallback = model in FALLBACK_MODELS
        val first = post(messagesBody(text, model, instructions, effort, fallback), fallback)
        if (first.httpCode == 400 && (effort != null || fallback)) {
            return post(messagesBody(text, model, instructions, null, false), false)
        }
        return first
    }

    /**
     * Ask the Page: send [images] (PNG, or JPEG for a large whole-screen picture; the handwritten question first when there is
     * one, then any circled parts of the screen (or the whole screen); possibly none at all, for a typed
     * question) with [userText], after the earlier exchanges in
     * [history] as plain text turns. Same effort setting and fallback opt-in as
     * [cleanup]; a 400 is retried once without both only when its message is about
     * them, so the images are not re-sent for a 400 that would fail again.
     * Blocking; run off the main thread.
     */
    fun ask(
        images: List<ByteArray>,
        history: List<Exchange>,
        model: String,
        system: String,
        userText: String,
    ): OpenAiClient.Result {
        val fallback = model in FALLBACK_MODELS
        val first = post(askBody(images, history, model, system, userText, ASK_EFFORT, fallback), fallback)
        // only a 400 about the effort setting or the fallback is worth re-sending the images for
        val about = first.error.orEmpty().lowercase()
        if (first.httpCode == 400 && RETRY_HINTS.any { it in about }) {
            return post(askBody(images, history, model, system, userText, null, false), false)
        }
        return first
    }

    /** Model ids visible to this key, sorted. */
    fun listModels(): List<String>? = try {
        val request = Request.Builder().url("$baseUrl/models?limit=100").headers().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            OpenAiClient.parseModelIds(resp.body?.string().orEmpty())
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun post(json: JSONObject, fallback: Boolean): OpenAiClient.Result {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        return try {
            // Inside the try: OkHttp rejects a header value with a stray character, such as a pasted line break.
            val builder = Request.Builder().url("$baseUrl/messages").headers().post(body)
            if (fallback) builder.header("anthropic-beta", FALLBACK_BETA)
            http.newCall(builder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val parsed = parseMessageText(raw)
                when {
                    resp.isSuccessful -> parsed
                    parsed.error != null -> parsed.copy(text = null, httpCode = resp.code)
                    else -> OpenAiClient.Result(null, "HTTP ${resp.code}", resp.code)
                }
            }
        } catch (e: IOException) {
            OpenAiClient.Result(null, e.message ?: "Network error")
        } catch (_: IllegalArgumentException) {
            OpenAiClient.Result(null, "The Anthropic API key has a character it cannot have")
        }
    }

    private fun Request.Builder.headers() = header("x-api-key", apiKey).header("anthropic-version", API_VERSION)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        const val API_VERSION = "2026-10-21"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Models that take `fallbacks: "default"`: a declined request is re-run on another model server-side. */
        private val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

        /** Room for the model's thinking as well as the text; a cleanup itself is short. */
        private const val MAX_TOKENS = 16_000

        fun messagesBody(text: String, model: String, instructions: String, effort: String?, fallback: Boolean): JSONObject =
            JSONObject().apply {
                put("model", model)
                put("max_tokens", MAX_TOKENS)
                put("system", instructions)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
                if (effort != null) put("output_config", JSONObject().put("effort", effort))
                if (fallback) put("fallbacks", "default")
            }

        /** Effort for Ask the Page: kept low, like cleanup, so the answer starts writing quickly. */
        private const val ASK_EFFORT = "low"

        /** Words in a 400's message that say the effort setting or the fallback was the problem. */
        private val RETRY_HINTS = listOf("effort", "output_config", "fallback", "anthropic-beta")

        /** "image/jpeg" for bytes that start like a JPEG (FF D8 FF), else "image/png", what every other page image is. */
        fun mediaTypeOf(image: ByteArray): String =
            if (image.size >= 3 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte() && image[2] == 0xFF.toByte()) {
                "image/jpeg"
            } else {
                "image/png"
            }

        /**
         * The Messages request for [ask]: each earlier exchange as a plain text user
         * turn (the question as read) and assistant turn (the answer), then the new
         * user turn with one base64 image block per entry of [images], in order (PNG, or
         * JPEG for a whole-screen picture that would be too big as PNG; see [mediaTypeOf]),
         * followed by one text block holding [userText]. With no images the turn is that
         * one text block, which the API takes like any text-only content.
         */
        fun askBody(
            images: List<ByteArray>,
            history: List<Exchange>,
            model: String,
            system: String,
            userText: String,
            effort: String?,
            fallback: Boolean,
        ): JSONObject = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("system", system)
            val messages = JSONArray()
            // The API rejects an empty text turn, so an exchange missing either side is left out.
            for (turn in history.filter { it.question.isNotBlank() && it.answer.isNotBlank() }) {
                messages.put(JSONObject().put("role", "user").put("content", turn.question))
                messages.put(JSONObject().put("role", "assistant").put("content", turn.answer))
            }
            val content = JSONArray()
            val encoder = Base64.getEncoder()
            for (image in images) {
                content.put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", mediaTypeOf(image))
                            .put("data", encoder.encodeToString(image)),
                    ),
                )
            }
            content.put(JSONObject().put("type", "text").put("text", userText))
            messages.put(JSONObject().put("role", "user").put("content", content))
            put("messages", messages)
            if (effort != null) put("output_config", JSONObject().put("effort", effort))
            if (fallback) put("fallbacks", "default")
        }

        /**
         * The text blocks of a Messages response, plus token usage. Thinking blocks
         * are skipped. After a server-side fallback only the blocks that follow the
         * last `fallback` block are the answer. A refusal or a cut-off answer is an
         * error, so dictation falls back to the raw transcript instead.
         */
        fun parseMessageText(json: String): OpenAiClient.Result = try {
            val obj = JSONObject(json)
            val content = obj.optJSONArray("content")
            when {
                content == null -> OpenAiClient.Result(
                    null, obj.optJSONObject("error")?.optString("message", "Unknown error") ?: "Unknown response",
                )
                obj.optString("stop_reason") == "refusal" -> OpenAiClient.Result(null, "Refused by Claude")
                obj.optString("stop_reason") == "max_tokens" -> OpenAiClient.Result(null, "Claude's answer was cut off")
                else -> {
                    var start = 0
                    for (i in 0 until content.length()) {
                        if (content.getJSONObject(i).optString("type") == "fallback") start = i + 1
                    }
                    val text = (start until content.length())
                        .map { content.getJSONObject(it) }
                        .filter { it.optString("type") == "text" }
                        .joinToString("") { it.optString("text") }
                    val usage = obj.optJSONObject("usage")
                    OpenAiClient.Result(
                        text.trim().ifEmpty { null },
                        if (text.isBlank()) "Empty response" else null,
                        inputTokens = usage?.optInt("input_tokens") ?: 0,
                        outputTokens = usage?.optInt("output_tokens") ?: 0,
                    )
                }
            }
        } catch (e: Exception) {
            OpenAiClient.Result(null, e.message ?: "Parse error")
        }

        /** Claude models worth offering for cleanup. */
        fun cleanupModels(ids: List<String>): List<String> = ids.filter { it.startsWith("claude-") }

        /** The models the picker offers first, cheapest first. Any other Claude model can still be picked. */
        val TIERS = listOf(
            Tier("claude-haiku-5-5", "Haiku 5.5", "fastest and cheapest"),
            Tier("claude-sonnet-5-5", "Sonnet 5.5", "the default"),
            Tier("claude-opus-5-5", "Opus 5.5", "most careful, costs the most"),
        )

        /** "Sonnet 5.5 (claude-sonnet-5-5)" for a listed tier, else the id itself. */
        fun displayName(model: String): String = TIERS.firstOrNull { it.id == model }?.let { "${it.name} ($model)" } ?: model
    }

    data class Tier(val id: String, val name: String, val note: String) {
        val label: String get() = "$name: $note"
    }
}
