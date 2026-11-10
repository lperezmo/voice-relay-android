package com.lperezmo.voicerelay

/**
 * Where the pills ([VolumePillView] above the dictate button, [VolumeTrackView]
 * above that, [GlyphPillView] below) sit for a button whose ring window is at
 * (bubbleX, bubbleY), and how far they reach past it. The button is a
 * [buttonDp] circle centered in a [ringDp] square window; [dp] is the screen
 * density. Kept free of Android so it can be unit tested.
 */
class PillLayout(private val dp: Float, ringDp: Int, buttonDp: Int, marginDp: Int) {

    private val ring = (ringDp * dp).toInt()
    private val button = (buttonDp * dp).toInt()
    private val margin = (marginDp * dp).toInt()

    data class Spot(val x: Int, val y: Int)

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
        val onRight = bubbleX + ring / 2 > screenW / 2
        val x = if (onRight) bubbleX + (ring + button) / 2 - width else bubbleX + (ring - button) / 2
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

    /** How far the volume pill reaches over the top of the ring window (0 when it is not shown), for [BubbleBounds]. */
    fun above(volumeShown: Boolean): Int {
        if (!volumeShown) return 0
        val inset = (ring - button) / 2
        return maxOf(0, ((VolumePillView.H_DP - VolumePillView.BOTTOM_PAD_DP + PILL_GAP_DP) * dp).toInt() - inset)
    }

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
    }
}
