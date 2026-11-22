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
    // The timer pill's touch window at 1x.
    private val timerW = PillLayout.TIMER_W_DP
    private val timerH = PillLayout.TIMER_H_DP

    private fun timer(x: Int, y: Int, stacked: Boolean, volumeShown: Boolean = true) =
        layout.timerPill(x, y, timerW, timerH, screenW, stacked, volumeShown)

    private fun fan(x: Int, y: Int, stacked: Boolean, volumeShown: Boolean = true) =
        layout.timerFan(timer(x, y, stacked, volumeShown), timerW, timerH, stacked, x, screenW, screenH)

    /** Button heights to try: as high and as low as [BubbleBounds] lets it go with these pills, and the middle. */
    private fun heights(stacked: Boolean): List<Int> {
        val bounds = BubbleBounds(0, 0, screenW, screenH, 56, layout.above(true, stacked), layout.below(true))
        return listOf(bounds.clampY(0), 372, bounds.clampY(screenH))
    }

    @Test fun `beside the button the timer pill is on the side facing the middle, level with the button`() {
        // Docked right: 2 px left of the ring window, 66 wide. The 44 px target is centred on the 56 px ring.
        assertEquals(PillLayout.Spot(336 - 2 - 66, 406), timer(right, 400, stacked = false))
        // Docked left: 2 px right of the ring window.
        assertEquals(PillLayout.Spot(8 + 56 + 2, 406), timer(left, 400, stacked = false))
    }

    @Test fun `beside the button the timer pill stays level with it near the top and the bottom`() {
        for (x in listOf(left, right)) for (y in heights(stacked = false)) {
            assertEquals(y + 6, timer(x, y, stacked = false).y)
        }
    }

    @Test fun `stacked, the timer pill's touch area ends where the volume pill's starts`() {
        for (x in listOf(left, right)) {
            val volume = volume(x, 400)
            val timer = timer(x, 400, stacked = true)
            // Volume touch area 360..404, so the timer's is 316..360: both keep 44 px and share no pixel.
            assertEquals(360, volume.y)
            assertEquals(volume.y, timer.y + timerH)
            // Same column: both centred on the button.
            assertEquals(x + 28, timer.x + timerW / 2)
        }
    }

    @Test fun `stacked with the volume pill off, the timer pill takes the volume pill's spot`() {
        for (x in listOf(left, right)) {
            assertEquals(volume(x, 400).y, timer(x, 400, stacked = true, volumeShown = false).y)
        }
    }

    @Test fun `the stack never goes off the top of the screen`() {
        // Two pills above the button: the volume pill's 40 px and the timer pill's 44.
        assertEquals(84, layout.above(volumeShown = true, timerStacked = true))
        assertEquals(40, layout.above(volumeShown = false, timerStacked = true))
        assertEquals(40, layout.above(volumeShown = true, timerStacked = false))
        assertEquals(0, layout.above(volumeShown = false, timerStacked = false))
        // As high as the button may go with both, the timer pill's touch area starts at the screen's top.
        val top = heights(stacked = true).first()
        assertEquals(84, top)
        assertEquals(0, timer(right, top, stacked = true).y)
    }

    @Test fun `with the timer pill stacked, the track opens above it`() {
        assertEquals(394 - 44, layout.track(300, 500, 262, 76, 400, timerStacked = true).y)
    }

    @Test fun `messages beside the button start past a timer pill that sits there`() {
        assertEquals(2 + 66, layout.beside(timerBeside = true))
        assertEquals(0, layout.beside(timerBeside = false))
    }

    @Test fun `the fan has five bubbles on a 92 px arc from the pill toward the middle`() {
        val f = fan(right, 372, stacked = false)
        // The pill's middle is (301, 400); straight out is 92 px left, the outer two 60 degrees up and down.
        assertEquals(PillLayout.Spot(301 - 92, 400), f.centres[2])
        assertEquals(PillLayout.Spot(301 - 46, 400 - 79), f.centres[0])
        assertEquals(PillLayout.Spot(301 - 46, 400 + 79), f.centres[4])
        val mirrored = fan(left, 372, stacked = false)
        assertEquals(PillLayout.Spot(99 + 92, 400), mirrored.centres[2])
    }

    @Test fun `the fan opens toward the middle, stays on screen and never covers the button`() {
        for (stacked in listOf(false, true)) for (x in listOf(left, right)) for (y in heights(stacked)) {
            val f = fan(x, y, stacked)
            val where = "stacked $stacked, x $x, y $y"
            val buttonCx = x + 28
            val buttonCy = y + 28
            assertEquals(where, 5, f.centres.size)
            for (c in f.centres) {
                // The bubble under the finger is 52 px across.
                assertTrue(where, c.x - 26 >= 0 && c.x + 26 <= screenW)
                assertTrue(where, c.y - 26 >= 0 && c.y + 26 <= screenH)
                assertTrue(where, c.x - 26 >= f.window.x && c.x + 26 <= f.window.x + f.width)
                assertTrue(where, c.y - 26 >= f.window.y + f.chipsHeight && c.y + 26 <= f.window.y + f.height)
                // Toward the middle of the screen from the button.
                assertTrue(where, if (x == right) c.x < buttonCx else c.x > buttonCx)
                // Clear of the 44 px button: the two circles do not touch.
                val dx = (c.x - buttonCx).toDouble()
                val dy = (c.y - buttonCy).toDouble()
                assertTrue(where, Math.hypot(dx, dy) > 22 + 26)
            }
            assertTrue(where, f.window.y >= 0 && f.window.y + f.height <= screenH)
        }
    }

    @Test fun `a timer card sits toward the middle with its bottom on the button's top`() {
        // Docked right, 272 x 120: 38 px left of the ring window.
        assertEquals(PillLayout.Spot(336 - 38 - 272, 400 - 120), layout.timerCard(right, 400, 272, 120, screenW, screenH))
        assertEquals(PillLayout.Spot(8 + 56 + 38, 400 - 120), layout.timerCard(left, 400, 272, 120, screenW, screenH))
    }

    @Test fun `a timer card goes under the button when there is no room above it`() {
        assertEquals(40 + 56, layout.timerCard(right, 40, 272, 120, screenW, screenH).y)
    }

    @Test fun `a timer card stays on screen and never covers the button`() {
        // A narrow phone too: 360 px wide.
        for (w in listOf(360, 400)) for (x in listOf(8, w - 64)) for (stacked in listOf(false, true)) for (y in heights(stacked)) {
            for (cardH in listOf(60, 120, 400)) {
                val spot = layout.timerCard(x, y, 272, cardH, w, screenH)
                val where = "screen $w, x $x, y $y, card $cardH"
                assertTrue(where, spot.x >= 0 && spot.x + 272 <= w)
                assertTrue(where, spot.y >= 0 && spot.y + cardH <= screenH)
                // The button is the 44 px in the middle of the ring window; the card is all to one side of it.
                assertTrue(where, spot.x + 272 <= x + 6 || spot.x >= x + 50)
            }
        }
    }
}
