package com.lperezmo.voicerelay

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the pills ([VolumePillView] above the dictate button, [VolumeTrackView]
 * above that, [GlyphPillView] below, [TimerPillView] beside the button or on top
 * of the volume pill) sit for a button whose ring window is at
 * (bubbleX, bubbleY), and how far they reach past it. The button is a
 * [buttonDp] circle centered in a [ringDp] square window; [dp] is the screen
 * density. Kept free of Android so it can be unit tested.
 */
class PillLayout(private val dp: Float, ringDp: Int, buttonDp: Int, marginDp: Int) {

    private val ring = (ringDp * dp).toInt()
    private val button = (buttonDp * dp).toInt()
    private val margin = (marginDp * dp).toInt()

    data class Spot(val x: Int, val y: Int)

    /** True when the button sits on the right half of the screen, so things open to its left. */
    fun onRight(bubbleX: Int, screenW: Int): Boolean = bubbleX + ring / 2 > screenW / 2

    /** Centered over the button, [PILL_GAP_DP] above it; the touch area reaches down to just short of it. */
    fun volumePill(bubbleX: Int, bubbleY: Int, width: Int, height: Int): Spot {
        val buttonTop = bubbleY + (ring - button) / 2
        val pillBottom = buttonTop - (PILL_GAP_DP * dp).toInt()
        return Spot(
            bubbleX + (ring - width) / 2,
            (pillBottom + (VolumePillView.BOTTOM_PAD_DP * dp).toInt() - height).coerceAtLeast(0),
        )
    }

    /**
     * Above the pill, flush with the button's outer edge so it opens toward the middle of the screen.
     * With the timer pill stacked on the volume pill ([timerStacked]) it sits above that one instead.
     */
    fun track(bubbleX: Int, bubbleY: Int, width: Int, height: Int, screenW: Int, timerStacked: Boolean = false): Spot {
        val x = if (onRight(bubbleX, screenW)) bubbleX + (ring + button) / 2 - width else bubbleX + (ring - button) / 2
        val buttonTop = bubbleY + (ring - button) / 2
        val pillTop = buttonTop - ((PILL_GAP_DP + VolumePillView.PILL_H_DP) * dp).toInt() -
            if (timerStacked) (TIMER_H_DP * dp).toInt() else 0
        return Spot(
            x.coerceIn(margin, maxOf(margin, screenW - width - margin)),
            (pillTop - (TRACK_GAP_DP * dp).toInt() - height).coerceAtLeast(margin),
        )
    }

    /** Centered under the button, [PILL_GAP_DP] below it; the touch area reaches up to just short of it. */
    fun glyphPill(bubbleX: Int, bubbleY: Int, width: Int, height: Int, screenH: Int): Spot {
        val buttonBottom = bubbleY + (ring + button) / 2
        val pillTop = buttonBottom + (PILL_GAP_DP * dp).toInt()
        return Spot(
            bubbleX + (ring - width) / 2,
            (pillTop - (GlyphPillView.TOP_PAD_DP * dp).toInt()).coerceAtMost(maxOf(0, screenH - height)),
        )
    }

    /**
     * The timer pill's touch window. Beside the button ([stacked] false) it is on the side
     * facing the middle of the screen, level with the button. Stacked, it is in the volume
     * pill's column: straight on top of that pill's touch window, so the two never overlap
     * and each keeps its full height, or in the volume pill's own spot when that pill is off.
     */
    fun timerPill(
        bubbleX: Int, bubbleY: Int, width: Int, height: Int, screenW: Int,
        stacked: Boolean, volumeShown: Boolean,
    ): Spot {
        if (!stacked) {
            val gap = (TIMER_SIDE_GAP_DP * dp).toInt()
            val x = if (onRight(bubbleX, screenW)) bubbleX - gap - width else bubbleX + ring + gap
            return Spot(x.coerceIn(0, maxOf(0, screenW - width)), bubbleY + (ring - height) / 2)
        }
        if (!volumeShown) return volumePill(bubbleX, bubbleY, width, height)
        val buttonTop = bubbleY + (ring - button) / 2
        val volumeTop = buttonTop - (PILL_GAP_DP * dp).toInt() +
            (VolumePillView.BOTTOM_PAD_DP * dp).toInt() - (VolumePillView.H_DP * dp).toInt()
        return Spot(bubbleX + (ring - width) / 2, (volumeTop - height).coerceAtLeast(0))
    }

    /**
     * The fan of times that opens while the timer pill is held: a see-through window at
     * [window], [width] x [height], with the bubbles' [centres] in screen px (5, 10, 15, 30
     * and your own, top to bottom). The top [chipsHeight] of the window is for the chips
     * that list the running timers.
     */
    class Fan(
        val window: Spot, val width: Int, val height: Int,
        val centres: List<Spot>, val chipsHeight: Int, val onRight: Boolean,
    )

    /**
     * The fan for a timer pill whose touch window is at [pill]. It opens from the drawn
     * pill toward the middle of the screen, slides down or up to stay on screen, and keeps
     * every bubble clear of the button even when the pill is stacked right above it.
     */
    fun timerFan(
        pill: Spot, pillW: Int, pillH: Int, stacked: Boolean,
        bubbleX: Int, screenW: Int, screenH: Int,
    ): Fan {
        val right = onRight(bubbleX, screenW)
        val buttonCx = bubbleX + ring / 2
        val r = TIMER_FAN_RADIUS_DP * dp
        // The bubbles nearest the button's column are the outer two, at 60 degrees.
        val clear = button / 2 + ((TIMER_FAN_HOT_DP / 2 + TIMER_FAN_CLEAR_DP) * dp - r * cos(FAN_EDGE)).toInt()
        val pillCx = pill.x + pillW / 2
        val originX = if (right) minOf(pillCx, buttonCx - clear) else maxOf(pillCx, buttonCx + clear)
        val pillCy = if (stacked) pill.y + pillH - ((TIMER_BOTTOM_PAD_DP + TIMER_PILL_H_DP / 2) * dp).toInt()
        else pill.y + pillH / 2

        val reach = (r * sin(FAN_EDGE) + TIMER_FAN_HOT_DP / 2 * dp).toInt() + 1
        val chips = (TIMER_CHIPS_H_DP * dp).toInt()
        val height = chips + reach * 2
        val top = (pillCy - reach - chips).coerceIn(margin, maxOf(margin, screenH - height - margin))
        val originY = top + chips + reach
        val width = (TIMER_FAN_W_DP * dp).toInt()
        val left = (if (right) originX - width else originX).coerceIn(0, maxOf(0, screenW - width))
        val centres = TIMER_FAN_ANGLES.map { degrees ->
            val a = Math.toRadians(degrees.toDouble())
            val dx = (r * cos(a)).toInt()
            Spot(if (right) originX - dx else originX + dx, originY + (r * sin(a)).toInt())
        }
        return Fan(Spot(left, top), width, height, centres, chips, right)
    }

    /**
     * A timer card (the running list, or "your own time"), [width] x [height]. It opens
     * toward the middle of the screen with its bottom at the top of the button, or under
     * the button when there is no room above, and never reaches the button's column.
     */
    fun timerCard(bubbleX: Int, bubbleY: Int, width: Int, height: Int, screenW: Int, screenH: Int): Spot {
        val gap = (TIMER_CARD_GAP_DP * dp).toInt()
        val edge = (TIMER_CARD_MARGIN_DP * dp).toInt()
        val x = if (onRight(bubbleX, screenW)) bubbleX - gap - width else bubbleX + ring + gap
        val over = bubbleY - height
        val y = if (over >= edge) over else bubbleY + ring
        return Spot(
            x.coerceIn(edge, maxOf(edge, screenW - width - edge)),
            y.coerceIn(edge, maxOf(edge, screenH - height - edge)),
        )
    }

    /**
     * How far the pills over the button reach past the top of the ring window (0 when none is
     * shown), for [BubbleBounds]: the volume pill, the timer pill stacked on it, or either alone.
     */
    fun above(volumeShown: Boolean, timerStacked: Boolean = false): Int {
        if (!volumeShown && !timerStacked) return 0
        val inset = (ring - button) / 2
        val one = ((VolumePillView.H_DP - VolumePillView.BOTTOM_PAD_DP + PILL_GAP_DP) * dp).toInt()
        val two = if (volumeShown && timerStacked) (TIMER_H_DP * dp).toInt() else 0
        return maxOf(0, one + two - inset)
    }

    /**
     * How far the timer pill reaches past the side of the ring window, toward the middle of the
     * screen, when it sits beside the button (0 when it does not): messages shown beside the
     * button start past it.
     */
    fun beside(timerBeside: Boolean): Int =
        if (timerBeside) ((TIMER_SIDE_GAP_DP + TIMER_W_DP) * dp).toInt() else 0

    /** How far the Glyph pill reaches under the bottom of the ring window (0 when it is not shown), for [BubbleBounds]. */
    fun below(glyphShown: Boolean): Int {
        if (!glyphShown) return 0
        val inset = (ring - button) / 2
        return maxOf(0, ((GlyphPillView.H_DP - GlyphPillView.TOP_PAD_DP + PILL_GAP_DP) * dp).toInt() - inset)
    }

    companion object {
        /** Space between each pill (volume above, Glyph below) and the dictate button. */
        const val PILL_GAP_DP = 6
        /** Space between the track and the volume pill. */
        const val TRACK_GAP_DP = 12

        /** The timer pill's touch window, and the pill drawn inside it (wider when the text needs it). */
        const val TIMER_W_DP = 66
        const val TIMER_H_DP = 44
        const val TIMER_PILL_MIN_W_DP = 50f
        const val TIMER_PILL_H_DP = 18f
        /** Space under the drawn timer pill when it is stacked; the same as the volume pill's. */
        const val TIMER_BOTTOM_PAD_DP = 4f
        /** Space between the timer pill's touch window and the button's, beside it. */
        const val TIMER_SIDE_GAP_DP = 2

        /** The fan: bubbles on an arc around the pill, bigger under the finger. */
        const val TIMER_FAN_RADIUS_DP = 92f
        const val TIMER_FAN_BUBBLE_DP = 44f
        const val TIMER_FAN_HOT_DP = 52f
        val TIMER_FAN_ANGLES = listOf(-60, -30, 0, 30, 60)
        /** Space kept between a bubble and the button. */
        const val TIMER_FAN_CLEAR_DP = 4f
        const val TIMER_FAN_W_DP = 267
        /** Room over the fan for the running timers' chips. */
        const val TIMER_CHIPS_H_DP = 58

        /** Space between a timer card and the button's window, and between the card and the screen edge. */
        const val TIMER_CARD_GAP_DP = 38
        const val TIMER_CARD_MARGIN_DP = 16

        private val FAN_EDGE = Math.toRadians(60.0)
    }
}
