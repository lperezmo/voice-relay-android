package com.lperezmo.phonewhisper

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The four stops on the volume track above the dictate button. Levels are
 * percentages of each stream's maximum, in [VolumeMath.STREAM_NAMES] order:
 * system, notifications, ringtone, media. Alarms and calls are never touched,
 * so a zero preset cannot swallow an alarm. The assistant stream is left out
 * because Android only lets system apps set it (MODIFY_AUDIO_ROUTING).
 */
enum class VolumePreset(
    val short: String,
    val label: String,
    val color: Int,
    val percents: IntArray,
    val ringer: Ringer,
    val dnd: Boolean,
) {
    DND("DND", "Silent + DND", 0xFF8B80F0.toInt(), intArrayOf(0, 0, 0, 0), Ringer.SILENT, dnd = true),
    MUTE("MUTE", "Mute", 0xFFA3A3AB.toInt(), intArrayOf(0, 0, 0, 0), Ringer.VIBRATE, dnd = false),
    MEDIUM("MED", "Medium", 0xFF3FB68B.toInt(), intArrayOf(40, 40, 40, 5), Ringer.NORMAL, dnd = false),
    LOUD("LOUD", "Loud", 0xFFF2994A.toInt(), intArrayOf(100, 100, 100, 20), Ringer.NORMAL, dnd = false);

    enum class Ringer { NORMAL, VIBRATE, SILENT }
}

/** What the pill shows: a preset, or one of the two states no preset sets directly. */
enum class PillState(val short: String, val label: String, val color: Int, val preset: VolumePreset?) {
    DND("DND", "Silent + DND", VolumePreset.DND.color, VolumePreset.DND),
    /** The DND hour ran out: DND is off and the volumes stayed at 0. */
    SILENT("SIL", "Silent, DND ended", 0xFF5E5A86.toInt(), null),
    MUTE("MUTE", "Mute", VolumePreset.MUTE.color, VolumePreset.MUTE),
    MEDIUM("MED", "Medium", VolumePreset.MEDIUM.color, VolumePreset.MEDIUM),
    LOUD("LOUD", "Loud", VolumePreset.LOUD.color, VolumePreset.LOUD),
    /** A volume was changed by hand, so no preset matches. */
    CUSTOM("CUST", "Custom", 0xFF6B6E75.toInt(), null),
}

/** Preset arithmetic, kept free of Android so it can be unit tested. */
object VolumeMath {
    val STREAM_NAMES = listOf("System", "Notifications", "Ringtone", "Media")
    private val MEDIA = STREAM_NAMES.indexOf("Media")

    /**
     * [percent] of [max] in whole steps. Anything above 0% gets at least one
     * step, since 0 is off: 5% of a 15-step stream is 1, not 0.
     */
    fun steps(percent: Int, max: Int): Int = when {
        max <= 0 || percent <= 0 -> 0
        else -> (percent * max / 100.0).roundToInt().coerceIn(1, max)
    }

    /** Target level per stream. A stream the phone does not have ([maxes] below 0) gets -1. */
    fun levelsFor(preset: VolumePreset, maxes: IntArray): IntArray =
        IntArray(maxes.size) { i -> if (maxes[i] < 0) -1 else steps(preset.percents[i], maxes[i]) }

    /**
     * Reads the phone's state back as a pill state. Streams the phone does not
     * have (-1) are ignored. Medium and Loud also ignore media, since the
     * volume keys change it all the time; DND and Mute count it, so they only
     * show when the phone is fully silent. DND on with anything but silence is
     * someone else's DND, so it reads as custom rather than claiming a preset.
     */
    fun classify(levels: IntArray, maxes: IntArray, dndOn: Boolean, ringer: VolumePreset.Ringer): PillState {
        val known = levels.indices.filter { maxes[it] >= 0 }
        if (known.all { levels[it] == 0 }) return when {
            dndOn -> PillState.DND
            ringer == VolumePreset.Ringer.SILENT -> PillState.SILENT
            else -> PillState.MUTE
        }
        if (dndOn) return PillState.CUSTOM
        for (state in listOf(PillState.MEDIUM, PillState.LOUD)) {
            val want = levelsFor(state.preset!!, maxes)
            if (known.all { it == MEDIA || levels[it] == want[it] }) return state
        }
        return PillState.CUSTOM
    }
}

/**
 * The down-then-up stroke that picks a preset: press, dip down past [dip] to
 * unlock, slide sideways to a stop, then come back up [rise] from the lowest
 * point to apply it. The stop is the one under the finger when the rise
 * completes. A tap, a sideways swipe with no dip, or dipping and letting go
 * applies nothing.
 */
class VGesture(
    private val dip: Float,
    private val rise: Float,
    private val slop: Float,
    private val stopAt: (Float) -> Int,
) {
    enum class Phase { IDLE, ARMED, UNLOCKED, DONE }
    enum class End { TAP, CANCELLED, COMMITTED }

    var phase = Phase.IDLE
        private set
    var target = 0
        private set

    private var startX = 0f
    private var startY = 0f
    private var lowY = 0f
    private var moved = 0f

    fun down(x: Float, y: Float, current: Int) {
        phase = Phase.ARMED
        target = current
        startX = x; startY = y; lowY = y
        moved = 0f
    }

    /** Returns the stop to apply, exactly once, when the rise completes. */
    fun move(x: Float, y: Float): Int? {
        if (phase == Phase.IDLE || phase == Phase.DONE) return null
        moved = max(moved, hypot(x - startX, y - startY))
        if (phase == Phase.ARMED && y - startY > dip) {
            phase = Phase.UNLOCKED
            lowY = y
        }
        if (phase != Phase.UNLOCKED) return null
        lowY = max(lowY, y)
        target = stopAt(x)
        if (lowY - y > rise) {
            phase = Phase.DONE
            return target
        }
        return null
    }

    fun up(): End {
        val end = when {
            phase == Phase.DONE -> End.COMMITTED
            moved < slop -> End.TAP
            else -> End.CANCELLED
        }
        phase = Phase.IDLE
        return end
    }
}
