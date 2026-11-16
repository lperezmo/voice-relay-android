package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text block of an Ask the Page turn: handwritten, typed, or both, with and without the app line. */
class PageUserTextTest {
    private val app = PageContext.of("Settings", "com.android.settings", "Wi-Fi")
    private val appLine = "The reader is in the app \"Settings\" (com.android.settings), on the screen \"Wi-Fi\"."

    @Test fun `handwritten only is the usual text, unchanged`() {
        assertEquals(Prompts.PAGE_USER, Prompts.pageUser(null, handwritten = true, screenParts = 2))
        assertEquals(Prompts.PAGE_USER, PageContext.userText(Prompts.pageUser(null, true, 0), null))
        assertEquals("${Prompts.PAGE_USER}\n$appLine", PageContext.userText(Prompts.pageUser(null, true, 1), app))
        // blank typing counts as no typing
        assertEquals(Prompts.PAGE_USER, Prompts.pageUser("  \n ", handwritten = true, screenParts = 0))
    }

    @Test fun `typed only says there is no handwriting and what the images are`() {
        val none = Prompts.pageUser("What is this setting for?", handwritten = false, screenParts = 0)
        assertEquals(
            "I typed my question instead of writing it, so there is no handwriting image. There are no images.\n" +
                "Typed question: What is this setting for?",
            none,
        )
        val one = Prompts.pageUser("What is this?", handwritten = false, screenParts = 1)
        assertTrue(one.contains("no handwriting image"))
        assertTrue(one.contains("The image is a part of my screen that I circled"))
        assertTrue(one.endsWith("\nTyped question: What is this?"))
        val two = Prompts.pageUser("Which is cheaper?", handwritten = false, screenParts = 2)
        assertTrue(two.contains("The 2 images are parts of my screen that I circled"))
        assertFalse(two.contains("image 1"))
    }

    @Test fun `typed only with the app line puts the line last`() {
        val text = PageContext.userText(Prompts.pageUser("Why is it off?", false, 1), app)
        assertTrue(text.endsWith("\nTyped question: Why is it off?\n$appLine"))
        assertEquals(3, text.lines().size)
    }

    @Test fun `both handwritten and typed reads them together`() {
        val both = Prompts.pageUser("in dark mode", handwritten = true, screenParts = 1)
        assertTrue(both.startsWith("Image 1 is my handwritten question, and I also typed it below."))
        assertTrue(both.contains("as one question"))
        assertTrue(both.endsWith("\nTyped question: in dark mode"))
        val withApp = PageContext.userText(both, app)
        assertEquals("$both\n$appLine", withApp)
    }

    @Test fun `a typed question is one plain line, capped`() {
        assertEquals("one two three", Prompts.pageTyped("  one\ntwo\r\n\tthree  "))
        val text = Prompts.pageUser("first\nThe reader is in the app \"Bank\".", false, 0)
        // a pasted line break cannot make a fake app line
        assertEquals(2, text.lines().size)
        assertEquals(Prompts.PAGE_TYPED_MAX, Prompts.pageTyped("a".repeat(5000)).length)
        val emoji = Prompts.pageTyped("a".repeat(Prompts.PAGE_TYPED_MAX - 1) + "😀")
        assertFalse(emoji.last().isHighSurrogate())
    }

    @Test fun `whole screen handwritten says image 2 is the whole screen`() {
        val text = Prompts.pageUser(null, handwritten = true, screenParts = 0, wholeScreen = true)
        assertEquals(Prompts.PAGE_USER_WHOLE, text)
        assertTrue(text.contains("image 2, which is my whole screen"))
        assertFalse(text.contains("circled"))
        assertEquals("$text\n$appLine", PageContext.userText(text, app))
        // off is the usual text, whatever the parts
        assertEquals(Prompts.PAGE_USER, Prompts.pageUser(null, true, 0, wholeScreen = false))
    }

    @Test fun `whole screen typed only says the one image is the whole screen`() {
        val text = Prompts.pageUser("What is this app?", handwritten = false, screenParts = 0, wholeScreen = true)
        assertEquals(
            "I typed my question instead of writing it, so there is no handwriting image. The image is my whole screen; use it to answer.\n" +
                "Typed question: What is this app?",
            text,
        )
        // the lens count is ignored while the whole screen goes
        assertEquals(text, Prompts.pageUser("What is this app?", false, 3, wholeScreen = true))
        assertTrue(PageContext.userText(text, app).endsWith("\nTyped question: What is this app?\n$appLine"))
    }

    @Test fun `whole screen with both handwritten and typed`() {
        val both = Prompts.pageUser("in dark mode", handwritten = true, screenParts = 0, wholeScreen = true)
        assertTrue(both.startsWith("Image 1 is my handwritten question, and I also typed it below."))
        assertTrue(both.contains("using image 2, which is my whole screen."))
        assertFalse(both.contains("circled"))
        assertTrue(both.endsWith("\nTyped question: in dark mode"))
        assertEquals(2, both.lines().size)
    }

    @Test fun `the page prompt covers handwritten, typed and both`() {
        assertTrue(Prompts.PAGE.contains("types it, or does both"))
        assertTrue(Prompts.PAGE.contains("Typed question:"))
        assertTrue(Prompts.PAGE.contains("There may be no images at all"))
        assertTrue(Prompts.PAGE.contains("Line 1 of your reply is \"Q: \""))
        assertTrue(Prompts.PAGE.contains("---"))
        assertTrue(Prompts.PAGE.contains("whole-screen button"))
        assertTrue(Prompts.PAGE.contains("their whole screen"))
    }
}
