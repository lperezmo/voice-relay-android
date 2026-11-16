package com.lperezmo.voicerelay.glyph.recognizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point of a drawing; [stroke] numbers the finger-down to finger-up runs. */
data class Pt(val x: Float, val y: Float, val stroke: Int)

/** One normalised sample of a shape. [key] says which shape it belongs to. */
class Template(val key: String, val points: List<Pt>)

/** The best shape for a drawing and how close it came: 1 is identical, 0 is nothing alike. */
data class Match(val key: String, val score: Float)

/**
 * The $P point-cloud recogniser (Vatavu, Anthony and Wobbrock, "Gestures as Point Clouds",
 * ICMI 2012), written from the paper. A drawing is resampled to [N] points, scaled
 * uniformly into a unit box and centred, then compared to each template as an unordered
 * cloud, so stroke order, stroke direction and the number of strokes do not matter.
 *
 * On top of the paper: the candidate is also tried turned by a few degrees either way
 * ([ANGLES]), so a shape drawn a little crooked still matches, and the raw $P distance is
 * turned into a 0..1 score that a threshold slider can use.
 */
object PDollar {
    const val N = 32

    /** Tilts tried on the candidate, in degrees. Big enough for a crooked hand, small enough that a V is not a <. */
    val ANGLES = floatArrayOf(-24f, -12f, 0f, 12f, 24f)

    /**
     * Mean weighted point distance at which the score reaches 0. A normalised cloud lives in
     * a unit box, so unrelated shapes sit around 0.25 to 0.35 apart and a fair copy under 0.08.
     */
    const val ZERO_SCORE_DISTANCE = 0.30f

    /** Default for the settings slider; see PDollarTest for where shapes and non-shapes land. */
    const val DEFAULT_THRESHOLD = 0.75f

    private val WEIGHT_SUM: Float = (0 until N).sumOf { 1.0 - it.toDouble() / N }.toFloat()

    fun normalize(points: List<Pt>): List<Pt> = translateToOrigin(scale(resample(points, N)))

    fun template(key: String, points: List<Pt>) = Template(key, normalize(points))

    /** Best template per key, best first. Empty when there is nothing to compare. */
    fun rank(points: List<Pt>, templates: List<Template>): List<Match> {
        if (points.size < 2 || templates.isEmpty()) return emptyList()
        val raw = resample(points, N)
        val candidates = ANGLES.map { a -> translateToOrigin(scale(rotate(raw, a))) }
        val best = HashMap<String, Float>()
        for (t in templates) {
            var d = Float.MAX_VALUE
            for (c in candidates) d = min(d, greedyCloudMatch(c, t.points))
            val score = toScore(d)
            if (score > (best[t.key] ?: -1f)) best[t.key] = score
        }
        return best.map { Match(it.key, it.value) }.sortedByDescending { it.score }
    }

    fun recognize(points: List<Pt>, templates: List<Template>): Match? = rank(points, templates).firstOrNull()

    fun toScore(distance: Float): Float = max(0f, 1f - distance / WEIGHT_SUM / ZERO_SCORE_DISTANCE)

    // ---- the paper's steps ---------------------------------------------------------

    private fun greedyCloudMatch(a: List<Pt>, b: List<Pt>): Float {
        val step = floor(sqrt(N.toDouble())).toInt() // n^(1 - epsilon) with epsilon 0.5
        var best = Float.MAX_VALUE
        var i = 0
        while (i < N) {
            best = min(best, min(cloudDistance(a, b, i), cloudDistance(b, a, i)))
            i += step
        }
        return best
    }

    private fun cloudDistance(a: List<Pt>, b: List<Pt>, start: Int): Float {
        val matched = BooleanArray(N)
        var sum = 0f
        var i = start
        do {
            var index = -1
            var min = Float.MAX_VALUE
            for (j in 0 until N) {
                if (matched[j]) continue
                val d = dist(a[i], b[j])
                if (d < min) {
                    min = d
                    index = j
                }
            }
            matched[index] = true
            val weight = 1f - ((i - start + N) % N).toFloat() / N
            sum += weight * min
            i = (i + 1) % N
        } while (i != start)
        return sum
    }

    fun resample(points: List<Pt>, n: Int): List<Pt> {
        val length = pathLength(points)
        if (length <= 1e-6f) return List(n) { points.first().copy(stroke = 0) }
        val interval = length / (n - 1)
        var acc = 0f
        val src = points.toMutableList()
        val out = ArrayList<Pt>(n)
        out += src[0]
        var i = 1
        while (i < src.size) {
            val p = src[i - 1]
            val q = src[i]
            if (p.stroke == q.stroke) {
                val d = dist(p, q)
                if (acc + d >= interval && d > 0f) {
                    val k = (interval - acc) / d
                    val r = Pt(p.x + k * (q.x - p.x), p.y + k * (q.y - p.y), p.stroke)
                    out += r
                    src.add(i, r)
                    acc = 0f
                } else {
                    acc += d
                }
            }
            i++
        }
        // rounding can leave the last point off; pad with the drawing's end
        while (out.size < n) out += src.last()
        return if (out.size > n) out.subList(0, n) else out
    }

    private fun rotate(points: List<Pt>, degrees: Float): List<Pt> {
        if (degrees == 0f) return points
        val c = centroid(points)
        val r = degrees * PI.toFloat() / 180f
        val cs = cos(r)
        val sn = sin(r)
        return points.map {
            val dx = it.x - c.first
            val dy = it.y - c.second
            Pt(dx * cs - dy * sn + c.first, dx * sn + dy * cs + c.second, it.stroke)
        }
    }

    private fun scale(points: List<Pt>): List<Pt> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        val size = max(maxX - minX, maxY - minY).takeIf { it > 1e-6f } ?: 1f
        return points.map { Pt((it.x - minX) / size, (it.y - minY) / size, it.stroke) }
    }

    private fun translateToOrigin(points: List<Pt>): List<Pt> {
        val c = centroid(points)
        return points.map { Pt(it.x - c.first, it.y - c.second, it.stroke) }
    }

    private fun centroid(points: List<Pt>): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (p in points) {
            x += p.x; y += p.y
        }
        return x / points.size to y / points.size
    }

    private fun pathLength(points: List<Pt>): Float {
        var d = 0f
        for (i in 1 until points.size) if (points[i].stroke == points[i - 1].stroke) d += dist(points[i - 1], points[i])
        return d
    }

    private fun dist(a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
