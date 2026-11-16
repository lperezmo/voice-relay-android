package com.lperezmo.voicerelay.glyph

import kotlin.math.max
import kotlin.math.min

/**
 * The Android-free half of [CursiveWriter]: word wrapping given a measure function,
 * picking a text size that fits, deciding which letter an outline belongs to, and
 * the pen's timeline (which outline the pen is on, and how far along it, at a given
 * moment). Kept apart so it can be unit tested on the JVM.
 */
object CursiveMath {
    /** How long the pen takes for a 40-word answer. */
    const val SECONDS_PER_40_WORDS = 7f
    /** The shortest and longest the writing may take, whatever the length of the text. */
    const val MIN_SECONDS = 1.5f
    const val MAX_SECONDS = 14f

    /** The number of whitespace-separated words in [text]. */
    fun words(text: String): Int = text.split(WHITESPACE).count { it.isNotEmpty() }

    /** How long writing [words] words takes, in seconds. */
    fun duration(words: Int): Float =
        min(MAX_SECONDS, max(MIN_SECONDS, words * SECONDS_PER_40_WORDS / 40f))

    /**
     * Breaks [text] into lines no wider than [width], where [measure] gives the width
     * of a piece of text. Words are kept whole unless a single word is wider than a
     * line, in which case it is broken between characters. Line breaks in [text] are
     * kept (an empty line stays empty); runs of spaces collapse to one, and blank
     * lines at the start and end are dropped.
     */
    fun wrap(text: String, width: Float, measure: (String) -> Float): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.replace("\r\n", "\n").split('\n')) {
            val words = paragraph.split(WHITESPACE).filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                out += ""
                continue
            }
            var line = ""
            for (word in words) {
                val joined = if (line.isEmpty()) word else "$line $word"
                if (measure(joined) <= width) {
                    line = joined
                    continue
                }
                if (line.isNotEmpty()) out += line
                var rest = word
                while (rest.length > 1 && measure(rest) > width) {
                    var n = rest.length - 1
                    while (n > 1 && measure(rest.substring(0, n)) > width) n--
                    // never split a surrogate pair
                    if (n > 1 && Character.isHighSurrogate(rest[n - 1])) n--
                    out += rest.substring(0, n)
                    rest = rest.substring(n)
                }
                line = rest
            }
            out += line
        }
        val first = out.indexOfFirst { it.isNotEmpty() }
        if (first < 0) return emptyList()
        val last = out.indexOfLast { it.isNotEmpty() }
        return out.subList(first, last + 1).toList()
    }

    /** A text size and the lines the text wraps into at that size. */
    data class Fit(val size: Float, val lines: List<String>)

    /**
     * The largest text size from [largest] down to [smallest], in steps of [step],
     * at which [text] wrapped to [width] is no taller than [maxHeight]. [measure]
     * gives a text's width at a size, [lineHeight] the height of one line at a size.
     * When even [smallest] is too tall, [smallest] is used anyway.
     */
    fun fit(
        text: String,
        width: Float,
        maxHeight: Float,
        largest: Float,
        smallest: Float,
        step: Float,
        measure: (String, Float) -> Float,
        lineHeight: (Float) -> Float,
    ): Fit {
        val stride = max(0.01f, step)
        var size = max(largest, smallest)
        while (true) {
            val s = size
            val lines = wrap(text, width) { measure(it, s) }
            if (s <= smallest || lines.size * lineHeight(s) <= maxHeight) return Fit(s, lines)
            size = max(smallest, s - stride)
        }
    }

    /**
     * Which character an outline centred at [x] belongs to, where [edges] holds the
     * right edge of each character in the line, left to right. Points past the last
     * edge belong to the last character.
     */
    fun charAt(edges: FloatArray, x: Float): Int {
        if (edges.isEmpty()) return 0
        var lo = 0
        var hi = edges.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (x < edges[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }

    /**
     * The indices 0 until keys.size sorted by key, keeping the original order among
     * equal keys: the order the pen visits outlines, letter by letter.
     */
    fun order(keys: IntArray): IntArray =
        keys.indices.sortedBy { keys[it] }.toIntArray() // sortedBy is stable

    private val WHITESPACE = Regex("\\s+")
}

/**
 * Where the pen is: on outline [index], [distance] pixels along it. [index] equal to
 * the outline count means done. One is reused every frame to avoid allocating.
 */
class PenCursor {
    var index = 0
    var distance = 0f
}

/**
 * The pen's schedule over outlines of the given [lengths], in drawing order. The pen
 * moves at one constant speed, chosen so everything takes [duration] seconds, and
 * lifts for [maxGap] seconds between outlines (less when there are so many outlines
 * that the lifts would take more than a third of the time).
 */
class PenTimeline(private val lengths: FloatArray, duration: Float, maxGap: Float) {
    private val starts = FloatArray(lengths.size)
    private val ends = FloatArray(lengths.size)

    /** Seconds until the last outline is finished. */
    val total: Float

    init {
        val n = lengths.size
        var sum = 0f
        for (l in lengths) sum += max(0f, l)
        val gap = if (n > 1) min(max(0f, maxGap), duration / 3f / (n - 1)) else 0f
        val moving = max(0.001f, duration - gap * max(0, n - 1))
        val speed = if (sum > 0f) sum / moving else 1f
        var t = 0f
        for (i in 0 until n) {
            if (i > 0) t += gap
            starts[i] = t
            t += max(0f, lengths[i]) / speed
            ends[i] = t
        }
        total = if (n == 0 || sum <= 0f) 0f else t
    }

    /** When the pen finishes outline [index], in seconds from the start. */
    fun endOf(index: Int): Float = if (index in ends.indices) ends[index] else total

    /** Writes where the pen is [elapsed] seconds after the start into [out]. */
    fun locate(elapsed: Float, out: PenCursor) {
        val n = lengths.size
        if (n == 0 || total <= 0f || elapsed >= total) {
            out.index = n
            out.distance = 0f
            return
        }
        if (elapsed <= 0f) {
            out.index = 0
            out.distance = 0f
            return
        }
        // the last outline that has started
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= elapsed) lo = mid else hi = mid - 1
        }
        if (elapsed >= ends[lo]) {
            // finished it and lifted the pen: the next one has not begun
            out.index = lo + 1
            out.distance = 0f
            return
        }
        val span = ends[lo] - starts[lo]
        val k = if (span > 0f) (elapsed - starts[lo]) / span else 1f
        out.index = lo
        out.distance = max(0f, lengths[lo]) * min(1f, max(0f, k))
    }
}
