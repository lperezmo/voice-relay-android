package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleBoundsTest {

    // A 1000 x 2000 screen with a 60 px status bar and a 100 px nav bar.
    private fun bounds(above: Int = 40, below: Int = 40) =
        BubbleBounds(left = 0, top = 60, right = 1000, bottom = 1900, ring = 56, above = above, below = below)

    @Test fun `dragged past the top, the pill above still fits under the status bar`() {
        assertEquals(100, bounds().clampY(-300))
    }

    @Test fun `dragged past the bottom, the Glyph pill below still fits above the nav bar`() {
        assertEquals(1900 - 56 - 40, bounds().clampY(5000))
    }

    @Test fun `without pills the button alone goes up to the edge of the safe area`() {
        assertEquals(60, bounds(above = 0, below = 0).clampY(-300))
    }

    @Test fun `a position in range is left alone`() {
        assertEquals(800, bounds().clampY(800))
    }

    @Test fun `a screen too short for the stack still keeps the button on it`() {
        val b = BubbleBounds(left = 0, top = 0, right = 1000, bottom = 100, ring = 56, above = 40, below = 40)
        assertEquals(0, b.clampY(-50))
        assertEquals(44, b.clampY(500))
    }

    @Test fun `x stays inside the safe area`() {
        val b = BubbleBounds(left = 80, top = 0, right = 1000, bottom = 2000, ring = 56, above = 0, below = 0)
        assertEquals(80, b.clampX(-20))
        assertEquals(944, b.clampX(990))
    }

    @Test fun `snaps to the nearer side`() {
        val b = bounds()
        assertEquals(1000 - 56 - 8, b.snapX(700, 8))
        assertEquals(8, b.snapX(200, 8))
    }

    @Test fun `a saved side goes back against that side`() {
        val b = bounds()
        assertEquals(true, b.isRight(700))
        assertEquals(false, b.isRight(200))
        assertEquals(1000 - 56 - 8, b.sideX(true, 8))
        assertEquals(8, b.sideX(false, 8))
    }

    @Test fun `a saved height comes back at the same spot on the same screen`() {
        val f = BubbleBounds.fraction(500, 56, 2000)
        assertEquals(500, BubbleBounds.yAt(f, 56, 2000))
    }

    @Test fun `a height saved in portrait lands inside a landscape screen`() {
        // At the very bottom of a 2000 px tall screen, then rotated to one 900 px tall.
        val landscape = BubbleBounds(left = 0, top = 0, right = 2000, bottom = 900, ring = 56, above = 40, below = 40)
        assertEquals(900 - 56 - 40, landscape.clampY(BubbleBounds.yAt(BubbleBounds.fraction(1900, 56, 2000), 56, 900)))
        // Higher up, the same share of the new height.
        assertEquals(794, landscape.clampY(BubbleBounds.yAt(BubbleBounds.fraction(1800, 56, 2000), 56, 900)))
    }

    @Test fun `an out of range fraction is kept on screen`() {
        assertEquals(1f, BubbleBounds.fraction(5000, 56, 2000))
        assertEquals(0f, BubbleBounds.fraction(-5000, 56, 2000))
        assertEquals(-28, BubbleBounds.yAt(-3f, 56, 2000))
        assertEquals(0.5f, BubbleBounds.fraction(10, 56, 0))
    }
}
