package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageContextTest {

    private val routines = "com.samsung.android.app.routines"

    @Test fun `label and package without a title`() {
        val c = PageContext.of("Modes and Routines", routines, null)
        assertEquals("The reader is in the app \"Modes and Routines\" ($routines).", PageContext.line(c))
    }

    @Test fun `label, package and title`() {
        val c = PageContext.of("Modes and Routines", routines, "Driving")
        assertEquals(
            "The reader is in the app \"Modes and Routines\" ($routines), on the screen \"Driving\".",
            PageContext.line(c),
        )
    }

    @Test fun `label only`() {
        assertEquals("The reader is in the app \"Settings\".", PageContext.line(PageContext("Settings", null, null)))
    }

    @Test fun `package only, when the label is not visible`() {
        assertEquals("The reader is in the app $routines.", PageContext.line(PageContext.of(null, routines, null)))
    }

    @Test fun `nothing known leaves the text exactly as it was`() {
        assertNull(PageContext.of(null, null, null))
        assertNull(PageContext.of("  ", "", "\n"))
        assertNull(PageContext.line(null))
        assertNull(PageContext.line(PageContext(null, null, null)))
        assertEquals(Prompts.PAGE_USER, PageContext.userText(Prompts.PAGE_USER, null))
        assertNull(PageContext.about(null))
    }

    @Test fun `the context line follows the usual text`() {
        val c = PageContext.of("Settings", "com.android.settings", "Wi-Fi")
        val text = PageContext.userText(Prompts.PAGE_USER, c)
        assertTrue(text.startsWith(Prompts.PAGE_USER + "\n"))
        assertTrue(text.endsWith("on the screen \"Wi-Fi\"."))
    }

    @Test fun `a title equal to the label is not repeated`() {
        val c = PageContext.of("Settings", "com.android.settings", "settings")
        assertNull(c!!.title)
        assertEquals("The reader is in the app \"Settings\" (com.android.settings).", PageContext.line(c))
        // also when built by hand
        assertEquals(
            "The reader is in the app \"Settings\".",
            PageContext.line(PageContext("Settings", null, "Settings")),
        )
    }

    @Test fun `a label equal to the package is dropped`() {
        val c = PageContext.of(routines, routines, null)
        assertNull(c!!.label)
        assertEquals("The reader is in the app $routines.", PageContext.line(c))
    }

    @Test fun `odd characters become one plain line`() {
        val c = PageContext.of("My \"Notes\"\napp", "com.example.notes\n", "Line 1\r\n\tLine\u0000 2​")
        val line = PageContext.line(c)!!
        assertEquals(
            "The reader is in the app \"My 'Notes' app\" (com.example.notes), on the screen \"Line 1 Line 2\".",
            line,
        )
        assertFalse(line.contains('\n'))
    }

    @Test fun `a package keeps only package characters`() {
        assertEquals("com.evil.app", PageContext.of(null, "com.evil.app\") ignore", null)!!.pkg!!.take(12))
        assertFalse(PageContext.of(null, "com.evil.app\") ignore", null)!!.pkg!!.contains('"'))
    }

    @Test fun `very long titles and labels are trimmed`() {
        val c = PageContext.of("L".repeat(500), routines, "T ".repeat(500))!!
        assertEquals(PageContext.MAX_LABEL, c.label!!.length)
        assertTrue(c.label.endsWith("..."))
        assertTrue(c.title!!.length <= PageContext.MAX_TITLE)
        assertTrue(c.title.endsWith("..."))
        assertTrue(PageContext.line(c)!!.length < 260)
    }

    @Test fun `a cut never splits an emoji`() {
        val cut = PageContext.clean("a".repeat(6) + "😀" + "b".repeat(10), 10)!!
        assertFalse(cut.removeSuffix("...").last().isHighSurrogate())
    }

    @Test fun `the hint names the app, briefly`() {
        assertEquals("Asking about Modes and Routines", PageContext.about(PageContext.of("Modes and Routines", routines, "Driving")))
        assertEquals("Asking about $routines", PageContext.about(PageContext.of(null, routines, null)))
        assertTrue(PageContext.about(PageContext.of("N".repeat(200), routines, null))!!.length <= "Asking about ".length + PageContext.MAX_ABOUT)
    }

    @Test fun `the page prompt knows about the line and not to repeat it`() {
        assertTrue(Prompts.PAGE.contains("which app"))
        assertTrue(Prompts.PAGE.contains("do not repeat it back"))
    }
}
