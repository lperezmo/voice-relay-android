package com.lperezmo.voicerelay

/**
 * Where the pills ([VolumePillView] above the dictate button, [VolumeTrackView]
 * above that, [GlyphPillView] below, [TimerPillView] upright beside the button,
 * toward the middle of the screen) sit for a button whose ring window is at
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

    /** Above the pill, flush with the button's outer edge so it opens toward the middle of the screen. */
    fun track(bubbleX: Int, bubbleY: Int, width: Int, height: Int, screenW: Int): Spot {
        val x = if (onRight(bubbleX, screenW)) bubbleX + (ring + button) / 2 - width else bubbleX + (ring - button) / 2
        val buttonTop = bubbleY + (ring - button) / 2
        val pillTop = buttonTop - ((PILL_GAP_DP + VolumePillView.PILL_H_DP) * dp).toInt()
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
     * The timer pill's touch window: beside the button on the side facing the middle of the
     * screen, level with it, its inner edge on the button's edge (the button's window lies over
     * that strip and keeps its own touches). The tab is drawn [timerStubInset] in from that
     * inner edge, so it sits [TIMER_STUB_GAP_DP] from the button.
     */
    fun timerPill(bubbleX: Int, bubbleY: Int, width: Int, height: Int, screenW: Int, screenH: Int): Spot {
        val buttonLeft = bubbleX + (ring - button) / 2
        val x = if (onRight(bubbleX, screenW)) buttonLeft - width else buttonLeft + button
        return Spot(
            x.coerceIn(0, maxOf(0, screenW - width)),
            (bubbleY + (ring - height) / 2).coerceIn(0, maxOf(0, screenH - height)),
        )
    }

    /** How far the tab's middle is from the touch window's inner edge (the edge on the button's side), in px. */
    fun timerStubInset(): Float = (TIMER_STUB_GAP_DP + TIMER_STUB_W_DP / 2) * dp

    /** The middle of the drawn tab, on the screen, for a button whose ring window is at (bubbleX, bubbleY). */
    fun timerStubCentre(bubbleX: Int, bubbleY: Int, screenW: Int, screenH: Int): Spot {
        val w = (TIMER_W_DP * dp).toInt()
        val h = (TIMER_H_DP * dp).toInt()
        val pill = timerPill(bubbleX, bubbleY, w, h, screenW, screenH)
        val inset = timerStubInset().toInt()
        val x = if (onRight(bubbleX, screenW)) pill.x + w - inset else pill.x + inset
        return Spot(x, pill.y + h / 2)
    }

    /**
     * The timer panel (the running timers over the timer track) or the minutes card, [width] x
     * [height]. It opens toward the middle of the screen with its bottom at the top of the
     * button, or under the button when there is no room above, never reaches the button's
     * column, and leaves the timer pill uncovered even where a narrow screen pushes it over
     * the pill's column.
     */
    fun timerPanel(bubbleX: Int, bubbleY: Int, width: Int, height: Int, screenW: Int, screenH: Int): Spot {
        val gap = (TIMER_CARD_GAP_DP * dp).toInt()
        val edge = (TIMER_CARD_MARGIN_DP * dp).toInt()
        val x = if (onRight(bubbleX, screenW)) bubbleX - gap - width else bubbleX + ring + gap
        // Clear of the timer pill's touch window too, which is a little taller than the ring window.
        val over = bubbleY - timerOverhang() - height
        val y = if (over >= edge) over else bubbleY + ring + timerOverhang()
        return Spot(
            x.coerceIn(edge, maxOf(edge, screenW - width - edge)),
            y.coerceIn(edge, maxOf(edge, screenH - height - edge)),
        )
    }

    /**
     * How far the pills over the button reach past the top of the ring window (0 when none is
     * shown), for [BubbleBounds]: the volume pill, and the timer pill's touch window, which is a
     * little taller than the ring.
     */
    fun above(volumeShown: Boolean, timerShown: Boolean = false): Int {
        val inset = (ring - button) / 2
        val volume = if (volumeShown) ((VolumePillView.H_DP - VolumePillView.BOTTOM_PAD_DP + PILL_GAP_DP) * dp).toInt() - inset else 0
        return maxOf(0, volume, if (timerShown) timerOverhang() else 0)
    }

    /**
     * How far the timer pill reaches past the side of the ring window, toward the middle of the
     * screen (0 when it is not shown): messages shown beside the button start past it.
     */
    fun beside(timerShown: Boolean): Int {
        if (!timerShown) return 0
        val inset = (ring - button) / 2
        return maxOf(0, (TIMER_W_DP * dp).toInt() - inset)
    }

    /** How far the pills under the button reach past the bottom of the ring window (0 when none is shown), for [BubbleBounds]. */
    fun below(glyphShown: Boolean, timerShown: Boolean = false): Int {
        val inset = (ring - button) / 2
        val glyph = if (glyphShown) ((GlyphPillView.H_DP - GlyphPillView.TOP_PAD_DP + PILL_GAP_DP) * dp).toInt() - inset else 0
        return maxOf(0, glyph, if (timerShown) timerOverhang() else 0)
    }

    /** The timer pill's touch window is centred on the ring window and this much taller on each side. */
    private fun timerOverhang(): Int = maxOf(0, ((TIMER_H_DP * dp).toInt() - ring + 1) / 2)

    companion object {
        /** Space between each pill (volume above, Glyph below) and the dictate button. */
        const val PILL_GAP_DP = 6
        /** Space between the track and the volume pill. */
        const val TRACK_GAP_DP = 12

        /**
         * The timer pill's touch window, beside the button, at least 44 dp each way. The drawn
         * pill is an upright tab [TIMER_STUB_W_DP] wide and [TIMER_TAB_H_DP] tall.
         */
        const val TIMER_W_DP = 44
        const val TIMER_H_DP = 60
        const val TIMER_STUB_W_DP = 20f
        const val TIMER_TAB_H_DP = 54f
        /** The tallest the tab gets for a long countdown: the button's ring window's height, so it never reaches the volume or Glyph pill. */
        const val TIMER_STUB_MAX_H_DP = 56f
        /** Room kept at each end of the tab, past its content. */
        const val TIMER_END_PAD_DP = 7f

        /**
         * The tab's height in dp for content [contentDp] long (dot or countdown, and badge):
         * [TIMER_TAB_H_DP], or taller when that does not fit, at most [TIMER_STUB_MAX_H_DP].
         */
        fun timerTabHeightDp(contentDp: Float): Float =
            maxOf(TIMER_TAB_H_DP, contentDp + 2 * TIMER_END_PAD_DP).coerceAtMost(TIMER_STUB_MAX_H_DP)
        /** Space between the tab and the button. */
        const val TIMER_STUB_GAP_DP = 5f

        /** Space between the timer panel and the button's window, and between the panel and the screen edge. */
        const val TIMER_CARD_GAP_DP = 38
        const val TIMER_CARD_MARGIN_DP = 16
    }
}
