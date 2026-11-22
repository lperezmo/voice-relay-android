package com.lperezmo.voicerelay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimersTest {

    private val now = 1_000_000L

    private fun timer(id: Int, label: String, endsAt: Long, rung: Boolean = false) =
        TimerEntry(id, label, endsAt, rung)

    // ---- Timers ----

    @Test fun `timers without a label are numbered Timer 1, Timer 2`() {
        val (a, first) = Timers().start(now, 60)
        val (_, second) = a.start(now, 60)
        assertEquals("Timer 1", first.label)
        assertEquals("Timer 2", second.label)
    }

    @Test fun `a blank label counts as no label`() {
        val (_, t) = Timers().start(now, 60, "   ")
        assertEquals("Timer 1", t.label)
    }

    @Test fun `a given label is kept, trimmed, and does not use up a number`() {
        val (a, pasta) = Timers().start(now, 60, "  Pasta  ")
        val (_, next) = a.start(now, 60)
        assertEquals("Pasta", pasta.label)
        assertEquals("Timer 1", next.label)
    }

    @Test fun `a long label is cut at MAX_LABEL`() {
        val (_, t) = Timers().start(now, 60, "x".repeat(Timers.MAX_LABEL + 10))
        assertEquals("x".repeat(Timers.MAX_LABEL), t.label)
    }

    @Test fun `ids go up by one, labelled or not`() {
        val (a, t1) = Timers().start(now, 60, "Pasta")
        val (b, t2) = a.start(now, 60)
        assertEquals(1, t1.id)
        assertEquals(2, t2.id)
        assertEquals(3, b.nextId)
    }

    @Test fun `numbering starts over once the last timer is removed, ids do not`() {
        val (a, t1) = Timers().start(now, 60)
        val (b, t2) = a.start(now, 60)
        val empty = b.remove(t1.id).remove(t2.id)
        val (_, again) = empty.start(now, 60)
        assertEquals("Timer 1", again.label)
        assertEquals(3, again.id)
    }

    @Test fun `numbering goes on while a timer is left`() {
        val (a, t1) = Timers().start(now, 60)
        val (b, _) = a.start(now, 60)
        val (_, t3) = b.remove(t1.id).start(now, 60)
        assertEquals("Timer 3", t3.label)
    }

    @Test fun `the timer ends that many seconds from now`() {
        val (_, t) = Timers().start(now, 90)
        assertEquals(now + 90_000, t.endsAt)
    }

    @Test fun `seconds below one are made one`() {
        assertEquals(now + 1000, Timers().start(now, 0).second.endsAt)
        assertEquals(now + 1000, Timers().start(now, -5).second.endsAt)
    }

    @Test fun `seconds above MAX_SECONDS are cut to a day`() {
        val (_, t) = Timers().start(now, Timers.MAX_SECONDS + 100)
        assertEquals(now + Timers.MAX_SECONDS * 1000, t.endsAt)
    }

    @Test fun `start adds the timer to the list`() {
        val (a, t) = Timers().start(now, 60)
        assertEquals(listOf(t), a.list)
        assertEquals(t, a.find(t.id))
        assertNull(a.find(99))
    }

    @Test fun `sorted puts the soonest first`() {
        val timers = Timers(
            list = listOf(timer(1, "a", 3000), timer(2, "b", 1000), timer(3, "c", 2000)),
            nextId = 4,
        )
        assertEquals(listOf(2, 3, 1), timers.sorted().map { it.id })
    }

    @Test fun `timers ending together are sorted by id`() {
        val timers = Timers(list = listOf(timer(5, "a", 1000), timer(2, "b", 1000)), nextId = 6)
        assertEquals(listOf(2, 5), timers.sorted().map { it.id })
    }

    @Test fun `extend adds a minute to a running timer`() {
        val timers = Timers(list = listOf(timer(1, "a", now + 30_000)), nextId = 2)
        assertEquals(now + 90_000, timers.extend(1, now).find(1)!!.endsAt)
    }

    @Test fun `extend restarts a done timer from now and clears rung`() {
        val timers = Timers(list = listOf(timer(1, "a", now - 5000, rung = true)), nextId = 2)
        val t = timers.extend(1, now).find(1)!!
        assertEquals(now + 60_000, t.endsAt)
        assertFalse(t.rung)
    }

    @Test fun `extend takes other amounts and leaves other timers alone`() {
        val other = timer(2, "b", now + 1000)
        val timers = Timers(list = listOf(timer(1, "a", now + 1000), other), nextId = 3)
        val out = timers.extend(1, now, seconds = 300)
        assertEquals(now + 301_000, out.find(1)!!.endsAt)
        assertEquals(other, out.find(2))
    }

    @Test fun `remove drops only that timer`() {
        val timers = Timers(list = listOf(timer(1, "a", 1000), timer(2, "b", 2000)), nextId = 3, nextNumber = 3)
        val out = timers.remove(1)
        assertEquals(listOf(2), out.list.map { it.id })
        assertEquals(3, out.nextNumber)
        assertEquals(3, out.nextId)
    }

    @Test fun `removing an unknown id changes nothing`() {
        val timers = Timers(list = listOf(timer(1, "a", 1000)), nextId = 2, nextNumber = 2)
        assertEquals(timers, timers.remove(42))
    }

    @Test fun `due lists done timers that have not rung`() {
        val timers = Timers(
            list = listOf(
                timer(1, "done", now - 1),
                timer(2, "rung", now - 1, rung = true),
                timer(3, "running", now + 1),
                timer(4, "just now", now),
            ),
            nextId = 5,
        )
        assertEquals(listOf(1, 4), timers.due(now).map { it.id })
    }

    @Test fun `a timer rings once`() {
        val timers = Timers(list = listOf(timer(1, "a", now - 1), timer(2, "b", now - 1)), nextId = 3)
        val out = timers.markRung(listOf(1))
        assertEquals(listOf(2), out.due(now).map { it.id })
        assertTrue(out.find(1)!!.rung)
        assertTrue(out.markRung(listOf(2)).due(now).isEmpty())
    }

    @Test fun `toJson and fromJson give back the same timers`() {
        val timers = Timers(
            list = listOf(timer(3, "Pasta · 2", now + 5000), timer(7, "Timer 2", now - 1, rung = true)),
            nextId = 8,
            nextNumber = 3,
        )
        assertEquals(timers, Timers.fromJson(timers.toJson()))
    }

    @Test fun `an empty list round trips`() {
        val timers = Timers(nextId = 12, nextNumber = 1)
        assertEquals(timers, Timers.fromJson(timers.toJson()))
    }

    @Test fun `fromJson of nothing or garbage is an empty list`() {
        assertEquals(Timers(), Timers.fromJson(null))
        assertEquals(Timers(), Timers.fromJson(""))
        assertEquals(Timers(), Timers.fromJson("   "))
        assertEquals(Timers(), Timers.fromJson("not json"))
        assertEquals(Timers(), Timers.fromJson("[1, 2]"))
        assertEquals(Timers(), Timers.fromJson("""{"timers":[{"label":"no id"}]}"""))
    }

    @Test fun `fromJson without a timers array is an empty list`() {
        assertEquals(Timers(), Timers.fromJson("{}"))
    }

    @Test fun `fromJson never hands out an id already in use`() {
        val json = """{"nextId":2,"nextNumber":1,"timers":[{"id":5,"label":"a","endsAt":1000}]}"""
        val timers = Timers.fromJson(json)
        assertEquals(6, timers.nextId)
        assertFalse(timers.find(5)!!.rung)
    }

    @Test fun `fromJson keeps nextNumber at least one`() {
        val timers = Timers.fromJson("""{"nextId":1,"nextNumber":0,"timers":[]}""")
        assertEquals(1, timers.nextNumber)
    }

    @Test fun `toJson writes the fields by name`() {
        val o = JSONObject(Timers(list = listOf(timer(1, "a", 1234)), nextId = 2, nextNumber = 2).toJson())
        assertEquals(2, o.getInt("nextId"))
        assertEquals(1234L, o.getJSONArray("timers").getJSONObject(0).getLong("endsAt"))
    }

    // ---- TimerEntry ----

    @Test fun `seconds left rounds up`() {
        val t = timer(1, "a", now + 10 * 60_000)
        assertEquals(600, t.secondsLeft(now))
        assertEquals(600, t.secondsLeft(now + 1)) // 599.999 s reads 10:00
        assertEquals(1, t.secondsLeft(t.endsAt - 1))
    }

    @Test fun `a timer is done exactly at its end, with no seconds left`() {
        val t = timer(1, "a", now)
        assertFalse(t.done(now - 1))
        assertTrue(t.done(now))
        assertEquals(0, t.secondsLeft(now))
        assertEquals(0, t.secondsLeft(now + 5000))
    }

    // ---- TimerText ----

    @Test fun `clock shows minutes and seconds, hours past an hour, Done at zero`() {
        assertEquals("Done", TimerText.clock(0))
        assertEquals("Done", TimerText.clock(-3))
        assertEquals("0:01", TimerText.clock(1))
        assertEquals("9:12", TimerText.clock(552))
        assertEquals("59:59", TimerText.clock(3599))
        assertEquals("1h00", TimerText.clock(3600))
        assertEquals("1h05", TimerText.clock(3900))
        assertEquals("24h00", TimerText.clock(86_400))
    }

    @Test fun `the face with no timers is idle`() {
        assertEquals(TimerText.Face("Timer", 0, TimerText.State.IDLE), TimerText.face(emptyList(), now))
    }

    @Test fun `the face with one running timer shows its time`() {
        val face = TimerText.face(listOf(timer(1, "a", now + 90_000)), now)
        // No length saved with it, so its drain line reads off an hour: 90 s of 3600.
        assertEquals(TimerText.Face("1:30", 1, TimerText.State.RUNNING, drain = 0.025f), face)
    }

    @Test fun `the face with several timers shows the soonest and the count`() {
        val sorted = listOf(timer(2, "b", now + 30_000), timer(1, "a", now + 600_000), timer(3, "c", now + 700_000))
        assertEquals(TimerText.Face("0:30", 3, TimerText.State.RUNNING, drain = 30f / 3600f), TimerText.face(sorted, now))
    }

    @Test fun `the face turns done when one timer is done`() {
        val sorted = listOf(timer(1, "a", now - 1000), timer(2, "b", now + 60_000))
        assertEquals(TimerText.Face("Done", 2, TimerText.State.DONE), TimerText.face(sorted, now))
    }

    @Test fun `started says minutes when whole, a clock when not`() {
        assertEquals("Timer · 10 min", TimerText.started(600))
        assertEquals("Timer · 1 min", TimerText.started(60))
        assertEquals("Timer · 1:30", TimerText.started(90))
        assertEquals("Timer · 60 min", TimerText.started(3600))
    }

    @Test fun `stopped says Stopped for a running timer and Dismissed for a done one`() {
        assertEquals("Stopped Pasta", TimerText.stopped(timer(1, "Pasta", now + 1), now))
        assertEquals("Dismissed Pasta", TimerText.stopped(timer(1, "Pasta", now), now))
    }

    @Test fun `clampMinutes keeps minutes between one and a day`() {
        assertEquals(1, TimerText.clampMinutes(0))
        assertEquals(1, TimerText.clampMinutes(-10))
        assertEquals(12, TimerText.clampMinutes(12))
        assertEquals(1440, TimerText.clampMinutes(1440))
        assertEquals(1440, TimerText.clampMinutes(5000))
    }

    @Test fun `describe with no timers says how to start one`() {
        assertEquals("Timer. Hold, then slide to a time.", TimerText.describe(emptyList(), now))
    }

    @Test fun `describe names the timer that ends first`() {
        val sorted = listOf(timer(1, "Pasta", now + 552_000), timer(2, "Tea", now + 900_000))
        assertEquals(
            "2 timers running. Pasta ends first, in 9:12. Tap to see them, hold to start another.",
            TimerText.describe(sorted, now),
        )
    }

    @Test fun `describe says when the first timer is done`() {
        val sorted = listOf(timer(1, "Pasta", now - 1))
        assertEquals(
            "1 timer running. Pasta is done. Tap to see them, hold to start another.",
            TimerText.describe(sorted, now),
        )
    }

    // ---- TimerPress ----

    // Slop 10 px, reach 30 px. The press starts at (100, 100); bubbles above it.
    private fun press() = TimerPress(slop = 10f, reach = 30f)
    private val bubbles = listOf(PillLayout.Spot(100, 0), PillLayout.Spot(200, 0))

    @Test fun `a quick tap is a Tap`() {
        val p = press()
        p.down(100f, 100f)
        assertEquals(TimerPress.Outcome.Tap, p.up())
    }

    @Test fun `a small move is still a Tap`() {
        val p = press()
        p.down(100f, 100f)
        assertFalse(p.move(106f, 108f, bubbles)) // 10 px away: not past the slop
        assertFalse(p.fanOpen)
        assertEquals(TimerPress.Outcome.Tap, p.up())
    }

    @Test fun `holding opens the fan once`() {
        val p = press()
        p.down(100f, 100f)
        assertTrue(p.held())
        assertTrue(p.fanOpen)
        assertFalse(p.held())
    }

    @Test fun `held does nothing when not pressing`() {
        val p = press()
        assertFalse(p.held())
        p.down(100f, 100f)
        p.up()
        assertFalse(p.held())
        assertFalse(p.fanOpen)
    }

    @Test fun `a drag past the slop opens the fan, once`() {
        val p = press()
        p.down(100f, 100f)
        assertTrue(p.move(100f, 89f, bubbles))
        assertTrue(p.fanOpen)
        assertFalse(p.move(100f, 80f, bubbles))
        assertFalse(p.held())
    }

    @Test fun `move does nothing when not pressing`() {
        val p = press()
        assertFalse(p.move(100f, 0f, bubbles))
        assertFalse(p.fanOpen)
        assertEquals(-1, p.hot)
    }

    @Test fun `hot is the nearest bubble within reach`() {
        val p = press()
        p.down(100f, 100f)
        p.move(110f, 5f, bubbles)
        assertEquals(0, p.hot)
        p.move(190f, 10f, bubbles)
        assertEquals(1, p.hot)
    }

    @Test fun `hot is -1 when no bubble is within reach`() {
        val p = press()
        p.down(100f, 100f)
        p.move(150f, 0f, bubbles) // 50 px from both
        assertEquals(-1, p.hot)
        p.move(100f, 30f, bubbles) // exactly at reach does not count
        assertEquals(-1, p.hot)
    }

    @Test fun `hot is found after a hold without moving past the slop`() {
        val p = press()
        p.down(100f, 10f)
        p.held()
        assertFalse(p.move(100f, 10f, bubbles))
        assertEquals(0, p.hot)
    }

    @Test fun `hot leaves a bubble when the finger moves off it`() {
        val p = press()
        p.down(100f, 100f)
        p.move(100f, 0f, bubbles)
        assertEquals(0, p.hot)
        p.move(150f, 100f, bubbles)
        assertEquals(-1, p.hot)
    }

    @Test fun `letting go on a bubble picks it`() {
        val p = press()
        p.down(100f, 100f)
        p.move(200f, 0f, bubbles)
        assertEquals(TimerPress.Outcome.Pick(1), p.up())
    }

    @Test fun `letting go elsewhere with the fan open does nothing`() {
        val p = press()
        p.down(100f, 100f)
        p.held()
        assertEquals(TimerPress.Outcome.None, p.up())
    }

    @Test fun `up resets the press`() {
        val p = press()
        p.down(100f, 100f)
        p.move(100f, 0f, bubbles)
        p.up()
        assertFalse(p.fanOpen)
        assertEquals(-1, p.hot)
        assertEquals(TimerPress.Outcome.None, p.up())
    }

    @Test fun `after cancel the next up is None`() {
        val p = press()
        p.down(100f, 100f)
        p.move(100f, 0f, bubbles)
        p.cancel()
        assertFalse(p.fanOpen)
        assertEquals(-1, p.hot)
        assertEquals(TimerPress.Outcome.None, p.up())
    }

    @Test fun `up without down is None`() {
        assertEquals(TimerPress.Outcome.None, press().up())
    }

    @Test fun `a new down starts fresh`() {
        val p = press()
        p.down(100f, 100f)
        p.move(100f, 0f, bubbles)
        p.down(300f, 300f)
        assertFalse(p.fanOpen)
        assertEquals(-1, p.hot)
        // The slop is measured from the new down.
        assertFalse(p.move(305f, 305f, bubbles))
        assertEquals(TimerPress.Outcome.Tap, p.up())
    }
}
