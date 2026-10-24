package com.lperezmo.voicerelay

/**
 * The vocabulary list: names and jargon to spell exactly. Transcription models
 * read only a short hint (OpenAI's prompt is cut to about 200 tokens), so they
 * get the first terms that fit [TRANSCRIPTION_CHARS]; cleanup gets every term.
 * Keep the ones you say most at the top.
 */
object Vocabulary {
    /** About 200 tokens of terms and separators. */
    const val TRANSCRIPTION_CHARS = 800

    /** A few starting entries for the empty editor. */
    const val EXAMPLES = "API\nMCP\nClaude Code\nbun\nMade2Manage"

    /** The leading terms whose text, with ", " between them, fits [TRANSCRIPTION_CHARS]. */
    fun forTranscription(terms: List<String>): List<String> {
        var used = 0
        return terms.takeWhile { term ->
            used += term.length + if (used == 0) 0 else 2
            used <= TRANSCRIPTION_CHARS
        }
    }

    /**
     * A pasted list, one term per line or comma-separated. List markers ("- ",
     * "* ", bullets, "1. ", "2) ") are dropped, so a list copied from a note or a
     * Markdown file pastes as is. Duplicates keep their first position.
     */
    fun fromPaste(raw: String): List<String> =
        AppSettings.splitList(raw.lines().joinToString("\n") { it.trim().replace(LIST_MARKER, "") })

    /** A marker needs the space after it, so a term such as "18650" or "-v" is kept whole. */
    private val LIST_MARKER = Regex("""^(?:[-*•]|\d+[.)])\s+""")
}
