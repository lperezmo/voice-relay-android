package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphGestureTest {

    private fun gesture() = PullDown(pull = 12f, slop = 10f)

    @Test fun `a tap fires on release`() {
        val g = gesture()
        g.down(0f, 0f)
        assertFalse(g.move(2f, 3f))
        assertTrue(g.up())
    }

    @Test fun `a pull down fires once, as soon as it is far enough, and not again on release`() {
        val g = gesture()
        g.down(0f, 0f)
        assertFalse(g.move(0f, 6f))
        assertTrue(g.move(1f, 13f))
        assertFalse(g.move(1f, 40f))
        assertFalse(g.move(1f, 120f))
        assertFalse(g.up())
    }

    @Test fun `history points fire once across a whole batch`() {
        val g = gesture()
        g.down(0f, 0f)
        val fires = (1..30).count { g.move(0f, it * 2f) }
        assertEquals(1, fires)
    }

    @Test fun `sideways, upward and diagonal-sideways drags do nothing`() {
        for ((x, y) in listOf(40f to 0f, 0f to -40f, 30f to 20f, -30f to 20f)) {
            val g = gesture()
            g.down(0f, 0f)
            assertFalse("($x, $y)", g.move(x, y))
            assertFalse("($x, $y) release", g.up())
        }
    }

    @Test fun `a cancelled touch fires nothing`() {
        val g = gesture()
        g.down(0f, 0f)
        g.cancel()
        assertFalse(g.move(0f, 30f))
        assertFalse(g.up())
    }

    @Test fun `each touch starts fresh`() {
        val g = gesture()
        g.down(0f, 0f)
        assertTrue(g.move(0f, 20f))
        g.up()
        g.down(100f, 100f)
        assertTrue(g.move(100f, 120f))
    }
}
