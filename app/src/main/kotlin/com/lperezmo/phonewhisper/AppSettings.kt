package com.lperezmo.phonewhisper

import android.content.Context

/** Typed access to user settings. The API key lives in [SecretStore], not here. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)

    var transcriptionModel: String
        get() = prefs.getString("transcription_model", null) ?: DEFAULT_TRANSCRIPTION_MODEL
        set(v) = prefs.edit().putString("transcription_model", v).apply()

    var cleanupEnabled: Boolean
        get() = prefs.getBoolean("use_post_processing", true)
        set(v) = prefs.edit().putBoolean("use_post_processing", v).apply()

    var cleanupModel: String
        get() = prefs.getString("cleanup_model", null) ?: DEFAULT_CLEANUP_MODEL
        set(v) = prefs.edit().putString("cleanup_model", v).apply()

    /** The active cleanup prompt (one of the presets or the custom text). */
    var cleanupPrompt: String
        get() = prefs.getString("post_processing_prompt", null) ?: Prompts.DEFAULT
        set(v) = prefs.edit().putString("post_processing_prompt", v).apply()

    var customPrompt: String
        get() = prefs.getString("custom_post_processing_prompt", null) ?: Prompts.DEFAULT
        set(v) = prefs.edit().putString("custom_post_processing_prompt", v).apply()

    /** Terms to spell exactly: names, products, jargon. Stored one per line. */
    var vocabulary: List<String>
        get() = splitList(prefs.getString("vocabulary", "") ?: "")
        set(v) = prefs.edit().putString("vocabulary", v.joinToString("\n")).apply()

    /** ISO-639-1 codes of the languages you dictate in. */
    var languages: List<String>
        get() = splitList(prefs.getString("languages", null) ?: DEFAULT_LANGUAGES).map { it.lowercase() }
        set(v) = prefs.edit().putString("languages", v.joinToString(", ")).apply()

    /** Model ids fetched from /v1/models, cached so the pickers open instantly. */
    var cachedModelIds: List<String>
        get() = splitList(prefs.getString("cached_model_ids", "") ?: "")
        set(v) = prefs.edit().putString("cached_model_ids", v.joinToString("\n")).apply()

    companion object {
        const val DEFAULT_TRANSCRIPTION_MODEL = "gpt-transcribe"
        const val DEFAULT_CLEANUP_MODEL = "gpt-5.6-luna"
        const val DEFAULT_LANGUAGES = "en, es"

        fun splitList(raw: String): List<String> =
            raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
