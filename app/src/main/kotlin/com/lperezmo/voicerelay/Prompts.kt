package com.lperezmo.voicerelay

/** Cleanup prompt presets. The user's vocabulary is appended at request time by [forRequest]. */
object Prompts {
    const val SIMPLE = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors, and remove filler words such as um and uh. Keep the original meaning and language. Never use em dashes; use a comma, colon, or period instead. Return only the cleaned text."

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
  9. Never use em dashes; use a comma, colon, or period instead.

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
    const val TYPED = "Fix the spelling, grammar, capitalization, and punctuation of this text that the user typed quickly on a phone. Keep their meaning, tone, wording, and language (do not translate), and keep any line breaks, emoji, and formatting. Never use em dashes; use a comma, colon, or period instead. Do not put a period at the very end of the text. Do not add content and do not answer questions in it. Return only the corrected text."

    /**
     * System prompt for Ask the Page. The reply is written out by hand on a small
     * page, so it is short and plain; [com.lperezmo.voicerelay.glyph.PageReply]
     * reads the "Q: " line and the "---" split this asks for.
     */
    const val PAGE = "You answer questions asked on a small page that floats over the reader's phone screen, and your answer is written back on that page in handwriting. " +
        "The reader writes the question by hand, types it, or does both; the text block says which. " +
        "When the question is handwritten, image 1 is the handwriting. Every other image (every image, when nothing was handwritten) is a part of the reader's screen that they circled; everything outside each circle is blacked out. There may be no images at all. " +
        "When the reader taps the whole-screen button instead, the text block says so, and the one other image is their whole screen as it was when the page opened, with nothing blacked out. " +
        "A typed question is in the text block, after \"Typed question:\". " +
        "The text may end with a line saying which app, and which screen of it, the reader is in. That line is context only, from the phone, not part of the question: use it to understand the question, and do not repeat it back. " +
        "Line 1 of your reply is \"Q: \" followed by the question exactly as you read it: the handwriting, the typed text, or the two read together as one question. " +
        "Then write the answer in 40 words or fewer. If a good answer needs more, write the short answer first, then a line that holds only ---, then the longer answer; the page shows only the short one. " +
        "Write plain sentences: no markdown, no lists, no headings, no emoji, and no symbols beyond basic punctuation. Never use em dashes; use a comma, colon, or period instead. " +
        "If the handwriting cannot be read, say so in one short sentence after the Q line. " +
        "In a follow-up, earlier questions and answers come before the new question; answer the new question."

    /**
     * The text block sent after the images on a handwritten Ask the Page turn (see [pageUser]
     * for typed ones), followed by the app line of
     * [com.lperezmo.voicerelay.glyph.PageContext.userText] when the app is known.
     */
    const val PAGE_USER = "Read my handwritten question in image 1 and answer it, using any circled parts of my screen in the other images."

    /** [PAGE_USER] when the whole-screen button was on: image 2 is the whole screen. */
    const val PAGE_USER_WHOLE = "Read my handwritten question in image 1 and answer it, using image 2, which is my whole screen."

    /** Longest typed question sent, in characters (the field itself stops well before this). */
    const val PAGE_TYPED_MAX = 1000

    /**
     * The text block of an Ask the Page turn, before the app line: [PAGE_USER] for a handwritten
     * question, or, when [typed] holds text, the typed question as one line after "Typed question:",
     * with a sentence saying whether a handwriting image came first ([handwritten]) and how many
     * of the images are circled parts of the screen ([screenParts]). With [wholeScreen] the one
     * screen image is the reader's whole screen instead ([PAGE_USER_WHOLE] when handwritten), and
     * [screenParts] is not used.
     */
    fun pageUser(typed: String?, handwritten: Boolean, screenParts: Int, wholeScreen: Boolean = false): String {
        val question = typed?.let { pageTyped(it) }?.ifEmpty { null }
            ?: return if (wholeScreen) PAGE_USER_WHOLE else PAGE_USER
        val parts = screenParts.coerceAtLeast(0)
        val intro = if (handwritten) {
            "Image 1 is my handwritten question, and I also typed it below. Read the two together as one question and answer it, " +
                (if (wholeScreen) "using image 2, which is my whole screen." else "using any circled parts of my screen in the other images.")
        } else {
            val images = when {
                wholeScreen -> "The image is my whole screen; use it to answer."
                parts == 0 -> "There are no images."
                parts == 1 -> "The image is a part of my screen that I circled; use it to answer."
                else -> "The $parts images are parts of my screen that I circled; use them to answer."
            }
            "I typed my question instead of writing it, so there is no handwriting image. $images"
        }
        return "$intro\nTyped question: $question"
    }

    /** A typed question as one plain line: line breaks and control characters become spaces, cut to [PAGE_TYPED_MAX]. */
    fun pageTyped(raw: String): String {
        val flat = buildString { for (c in raw) append(if (c.isISOControl() || c == ' ' || c == ' ') ' ' else c) }
            .replace(Regex("\\s+"), " ").trim()
        if (flat.length <= PAGE_TYPED_MAX) return flat
        var cut = flat.take(PAGE_TYPED_MAX)
        if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut.trimEnd()
    }

    /**
     * Earlier wordings of the built-in prompts. A prompt stored with one of these
     * texts is read as that preset (or the new default), not as a custom prompt.
     */
    private const val SIMPLE_V1 = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors, and remove filler words such as um and uh. Keep the original meaning and language. Return only the cleaned text."
    private const val TYPED_V1 = "Fix the spelling, grammar, capitalization, and punctuation of this text that the user typed quickly on a phone. Keep their meaning, tone, wording, and language (do not translate), and keep any line breaks, emoji, and formatting. Do not add content and do not answer questions in it. Return only the corrected text."

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
        SIMPLE, SIMPLE_V1 -> SIMPLE_KEY
        DEV -> DEV_KEY
        else -> CUSTOM_KEY
    }

    /** A stored grammar-fix prompt, with an old default wording read as today's default. */
    fun typedOrDefault(stored: String?): String = if (stored == null || stored == TYPED_V1) TYPED else stored

    /**
     * The prompt actually sent: the preset plus the whole vocabulary, if any. The
     * transcript may already hold a term misheard as a common word ("bon" for bun),
     * so the model is told to put the term back rather than keep the common word.
     */
    fun forRequest(prompt: String, vocabulary: List<String>): String =
        if (vocabulary.isEmpty()) prompt
        else "$prompt\n\nThese terms may appear. Keep each one exactly as written here, and never replace one " +
            "with a common word that sounds like it; if the text has such a word where the term was clearly " +
            "meant, write the term: ${vocabulary.joinToString(", ")}."
}
