package com.lperezmo.voicerelay

import android.content.Context

/**
 * The timer track at the bottom of the timer panel ([TimerPanel]): stops for 5, 10, 15
 * and 30 minutes and "Own". Like the volume track, taps do nothing: a timer starts only
 * with a V drawn on it (the gesture and the drawing are [VTrackView]'s). Picking "Own"
 * calls [onOwn]; any other stop calls [onStart] with its minutes.
 */
class TimerTrackView(context: Context) : VTrackView(context, TrackStops.TIMER) {

    var onStart: ((minutes: Int) -> Unit)? = null
    var onOwn: (() -> Unit)? = null

    init {
        contentDescription = "Timer presets. Hold, dip down, slide to a time, then rise to start."
    }

    override fun label(i: Int) = TimerText.trackLabel(TimerText.TRACK_MINUTES[i])
    override fun color(i: Int) = PillSkin.COLOR_TIMER
    override fun picked(i: Int) {
        val minutes = TimerText.TRACK_MINUTES[i]
        if (minutes == 0) onOwn?.invoke() else onStart?.invoke(minutes)
    }
}
