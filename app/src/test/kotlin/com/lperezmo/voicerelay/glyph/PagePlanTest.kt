package com.lperezmo.voicerelay.glyph

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which images a question sends: ink, lenses, or the whole screen, never lenses and the whole screen together. */
class PagePlanTest {
    @Test fun `off, it is the ink and the lit lenses as before`() {
        assertEquals(PagePlan(ink = true, lenses = 2, wholeScreen = false), PagePlan.of(true, 2, wholeScreen = false, hasShot = true))
        assertEquals(PagePlan(ink = false, lenses = 1, wholeScreen = false), PagePlan.of(false, 1, wholeScreen = false, hasShot = true))
        assertEquals(PagePlan(ink = true, lenses = 0, wholeScreen = false), PagePlan.of(true, 0, wholeScreen = false, hasShot = true))
        assertEquals(0, PagePlan.of(false, 0, wholeScreen = false, hasShot = true).count)
    }

    @Test fun `on, it is the ink and one whole screen, with no lens crops`() {
        val plan = PagePlan.of(true, 3, wholeScreen = true, hasShot = true)
        assertEquals(PagePlan(ink = true, lenses = 0, wholeScreen = true), plan)
        assertEquals(2, plan.count)
        val typed = PagePlan.of(false, 2, wholeScreen = true, hasShot = true)
        assertEquals(PagePlan(ink = false, lenses = 0, wholeScreen = true), typed)
        assertEquals(1, typed.count)
    }

    @Test fun `without a screenshot nothing of the screen goes, on or off`() {
        assertEquals(PagePlan(ink = true, lenses = 0, wholeScreen = false), PagePlan.of(true, 2, wholeScreen = true, hasShot = false))
        assertEquals(PagePlan(ink = true, lenses = 0, wholeScreen = false), PagePlan.of(true, 2, wholeScreen = false, hasShot = false))
        assertEquals(0, PagePlan.of(false, 4, wholeScreen = true, hasShot = false).count)
    }

    @Test fun `lenses are capped at the lit limit`() {
        assertEquals(Lens.MAX_LIT, PagePlan.of(true, Lens.MAX_LIT + 3, wholeScreen = false, hasShot = true).lenses)
        assertEquals(0, PagePlan.of(true, -1, wholeScreen = false, hasShot = true).lenses)
    }
}
