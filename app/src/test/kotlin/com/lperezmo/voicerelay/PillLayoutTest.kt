package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
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
}
