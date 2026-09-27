package com.lperezmo.voicerelay

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

/**
 * Applies a [VolumePreset] to the phone and reads the result back as a
 * [PillState]. Needs Do Not Disturb access: without it Android refuses to
 * start DND, and refuses to move the ringer in or out of silent.
 *
 * The DND hour is an alarm, not a timer in this process, so it still ends if
 * the app is killed. When it ends only DND turns off; the volumes stay at 0.
 */
class VolumeController(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val settings = AppSettings(context)

    val hasDndAccess: Boolean get() = notifications.isNotificationPolicyAccessGranted

    val dndOn: Boolean
        get() = notifications.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }

    /** Each stream's top step, or -1 for a stream this phone does not have. */
    fun maxes(): IntArray = IntArray(STREAMS.size) { i ->
        try { audio.getStreamMaxVolume(STREAMS[i]) } catch (_: RuntimeException) { -1 }
    }

    fun levels(): IntArray = IntArray(STREAMS.size) { i ->
        try { audio.getStreamVolume(STREAMS[i]) } catch (_: RuntimeException) { -1 }
    }

    fun ringer(): VolumePreset.Ringer = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> VolumePreset.Ringer.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> VolumePreset.Ringer.VIBRATE
        else -> VolumePreset.Ringer.NORMAL
    }

    fun current(): PillState = VolumeMath.classify(levels(), maxes(), dndOn, ringer())

    /** Returns null on success, or a message saying what stopped it. */
    fun apply(preset: VolumePreset): String? {
        if (!hasDndAccess) return "Grant Do Not Disturb access in Voice Relay"
        val maxes = maxes()
        val levels = VolumeMath.levelsFor(preset, maxes)
        return try {
            if (!preset.dnd) endDnd()
            // Out of silent first, or Android pins the ringtone and notification streams at 0.
            if (preset.ringer == VolumePreset.Ringer.NORMAL) audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            for (i in STREAMS.indices) {
                if (levels[i] < 0) continue
                try {
                    audio.setStreamVolume(STREAMS[i], levels[i], 0)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "${VolumeMath.STREAM_NAMES[i]} refused: ${e.javaClass.simpleName}")
                }
            }
            // A ringtone at 0 already means vibrate; silent is set on top of that.
            when (preset.ringer) {
                VolumePreset.Ringer.SILENT -> audio.ringerMode = AudioManager.RINGER_MODE_SILENT
                VolumePreset.Ringer.VIBRATE -> audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                VolumePreset.Ringer.NORMAL -> {}
            }
            if (preset.dnd) startDnd()
            Log.i(TAG, "Applied ${preset.label}: ${levels().joinToString("/")} of ${maxes.joinToString("/")}, ringer ${ringer()}, dnd $dndOn")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Preset refused", e)
            "Android refused the change. Check Do Not Disturb access."
        }
    }

    /** DND that lets alarms through, for [DND_MS]. */
    private fun startDnd() {
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        val until = System.currentTimeMillis() + DND_MS
        settings.dndUntil = until
        scheduleExpiry(until)
    }

    /** Turns off DND and forgets the hour. Volumes are left as they are. */
    fun endDnd() {
        alarms.cancel(expiryIntent())
        settings.dndUntil = 0L
        if (hasDndAccess && dndOn) notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    /**
     * After a reboot the alarm is gone: end an hour that already ran out, or
     * set the alarm again for one still running.
     */
    fun resumeDndTimer() {
        val until = settings.dndUntil
        if (until <= 0L) return
        if (System.currentTimeMillis() >= until) endDnd() else scheduleExpiry(until)
    }

    /** True when the hour ends on the minute; otherwise Android may run it up to 10 minutes late. */
    val exactTimer: Boolean get() = alarms.canScheduleExactAlarms()

    private fun scheduleExpiry(until: Long) {
        val pending = expiryIntent()
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, until, INEXACT_WINDOW_MS, pending)
        }
    }

    private fun expiryIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, DndExpiryReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "VoiceRelay"
        const val DND_MS = 60 * 60 * 1000L
        private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L
        /** Same order as [VolumeMath.STREAM_NAMES] and [VolumePreset.percents]. */
        val STREAMS = intArrayOf(
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
        )
    }
}

/** The DND hour is up: DND off, volumes untouched. */
class DndExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppSettings(context).dndUntil > 0L) VolumeController(context).endDnd()
    }
}
