package com.lperezmo.voicerelay

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The timers Voice Relay runs itself, from the timer pill and from "hey timer".
 * It owns them (not the clock app) so it can list, extend and stop them. The
 * rules are [Timers]; this saves the list, sets one alarm per timer, and when
 * one ends rings on the alarm stream through a notification with a Stop action.
 * One instance for the whole app ([get]), so the pill, the alarm and the
 * notification all see the same list; it survives the pills being hidden and
 * the service restarting because everything is read back from storage.
 */
class TimerController private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("timers", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var book = Timers.fromJson(prefs.getString(KEY_BOOK, null))
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    /** Only used when notifications are off, so a finished timer is still heard. */
    private var ringtone: Ringtone? = null
    private val stopRingtone = Runnable {
        ringtone?.stop()
        ringtone = null
    }

    /** False when Android has not allowed exact alarms: a timer may then ring a few minutes late in deep sleep. */
    val exactAlarms: Boolean get() = alarms.canScheduleExactAlarms()

    /** False when notifications are off for the app: a finished timer then has no Stop notification. */
    val canNotify: Boolean get() = notifications.areNotificationsEnabled()

    private fun now() = System.currentTimeMillis()

    /** Every timer, soonest first. Done ones stay until they are stopped. */
    @Synchronized fun timers(): List<TimerEntry> = book.sorted()

    /** [listener] runs on the main thread after every change. */
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    /** Starts a timer [seconds] long. Without a [label] it is "Timer 1", "Timer 2"... */
    @Synchronized fun start(seconds: Long, label: String? = null): TimerEntry {
        val (next, entry) = book.start(now(), seconds, label)
        save(next)
        schedule(entry)
        Log.i(TAG, "Timer: started #${entry.id}, ${(entry.endsAt - now()) / 1000} s, exact alarm ${exactAlarms}")
        return entry
    }

    /** A minute more. A timer that already rang starts again, one minute from now. */
    @Synchronized fun addMinute(id: Int): TimerEntry? {
        book.find(id) ?: return null
        save(book.extend(id, now()))
        val entry = book.find(id) ?: return null
        notifications.cancel(NOTIFICATION_TAG, id)
        quietIfNothingRings()
        schedule(entry)
        return entry
    }

    /** Stops a running timer or dismisses a done one. Returns it, or null if it was already gone. */
    @Synchronized fun stop(id: Int): TimerEntry? {
        val entry = book.find(id) ?: return null
        save(book.remove(id))
        alarms.cancel(alarmIntent(id))
        notifications.cancel(NOTIFICATION_TAG, id)
        quietIfNothingRings()
        Log.i(TAG, "Timer: stopped #$id")
        return entry
    }

    /** Rings every timer whose end has come. Safe to call at any time; each timer rings once. */
    @Synchronized fun checkDue() {
        val due = book.due(now())
        if (due.isEmpty()) return
        save(book.markRung(due.map { it.id }))
        for (entry in due) ring(entry)
    }

    /** Sets the alarms again after a restart or a reboot, and rings what ended meanwhile. */
    @Synchronized fun resume() {
        val now = now()
        for (entry in book.list) if (!entry.done(now)) schedule(entry)
        checkDue()
    }

    private fun save(next: Timers) {
        book = next
        prefs.edit().putString(KEY_BOOK, next.toJson()).apply()
        main.post { for (listener in listeners) listener() }
    }

    private fun schedule(entry: TimerEntry) {
        val pending = alarmIntent(entry.id)
        try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.endsAt, pending)
                return
            }
        } catch (_: SecurityException) {
            // The permission was taken away between the check and the call: fall through.
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.endsAt, pending)
    }

    private fun alarmIntent(id: Int) = receiverIntent(ACTION_FIRE, id)

    private fun receiverIntent(action: String, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id,
        Intent(context, TimerReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // checked with canNotify: without it the ringtone plays instead
    private fun ring(entry: TimerEntry) {
        Log.i(TAG, "Timer: #${entry.id} is done, notification ${canNotify}")
        if (!canNotify) {
            main.post { playRingtone() }
            return
        }
        ensureChannel()
        val stop = receiverIntent(ACTION_STOP, entry.id)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_voice_mark)
            .setContentTitle("${entry.label} is done")
            .setContentText("Tap Stop to silence it.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setTimeoutAfter(RING_MS)
            .build()
        // Keeps the sound going until it is stopped, like an alarm.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        try {
            notifications.notify(NOTIFICATION_TAG, entry.id, notification)
        } catch (_: SecurityException) {
            main.post { playRingtone() }
        }
    }

    private fun ensureChannel() {
        if (notifications.getNotificationChannel(CHANNEL) != null) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Timers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when a timer ends, with a button to stop it."
                setSound(alarmSound(), alarmAttributes())
                enableVibration(true)
                setBypassDnd(true)
                setShowBadge(false)
            }
        )
    }

    private fun alarmSound() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun alarmAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun playRingtone() {
        if (ringtone != null) return
        val tone = try { RingtoneManager.getRingtone(context, alarmSound()) } catch (_: Exception) { null } ?: return
        tone.audioAttributes = alarmAttributes()
        tone.isLooping = true
        tone.play()
        ringtone = tone
        main.removeCallbacks(stopRingtone)
        main.postDelayed(stopRingtone, RINGTONE_MS)
    }

    /** Stops the ringtone once no done timer is left. */
    private fun quietIfNothingRings() {
        val now = now()
        if (book.list.none { it.done(now) }) {
            main.removeCallbacks(stopRingtone)
            main.post(stopRingtone)
        }
    }

    companion object {
        private const val TAG = "VoiceRelay"
        private const val KEY_BOOK = "book"
        private const val CHANNEL = "timer_done"
        /** With the timer's id; the tag keeps it clear of the app's other notifications. */
        private const val NOTIFICATION_TAG = "timer"
        /** A timer nobody stops goes quiet after this long; it stays in the list as done. */
        private const val RING_MS = 10 * 60_000L
        private const val RINGTONE_MS = 60_000L
        const val ACTION_FIRE = "com.lperezmo.voicerelay.TIMER_FIRE"
        const val ACTION_STOP = "com.lperezmo.voicerelay.TIMER_STOP"
        const val EXTRA_ID = "id"

        @SuppressLint("StaticFieldLeak") // holds the application context only
        @Volatile private var instance: TimerController? = null

        fun get(context: Context): TimerController = instance ?: synchronized(this) {
            instance ?: TimerController(context.applicationContext).also { instance = it }
        }
    }
}

/** A timer's alarm went off, or Stop was tapped on its notification. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timers = TimerController.get(context)
        when (intent.action) {
            TimerController.ACTION_FIRE -> timers.checkDue()
            TimerController.ACTION_STOP -> timers.stop(intent.getIntExtra(TimerController.EXTRA_ID, -1))
        }
    }
}
