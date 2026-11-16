package com.lperezmo.voicerelay.glyph

/**
 * Which app Ask the Page was opened over: the app's user-visible [label], its [pkg] name and
 * the [title] of its window, any of which may be unknown. Read by [ScreenGrab] as the canvas
 * opens and kept next to the screenshot; never the text or content of the window.
 *
 * Android-free, so the wording sent to Claude ([line]) and shown on the page ([about]) is
 * tested on the JVM.
 */
data class PageContext(val label: String?, val pkg: String?, val title: String?) {
    companion object {
        const val MAX_LABEL = 60
        const val MAX_TITLE = 80
        const val MAX_PKG = 100
        /** The app name on the page's hint line is kept shorter still. */
        const val MAX_ABOUT = 40

        /** Tidy what Android reported; null when nothing usable is left. */
        fun of(label: CharSequence?, pkg: CharSequence?, title: CharSequence?): PageContext? {
            val p = pkg?.toString()?.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
                ?.trim('.')?.take(MAX_PKG)?.ifEmpty { null }
            val l = clean(label, MAX_LABEL)?.takeUnless { it == p }
            val t = clean(title, MAX_TITLE)?.takeUnless { same(it, l) || it == p }
            if (l == null && p == null && t == null) return null
            return PageContext(l, p, t)
        }

        /**
         * The line added to the question's text block, such as
         * `The reader is in the app "Modes and Routines" (com.samsung.android.app.routines), on the screen "Driving".`
         * Unknown parts are left out; null when nothing is known.
         */
        fun line(context: PageContext?): String? {
            if (context == null) return null
            val label = clean(context.label, MAX_LABEL)
            val pkg = context.pkg?.takeIf { it.isNotBlank() }
            val title = clean(context.title, MAX_TITLE)?.takeUnless { same(it, label) || it == pkg }
            val app = when {
                label != null && pkg != null -> "in the app \"$label\" ($pkg)"
                label != null -> "in the app \"$label\""
                pkg != null -> "in the app $pkg"
                else -> null
            }
            val screen = title?.let { "on the screen \"$it\"" }
            val parts = listOfNotNull(app, screen)
            if (parts.isEmpty()) return null
            return "The reader is ${parts.joinToString(", ")}."
        }

        /** The text block sent after the images: [base], then the context line when there is one. */
        fun userText(base: String, context: PageContext?): String =
            line(context)?.let { "$base\n$it" } ?: base

        /** "Asking about Modes and Routines" for the page's hint, so it is plain the app's name is shared. */
        fun about(context: PageContext?): String? {
            val name = clean(context?.label, MAX_ABOUT) ?: context?.pkg?.takeIf { it.isNotBlank() }?.let { clean(it, MAX_ABOUT) }
            return name?.let { "Asking about $it" }
        }

        /**
         * One line of plain text: control characters and line breaks become spaces, double
         * quotes become single ones (the line quotes the name), runs of spaces collapse, and
         * anything longer than [max] is cut with "...".
         */
        fun clean(raw: CharSequence?, max: Int): String? {
            if (raw == null) return null
            val flat = buildString {
                for (c in raw) {
                    append(
                        when {
                            c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() ||
                                c == ' ' || c == ' ' -> ' '
                            c == '"' || c == '“' || c == '”' -> '\''
                            else -> c
                        },
                    )
                }
            }.replace(Regex("\\s+"), " ").trim()
            if (flat.isEmpty()) return null
            if (flat.length <= max) return flat
            var cut = flat.take(max - 3).trimEnd()
            // do not leave half of a surrogate pair at the cut
            if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
            return "$cut..."
        }

        private fun same(a: String, b: String?) = b != null && a.equals(b, ignoreCase = true)
    }
}
