package com.lperezmo.voicerelay

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * One timer Voice Relay runs itself. [endsAt] is wall-clock milliseconds; a
 * timer whose end has passed is done and stays in the list (the pill turns
 * gold) until it is dismissed. [rung] is set once its alarm has been raised,
 * so it rings only once. [totalMs] is how long it runs in all, extensions
 * included (0 for a timer saved before it was kept).
 */
data class TimerEntry(
    val id: Int,
    val label: String,
    val endsAt: Long,
    val rung: Boolean = false,
    val totalMs: Long = 0,
) {
    fun done(now: Long): Boolean = endsAt <= now

    /**
     * How much is left, 1 at the start and 0 at the end, for the gold arc and drain line.
     * Without a [totalMs] it is read off an hour, like a kitchen timer's dial.
     */
    fun fractionLeft(now: Long): Float {
        if (done(now)) return 0f
        val whole = if (totalMs > 0) totalMs else HOUR_MS
        return ((endsAt - now).toFloat() / whole).coerceIn(0f, 1f)
    }

    private companion object {
        const val HOUR_MS = 60 * 60_000L
    }

    /** Whole seconds left, rounded up so a fresh 10 minute timer reads 10:00. */
    fun secondsLeft(now: Long): Int = if (done(now)) 0 else ((endsAt - now + 999) / 1000).toInt()
}

/**
 * The list of timers and the rules for changing it. Every change returns a new
 * list, so [TimerController] can save and publish it in one step. Kept free of
 * Android so it can be unit tested.
 */
data class Timers(
    val list: List<TimerEntry> = emptyList(),
    /** Never reused: it names each timer's alarm and notification. */
    val nextId: Int = 1,
    /** The N of the next "Timer N"; starts over once nothing is left. */
    val nextNumber: Int = 1,
) {
    /** Soonest first. */
    fun sorted(): List<TimerEntry> = list.sortedWith(compareBy({ it.endsAt }, { it.id }))

    fun find(id: Int): TimerEntry? = list.firstOrNull { it.id == id }

    /** Adds a timer [seconds] long. Without a [label] it is "Timer 1", "Timer 2"... */
    fun start(now: Long, seconds: Long, label: String? = null): Pair<Timers, TimerEntry> {
        val name = label?.trim()?.take(MAX_LABEL).orEmpty()
        val numbered = name.isEmpty()
        val length = seconds.coerceIn(1, MAX_SECONDS) * 1000
        val entry = TimerEntry(
            id = nextId,
            label = if (numbered) "Timer $nextNumber" else name,
            endsAt = now + length,
            totalMs = length,
        )
        return copy(
            list = list + entry,
            nextId = nextId + 1,
            nextNumber = if (numbered) nextNumber + 1 else nextNumber,
        ) to entry
    }

    /** Adds [seconds] to a timer. One that is already done starts again from now. */
    fun extend(id: Int, now: Long, seconds: Long = 60): Timers = copy(
        list = list.map {
            if (it.id != id) it
            else it.copy(
                endsAt = maxOf(it.endsAt, now) + seconds * 1000,
                rung = false,
                // A finished timer starts over, so the added time is all of it. A running one saved
                // before totals were kept counts from what is left now.
                totalMs = when {
                    it.done(now) -> seconds * 1000
                    it.totalMs <= 0 -> it.endsAt - now + seconds * 1000
                    else -> it.totalMs + seconds * 1000
                },
            )
        },
    )

    fun remove(id: Int): Timers {
        val rest = list.filter { it.id != id }
        return copy(list = rest, nextNumber = if (rest.isEmpty()) 1 else nextNumber)
    }

    /** Timers whose end has come and that have not rung yet. */
    fun due(now: Long): List<TimerEntry> = list.filter { it.done(now) && !it.rung }

    fun markRung(ids: Collection<Int>): Timers =
        copy(list = list.map { if (it.id in ids) it.copy(rung = true) else it })

    fun toJson(): String = JSONObject()
        .put("nextId", nextId)
        .put("nextNumber", nextNumber)
        .put("timers", JSONArray().apply {
            for (t in list) put(
                JSONObject().put("id", t.id).put("label", t.label).put("endsAt", t.endsAt).put("rung", t.rung)
                    .put("total", t.totalMs)
            )
        })
        .toString()

    companion object {
        const val MAX_SECONDS = 24 * 60 * 60L
        const val MAX_LABEL = 40

        /** What [toJson] wrote; anything unreadable is an empty list, never a crash. */
        fun fromJson(json: String?): Timers {
            if (json.isNullOrBlank()) return Timers()
            return try {
                val o = JSONObject(json)
                val arr = o.optJSONArray("timers") ?: JSONArray()
                val list = (0 until arr.length()).map {
                    val t = arr.getJSONObject(it)
                    TimerEntry(t.getInt("id"), t.getString("label"), t.getLong("endsAt"), t.optBoolean("rung"), t.optLong("total", 0))
                }
                Timers(
                    list = list,
                    nextId = maxOf(o.optInt("nextId", 1), (list.maxOfOrNull { it.id } ?: 0) + 1),
                    nextNumber = maxOf(1, o.optInt("nextNumber", 1)),
                )
            } catch (_: Exception) {
                Timers()
            }
        }
    }
}

/** The words and numbers the timer pill, its cards and its confirmations show. */
object TimerText {
    /** The stops on the timer track, in minutes; 0 is "Own", your own time. */
    val TRACK_MINUTES = listOf(5, 10, 15, 30, 0)
    const val OWN_LABEL = "Own"

    /** A track stop's name: "10m", or "Own" for 0. */
    fun trackLabel(minutes: Int): String = if (minutes == 0) OWN_LABEL else "${minutes}m"
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 1440
    const val DEFAULT_OWN_MINUTES = 12

    enum class State { IDLE, RUNNING, DONE }

    /**
     * What the pill shows: the next timer to end, how many there are, whether one is done,
     * and how much of the next one is left (0 to 1) for its drain line.
     */
    data class Face(val text: String, val count: Int, val state: State, val drain: Float = 0f)

    /** "9:12", "1h05", or "Done" at zero. */
    fun clock(seconds: Int): String {
        if (seconds <= 0) return "Done"
        val m = seconds / 60
        return if (m >= 60) "${m / 60}h${(m % 60).toString().padStart(2, '0')}"
        else "$m:${(seconds % 60).toString().padStart(2, '0')}"
    }

    fun face(sorted: List<TimerEntry>, now: Long): Face {
        val first = sorted.firstOrNull() ?: return Face("Timer", 0, State.IDLE)
        val done = sorted.any { it.done(now) }
        return Face(
            text = clock(first.secondsLeft(now)),
            count = sorted.size,
            state = if (done) State.DONE else State.RUNNING,
            drain = if (done) 0f else first.fractionLeft(now),
        )
    }

    /**
     * Whether the button's ring shows a finished timer. With the timer pill shown it stays
     * until it is dismissed there. With the pill off there is nothing on screen to dismiss it
     * with, so only for as long as it rings ([RING_MS] from its end); after that the timer
     * waits in the list for the pill.
     */
    fun ringDone(sorted: List<TimerEntry>, now: Long, pillShown: Boolean): Boolean =
        sorted.any { it.done(now) && (pillShown || now - it.endsAt < RING_MS) }

    /**
     * The timers whose alarm is sounding now: done, and less than [RING_MS] since they ended
     * (after that the alarm has gone quiet on its own and the timer only waits to be dismissed).
     */
    fun ringing(sorted: List<TimerEntry>, now: Long): List<TimerEntry> =
        sorted.filter { it.done(now) && now - it.endsAt < RING_MS }

    /** With the pill off, how long until [ringDone] turns false on its own; null when it will not. */
    fun ringDoneEndsIn(sorted: List<TimerEntry>, now: Long, pillShown: Boolean): Long? {
        if (pillShown) return null
        return sorted.filter { it.done(now) && now - it.endsAt < RING_MS }.maxOfOrNull { it.endsAt + RING_MS - now }
    }

    /**
     * How strongly the done pill shows, [sinceDoneMs] after it turned done: a blink every
     * [BLINK_MS] from full down to [BLINK_LOW] for [ButtonRing.DONE_FLASHING_MS], like the
     * ring, then null: it stays solid gold and stops redrawing.
     */
    fun blink(sinceDoneMs: Long): Float? {
        if (sinceDoneMs < 0 || sinceDoneMs >= ButtonRing.DONE_FLASHING_MS) return null
        val t = (sinceDoneMs % BLINK_MS) / BLINK_MS.toFloat()
        return BLINK_LOW + (1f - BLINK_LOW) * (0.5f + 0.5f * kotlin.math.cos(2 * Math.PI * t).toFloat())
    }

    const val BLINK_MS = 600L
    const val BLINK_LOW = 0.35f
    /** A timer nobody stops goes quiet after this long; it stays in the list as done. */
    const val RING_MS = 10 * 60_000L

    /** For screen readers. */
    fun describe(sorted: List<TimerEntry>, now: Long): String {
        val first = sorted.firstOrNull() ?: return "Timer. Tap to see timers and start one."
        val count = if (sorted.size == 1) "1 timer running" else "${sorted.size} timers running"
        val next = if (first.done(now)) "${first.label} is done"
        else "${first.label} ends first, in ${clock(first.secondsLeft(now))}"
        return "$count. $next. Tap to see them."
    }

    /** "Timer · 10 min", or "Timer · 1:30" when it is not whole minutes. */
    fun started(seconds: Long): String =
        if (seconds % 60 == 0L) "Timer · ${seconds / 60} min" else "Timer · ${clock(seconds.toInt())}"

    /** "Stopped Pasta" for a running timer, "Dismissed Pasta" for one that is done. */
    fun stopped(timer: TimerEntry, now: Long): String =
        (if (timer.done(now)) "Dismissed " else "Stopped ") + timer.label

    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
}

/**
 * Whether a press on the timer pill is a tap: let go within [maxMs] of landing, never having
 * moved past [slop] px. Holding or dragging the pill does nothing. Kept free of Android so it
 * can be unit tested.
 */
class TimerTap(private val slop: Float, private val maxMs: Long) {
    private var pressing = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    fun down(x: Float, y: Float, at: Long) {
        pressing = true
        downX = x
        downY = y
        downAt = at
    }

    fun move(x: Float, y: Float) {
        if (pressing && hypot(x - downX, y - downY) > slop) pressing = false
    }

    /** True when this press was a tap. */
    fun up(at: Long): Boolean {
        val tap = pressing && at - downAt <= maxMs
        pressing = false
        return tap
    }

    fun cancel() {
        pressing = false
    }

    companion object {
        const val SLOP_DP = 8f
    }
}
