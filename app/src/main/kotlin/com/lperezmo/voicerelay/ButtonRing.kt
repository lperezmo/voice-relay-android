package com.lperezmo.voicerelay

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The ring around the dictate button, as a gauge: what Voice Relay is doing now.
 * It is told about dictation ([dictation], [delivered]), the wake words ([wake]),
 * the "hey timer" request ([voice], [voiceDone]) and the timers ([timers]), and
 * [look] says what to draw at a given moment. [ButtonRingView] only draws that.
 * Kept free of Android so it can be unit tested; times are any one millisecond clock.
 *
 * Which state wins when several apply: dictation in progress, then a wake word or
 * "hey timer", then a finished timer, then a failed dictation waiting for a retry.
 * Every wake word and "hey timer" state goes back to idle on its own after a while
 * ([limitMs]), so a missed "done" can never leave the ring stuck.
 */
class ButtonRing {

    /** The dictation button's own state, from [DictationMachine.State]. */
    enum class Dictation { IDLE, RECORDING, WORKING, FAILED }

    /** The wake words and the steps of a "hey timer" request. */
    enum class Voice { CLAUDE, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

    /** What the ring shows. */
    enum class Mode { IDLE, RECORDING, TRANSCRIBING, FAILED, CLAUDE, LISTENING, THINKING, SPEAKING, TIMER_DONE }

    /** A part of the ring, in Android's angles: 0 is three o'clock, -90 is the top, clockwise. */
    data class Arc(val color: Int, val startDeg: Float, val sweepDeg: Float)

    data class Look(
        val mode: Mode,
        /** The mark in the middle. */
        val mic: Int,
        /** The state arc, drawn over the track; null for none. */
        val arc: Arc?,
        /** The whole track in this colour (alpha included), over the plain track; null for the plain track. */
        val track: Int?,
        /** The thin inner ring that moves with the voice, and its radius in dp; null for none. */
        val level: Int?,
        val levelRadiusDp: Float,
        /** A full-ring flash over everything (alpha included); null for none. */
        val flash: Int?,
        /** How much of the next timer is left, 0 to 1, drawn as the inner gold arc; null for none. */
        val gold: Float?,
        /** The retry badge. */
        val badge: Boolean,
        /** True while anything moves: the view redraws on every frame until it is false. */
        val animating: Boolean,
    )

    private var dictation = Dictation.IDLE
    private var dictationAt = 0L
    private var voice: Voice? = null
    private var voiceAt = 0L
    /** When the step's [limitMs] started counting: [voiceAt], or the last call for a THINKING step. */
    private var voiceLimitFrom = 0L
    /** A finger is on the button: it never dims under it. */
    private var pressed = false
    private var flashColor = 0
    private var flashAt: Long? = null
    private var nextTimer: Float? = null
    private var timerDone = false
    private var timerDoneAt = 0L
    /** The last touch or change, for dimming when idle. */
    private var activityAt = 0L

    fun dictation(state: Dictation, now: Long) {
        if (state == dictation) return
        dictation = state
        dictationAt = now
        activityAt = now
    }

    /** The text landed: a short green flash. */
    fun delivered(now: Long) = flash(GREEN, now)

    /** A wake word was heard: clay for "hey claude", blue for "hey timer", each with a flash of its colour. */
    fun wake(timer: Boolean, now: Long) {
        voice = null
        voice(if (timer) Voice.LISTENING else Voice.CLAUDE, now)
        flash(if (timer) BLUE else CLAY, now)
    }

    /**
     * A step of the "hey timer" request. The answer starts with a gold flash.
     * THINKING is posted before each call to Claude, and each post starts its limit
     * again, so the limit holds per call however many calls the request makes.
     */
    fun voice(stage: Voice, now: Long) {
        activityAt = now
        if (stage == voice && now - voiceLimitFrom < limitMs(stage)) {
            if (stage == Voice.THINKING) voiceLimitFrom = now
            return
        }
        if (stage == Voice.SPEAKING) flash(GOLD, now)
        voice = stage
        voiceAt = now
        voiceLimitFrom = now
    }

    /** "hey timer" stopped a ringing timer: a short blue flash, nothing else. */
    fun stoppedByVoice(now: Long) {
        activityAt = now
        flash(BLUE, now)
    }

    /** The "hey timer" request is over, however it ended. */
    fun voiceDone(now: Long) {
        if (voice == null) return
        voice = null
        activityAt = now
    }

    /** [next] is how much of the next running timer is left (null with none running); [done] is true while a finished one waits. */
    fun timers(next: Float?, done: Boolean, now: Long) {
        if (done != timerDone) {
            timerDone = done
            timerDoneAt = now
            activityAt = now
        }
        nextTimer = next?.coerceIn(0f, 1f)
    }

    /** A finger touched the button or a pill. */
    fun touched(now: Long) {
        activityAt = now
    }

    /** A finger went down on the button ([down] true) or came up; while it is down nothing dims. */
    fun pressed(down: Boolean, now: Long) {
        pressed = down
        activityAt = now
    }

    private fun flash(color: Int, now: Long) {
        flashColor = color
        flashAt = now
        activityAt = now
    }

    /** The wake word or "hey timer" step still showing, or null once it ran past its [limitMs]. */
    private fun activeVoice(now: Long): Voice? = voice?.takeIf { now - voiceLimitFrom < limitMs(it) }

    fun mode(now: Long): Mode {
        when (dictation) {
            Dictation.RECORDING -> return Mode.RECORDING
            Dictation.WORKING -> return Mode.TRANSCRIBING
            else -> {}
        }
        when (activeVoice(now)) {
            Voice.CLAUDE -> return Mode.CLAUDE
            Voice.LISTENING -> return Mode.LISTENING
            Voice.TRANSCRIBING -> return Mode.TRANSCRIBING
            Voice.THINKING -> return Mode.THINKING
            Voice.SPEAKING -> return Mode.SPEAKING
            null -> {}
        }
        if (timerDone) return Mode.TIMER_DONE
        if (dictation == Dictation.FAILED) return Mode.FAILED
        return Mode.IDLE
    }

    private fun flashAlpha(now: Long): Float {
        val at = flashAt ?: return 0f
        val age = now - at
        if (age < 0 || age >= FLASH_MS) return 0f
        return 1f - age / FLASH_MS.toFloat()
    }

    fun look(now: Long): Look {
        val mode = mode(now)
        val since = when (mode) {
            Mode.RECORDING, Mode.TRANSCRIBING -> if (dictation == Dictation.WORKING || dictation == Dictation.RECORDING) dictationAt else voiceAt
            Mode.CLAUDE, Mode.LISTENING, Mode.THINKING, Mode.SPEAKING -> voiceAt
            Mode.TIMER_DONE -> timerDoneAt
            else -> now
        }
        val t = (now - since).coerceAtLeast(0)
        val level = LEVEL_MIN_DP + LEVEL_RANGE_DP * voiceLevel(t)

        var mic = WHITE
        var arc: Arc? = null
        var track: Int? = null
        var levelColor: Int? = null
        var moving = true
        when (mode) {
            Mode.IDLE -> moving = false
            Mode.RECORDING -> {
                mic = RED
                levelColor = RED
                // One turn a minute: the arc counts the minute the recording is in.
                val turn = (t % RECORD_TURN_MS) / RECORD_TURN_MS.toFloat()
                arc = Arc(RED, TOP, maxOf(turn, MIN_TURN) * 360f)
            }
            Mode.TRANSCRIBING -> arc = Arc(WORKING, TOP + spin(t, TRANSCRIBE_TURN_MS), TRANSCRIBE_SWEEP)
            Mode.THINKING -> {
                mic = CLAY
                arc = Arc(CLAY, TOP + ease(spinFraction(t, THINK_TURN_MS)) * 360f, THINK_SWEEP)
            }
            Mode.LISTENING -> {
                mic = BLUE
                levelColor = BLUE
            }
            Mode.SPEAKING -> {
                mic = CLAY
                levelColor = CLAY
                track = withAlpha(CLAY, pulse(t, PULSE_MS, PULSE_LOW))
            }
            Mode.CLAUDE -> {
                mic = CLAY
                track = withAlpha(CLAY, pulse(t, PULSE_MS, PULSE_LOW))
            }
            Mode.TIMER_DONE -> {
                // Flashes for a while, then stays gold without moving until it is dismissed.
                moving = t < DONE_FLASHING_MS
                track = if (moving) withAlpha(GOLD, pulse(t, DONE_PULSE_MS, DONE_PULSE_LOW)) else GOLD
            }
            Mode.FAILED -> {
                moving = false
                arc = Arc(AMBER, TOP, FAILED_SWEEP)
            }
        }
        val flash = flashAlpha(now)
        return Look(
            mode = mode,
            mic = mic,
            arc = arc,
            track = track,
            level = levelColor?.let { withAlpha(it, LEVEL_ALPHA) },
            levelRadiusDp = level,
            flash = if (flash > 0f) withAlpha(flashColor, flash) else null,
            gold = if (mode == Mode.TIMER_DONE || timerDone) null else nextTimer,
            badge = dictation == Dictation.FAILED,
            animating = moving || flash > 0f,
        )
    }

    /**
     * True when the button and pills should fade: [enabled] by the setting, nothing
     * open ([popupsOpen]), nothing going on, and [DIM_AFTER_MS] since the last touch or change.
     */
    fun dimmed(now: Long, enabled: Boolean, popupsOpen: Boolean): Boolean =
        enabled && !popupsOpen && !pressed && mode(now) == Mode.IDLE && flashAlpha(now) == 0f &&
            now - activityAt >= DIM_AFTER_MS

    /**
     * How long until [dimmed] should be checked again if nothing else happens, or null when
     * only a new event can change it (dictation in progress or failed, a finished timer, a
     * finger on the button): those events check it themselves, so nothing needs to poll.
     */
    fun dimDueIn(now: Long): Long? {
        if (pressed || dictation == Dictation.RECORDING || dictation == Dictation.WORKING) return null
        // A wake word or "hey timer" step ends on its own after its limit.
        activeVoice(now)?.let { return (voiceLimitFrom + limitMs(it) - now).coerceAtLeast(1) }
        if (timerDone || dictation == Dictation.FAILED) return null
        val flashLeft = flashAt?.let { it + FLASH_MS - now } ?: 0L
        return maxOf(activityAt + DIM_AFTER_MS - now, flashLeft, 0L)
    }

    companion object {
        // Instrument colours (see .claude/briefs/visual-directions.md, #10).
        const val FILL = 0xDD1C1C1E.toInt()
        /** Crisp Capsule's double edge: light inside, dark outside, so it holds on white pages and on photos. */
        const val EDGE_IN = 0x47FFFFFF
        const val EDGE_OUT = 0x4D000000
        const val TRACK = 0x2EFFFFFF
        const val TICKS = 0x59FFFFFF
        const val WHITE = 0xFFFFFFFF.toInt()
        const val RED = 0xFFEF4444.toInt()
        const val WORKING = 0xFFE8EAED.toInt()
        const val BLUE = 0xFF8AB4F8.toInt()
        const val CLAY = 0xFFD97757.toInt()
        const val AMBER = 0xFFD97706.toInt()
        const val GOLD = 0xFFF2C46D.toInt()
        const val GREEN = 0xFF3FB68B.toInt()

        // Geometry, in dp, for a 44 dp button.
        const val TRACK_R_DP = 19.5f
        const val TRACK_W_DP = 2.5f
        const val TICK_OUT_DP = 17f
        const val TICK_IN_DP = 15.5f
        const val TICKS_COUNT = 12
        const val GOLD_R_DP = 15.5f
        const val GOLD_W_DP = 1.5f
        const val LEVEL_W_DP = 1f
        const val LEVEL_MIN_DP = 14f
        const val LEVEL_RANGE_DP = 3f
        const val LEVEL_ALPHA = 0.7f
        const val MIC_DP = 18f

        // Timings.
        const val RECORD_TURN_MS = 60_000L
        const val TRANSCRIBE_TURN_MS = 900L
        const val THINK_TURN_MS = 1400L
        const val PULSE_MS = 1200L
        const val PULSE_LOW = 0.35f
        const val DONE_PULSE_MS = 800L
        const val DONE_PULSE_LOW = 0.15f
        const val DONE_FLASHING_MS = 30_000L
        const val FLASH_MS = 700L
        const val DIM_AFTER_MS = 3000L
        /** How long "hey claude" shows: Claude's own app takes over from there. */
        const val CLAUDE_MS = 2600L

        const val TOP = -90f
        const val TRANSCRIBE_SWEEP = 60f
        const val THINK_SWEEP = 108f
        const val FAILED_SWEEP = 151f
        private const val MIN_TURN = 0.01f

        /**
         * How long each wake word or "hey timer" step may show before the ring gives up on it.
         * Longer than the request's own limits (15 s of listening, 30 s per call). THINKING
         * starts again with each call to Claude, so its limit is one call's.
         */
        fun limitMs(voice: Voice): Long = when (voice) {
            Voice.CLAUDE -> CLAUDE_MS
            Voice.LISTENING -> 25_000L
            Voice.TRANSCRIBING -> 40_000L
            Voice.THINKING -> 40_000L
            Voice.SPEAKING -> 35_000L
        }

        /** [color] with its alpha scaled by [alpha] (0 to 1). */
        fun withAlpha(color: Int, alpha: Float): Int {
            val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).roundToInt()
            return (a shl 24) or (color and 0xFFFFFF)
        }

        /** 1 at the start of each [periodMs], [low] halfway through, smoothly. */
        fun pulse(t: Long, periodMs: Long, low: Float): Float {
            val k = 0.5f + 0.5f * cos(2 * PI * (t % periodMs) / periodMs).toFloat()
            return low + (1f - low) * k
        }

        private fun spinFraction(t: Long, periodMs: Long): Float = (t % periodMs) / periodMs.toFloat()
        private fun spin(t: Long, periodMs: Long): Float = spinFraction(t, periodMs) * 360f

        /** Slow, fast, slow: the "thinking" comet. */
        fun ease(x: Float): Float = x * x * (3f - 2f * x)

        /** A stand-in for the voice level, 0 to 1, that wobbles like speech. */
        fun voiceLevel(t: Long): Float = 0.5f + 0.5f * abs(sin(t / 130.0) * sin(t / 410.0)).toFloat()
    }
}
