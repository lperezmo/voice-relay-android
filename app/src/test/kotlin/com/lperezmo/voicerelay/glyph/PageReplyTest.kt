package com.lperezmo.voicerelay.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageReplyTest {

    @Test fun `the Q line, the short answer and the long answer are split apart`() {
        val r = PageReply.parse("Q: What is a mole?\nAbout 6.022e23 particles.\n---\nA mole is the amount of a substance.\n\nIt is defined by Avogadro's number.")
        assertEquals("What is a mole?", r.question)
        assertEquals("About 6.022e23 particles.", r.short)
        assertEquals("A mole is the amount of a substance.\n\nIt is defined by Avogadro's number.", r.long)
    }

    @Test fun `without a split line it is all short answer`() {
        val r = PageReply.parse("Q: 2 + 2?\nFour.")
        assertEquals("2 + 2?", r.question)
        assertEquals("Four.", r.short)
        assertNull(r.long)
    }

    @Test fun `a reply without a Q line keeps an empty question`() {
        val r = PageReply.parse("Paris is the capital of France.")
        assertEquals("", r.question)
        assertEquals("Paris is the capital of France.", r.short)
        assertNull(r.long)
    }

    @Test fun `blank lines around and inside the reply are ignored on the page`() {
        val r = PageReply.parse("\n\n  q:  Where is it?  \r\n\r\nIt is in the\ntop corner.\n\n\n---\n\n\nFirst part.\n\n\n\nSecond part.\n\n")
        assertEquals("Where is it?", r.question)
        assertEquals("It is in the top corner.", r.short)
        assertEquals("First part.\n\nSecond part.", r.long)
    }

    @Test fun `a reply that is only an apology is the short answer`() {
        val r = PageReply.parse("Sorry, I cannot read the handwriting.")
        assertEquals("", r.question)
        assertEquals("Sorry, I cannot read the handwriting.", r.short)
        assertNull(r.long)
        assertEquals("A: Sorry, I cannot read the handwriting.", r.clipboardText())
    }

    @Test fun `an empty short answer takes the long one`() {
        val r = PageReply.parse("Q: Why?\n---\nBecause it is.")
        assertEquals("Because it is.", r.short)
        assertNull(r.long)
    }

    @Test fun `an A prefix on the answer is dropped`() {
        assertEquals("Yes.", PageReply.parse("Q: Is it on?\nA: Yes.").short)
    }

    @Test fun `an empty reply parses to empty parts`() {
        assertEquals(PageReply("", "", null), PageReply.parse("  \n "))
    }

    @Test fun `clipboard text has the question, the short answer and then the long one`() {
        assertEquals("Q: What is a mole?\n\nA: About 6.022e23 particles.\n\nIt is defined by Avogadro's number.",
            PageReply("What is a mole?", "About 6.022e23 particles.", "It is defined by Avogadro's number.").clipboardText())
        assertEquals("Q: 2 + 2?\n\nA: Four.", PageReply("2 + 2?", "Four.", null).clipboardText())
    }
}
