package com.lperezmo.voicerelay.glyph.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PDollarTest {
    private val threshold = PDollar.DEFAULT_THRESHOLD

    private val starters = mapOf(
        "heart" to Starters.heart(),
        "v" to Starters.v(),
        "circle" to Starters.circle(),
        "z" to Starters.z(),
    )
    private val templates = starters.map { (k, pts) -> PDollar.template(k, pts) }

    /** Scale, turn, move, wobble and optionally reverse a drawing, the way a hand would. */
    private fun variant(
        points: List<Pt>,
        scale: Float = 1f,
        degrees: Float = 0f,
        dx: Float = 0f,
        dy: Float = 0f,
        noise: Float = 0f,
        seed: Int = 1,
        reverse: Boolean = false,
    ): List<Pt> {
        val rnd = Random(seed)
        val r = degrees * PI.toFloat() / 180f
        val moved = points.map {
            val x = (it.x - 150) * scale
            val y = (it.y - 150) * scale
            val nx = (rnd.nextFloat() * 2 - 1) * noise * scale
            val ny = (rnd.nextFloat() * 2 - 1) * noise * scale
            Pt(x * cos(r) - y * sin(r) + 500 + dx + nx, x * sin(r) + y * cos(r) + 900 + dy + ny, it.stroke)
        }
        return if (reverse) moved.reversed() else moved
    }

    private fun assertMatches(expected: String, points: List<Pt>, label: String) {
        val ranked = PDollar.rank(points, templates)
        val best = ranked.first()
        println("$label -> " + ranked.joinToString { "%s %.3f".format(it.key, it.score) })
        assertEquals("$label best match", expected, best.key)
        assertTrue("$label score ${best.score} below threshold $threshold", best.score >= threshold)
    }

    @Test
    fun eachStarterMatchesItselfExactly() {
        for ((k, pts) in starters) assertMatches(k, pts, "$k exact")
    }

    @Test
    fun scaledVariantsMatch() {
        for ((k, pts) in starters) {
            assertMatches(k, variant(pts, scale = 0.35f, dx = -200f), "$k small")
            assertMatches(k, variant(pts, scale = 2.8f, dy = 300f), "$k large")
        }
    }

    @Test
    fun rotatedVariantsMatch() {
        for ((k, pts) in starters) {
            for (deg in listOf(-20f, -10f, 10f, 20f)) assertMatches(k, variant(pts, degrees = deg), "$k turned $deg")
        }
    }

    @Test
    fun wobblyHandDrawnVariantsMatch() {
        for ((k, pts) in starters) {
            for (seed in 1..5) {
                val p = variant(pts, scale = 0.8f + seed * 0.1f, degrees = (seed - 3) * 6f, noise = 10f, seed = seed)
                assertMatches(k, p, "$k wobbly $seed")
            }
        }
    }

    @Test
    fun directionDoesNotMatter() {
        for ((k, pts) in starters) assertMatches(k, variant(pts, reverse = true), "$k reversed")
    }

    @Test
    fun twoStrokeHeartMatchesTheOneStrokeHeart() {
        assertMatches("heart", Starters.heartTwoStrokes(), "heart two strokes")
        assertMatches("heart", variant(Starters.heartTwoStrokes(), scale = 0.6f, degrees = 12f, noise = 8f), "heart two strokes wobbly")
    }

    @Test
    fun aStraightLineIsNotAHeart() {
        val lines = listOf(
            Starters.polyline(listOf(20f to 150f, 280f to 150f)),
            Starters.polyline(listOf(150f to 20f, 150f to 280f)),
            Starters.polyline(listOf(20f to 20f, 280f to 280f)),
        )
        for ((i, line) in lines.withIndex()) {
            val ranked = PDollar.rank(line, templates)
            println("line $i -> " + ranked.joinToString { "%s %.3f".format(it.key, it.score) })
            val heart = ranked.first { it.key == "heart" }
            assertTrue("line $i scored ${heart.score} as a heart", heart.score < threshold)
            assertTrue("line $i matched ${ranked.first().key}", ranked.first().score < threshold)
        }
    }

    @Test
    fun aLooselyDrawnScribbleMatchesNothing() {
        val rnd = Random(7)
        val scribble = List(80) { Pt(rnd.nextFloat() * 300, rnd.nextFloat() * 300, 0) }
        val best = PDollar.recognize(scribble, templates)!!
        println("scribble -> ${best.key} ${best.score}")
        assertTrue("scribble scored ${best.score} as ${best.key}", best.score < threshold)
    }

    /** Every shipped sample, as the app loads them. */
    private val library = Starters.library().flatMap { (name, samples) -> samples.map { PDollar.template(name, it) } }

    private fun assertLibraryMatches(expected: String, points: List<Pt>, label: String) {
        val ranked = PDollar.rank(points, library)
        println("$label (library) -> " + ranked.joinToString { "%s %.3f".format(it.key, it.score) })
        assertEquals("$label best match", expected, ranked.first().key)
        assertTrue("$label score ${ranked.first().score} below threshold", ranked.first().score >= threshold)
    }

    @Test
    fun everyStarterVariantMatchesItsOwnShape() {
        for ((name, samples) in Starters.library()) {
            for ((i, pts) in samples.withIndex()) {
                assertLibraryMatches(name, pts, "$name variant $i")
                assertLibraryMatches(name, variant(pts, scale = 0.7f, degrees = 10f, noise = 9f, seed = i + 3), "$name variant $i wobbly")
            }
        }
    }

    @Test
    fun theLibraryStillReadsWobblyStartersAndKeepsHeartAndCircleApart() {
        val base = mapOf("Heart" to Starters.heart(), "V" to Starters.v(), "Circle" to Starters.circle(), "Z" to Starters.z())
        for ((name, pts) in base) {
            for (seed in 1..5) {
                val p = variant(pts, scale = 0.8f + seed * 0.1f, degrees = (seed - 3) * 6f, noise = 10f, seed = seed)
                assertLibraryMatches(name, p, "$name wobbly $seed")
            }
        }
        // sloppy circles: squashed, overshot, open; none may read as a heart
        val circles = listOf(Starters.oval(), Starters.circleOvershoot(), Starters.circle(40).subList(0, 37))
        for ((i, c) in circles.withIndex()) {
            for (seed in 1..3) {
                val ranked = PDollar.rank(variant(c, degrees = (seed - 2) * 15f, noise = 12f, seed = seed + 10 * i), library)
                println("sloppy circle $i/$seed -> " + ranked.joinToString { "%s %.3f".format(it.key, it.score) })
                assertEquals("sloppy circle $i/$seed", "Circle", ranked.first().key)
            }
        }
    }

    @Test
    fun theLibraryRejectsLinesAndScribbles() {
        val rnd = Random(7)
        val junk = listOf(
            Starters.polyline(listOf(20f to 150f, 280f to 150f)),
            Starters.polyline(listOf(150f to 20f, 150f to 280f)),
            List(80) { Pt(rnd.nextFloat() * 300, rnd.nextFloat() * 300, 0) },
        )
        for ((i, j) in junk.withIndex()) {
            val best = PDollar.recognize(j, library)!!
            println("junk $i (library) -> ${best.key} ${best.score}")
            assertTrue("junk $i scored ${best.score} as ${best.key}", best.score < threshold)
        }
    }

    @Test
    fun degenerateInputDoesNotCrash() {
        assertEquals(null, PDollar.recognize(emptyList(), templates))
        val dot = listOf(Pt(5f, 5f, 0), Pt(5f, 5f, 0), Pt(5f, 5f, 0))
        PDollar.recognize(dot, templates)
        assertEquals(PDollar.N, PDollar.resample(dot, PDollar.N).size)
    }

    @Test
    fun resampleAlwaysGivesNPoints() {
        for ((_, pts) in starters) assertEquals(PDollar.N, PDollar.resample(pts, PDollar.N).size)
        assertEquals(PDollar.N, PDollar.resample(Starters.heartTwoStrokes(), PDollar.N).size)
    }

    @Test
    fun recognitionIsFastEnough() {
        // 4 shapes x 3 samples, the size of a real library
        val lib = (1..3).flatMap { s -> starters.map { (k, pts) -> PDollar.template(k, variant(pts, noise = 6f, seed = s)) } }
        val probe = variant(Starters.heart(), noise = 8f, seed = 9)
        repeat(5) { PDollar.rank(probe, lib) }
        val start = System.nanoTime()
        repeat(20) { PDollar.rank(probe, lib) }
        val ms = (System.nanoTime() - start) / 1e6 / 20
        println("rank over ${lib.size} templates: %.2f ms".format(ms))
        assertTrue("took $ms ms", ms < 50)
    }
}
