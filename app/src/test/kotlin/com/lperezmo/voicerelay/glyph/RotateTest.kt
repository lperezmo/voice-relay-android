package com.lperezmo.voicerelay.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotateTest {
    @Test
    fun flipWithNothingHeldGoesAgainstWhatShows() {
        assertEquals(Rotate.Step(Hold.SIDEWAYS, "Screen held sideways."), Rotate.flip(null, showingSideways = false))
        assertEquals(Rotate.Step(Hold.UPRIGHT, "Screen held upright."), Rotate.flip(null, showingSideways = true))
    }

    @Test
    fun flipWhileHeldGoesToTheOtherHold() {
        assertEquals(Rotate.Step(Hold.UPRIGHT, "Screen held upright."), Rotate.flip(Hold.SIDEWAYS, showingSideways = true))
        assertEquals(Rotate.Step(Hold.SIDEWAYS, "Screen held sideways."), Rotate.flip(Hold.UPRIGHT, showingSideways = false))
        // the hold wins over a stale reading of the screen
        assertEquals(Hold.UPRIGHT, Rotate.flip(Hold.SIDEWAYS, showingSideways = false).hold)
        assertEquals(Hold.SIDEWAYS, Rotate.flip(Hold.UPRIGHT, showingSideways = true).hold)
    }

    @Test
    fun flippingTwiceComesBack() {
        val first = Rotate.flip(null, showingSideways = false)
        val second = Rotate.flip(first.hold, showingSideways = true)
        assertEquals(Hold.UPRIGHT, second.hold)
    }

    @Test
    fun autoLetsGo() {
        for (h in Hold.values()) {
            val step = Rotate.auto(h)
            assertNull(step.hold)
            assertEquals("Rotation is back to auto.", step.say)
        }
        assertEquals(Rotate.Step(null, "The screen isn't held."), Rotate.auto(null))
    }
}
