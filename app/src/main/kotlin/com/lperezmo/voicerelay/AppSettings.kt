package com.lperezmo.voicerelay

import android.content.Context

/** Typed access to user settings. The API key lives in [SecretStore], not here. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("voicerelay", Context.MODE_PRIVATE)

    var transcriptionModel: String
        get() = prefs.getString("transcription_model", null) ?: DEFAULT_TRANSCRIPTION_MODEL
        set(v) = prefs.edit().putString("transcription_model", v).apply()

    /** Live mode: stream to the Realtime API while talking instead of uploading after stop. */
    var liveTranscription: Boolean
        get() = prefs.getBoolean("live_transcription", false)
        set(v) = prefs.edit().putBoolean("live_transcription", v).apply()

    var liveModel: String
        get() = prefs.getString("live_model", null) ?: DEFAULT_LIVE_MODEL
        set(v) = prefs.edit().putString("live_model", v).apply()

    var cleanupEnabled: Boolean
        get() = prefs.getBoolean("use_post_processing", true)
        set(v) = prefs.edit().putBoolean("use_post_processing", v).apply()

    var cleanupModel: String
        get() = prefs.getString("cleanup_model", null) ?: DEFAULT_CLEANUP_MODEL
        set(v) = prefs.edit().putString("cleanup_model", v).apply()

    /**
     * Which cleanup prompt is in use: [Prompts.SIMPLE_KEY], [Prompts.DEV_KEY] or
     * [Prompts.CUSTOM_KEY]. Stored by name, so editing the custom text cannot
     * change which one is selected. Installs from before this setting existed
     * keep their choice: it is read back from the prompt text they had stored.
     */
    var promptPreset: String
        get() = prefs.getString("prompt_preset", null)
            ?: Prompts.presetFor(prefs.getString("post_processing_prompt", null) ?: Prompts.DEFAULT)
        set(v) = prefs.edit().putString("prompt_preset", v).apply()

    /** The cleanup prompt actually sent: the selected preset, or your own text. */
    val cleanupPrompt: String
        get() = Prompts.resolve(promptPreset, customPrompt)

    /** Your own cleanup prompt, kept even while a preset is selected. */
    var customPrompt: String
        get() = prefs.getString("custom_post_processing_prompt", null)
            ?: prefs.getString("post_processing_prompt", null) ?: Prompts.DEFAULT
        set(v) = prefs.edit().putString("custom_post_processing_prompt", v).apply()

    /** The grammar-fix prompt, for text you typed rather than dictated. */
    var fixPrompt: String
        get() = Prompts.typedOrDefault(prefs.getString("fix_prompt", null))
        set(v) = prefs.edit().putString("fix_prompt", v).apply()

    /** Back to the built-in fix prompt, so a later change to its wording applies too. */
    fun resetFixPrompt() = prefs.edit().remove("fix_prompt").apply()

    /** The floating arrow pad, for apps that want arrow keys (remote desktop, terminals). */
    var joystickEnabled: Boolean
        get() = prefs.getBoolean("joystick", false)
        set(v) = prefs.edit().putBoolean("joystick", v).apply()

    /** Where the pad was left. -1 means "not placed yet": the service picks a spot. */
    var joystickX: Int
        get() = prefs.getInt("joystick_x", -1)
        set(v) = prefs.edit().putInt("joystick_x", v).apply()

    var joystickY: Int
        get() = prefs.getInt("joystick_y", -1)
        set(v) = prefs.edit().putInt("joystick_y", v).apply()

    /** Switch to the arrows keyboard when the pad is used, and back when it goes quiet. */
    var joystickAutoSwitch: Boolean
        get() = prefs.getBoolean("joystick_auto_switch", true)
        set(v) = prefs.edit().putBoolean("joystick_auto_switch", v).apply()

    /** The volume preset pill above the dictate button. */
    var volumePillEnabled: Boolean
        get() = prefs.getBoolean("volume_pill", false)
        set(v) = prefs.edit().putBoolean("volume_pill", v).apply()

    /** The Glyph pill below the dictate button. Shown only while Glyph is installed. */
    var glyphPillEnabled: Boolean
        get() = prefs.getBoolean("glyph_pill", true)
        set(v) = prefs.edit().putBoolean("glyph_pill", v).apply()

    /** When the Silent + DND hour ends, in epoch ms. 0 means no hour is running. */
    var dndUntil: Long
        get() = prefs.getLong("dnd_until", 0L)
        set(v) = prefs.edit().putLong("dnd_until", v).apply()

    /** Keyboard to go back to after the joystick switched to the arrows keyboard. */
    var previousIme: String
        get() = prefs.getString("previous_ime", "") ?: ""
        set(v) = prefs.edit().putString("previous_ime", v).apply()

    /**
     * The wake phrase as shown ("hey claude"), tidied by [WakeWord.tidy].
     * [wakeKeywords] holds what the spotter listens for; [setWakePhrase] keeps the three in step.
     */
    val wakePhrase: String
        get() = prefs.getString("wake_phrase", null) ?: WakeWord.DEFAULT_PHRASE

    /** Phones typed by hand for [wakePhrase], or "" when the dictionary spelled it. */
    val wakePhones: String
        get() = prefs.getString("wake_phones", null) ?: ""

    /** The phrase's keyword lines, checked when saved. "" means the built-in default. */
    val wakeKeywords: String
        get() = prefs.getString("wake_keywords", null) ?: ""

    /**
     * Saves a checked phrase. Pass null to go back to [WakeWord.DEFAULT_PHRASE];
     * a phrase that comes out exactly as the default is stored as the default.
     */
    fun setWakePhrase(ready: WakeWord.Result.Ready?, phones: String = "") {
        prefs.edit().apply {
            if (ready == null || (ready.keywords == WakeWord.DEFAULT_KEYWORDS && phones.isBlank())) {
                remove("wake_phrase")
                remove("wake_phones")
                remove("wake_keywords")
            } else {
                putString("wake_phrase", ready.phrase)
                putString("wake_phones", phones.trim())
                putString("wake_keywords", ready.keywords.joinToString("\n") { it.line })
            }
        }.apply()
    }

    /** Listen for the wake phrase and open Claude's voice assistant. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(v) = prefs.edit().putBoolean("wake_word", v).apply()

    /**
     * Set the first time the wake word is turned on in the app, where its
     * permissions are granted. Until then, Tasker and other apps cannot turn it on.
     */
    var wakeWordSetUp: Boolean
        get() = prefs.getBoolean("wake_word_set_up", false)
        set(v) = prefs.edit().putBoolean("wake_word_set_up", v).apply()

    /** 1 (strict) to 7 (loose); see [WakeWord.threshold]. */
    var wakeSensitivity: Int
        get() = prefs.getInt("wake_sensitivity", WakeWord.DEFAULT_SENSITIVITY)
            .coerceIn(WakeWord.MIN_SENSITIVITY, WakeWord.MAX_SENSITIVITY)
        set(v) = prefs.edit().putInt("wake_sensitivity", v).apply()

    /** Press play again once Claude is done, if music was playing when the wake word fired. */
    var wakeResumeMedia: Boolean
        get() = prefs.getBoolean("wake_resume_media", true)
        set(v) = prefs.edit().putBoolean("wake_resume_media", v).apply()

    /** Terms to spell exactly: names, products, jargon. Stored one per line. */
    var vocabulary: List<String>
        get() = splitList(prefs.getString("vocabulary", "") ?: "")
        set(v) = prefs.edit().putString("vocabulary", v.joinToString("\n")).apply()

    /** ISO-639-1 codes of the languages you dictate in. Empty means auto-detect. */
    var languages: List<String>
        get() = splitList(prefs.getString("languages", null) ?: DEFAULT_LANGUAGES).map { it.lowercase() }
        set(v) = prefs.edit().putString("languages", v.joinToString(", ")).apply()

    /** Model ids fetched from /v1/models, cached so the pickers open instantly. */
    var cachedModelIds: List<String>
        get() = splitList(prefs.getString("cached_model_ids", "") ?: "")
        set(v) = prefs.edit().putString("cached_model_ids", v.joinToString("\n")).apply()

    companion object {
        const val DEFAULT_TRANSCRIPTION_MODEL = "gpt-transcribe"
        const val DEFAULT_LIVE_MODEL = "gpt-live-transcribe"
        const val DEFAULT_CLEANUP_MODEL = "gpt-6-luna"
        const val DEFAULT_LANGUAGES = ""

        fun splitList(raw: String): List<String> =
            raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
