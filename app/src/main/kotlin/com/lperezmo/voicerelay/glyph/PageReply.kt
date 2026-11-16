package com.lperezmo.voicerelay.glyph

/**
 * Claude's reply to an Ask the Page question, split the way [com.lperezmo.voicerelay.Prompts.PAGE]
 * asks for it: the question as Claude read it, the short answer the page writes
 * out, and an optional longer answer that only goes to the clipboard.
 */
data class PageReply(val question: String, val short: String, val long: String?) {

    /** Question and answer for the Copy chip, with the long answer after the short one when there is one. */
    fun clipboardText(): String = buildString {
        if (question.isNotBlank()) append("Q: ").append(question).append("\n\n")
        append("A: ").append(short)
        if (!long.isNullOrBlank()) append("\n\n").append(long)
    }

    companion object {
        private val Q_LINE = Regex("^\\s*Q\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
        private val A_PREFIX = Regex("^\\s*A\\s*:\\s*", RegexOption.IGNORE_CASE)
        private val SPLIT = Regex("^\\s*-{3,}\\s*$")

        /**
         * Read a raw reply. Line 1 "Q: ..." is the question; a reply without it
         * keeps an empty question and is all answer. A line of only "---" splits
         * the short answer from the long one. The short answer is joined into one
         * line for the page; the long one keeps its paragraphs. A short answer
         * that came out empty takes the long one's place.
         */
        fun parse(raw: String): PageReply {
            val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            var start = lines.indexOfFirst { it.isNotBlank() }
            if (start < 0) return PageReply("", "", null)
            var question = ""
            val q = Q_LINE.find(lines[start])
            if (q != null) {
                question = q.groupValues[1].trim()
                start++
            }
            val body = lines.drop(start)
            val split = body.indexOfFirst { SPLIT.matches(it) }
            val shortLines = if (split < 0) body else body.take(split)
            val longLines = if (split < 0) emptyList() else body.drop(split + 1)
            var short = oneLine(shortLines).replaceFirst(A_PREFIX, "")
            var long: String? = paragraphs(longLines).ifEmpty { null }
            if (short.isEmpty() && long != null) {
                short = oneLine(long.split('\n'))
                long = null
            }
            return PageReply(question, short, long)
        }

        private fun oneLine(lines: List<String>): String =
            lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

        /** Lines joined back with their breaks, runs of blank lines cut to one, ends trimmed. */
        private fun paragraphs(lines: List<String>): String =
            lines.joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
