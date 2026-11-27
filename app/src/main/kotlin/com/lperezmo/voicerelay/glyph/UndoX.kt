package com.lperezmo.voicerelay.glyph

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.lperezmo.voicerelay.R
import com.lperezmo.voicerelay.glyph.recognizer.Undo

/**
 * After a match fires: a small light-red X near the top left for [UndoOverlayService.SHOW_MS]
 * over whatever opened (needs "Display over other apps"), or without that permission a
 * heads-up notification with the same choices. Either way it leads to [UndoActivity].
 */
object UndoX {
    const val CHANNEL = "glyph_undo"
    /** Clear of the wake word's 7001 and 7002. */
    private const val NOTIFICATION_ID = 7101
    private const val NOTIFICATION_MS = 10_000L

    fun overlayAllowed(context: Context) = Settings.canDrawOverlays(context)

    fun notificationsAllowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun offer(context: Context, e: LastMatch.Entry) {
        LastMatch.put(e)
        if (overlayAllowed(context)) {
            context.startService(Intent(context, UndoOverlayService::class.java))
        } else {
            notify(context, e)
        }
    }

    /** "Opened Claude from a heart. Wrong shape?" */
    fun headline(e: LastMatch.Entry): String {
        val name = e.shapeName.lowercase()
        val article = if (name.firstOrNull()?.let { it in "aeiou" } == true) "an" else "a"
        return "${e.actionText} from $article $name. Wrong shape?"
    }

    fun pastTense(action: Action): String = when (action) {
        Action.Nothing -> "Matched"
        is Action.OpenApp -> "Opened ${action.name}"
        is Action.Url -> "Opened the link"
        Action.RelayClipboard -> "Sent the clipboard"
        Action.WakeToggle -> "Toggled the wake word"
        Action.PrintDateLabel -> "Printed a date label"
        Action.Ask -> "Opened Ask the Page"
        Action.RotateFlip -> "Flipped the screen"
        Action.RotateAuto -> "Set rotation to auto"
    }

    @SuppressLint("MissingPermission") // checked: without it the notification is simply not posted
    private fun notify(context: Context, e: LastMatch.Entry) {
        if (!notificationsAllowed(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Undo after a match", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Offers to relearn a shape that was misrecognised."
                setShowBadge(false)
            })
        }
        val names = Store.shapes(context).associate { it.id to it.name }
        val best = Undo.alternatives(e.ranked, e.shapeId).firstOrNull { it.key in names }
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.glyph_ic_tile)
            .setContentTitle(headline(e))
            .setContentText("Tap for every choice.")
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_MS)
            .setContentIntent(sheet(context, e, null, 0))
        // Android shows at most three actions; the sheet (tap the notification) has every "Learn as"
        best?.let { b.addAction(Notification.Action.Builder(null, "Learn as ${names[it.key]}", sheet(context, e, UndoActivity.LEARN_PREFIX + it.key, 1)).build()) }
        b.addAction(Notification.Action.Builder(null, "New shape", sheet(context, e, UndoActivity.NEW, 2)).build())
        b.addAction(Notification.Action.Builder(null, "Just undo", sheet(context, e, UndoActivity.UNDO, 3)).build())
        nm.notify(NOTIFICATION_ID, b.build())
    }

    fun cancelNotification(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

    fun sheetIntent(context: Context, e: LastMatch.Entry, choice: String?): Intent =
        Intent(context, UndoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(UndoActivity.EXTRA_MATCH, e.id)
            .apply { choice?.let { putExtra(UndoActivity.EXTRA_CHOICE, it) } }

    private fun sheet(context: Context, e: LastMatch.Entry, choice: String?, code: Int): PendingIntent =
        PendingIntent.getActivity(context, code, sheetIntent(context, e, choice), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/**
 * Holds the undo X on screen for [SHOW_MS] after a match, then stops. Not a foreground
 * service: it is started while the canvas is in front and is done within seconds.
 */
class UndoOverlayService : Service() {
    companion object {
        const val SHOW_MS = 4000L
        private const val FADE_MS = 150L
        /** A stray tap only arms the X; a second tap within this window opens the sheet. */
        private const val ARM_MS = 1500L
    }

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var entry: LastMatch.Entry? = null
    private var armedAt = 0L
    private val motion get() = ValueAnimator.areAnimatorsEnabled()

    private val timeout = Runnable { hide() }
    private val disarm = Runnable { setArmed(false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = LastMatch.get()
        if (e == null || !UndoX.overlayAllowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        entry = e
        main.removeCallbacks(timeout)
        view?.let { it.animate().cancel(); it.alpha = 1f } ?: show()
        main.postDelayed(timeout, SHOW_MS)
        return START_NOT_STICKY
    }

    private fun show() {
        val density = resources.displayMetrics.density
        val target = (48 * density).toInt() // 36 dp circle in a 48 dp touch target
        val x = XView(this).apply {
            contentDescription = "Wrong shape? Tap twice to undo"
            setOnClickListener { tapped() }
            alpha = if (motion) 0f else 1f
        }
        val lp = WindowManager.LayoutParams(
            target, target,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // top left, below the status bar and clear of a typical app bar's back arrow, so it
            // stays out of the way of taps in the bottom corners
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = WindowInsets.Type.systemBars()
            this.x = (10 * density).toInt()
            y = (64 * density).toInt()
            title = "Glyph undo"
        }
        getSystemService(WindowManager::class.java).addView(x, lp)
        view = x
        if (motion) x.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    private fun tapped() {
        val now = System.currentTimeMillis()
        if (now - armedAt > ARM_MS) {
            // first tap: arm, and keep the X up long enough for the second
            setArmed(true)
            armedAt = now
            main.removeCallbacks(disarm)
            main.postDelayed(disarm, ARM_MS)
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, ARM_MS + FADE_MS)
            return
        }
        main.removeCallbacks(timeout)
        main.removeCallbacks(disarm)
        val e = entry
        remove()
        if (e != null && LastMatch.get() === e) startActivity(UndoX.sheetIntent(this, e, null))
        stopSelf()
    }

    /** Time is up and the X went unused: fade it out and forget the match. */
    private fun hide() {
        val v = view ?: return stopSelf()
        val done = {
            remove()
            LastMatch.clear(entry)
            stopSelf()
        }
        if (motion) v.animate().alpha(0f).setDuration(FADE_MS).withEndAction(done).start() else done()
    }

    private fun setArmed(on: Boolean) {
        if (!on) armedAt = 0L
        (view as? XView)?.armed = on
    }

    private fun remove() {
        view?.let { runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
        view = null
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(disarm)
        remove()
        super.onDestroy()
    }

    /** A translucent light-red circle with a white X; solid red once armed by a first tap. */
    private class XView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 242, 139, 130) }
        var armed = false
            set(on) {
                field = on
                fill.color = if (on) Color.rgb(217, 48, 37) else Color.argb(215, 242, 139, 130)
                invalidate()
            }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * d
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, 18 * d, fill)
            val k = 6.5f * d
            canvas.drawLine(cx - k, cy - k, cx + k, cy + k, cross)
            canvas.drawLine(cx - k, cy + k, cx + k, cy - k, cross)
        }
    }
}
