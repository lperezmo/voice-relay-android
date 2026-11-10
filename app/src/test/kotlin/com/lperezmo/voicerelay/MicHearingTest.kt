package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicHearingTest {

    @Test fun `zeros for the whole run report once, and sound reports once`() {
        val run = ZeroRun(3)
        assertNull(run.feed(0))
        assertNull(run.feed(0))
        assertEquals(true, run.feed(0))
        assertNull(run.feed(0))
        assertNull(run.feed(0))
        assertEquals(false, run.feed(120))
        assertNull(run.feed(80))
    }

    @Test fun `a single loud chunk starts the count over`() {
        val run = ZeroRun(3)
        run.feed(0)
        run.feed(0)
        assertNull(run.feed(5))
        assertNull(run.feed(0))
        assertNull(run.feed(0))
        assertEquals(true, run.feed(0))
    }

    @Test fun `ordinary sound never reports`() {
        val run = ZeroRun(3)
        repeat(100) { assertNull(run.feed(1)) }
    }

    @Test fun `either sign makes it deaf, and it hears again only when both are gone`() {
        val h = MicHearing()
        assertFalse(h.deaf)
        assertTrue(h.onSilenced(true))
        assertTrue(h.deaf)
        assertFalse(h.onZeros(true))
        assertFalse(h.onSilenced(false))
        assertTrue(h.deaf)
        assertTrue(h.onZeros(false))
        assertFalse(h.deaf)
    }

    @Test fun `repeating the same state is not a change`() {
        val h = MicHearing()
        assertFalse(h.onSilenced(false))
        assertTrue(h.onZeros(true))
        assertFalse(h.onZeros(true))
    }

    @Test fun `a new recording starts out hearing`() {
        val h = MicHearing()
        h.onSilenced(true)
        assertTrue(h.reset())
        assertFalse(h.deaf)
        assertFalse(h.reset())
    }

    @Test fun `the notification says when it cannot hear`() {
        assertEquals("Listening for hey claude. Audio stays on the phone.", MicHearing.listeningText("hey claude", false))
        assertTrue(MicHearing.listeningText("hey claude", true).startsWith("Cannot hear right now"))
    }
}
