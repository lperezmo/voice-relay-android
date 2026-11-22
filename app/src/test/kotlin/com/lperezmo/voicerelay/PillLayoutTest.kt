package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PillLayoutTest {

    // The service's button: a 44 dp circle in a 56 dp ring window, 8 dp from the edge.
    // At 1x the ring is 56 px and the button sits 6 px inside it.
    private val layout = PillLayout(dp = 1f, ringDp = 56, buttonDp = 44, marginDp = 8)

    // The views' own sizes at 1x: volume pill 50 x 44, track 262 x 76, Glyph pill 56 x 44.
    private fun volume(x: Int, y: Int) = layout.volumePill(x, y, 50, 44)
    private fun track(x: Int, y: Int, screenW: Int = 400) = layout.track(x, y, 262, 76, screenW)
    private fun glyph(x: Int, y: Int, screenH: Int = 2000) = layout.glyphPill(x, y, 56, 44, screenH)

    @Test fun `the volume pill is centered over the button, a gap above it`() {
        // Button top 506, pill bottom 500, the touch area reaches 4 px further down.
        assertEquals(PillLayout.Spot(303, 460), volume(300, 500))
    }

    @Test fun `the volume pill never goes above the screen`() {
        assertEquals(0, volume(300, 10).y)
    }

    @Test fun `the Glyph pill is centered under the button, a gap below it`() {
        // Button bottom 550, pill top 556, the touch area reaches 4 px further up.
        assertEquals(PillLayout.Spot(300, 552), glyph(300, 500))
    }

    @Test fun `the Glyph pill never goes below the screen`() {
        assertEquals(600 - 44, glyph(300, 560, screenH = 600).y)
    }

    @Test fun `on the right the track ends at the button's outer edge`() {
        // Button right edge 350; pill top 482, then a 12 px gap.
        assertEquals(PillLayout.Spot(350 - 262, 394), track(300, 500))
    }

    @Test fun `on the left the track starts at the button's outer edge`() {
        assertEquals(PillLayout.Spot(8 + 6, 394), track(8, 500))
    }

    @Test fun `the track stays a margin inside the screen`() {
        // A narrow screen: the right-side track would start at -112.
        assertEquals(8, track(100, 500, screenW = 200).x)
        assertEquals(8, track(300, 20).y)
    }

    @Test fun `only a shown pill takes room above or below the button`() {
        assertEquals(40, layout.above(volumeShown = true))
        assertEquals(40, layout.below(glyphShown = true))
        assertEquals(0, layout.above(volumeShown = false))
        assertEquals(0, layout.below(glyphShown = false))
    }

    @Test fun `sizes scale with the density and round down like the views do`() {
        val l = PillLayout(dp = 2.75f, ringDp = 56, buttonDp = 44, marginDp = 8)
        // ring 154, button 121, inset 16; (44 - 4 + 6) * 2.75 = 126.5 -> 126.
        assertEquals(126 - 16, l.above(volumeShown = true))
        // Width 137 (50 dp), height 121: button top 1016, gap 16, pad 11.
        assertEquals(PillLayout.Spot(1000 + (154 - 137) / 2, 1016 - 16 + 11 - 121), l.volumePill(1000, 1000, 137, 121))
    }

    // --- Timer pill ---

    // A 400 x 800 screen. Docked right the ring window is at x 336 (8 from the edge), docked left at 8.
    private val screenW = 400
    private val screenH = 800
    private val right = 336
    private val left = 8
    // The timer pill's touch window at 1x: 44 x 60.
    private val timerW = PillLayout.TIMER_W_DP
    private val timerH = PillLayout.TIMER_H_DP

    private fun timer(x: Int, y: Int, w: Int = screenW, h: Int = screenH) = layout.timerPill(x, y, timerW, timerH, w, h)
    private fun stub(x: Int, y: Int, w: Int = screenW, h: Int = screenH) = layout.timerStubCentre(x, y, w, h)
    private fun fan(x: Int, y: Int, w: Int = screenW, h: Int = screenH) = layout.timerFan(x, y, w, h)

    /** Button heights to try on a [h] px screen: as high and as low as [BubbleBounds] lets it go with every pill, and the middle. */
    private fun heights(w: Int = screenW, h: Int = screenH): List<Int> {
        val bounds = BubbleBounds(0, 0, w, h, 56, layout.above(true, true), layout.below(true, true))
        return listOf(bounds.clampY(0), h / 2 - 28, bounds.clampY(h))
    }

    /** Screens to try: a phone, a narrow phone and a phone on its side, with the button docked on each edge. */
    private val screens = listOf(400 to 800, 320 to 640, 800 to 360)
    private fun docks(w: Int) = listOf(8, w - 64)

    @Test fun `the timer pill's touch window sits beside the button, toward the middle, level with it`() {
        // Docked right: the button is 342..386, so the 44 px window ends on its left edge, 2 px taller than the ring.
        assertEquals(PillLayout.Spot(342 - 44, 398), timer(right, 400))
        // Docked left: the button is 14..58, so the window starts on its right edge.
        assertEquals(PillLayout.Spot(58, 398), timer(left, 400))
    }

    @Test fun `the stub sits 5 px from the button, its middle 36 px from the button's`() {
        // Button middle (364, 428): the 18 px stub is 364 - 22 - 5 - 9.
        assertEquals(PillLayout.Spot(364 - 36, 428), stub(right, 400))
        assertEquals(PillLayout.Spot(36 + 36, 428), stub(left, 400))
        assertEquals(14f, layout.timerStubInset())
    }

    @Test fun `the stub stays inside its touch window, grown or not`() {
        for (x in listOf(left, right)) {
            val window = timer(x, 400)
            val c = stub(x, 400)
            val halfW = PillLayout.TIMER_STUB_W_DP / 2
            assertTrue(c.x - halfW >= window.x && c.x + halfW <= window.x + timerW)
            assertTrue(c.y - PillLayout.TIMER_GROWN_H_DP / 2 >= window.y)
            assertTrue(c.y + PillLayout.TIMER_GROWN_H_DP / 2 <= window.y + timerH)
            // Grown it stays within the button's ring window's height, so it never reaches the volume or Glyph pill.
            assertTrue(PillLayout.TIMER_GROWN_H_DP <= 56)
        }
    }

    @Test fun `the timer pill stays on screen and level with the button everywhere it can go`() {
        for ((w, h) in screens) for (x in docks(w)) for (y in heights(w, h)) {
            val where = "screen $w x $h, x $x, y $y"
            val t = timer(x, y, w, h)
            assertTrue(where, t.x >= 0 && t.x + timerW <= w)
            assertTrue(where, t.y >= 0 && t.y + timerH <= h)
            assertEquals(where, y + 28, stub(x, y, w, h).y)
            // Toward the middle, never over the button's middle.
            val buttonCx = x + 28
            assertTrue(where, if (x > w / 2) t.x + timerW <= buttonCx - 22 else t.x >= buttonCx + 22)
        }
    }

    @Test fun `the timer pill's room above, below and beside the button`() {
        // Its 60 px window is 2 px taller than the ring window at each end; the volume and Glyph pills reach further.
        assertEquals(2, layout.above(volumeShown = false, timerShown = true))
        assertEquals(2, layout.below(glyphShown = false, timerShown = true))
        assertEquals(40, layout.above(volumeShown = true, timerShown = true))
        assertEquals(40, layout.below(glyphShown = true, timerShown = true))
        // Messages beside the button start past the window: 44 px, less the 6 px it shares with the ring window.
        assertEquals(38, layout.beside(timerShown = true))
        assertEquals(0, layout.beside(timerShown = false))
    }

    @Test fun `the fan has five bubbles on a 92 px arc from the stub toward the middle`() {
        val f = fan(right, 372)
        // The stub's middle is (328, 400); straight out is 92 px left, the outer two 60 degrees up and down.
        assertEquals(PillLayout.Spot(328 - 92, 400), f.centres[2])
        assertEquals(PillLayout.Spot(328 - 46, 400 - 79), f.centres[0])
        assertEquals(PillLayout.Spot(328 - 46, 400 + 79), f.centres[4])
        val mirrored = fan(left, 372)
        assertEquals(PillLayout.Spot(72 + 92, 400), mirrored.centres[2])
    }

    @Test fun `the fan opens toward the middle, stays on screen and never covers the button`() {
        for ((w, h) in screens) for (x in docks(w)) for (y in heights(w, h)) {
            val f = fan(x, y, w, h)
            val where = "screen $w x $h, x $x, y $y"
            val buttonCx = x + 28
            val buttonCy = y + 28
            assertEquals(where, 5, f.centres.size)
            for (c in f.centres) {
                // The bubble under the finger is 52 px across.
                assertTrue(where, c.x - 26 >= 0 && c.x + 26 <= w)
                assertTrue(where, c.y - 26 >= 0 && c.y + 26 <= h)
                assertTrue(where, c.x - 26 >= f.window.x && c.x + 26 <= f.window.x + f.width)
                assertTrue(where, c.y - 26 >= f.window.y + f.chipsHeight && c.y + 26 <= f.window.y + f.height)
                // Toward the middle of the screen from the button.
                assertTrue(where, if (x > w / 2) c.x < buttonCx else c.x > buttonCx)
                // Clear of the 44 px button: the two circles do not touch.
                val dx = (c.x - buttonCx).toDouble()
                val dy = (c.y - buttonCy).toDouble()
                assertTrue(where, Math.hypot(dx, dy) > 22 + 26)
            }
            assertTrue(where, f.window.y >= 0 && f.window.y + f.height <= h)
        }
    }

    @Test fun `a timer card sits toward the middle with its bottom on the button's top`() {
        // Docked right, 272 x 120: 38 px left of the ring window, its bottom 2 px over it, where the timer pill's window starts.
        assertEquals(PillLayout.Spot(336 - 38 - 272, 400 - 2 - 120), layout.timerCard(right, 400, 272, 120, screenW, screenH))
        assertEquals(PillLayout.Spot(8 + 56 + 38, 400 - 2 - 120), layout.timerCard(left, 400, 272, 120, screenW, screenH))
    }

    @Test fun `a timer card goes under the button when there is no room above it`() {
        assertEquals(40 + 56 + 2, layout.timerCard(right, 40, 272, 120, screenW, screenH).y)
    }

    @Test fun `a timer card stays on screen and never covers the button or the timer pill`() {
        // Phones at least 360 px wide: the card is 272 px and needs room beside the button.
        for ((w, h) in listOf(400 to 800, 360 to 640, 800 to 360)) for (x in docks(w)) for (y in heights(w, h)) {
            for (cardH in listOf(60, 120, 400)) {
                val cardW = 272
                val spot = layout.timerCard(x, y, cardW, cardH, w, h)
                val where = "screen $w x $h, x $x, y $y, card $cardH"
                assertTrue(where, spot.x >= 0 && spot.x + cardW <= w)
                // The button is the 44 px in the middle of the ring window; the card is all to one side of it.
                assertTrue(where, spot.x + cardW <= x + 6 || spot.x >= x + 50)
                if (cardH + 32 <= h) assertTrue(where, spot.y >= 0 && spot.y + cardH <= h)
                // The card leaves the timer pill's window alone, whenever it fits over or under the button.
                if (y - 2 - cardH >= 16 || y + 58 + cardH <= h - 16) {
                    val t = timer(x, y, w, h)
                    val apart = spot.x + cardW <= t.x || spot.x >= t.x + timerW ||
                        spot.y + cardH <= t.y || spot.y >= t.y + timerH
                    assertTrue(where, apart)
                }
            }
        }
    }
}
