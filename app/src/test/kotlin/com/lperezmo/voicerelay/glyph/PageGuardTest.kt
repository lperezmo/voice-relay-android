package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.glyph.recognizer.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Strokes are built in dp and scaled to pixels at a typical phone density. */
class PageGuardTest {
    private val density = 2.75f

    private fun px(v: Float) = v * density
    private fun dot(x: Float, y: Float) = listOf(Pt(px(x), px(y), 0))

    /** A straight line from (x0, y0) to (x1, y1), in dp, in [n] steps. */
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 10): List<Pt> =
        (0..n).map { i -> Pt(px(x0 + (x1 - x0) * i / n), px(y0 + (y1 - y0) * i / n), 0) }

    /** A polyline through dp points. */
    private fun poly(vararg xy: Float): List<Pt> = xy.toList().chunked(2).map { (x, y) -> Pt(px(x), px(y), 0) }

    /** A circle (or an arc of [sweep] degrees) around (cx, cy), radius [r], in dp. */
    private fun circle(cx: Float, cy: Float, r: Float, sweep: Float = 360f, n: Int = 32): List<Pt> =
        (0..n).map { i ->
            val a = sweep / 360f * 2 * PI * i / n
            Pt(px(cx + r * cos(a).toFloat()), px(cy + r * sin(a).toFloat()), 0)
        }

    /** "ok" with letters [h] dp tall: an o, the k's stem and the k's arms. */
    private fun ok(h: Float): List<List<Pt>> = listOf(
        circle(100f, 200f - h / 4, h / 4),
        line(100f + h * 0.7f, 200f - h, 100f + h * 0.7f, 200f),
        poly(100f + h * 1.1f, 200f - h / 2, 100f + h * 0.7f, 200f - h / 4, 100f + h * 1.1f, 200f),
    )

    /** A "?" [h] dp tall: the hook and the dot. */
    private fun question(x: Float, h: Float): List<List<Pt>> = listOf(
        circle(x, 200f - h * 0.7f, h * 0.25f, sweep = 270f) + line(x, 200f - h * 0.45f, x, 200f - h * 0.2f, 3),
        dot(x, 200f),
    )

    @Test fun `three dots are not a question`() {
        assertFalse(PageGuard.enough(listOf(dot(10f, 10f), dot(40f, 12f), dot(80f, 30f)), density))
    }

    @Test fun `a single short dash is not a question`() {
        assertFalse(PageGuard.enough(listOf(line(50f, 50f, 90f, 52f)), density))
        // nor a long one: one stroke, and not a word's length
        assertFalse(PageGuard.enough(listOf(line(20f, 50f, 220f, 52f)), density))
    }

    @Test fun `a single small loop or squiggle is not a question`() {
        assertFalse(PageGuard.enough(listOf(circle(100f, 100f, 15f)), density))
        val squiggle = poly(0f, 0f, 15f, 20f, 30f, 0f, 45f, 20f, 60f, 0f, 75f, 20f, 90f, 0f)
        assertFalse(PageGuard.enough(listOf(squiggle), density))
        // dots next to it add nothing
        assertFalse(PageGuard.enough(listOf(squiggle, dot(5f, 40f), dot(30f, 40f)), density))
    }

    @Test fun `a dash and a few dots are not a question`() {
        assertFalse(PageGuard.enough(listOf(line(50f, 50f, 80f, 50f), dot(10f, 10f), dot(100f, 10f)), density))
        // two short flicks either
        assertFalse(PageGuard.enough(listOf(line(0f, 0f, 20f, 0f), line(40f, 0f, 60f, 0f)), density))
    }

    @Test fun `a two-letter word is a question, even written small`() {
        assertTrue(PageGuard.enough(ok(40f), density))
        // a k 36 dp tall (under 6 mm) is about 126 dp of ink; much smaller is no longer writing
        assertTrue(PageGuard.enough(ok(36f), density))
        assertFalse(PageGuard.enough(ok(28f), density))
        assertTrue(PageGuard.enough(ok(40f) + question(180f, 40f), density))
    }

    @Test fun `a normal sentence is a question`() {
        val ink = mutableListOf<List<Pt>>()
        var x = 20f
        repeat(4) {
            ink += ok(45f).map { s -> s.map { p -> Pt(p.x + px(x), p.y, 0) } }
            x += 90f
        }
        ink += question(x + 20f, 45f)
        assertTrue(PageGuard.enough(ink, density))
    }

    @Test fun `one long cursive stroke is a word`() {
        val cursive = (0..60).map { i -> Pt(px(20f + i * 5f), px(200f + 20f * sin(i * 0.9f)), 0) }
        assertTrue(PageGuard.enough(listOf(cursive), density))
    }

    @Test fun `lenses only, or dots plus a lens, are not a question`() {
        // lit lenses are not in the ink at all
        val state = PageState()
        val lens = state.addStroke(circle(200f, 300f, 80f), density)
        assertTrue(lens!!.lit)
        assertTrue(state.ink.isEmpty())
        assertFalse(PageGuard.enough(state.ink, density))
        state.ink += dot(30f, 30f)
        state.ink += dot(60f, 30f)
        assertFalse(PageGuard.enough(state.ink, density))
        // the same writing that counts without a lens counts with one
        state.ink += ok(40f)
        assertTrue(PageGuard.enough(state.ink, density))
    }

    @Test fun `nothing is not a question, and lengths are in dp`() {
        assertFalse(PageGuard.enough(emptyList(), density))
        assertEquals(0f, PageGuard.length(dot(1f, 1f)))
        assertEquals(px(40f), PageGuard.length(line(0f, 0f, 40f, 0f)), 0.01f)
        // the same strokes at another density give the same answer
        assertTrue(PageGuard.enough(ok(40f).map { s -> s.map { Pt(it.x / density, it.y / density, 0) } }, 1f))
    }
}
