package com.lperezmo.voicerelay.glyph.recognizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The shapes the app ships with, drawn in code in a 300 x 300 box with screen
 * coordinates (y grows downwards). The user can redraw or delete any of them.
 */
object Starters {
    /** The classic heart curve, one stroke from the top dip round both lobes to the tip and back. */
    fun heart(steps: Int = 64): List<Pt> = List(steps + 1) { i ->
        val t = 2 * PI * i / steps
        val x = 16 * sin(t).pow(3)
        val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
        Pt((150 + x * 9).toFloat(), (150 - y * 9).toFloat(), 0)
    }

    /** A heart as most people draw it: two strokes, each from the top dip down to the tip. */
    fun heartTwoStrokes(steps: Int = 32): List<Pt> {
        val full = heart(steps * 2)
        val right = full.subList(0, steps + 1).map { it.copy(stroke = 0) }
        // the curve's second half runs tip to dip; drawn by hand it goes dip to tip
        val left = full.subList(steps, full.size).reversed().map { it.copy(stroke = 1) }
        return right + left
    }

    /**
     * A heart the way it comes out in a hurry: lopsided lobes, a longer tip, and a gap
     * at the top where the stroke did not quite close.
     */
    fun heartSloppy(steps: Int = 64): List<Pt> {
        val full = heart(steps)
        return full.subList(3, full.size - 3).map {
            val x = it.x - 150
            val y = it.y - 150
            val sx = if (x > 0) 1.14f else 0.9f
            val sy = if (y > 0) 1.18f else 1f
            Pt(150 + x * sx, 150 + y * sy, 0)
        }
    }

    /** Two round bumps and two straight sides to the tip, one stroke per side. */
    fun heartBumps(steps: Int = 16): List<Pt> {
        fun side(cx: Float, end: Float, stroke: Int): List<Pt> {
            val r = 55.0
            val lobe = List(steps + 1) { i ->
                val a = PI * i / steps
                val dir = if (end < 150f) 1 else -1
                Pt((cx + dir * r * cos(a)).toFloat(), (100 - r * sin(a)).toFloat(), stroke)
            }
            return lobe + polyline(listOf(end to 100f, 150f to 275f), stroke = stroke).drop(1)
        }
        return side(95f, 40f, 0) + side(205f, 260f, 1)
    }

    fun v(): List<Pt> = polyline(listOf(40f to 40f, 150f to 260f, 260f to 40f))

    /** A V with a rounded bottom, close to a U. */
    fun vRound(steps: Int = 40): List<Pt> = List(steps + 1) { i ->
        val t = i.toFloat() / steps
        val u = 1 - t
        // quadratic curve from the top left, pulled down past the bottom, to the top right
        Pt(u * u * 40f + 2 * u * t * 150f + t * t * 260f, u * u * 40f + 2 * u * t * 400f + t * t * 40f, 0)
    }

    /** A lopsided V: the right arm longer and leaning out. */
    fun vLopsided(): List<Pt> = polyline(listOf(70f to 80f, 135f to 260f, 275f to 30f))

    fun circle(steps: Int = 48): List<Pt> = List(steps + 1) { i ->
        val t = 2 * PI * i / steps - PI / 2
        Pt((150 + 120 * cos(t)).toFloat(), (150 + 120 * sin(t)).toFloat(), 0)
    }

    /** A wide oval. */
    fun oval(steps: Int = 48): List<Pt> = List(steps + 1) { i ->
        val t = 2 * PI * i / steps - PI / 2
        Pt((150 + 135 * cos(t)).toFloat(), (150 + 95 * sin(t)).toFloat(), 0)
    }

    /** A circle that starts at the side, overshoots its start and drifts inwards a little. */
    fun circleOvershoot(steps: Int = 54): List<Pt> = List(steps + 1) { i ->
        val k = i.toDouble() / 48
        val t = 2 * PI * k + PI
        val r = 125 - 12 * k
        Pt((150 + r * cos(t)).toFloat(), (150 - r * sin(t)).toFloat(), 0)
    }

    fun z(): List<Pt> = polyline(listOf(40f to 40f, 260f to 40f, 40f to 260f, 260f to 260f))

    /** A Z with a sloping top and bottom. */
    fun zSlanted(): List<Pt> = polyline(listOf(50f to 70f, 250f to 35f, 55f to 250f, 265f to 230f))

    /** A narrow Z with a long diagonal. */
    fun zNarrow(): List<Pt> = polyline(listOf(90f to 30f, 215f to 30f, 85f to 270f, 220f to 270f))

    /** Name of the "?" starter, whose action is Ask the Page. */
    const val QUESTION = "?"

    /**
     * A question mark: a hook (an arc over the top that curls back to the middle, then a
     * short drop) as stroke 0, and the dot under it as stroke 1. [r] is the arc's radius,
     * [sweep] how far round the arc goes in degrees, [drop] the straight run below it and
     * [gap] the space between the end of the hook and the dot.
     */
    fun question(r: Float = 70f, sweep: Float = 250f, drop: Float = 45f, gap: Float = 40f, cx: Float = 150f, steps: Int = 36): List<Pt> {
        val cy = 40f + r
        val hook = ArrayList<Pt>()
        // from the left side of the arc (180 degrees), over the top, round to the right and back under
        for (i in 0..steps) {
            val a = Math.toRadians(180.0 + sweep * i / steps)
            hook += Pt((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat(), 0)
        }
        val end = hook.last()
        // the curl comes back toward the middle, then drops straight down
        val stemTop = Pt(cx, end.y + r * 0.25f, 0)
        hook += polyline(listOf(end.x to end.y, stemTop.x to stemTop.y, cx to stemTop.y + drop), perSegment = 10).drop(1)
        val dotY = hook.last().y + gap
        val dot = List(7) { i ->
            val a = 2 * PI * i / 6
            Pt((cx + 4 * cos(a)).toFloat(), (dotY + 4 * sin(a)).toFloat(), 1)
        }
        return hook + dot
    }

    /** A rounder, wider question mark with a short stem. */
    fun questionWide(): List<Pt> = question(r = 90f, sweep = 240f, drop = 30f, gap = 35f)

    /** A narrow, tall question mark with a long stem and a far dot. */
    fun questionTall(): List<Pt> = question(r = 55f, sweep = 260f, drop = 70f, gap = 50f)

    /** A quick one: the curl barely turns back and the dot is a short dash. */
    fun questionQuick(): List<Pt> {
        val hook = question(r = 75f, sweep = 215f, drop = 50f, gap = 0f).filter { it.stroke == 0 }
        val y = hook.last().y + 38f
        return hook + polyline(listOf(147f to y, 153f to y + 4f), perSegment = 4, stroke = 1)
    }

    /** Every starter sample by shape name, the shipped drawing first. */
    fun library(): Map<String, List<List<Pt>>> = linkedMapOf(
        "Heart" to listOf(heart(), heartTwoStrokes(), heartSloppy(), heartBumps()),
        "V" to listOf(v(), vRound(), vLopsided()),
        "Circle" to listOf(circle(), oval(), circleOvershoot()),
        "Z" to listOf(z(), zSlanted(), zNarrow()),
        QUESTION to listOf(question(), questionWide(), questionTall(), questionQuick()),
    )

    /** Corners joined by straight runs, densely sampled so a recogniser sees a hand-drawn path. */
    fun polyline(corners: List<Pair<Float, Float>>, perSegment: Int = 20, stroke: Int = 0): List<Pt> {
        val out = ArrayList<Pt>()
        for (s in 0 until corners.size - 1) {
            val (ax, ay) = corners[s]
            val (bx, by) = corners[s + 1]
            val last = if (s == corners.size - 2) perSegment else perSegment - 1
            for (i in 0..last) {
                val k = i.toFloat() / perSegment
                out += Pt(ax + (bx - ax) * k, ay + (by - ay) * k, stroke)
            }
        }
        return out
    }
}
