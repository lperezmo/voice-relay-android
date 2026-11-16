package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.glyph.recognizer.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class LensTest {
    /** An arc of [sweepDeg] degrees around (cx, cy), [n] segments; 360 ends where it began. */
    private fun arc(cx: Float, cy: Float, r: Float, sweepDeg: Float = 360f, n: Int = 40): List<Pt> =
        (0..n).map { i ->
            val a = sweepDeg / 360f * 2 * PI * i / n
            Pt(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), 0)
        }

    private fun circle(cx: Float, cy: Float, r: Float) = arc(cx, cy, r)

    /** A closed square traced with [perSide] points on each side. */
    private fun square(x0: Float, y0: Float, side: Float, perSide: Int = 5): List<Pt> {
        val corners = listOf(x0 to y0, x0 + side to y0, x0 + side to y0 + side, x0 to y0 + side, x0 to y0)
        val pts = mutableListOf<Pt>()
        for (c in 0 until 4) {
            val (ax, ay) = corners[c]
            val (bx, by) = corners[c + 1]
            for (k in 0 until perSide) {
                val t = k.toFloat() / perSide
                pts.add(Pt(ax + (bx - ax) * t, ay + (by - ay) * t, 0))
            }
        }
        pts.add(Pt(x0, y0, 0))
        return pts
    }

    // --- isClosed -------------------------------------------------------------------------

    @Test
    fun aFullCircleIsClosed() {
        assertTrue(Lens.isClosed(circle(200f, 200f, 100f)))
        assertTrue(Lens.isClosed(square(0f, 0f, 300f)))
    }

    @Test
    fun aNearlyFullCircleIsClosedButAnOpenArcIsNot() {
        // 330 degrees: gap about 0.09 of the path, under CLOSE_RATIO
        assertTrue(Lens.isClosed(arc(200f, 200f, 100f, 330f)))
        // 300 degrees: gap about 0.19 of the path, just over CLOSE_RATIO
        assertFalse(Lens.isClosed(arc(200f, 200f, 100f, 300f)))
        // a C shape and a half circle are clearly open
        assertFalse(Lens.isClosed(arc(200f, 200f, 100f, 270f)))
        assertFalse(Lens.isClosed(arc(200f, 200f, 100f, 180f)))
    }

    @Test
    fun aStraightLineIsNotClosed() {
        val line = (0..20).map { Pt(it * 10f, it * 3f, 0) }
        assertFalse(Lens.isClosed(line))
    }

    @Test
    fun aLineDrawnOutAndBackIsNotClosedEvenThoughItsEndsMeet() {
        val out = (0..10).map { Pt(it * 20f, 0f, 0) }
        val back = (9 downTo 0).map { Pt(it * 20f, 0.5f, 0) }
        assertFalse(Lens.isClosed(out + back))
    }

    @Test
    fun aTinyLoopIsNeverClosed() {
        // path about 19 px, under MIN_PATH_PX
        assertFalse(Lens.isClosed(circle(50f, 50f, 3f)))
    }

    @Test
    fun aStrokeWithTooFewPointsIsNeverClosed() {
        // a big closed square, but only 5 points (4 corners and back to the start)
        val five = listOf(Pt(0f, 0f, 0), Pt(300f, 0f, 0), Pt(300f, 300f, 0), Pt(0f, 300f, 0), Pt(0f, 0f, 0))
        assertFalse(Lens.isClosed(five))
        assertFalse(Lens.isClosed(arc(200f, 200f, 100f, n = Lens.MIN_POINTS - 2))) // MIN_POINTS - 1 points
        assertTrue(Lens.isClosed(arc(200f, 200f, 100f, n = Lens.MIN_POINTS - 1)))  // MIN_POINTS points
        assertFalse(Lens.isClosed(emptyList()))
        assertFalse(Lens.isClosed(listOf(Pt(1f, 1f, 0))))
    }

    // --- loopOf -----------------------------------------------------------------------------

    @Test
    fun loopOfIsNullForAnOpenStroke() {
        assertNull(Lens.loopOf(arc(200f, 200f, 100f, 180f), 1f, 0))
    }

    @Test
    fun aBigLoopIsLitAndASmallOneIsInk() {
        // density 1: 72 px threshold
        val big = Lens.loopOf(circle(200f, 200f, 40f), 1f, 0) // 80 px across
        assertNotNull(big)
        assertTrue(big!!.lit)
        val small = Lens.loopOf(circle(200f, 200f, 30f), 1f, 0) // 60 px across
        assertNotNull(small)
        assertFalse(small!!.lit)
    }

    @Test
    fun theSizeThresholdScalesWithDensity() {
        val stroke = circle(400f, 400f, 120f) // 240 px across
        assertTrue(Lens.loopOf(stroke, 2.75f, 0)!!.lit)  // threshold 198 px
        assertFalse(Lens.loopOf(stroke, 3.5f, 0)!!.lit)  // threshold 252 px
        val mid = circle(400f, 400f, 90f) // 180 px across
        assertTrue(Lens.loopOf(mid, 2f, 0)!!.lit)       // threshold 144 px
        assertFalse(Lens.loopOf(mid, 2.75f, 0)!!.lit)
    }

    @Test
    fun theSmallerSideDecidesSoAThinLoopIsInk() {
        // 400 wide but only 60 tall at density 1
        val thin = square(0f, 0f, 1f).map { Pt(it.x * 400f, it.y * 60f, 0) }
        val loop = Lens.loopOf(thin, 1f, 0)
        assertNotNull(loop)
        assertFalse(loop!!.lit)
    }

    @Test
    fun aBigLoopIsInkOnceMaxLitAreLit() {
        val stroke = circle(200f, 200f, 100f)
        assertTrue(Lens.loopOf(stroke, 1f, Lens.MAX_LIT - 1)!!.lit)
        assertFalse(Lens.loopOf(stroke, 1f, Lens.MAX_LIT)!!.lit)
    }

    @Test
    fun loopOfKeepsTheStroke() {
        val stroke = circle(200f, 200f, 100f)
        assertSame(stroke, Lens.loopOf(stroke, 1f, 0)!!.stroke)
    }

    // --- contains ---------------------------------------------------------------------------

    @Test
    fun containsFindsPointsInsideACircle() {
        val c = circle(200f, 200f, 100f)
        assertTrue(Lens.contains(c, 200f, 200f))
        assertTrue(Lens.contains(c, 270f, 200f))
        assertFalse(Lens.contains(c, 310f, 200f))
        assertFalse(Lens.contains(c, 290f, 290f)) // inside the bounding box, outside the circle
        assertFalse(Lens.contains(c, -50f, -50f))
    }

    @Test
    fun containsHandlesAConcaveShape() {
        // an L: the notch at the top right is outside
        val l = listOf(
            Pt(0f, 0f, 0), Pt(100f, 0f, 0), Pt(100f, 200f, 0), Pt(300f, 200f, 0),
            Pt(300f, 300f, 0), Pt(0f, 300f, 0),
        )
        assertTrue(Lens.contains(l, 50f, 50f))
        assertTrue(Lens.contains(l, 250f, 250f))
        assertFalse(Lens.contains(l, 200f, 100f))
    }

    @Test
    fun containsClosesTheGapOfANearlyClosedStroke() {
        val c = arc(200f, 200f, 100f, 330f)
        assertTrue(Lens.contains(c, 200f, 200f))
    }

    @Test
    fun containsIsFalseForDegenerateStrokes() {
        assertFalse(Lens.contains(emptyList(), 0f, 0f))
        assertFalse(Lens.contains(listOf(Pt(0f, 0f, 0), Pt(10f, 10f, 0)), 5f, 5f))
    }

    // --- toggle -----------------------------------------------------------------------------

    @Test
    fun toggleFlipsTheLoopUnderThePoint() {
        val loop = Loop(circle(200f, 200f, 100f), lit = true)
        val loops = listOf(loop)
        assertSame(loop, Lens.toggle(loops, 200f, 200f))
        assertFalse(loop.lit)
        assertSame(loop, Lens.toggle(loops, 200f, 200f))
        assertTrue(loop.lit)
    }

    @Test
    fun toggleIsNullOutsideEveryLoop() {
        val loop = Loop(circle(200f, 200f, 100f), lit = true)
        assertNull(Lens.toggle(listOf(loop), 600f, 600f))
        assertTrue(loop.lit)
        assertNull(Lens.toggle(emptyList(), 0f, 0f))
    }

    @Test
    fun togglePicksTheTopmostLoop() {
        val outer = Loop(circle(300f, 300f, 200f), lit = true)
        val inner = Loop(circle(300f, 300f, 50f), lit = false)
        val loops = listOf(outer, inner)
        // inside both: the later one (inner) is on top
        assertSame(inner, Lens.toggle(loops, 300f, 300f))
        assertTrue(inner.lit)
        assertTrue(outer.lit)
        // inside only the outer one
        assertSame(outer, Lens.toggle(loops, 300f, 150f))
        assertFalse(outer.lit)
        assertTrue(inner.lit)
    }

    @Test
    fun toggleRefusesAFifthLitLoop() {
        val lit = (0 until Lens.MAX_LIT).map { Loop(circle(150f + it * 300f, 150f, 100f), lit = true) }
        val fifth = Loop(circle(150f, 600f, 100f), lit = false)
        val loops = lit + fifth
        val hit = Lens.toggle(loops, 150f, 600f)
        assertSame(fifth, hit)
        assertFalse(fifth.lit)
        assertEquals(Lens.MAX_LIT, loops.count { it.lit })
        // turning one off makes room
        Lens.toggle(loops, 150f, 150f)
        assertFalse(lit[0].lit)
        Lens.toggle(loops, 150f, 600f)
        assertTrue(fifth.lit)
        assertEquals(Lens.MAX_LIT, loops.count { it.lit })
    }

    @Test
    fun toggleCanAlwaysTurnALoopOff() {
        val loops = (0 until Lens.MAX_LIT).map { Loop(circle(150f + it * 300f, 150f, 100f), lit = true) }
        Lens.toggle(loops, 450f, 150f)
        assertFalse(loops[1].lit)
        assertEquals(Lens.MAX_LIT - 1, loops.count { it.lit })
    }
}
