package com.lperezmo.voicerelay

import android.content.Context

/**
 * The four-stop volume track, opened from [VolumePillView]. Taps do nothing:
 * a preset is picked with a V drawn on the track; the gesture and the drawing
 * are [VTrackView]'s.
 */
class VolumeTrackView(context: Context) : VTrackView(context, TrackStops.VOLUME) {

    var onCommit: ((VolumePreset) -> Unit)? = null

    private val presets = VolumePreset.entries

    init {
        contentDescription = "Volume presets. Hold, dip down, slide to a preset, then rise to apply."
    }

    override fun label(i: Int) = presets[i].short
    override fun color(i: Int) = presets[i].color
    override fun picked(i: Int) { onCommit?.invoke(presets[i]) }
}
