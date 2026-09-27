package com.lperezmo.voicerelay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumePresetsTest {

    private val s25 = intArrayOf(15, 15, 15, 15)
    private val normal = VolumePreset.Ringer.NORMAL

    @Test fun `percentages land on whole steps, and above zero never rounds to off`() {
        assertArrayEquals(intArrayOf(6, 6, 6, 1), VolumeMath.levelsFor(VolumePreset.MEDIUM, s25))
        assertArrayEquals(intArrayOf(15, 15, 15, 3), VolumeMath.levelsFor(VolumePreset.LOUD, s25))
        assertEquals(1, VolumeMath.steps(1, 7))
        assertEquals(0, VolumeMath.steps(0, 15))
    }

    @Test fun `a stream the phone lacks is skipped, not zeroed`() {
        val noNotifications = intArrayOf(15, -1, 15, 15)
        assertEquals(-1, VolumeMath.levelsFor(VolumePreset.LOUD, noNotifications)[1])
        val levels = intArrayOf(6, -1, 6, 1)
        assertEquals(PillState.MEDIUM, VolumeMath.classify(levels, noNotifications, false, normal))
    }

    @Test fun `each preset reads back as itself`() {
        val zero = IntArray(4)
        assertEquals(PillState.DND, VolumeMath.classify(zero, s25, true, VolumePreset.Ringer.SILENT))
        assertEquals(PillState.MUTE, VolumeMath.classify(zero, s25, false, VolumePreset.Ringer.VIBRATE))
        assertEquals(PillState.MEDIUM, VolumeMath.classify(intArrayOf(6, 6, 6, 1), s25, false, normal))
        assertEquals(PillState.LOUD, VolumeMath.classify(intArrayOf(15, 15, 15, 3), s25, false, normal))
    }

    @Test fun `the DND hour ending leaves silent, not mute`() {
        assertEquals(PillState.SILENT, VolumeMath.classify(IntArray(4), s25, false, VolumePreset.Ringer.SILENT))
    }

    @Test fun `changing media keeps the preset`() {
        assertEquals(PillState.MEDIUM, VolumeMath.classify(intArrayOf(6, 6, 6, 9), s25, false, normal))
        assertEquals(PillState.MEDIUM, VolumeMath.classify(intArrayOf(6, 6, 6, 0), s25, false, normal))
        assertEquals(PillState.LOUD, VolumeMath.classify(intArrayOf(15, 15, 15, 1), s25, false, normal))
    }

    @Test fun `mute and DND need media at 0 too`() {
        assertEquals(PillState.CUSTOM, VolumeMath.classify(intArrayOf(0, 0, 0, 7), s25, false, VolumePreset.Ringer.VIBRATE))
        assertEquals(PillState.CUSTOM, VolumeMath.classify(intArrayOf(0, 0, 0, 7), s25, true, VolumePreset.Ringer.SILENT))
    }

    @Test fun `a hand-changed volume, or someone else's DND, is custom`() {
        assertEquals(PillState.CUSTOM, VolumeMath.classify(intArrayOf(6, 6, 8, 1), s25, false, normal))
        assertEquals(PillState.CUSTOM, VolumeMath.classify(intArrayOf(15, 15, 15, 3), s25, true, normal))
    }

    // --- The V ---

    /** Stops every 60 px from x = 40, as on the track at 1 px per dp. */
    private fun gesture() = VGesture(dip = 20f, rise = 16f, slop = 8f) { x ->
        ((x - 40f) / 60f).let { Math.round(it) }.coerceIn(0, 3)
    }

    @Test fun `a V from DND to LOUD applies LOUD once`() {
        val g = gesture()
        g.down(40f, 24f, current = 0)
        assertNull(g.move(100f, 50f))
        assertNull(g.move(160f, 70f))
        assertEquals(3, g.move(220f, 50f))
        assertNull(g.move(220f, 20f))
        assertEquals(VGesture.End.COMMITTED, g.up())
    }

    @Test fun `dip and rise in place re-applies the current preset`() {
        val g = gesture()
        g.down(160f, 24f, current = 2)
        assertNull(g.move(160f, 50f))
        assertEquals(2, g.move(160f, 30f))
    }

    @Test fun `a tap applies nothing`() {
        val g = gesture()
        g.down(100f, 24f, current = 1)
        assertNull(g.move(102f, 26f))
        assertEquals(VGesture.End.TAP, g.up())
    }

    @Test fun `a sideways swipe with no dip applies nothing`() {
        val g = gesture()
        g.down(40f, 24f, current = 0)
        for (x in 40..220 step 10) assertNull(g.move(x.toFloat(), 30f))
        assertEquals(VGesture.End.CANCELLED, g.up())
    }

    @Test fun `dipping and letting go cancels`() {
        val g = gesture()
        g.down(100f, 24f, current = 1)
        assertNull(g.move(220f, 60f))
        assertNull(g.move(220f, 50f))
        assertEquals(VGesture.End.CANCELLED, g.up())
    }

    @Test fun `the rise counts from the lowest point, not from the dip`() {
        val g = gesture()
        g.down(100f, 20f, current = 1)
        assertNull(g.move(100f, 45f))
        assertNull(g.move(100f, 70f))
        // 15 px back up from 70 is short of the rise, though it is well above the dip.
        assertNull(g.move(100f, 55f))
        assertEquals(1, g.move(100f, 53f))
    }
}
