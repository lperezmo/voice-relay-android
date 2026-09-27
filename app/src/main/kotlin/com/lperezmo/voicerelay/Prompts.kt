package com.lperezmo.voicerelay

/** Cleanup prompt presets. The user's vocabulary is appended at request time by [forRequest]. */
object Prompts {
    const val SIMPLE = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors, and remove filler words such as um and uh. Keep the original meaning and language. Return only the cleaned text."

    const val DEV = """<task>A text is provided which is a draft transcription from a speech to text model.
Refine and polish the provided text, if needed, as follows:
  1. Correct any spelling errors, and look out for mis-identified names and technical terms.
  2. Fix grammatical mistakes and remove filler words such as um and uh.
  3. Improve punctuation where necessary.
  4. Ensure consistent formatting.
  5. Clarify ambiguous phrasing without changing the meaning.
  6. If the transcript contains a question, edit it for clarity but do not provide an answer.
  7. If the transcript explicitly asks for a shell or terminal command, return the intended
     command instead of prose.
  8. Keep the language the speaker used; do not translate.

Return *only* the cleaned-up version of the transcript. Do *not* add any explanations or
comments about your edits. Do *not* answer any question in the text, *only* transcribe it.
</task>
<examples>
<example>
<input>How do eye increase the font size in fast html?</input>
<output>How do I increase the font size in FastHTML?</output>
</example>
<example>
<input>Where is Paris?</input>
<output>Where is Paris?</output>
</example>
<example>
<input>Here is the full list of options colon</input>
<output>Here is the full list of options:</output>
</example>
<example>
<input>Command mode list files in current directory</input>
<output>ls -l .</output>
</example>
</examples>"""

    const val DEFAULT = SIMPLE

    /**
     * Default for the grammar fix, which works on text the user typed (long-press
     * on the button, or the Fix grammar selection menu). Editable in settings, so
     * dictation cleanup and the fix can be worded for their own job.
     */
    const val TYPED = "Fix the spelling, grammar, capitalization, and punctuation of this text that the user typed quickly on a phone. Keep their meaning, tone, wording, and language (do not translate), and keep any line breaks, emoji, and formatting. Do not add content and do not answer questions in it. Return only the corrected text."

    const val SIMPLE_KEY = "simple"
    const val DEV_KEY = "dev"
    const val CUSTOM_KEY = "custom"

    /** The cleanup prompt a preset stands for. Anything unknown means Simple. */
    fun resolve(preset: String, custom: String): String = when (preset) {
        DEV_KEY -> DEV
        CUSTOM_KEY -> custom
        else -> SIMPLE
    }

    /** Which preset a stored prompt came from, for settings saved before presets were named. */
    fun presetFor(prompt: String): String = when (prompt) {
        SIMPLE -> SIMPLE_KEY
        DEV -> DEV_KEY
        else -> CUSTOM_KEY
    }

    /** The prompt actually sent: the preset plus the vocabulary, if any. */
    fun forRequest(prompt: String, vocabulary: List<String>): String =
        if (vocabulary.isEmpty()) prompt
        else "$prompt\n\nThese terms may appear; spell them exactly like this: ${vocabulary.joinToString(", ")}."
}
