package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerFractionTest {

    @Test fun `a new timer is full and drains to empty`() {
        val (_, t) = Timers().start(now = 0, seconds = 600)
        assertEquals(600_000L, t.totalMs)
        assertEquals(1f, t.fractionLeft(0), 0f)
        assertEquals(0.25f, t.fractionLeft(450_000), 1e-6f)
        assertEquals(0f, t.fractionLeft(600_000), 0f)
    }

    @Test fun `a minute more adds to the whole`() {
        val (book, t) = Timers().start(now = 0, seconds = 60)
        val longer = book.extend(t.id, now = 30_000).find(t.id)!!
        assertEquals(120_000L, longer.totalMs)
        assertEquals(0.75f, longer.fractionLeft(30_000), 1e-6f)
    }

    @Test fun `a finished timer given a minute more starts over full`() {
        val (book, t) = Timers().start(now = 0, seconds = 300)
        val again = book.extend(t.id, now = 400_000).find(t.id)!!
        assertEquals(60_000L, again.totalMs)
        assertEquals(1f, again.fractionLeft(400_000), 0f)
    }

    @Test fun `a timer saved without its length reads off an hour`() {
        val old = TimerEntry(1, "Pasta", endsAt = 15 * 60_000L)
        assertEquals(0.25f, old.fractionLeft(0), 1e-6f)
        assertEquals(1f, TimerEntry(2, "Roast", endsAt = 3 * 60 * 60_000L).fractionLeft(0), 0f)
    }

    @Test fun `the length survives saving`() {
        val (book, t) = Timers().start(now = 0, seconds = 90)
        assertEquals(90_000L, Timers.fromJson(book.toJson()).find(t.id)!!.totalMs)
        // An old save without it still loads.
        val old = Timers.fromJson("""{"timers":[{"id":3,"label":"Tea","endsAt":5000}]}""")
        assertEquals(0L, old.find(3)!!.totalMs)
    }

    @Test fun `the pill's drain line follows the next timer`() {
        val (book, _) = Timers().start(now = 0, seconds = 600)
        val (both, _) = book.start(now = 0, seconds = 1200)
        assertEquals(0.5f, TimerText.face(both.sorted(), 300_000).drain, 1e-6f)
        assertEquals(0f, TimerText.face(both.sorted(), 600_000).drain, 0f)
        assertEquals(0f, TimerText.face(emptyList(), 0).drain, 0f)
    }

    @Test fun `a minute more on a running timer saved before totals counts from what is left`() {
        val old = Timers.fromJson("""{"timers":[{"id":3,"label":"Tea","endsAt":1800000}]}""")
        val longer = old.extend(3, now = 0).find(3)!!
        assertEquals(1_860_000L, longer.totalMs)
        assertEquals(1f, longer.fractionLeft(0), 1e-6f)
        assertEquals(0.5f, longer.fractionLeft(930_000), 1e-6f)
    }

    @Test fun `a done pill blinks for as long as the ring flashes, then stays solid`() {
        assertEquals(1f, TimerText.blink(0)!!, 1e-6f)
        assertEquals(TimerText.BLINK_LOW, TimerText.blink(TimerText.BLINK_MS / 2)!!, 1e-6f)
        assertNotNull(TimerText.blink(ButtonRing.DONE_FLASHING_MS - 1))
        assertNull(TimerText.blink(ButtonRing.DONE_FLASHING_MS))
        assertNull(TimerText.blink(10 * 60 * 60_000L))
    }

    @Test fun `ringing lists every timer whose alarm sounds now, and only those`() {
        var book = Timers()
        book = book.start(now = 0, seconds = 60).first        // ends at 60 s
        book = book.start(now = 0, seconds = 90).first        // ends at 90 s
        book = book.start(now = 0, seconds = 3_600).first     // still running
        assertTrue(TimerText.ringing(book.sorted(), 30_000).isEmpty())
        assertEquals(listOf(1), TimerText.ringing(book.sorted(), 60_000).map { it.id })
        assertEquals(listOf(1, 2), TimerText.ringing(book.sorted(), 100_000).map { it.id })
        // Ten minutes after its end a timer's alarm has gone quiet: it is no longer ringing.
        assertEquals(listOf(2), TimerText.ringing(book.sorted(), 60_000 + TimerText.RING_MS).map { it.id })
    }

    @Test fun `with the pill off the ring shows a finished timer only while it rings`() {
        val (book, _) = Timers().start(now = 0, seconds = 60)
        val list = book.sorted()
        assertFalse(TimerText.ringDone(list, 30_000, pillShown = false))
        assertTrue(TimerText.ringDone(list, 60_000, pillShown = false))
        assertEquals(TimerText.RING_MS, TimerText.ringDoneEndsIn(list, 60_000, pillShown = false))
        val quiet = 60_000 + TimerText.RING_MS
        assertFalse(TimerText.ringDone(list, quiet, pillShown = false))
        assertNull(TimerText.ringDoneEndsIn(list, quiet, pillShown = false))
        // With the pill it stays until it is dismissed there.
        assertTrue(TimerText.ringDone(list, quiet, pillShown = true))
        assertNull(TimerText.ringDoneEndsIn(list, 60_000, pillShown = true))
    }
}
