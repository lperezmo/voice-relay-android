package com.lperezmo.voicerelay

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pronunciations for a wake phrase from a language model, for words the
 * dictionary lacks or phrases in another language. Only the phrase's text goes
 * out, with the user's own key and cleanup model. What comes back is checked
 * like hand-typed phones (model tokens only, a vowel in each), fills the
 * Advanced field for review, and is saved only when the user taps Save.
 */
object WakeWordAi {
    enum class Language(val label: String, val hint: String) {
        AUTO("Auto", "Work out each word's language from the phrase itself."),
        ENGLISH("English", "The phrase is English."),
        SPANISH("Spanish", "The phrase is Spanish, though it may contain English names such as Claude."),
    }

    /** Most suggestions kept from one answer: room for accents without flooding the spotter. */
    const val MAX_SUGGESTIONS = 8

    const val SCHEMA_NAME = "wake_pronunciations"

    val SCHEMA: JSONObject
        get() = JSONObject(
            """
            {"type": "object", "additionalProperties": false, "required": ["pronunciations"],
             "properties": {"pronunciations": {"type": "array", "items": {
               "type": "object", "additionalProperties": false, "required": ["phones", "note"],
               "properties": {"phones": {"type": "string"}, "note": {"type": "string"}}}}}}
            """.trimIndent(),
        )

    fun instructions(tokens: Set<String>, language: Language): String = """
        You write pronunciations for an on-device wake word detector. The user sends the wake phrase.
        The detector only knows these phones, CMU ARPAbet as in CMUdict, where vowels carry a stress digit
        (0 unstressed, 1 primary, 2 secondary): ${tokens.sorted().joinToString(" ")}
        ${language.hint}
        Give 2 to $MAX_SUGGESTIONS ways people commonly say the whole phrase, most likely first. Each is the
        whole phrase as phones from that list only, separated by single spaces, with no word breaks, labels
        or punctuation. Every vowel needs its stress digit.
        For words that are not English, give the native pronunciation approximated with these phones, then how
        an English speaker would likely say it. For Spanish: pure vowels a AA1, e EY1, i IY1, o OW1, u UW1
        (0 when unstressed); r and rr as R; j and soft g as HH; ll and y as Y; n with tilde as N Y; soft d
        between vowels as DH; v as B.
        Include common accent or speed variants only when they differ in sound.
        Reply with JSON: {"pronunciations": [{"phones": "...", "note": "..."}]}, where note names the variant
        in two to four words, such as "Spanish" or "English speaker".
    """.trimIndent()

    data class Suggestion(val phones: List<String>, val note: String)

    /** [kept] is ready for the phones field; [dropped] says what was refused and why. */
    data class Parsed(val kept: List<Suggestion>, val dropped: List<String>)

    /**
     * Reads the model's answer: the JSON object (code fences and stray text
     * around it are ignored), then each pronunciation, uppercased and split on
     * whitespace. Refuses phones the model lacks and pronunciations without a
     * vowel, drops repeats, and keeps at most [MAX_SUGGESTIONS]. Null when the
     * answer holds no such JSON.
     */
    fun parse(text: String, tokens: Set<String>): Parsed? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val items: JSONArray = try {
            JSONObject(text.substring(start, end + 1)).optJSONArray("pronunciations") ?: return null
        } catch (_: Exception) {
            return null
        }
        val kept = mutableListOf<Suggestion>()
        val dropped = mutableListOf<String>()
        val seen = mutableSetOf<List<String>>()
        for (i in 0 until items.length()) {
            val item = items.opt(i)
            val raw = (item as? JSONObject)?.optString("phones") ?: item as? String ?: continue
            val note = (item as? JSONObject)?.optString("note").orEmpty().trim().take(40)
            val phones = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.uppercase() }
            if (phones.isEmpty()) continue
            val unknown = phones.filter { it !in tokens }.distinct()
            when {
                unknown.isNotEmpty() -> dropped += "${phones.joinToString(" ")} (not model phones: ${unknown.joinToString(" ")})"
                WakeWord.syllables(phones) == 0 -> dropped += "${phones.joinToString(" ")} (no vowel)"
                !seen.add(phones) -> {}
                kept.size >= MAX_SUGGESTIONS -> dropped += "${phones.joinToString(" ")} (more than $MAX_SUGGESTIONS)"
                else -> kept += Suggestion(phones, note)
            }
        }
        return Parsed(kept, dropped)
    }
}
