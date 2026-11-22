package com.lperezmo.voicerelay

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.lperezmo.voicerelay.glyph.CanvasActivity

/**
 * The optional pills that ride with the dictate button: the volume pill
 * ([VolumePillView]) above it, which opens the preset track ([VolumeTrackView]),
 * the Glyph pill ([GlyphPillView]) below it, and the timer pill ([TimerPillView])
 * beside it or on top of the volume pill, with its fan of times ([TimerFanView])
 * and its two cards ([TimerListCard], [TimerOwnCard]). Where they sit is [PillLayout].
 *
 * The button stays the service's: [bubble] reads its window as it is now (null
 * while it is not on screen), and [onPillAdded] lets the service settle the
 * button with the new pill's room and raise it back over the pill. The service
 * calls [follow] whenever the button moves. Everything runs on the main thread.
 */
class FloatingPills(
    private val service: AccessibilityService,
    private val settings: AppSettings,
    private val volume: VolumeController,
    private val timers: TimerController,
    private val handler: Handler,
    /** The ring window, the button inside it and the screen margin, in dp: the button's geometry. */
    private val ringDp: Int,
    private val buttonDp: Int,
    private val marginDp: Int,
    private val bubble: () -> WindowManager.LayoutParams?,
    private val buttonsHidden: () -> Boolean,
    private val onPillAdded: () -> Unit,
    private val showFeedback: (text: String, durationMs: Long) -> Unit,
    /** The timers changed or ticked: how much of the next running one is left (0 to 1, null with none), and whether one is done. */
    private val onTimers: (next: Float?, done: Boolean) -> Unit,
    /** A finger touched a pill, or a pill's popup opened or closed: keeps the cluster from dimming. */
    private val onActivity: () -> Unit,
) {
    private var pillView: VolumePillView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var trackView: VolumeTrackView? = null
    private var trackParams: WindowManager.LayoutParams? = null
    private val closeTrack = Runnable { hideTrack() }
    private var glyphView: GlyphPillView? = null
    private var glyphParams: WindowManager.LayoutParams? = null
    /** Glyph installed, updated or removed: show or hide its pill to match. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart == GlyphPillView.GLYPH_PACKAGE) refreshGlyph()
        }
    }
    private var timerView: TimerPillView? = null
    private var timerParams: WindowManager.LayoutParams? = null
    private var fanView: TimerFanView? = null
    /** Where the fan goes for the press in progress, worked out when the finger lands. */
    private var fan: PillLayout.Fan? = null
    private var listCard: TimerListCard? = null
    private var listParams: WindowManager.LayoutParams? = null
    private var ownCard: TimerOwnCard? = null
    private var ownParams: WindowManager.LayoutParams? = null
    /** What "your own time" starts at: the last time picked there, until the service restarts. */
    private var ownMinutes = TimerText.DEFAULT_OWN_MINUTES
    private val press by lazy { TimerPress(TimerPress.SLOP_DP * dp, TimerPress.REACH_DP * dp) }
    private val holdTimer = Runnable { if (press.held()) openFan() }
    private val tickTimers = Runnable { renderTimers() }
    /** A timer started, stopped, extended or ended, from here, from "hey timer" or from its notification. */
    private val timersChanged: () -> Unit = { renderTimers() }
    private val timerStacked get() = settings.timerPillPosition == AppSettings.TIMER_ABOVE

    /** A tap on the pill closes an open track through its outside touch first; this keeps it from reopening. */
    private var trackClosedOutsideAt = 0L
    private val refreshPill = Runnable { pillView?.update(volume.current()) }
    /** Any volume, ringer or DND change, from the presets or not, redraws the pill. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(refreshPill)
            handler.postDelayed(refreshPill, PILL_REFRESH_MS)
        }
    }

    private val dp get() = service.resources.displayMetrics.density
    private val screenW get() = service.resources.displayMetrics.widthPixels
    private val screenH get() = service.resources.displayMetrics.heightPixels
    private val wm get() = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun layout() = PillLayout(dp, ringDp, buttonDp, marginDp)

    /** Starts watching for Glyph being installed or removed, and the timers for the button's ring. Call once, when the service connects. */
    fun start() {
        timers.addListener(timersChanged)
        renderTimers()
        ContextCompat.registerReceiver(
            service, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** The service is going away: stop watching Glyph. Call [hide] too, to take the pills down. */
    fun stop() {
        timers.removeListener(timersChanged)
        handler.removeCallbacks(tickTimers)
        try { service.unregisterReceiver(packageReceiver) } catch (_: IllegalArgumentException) {}
    }

    /** Takes down every pill and whatever they have open. */
    fun hide() {
        hideVolumePill()
        hideGlyphPill()
        hideTimerPill()
    }

    /** Closes what the pills have open (the preset track, the timer fan and cards). Safe to call at any time. */
    fun hidePopups() {
        hideTrack()
        handler.removeCallbacks(holdTimer)
        press.cancel()
        closeFan()
        hideTimerCards()
    }

    /** True while the preset track, the timer fan or a timer card is open. */
    fun popupsOpen(): Boolean = trackView != null || fanView != null || listCard != null || ownCard != null

    /** Fades the three pills to [alpha] over [durationMs], for dimming when idle; popups are never faded. */
    fun fade(alpha: Float, durationMs: Long) {
        for (view in listOf(pillView, glyphView, timerView)) view?.animate()?.alpha(alpha)?.setDuration(durationMs)?.start()
        pillAlpha = alpha
    }

    /** What [fade] last asked for, so a pill shown while dimmed starts dimmed. */
    private var pillAlpha = 1f

    /** How far the shown pills reach over and under the button's ring window, for [BubbleBounds]. */
    fun above(): Int = layout().above(pillView != null, timerView != null && timerStacked)
    fun below(): Int = layout().below(glyphView != null)

    /** How far the timer pill reaches beside the button, toward the middle of the screen (0 when it is not there). */
    fun beside(): Int = layout().beside(timerView != null && !timerStacked)

    /** Keeps the volume pill and track, and the Glyph pill, next to the button as it moves. */
    fun follow(wm: WindowManager, bubble: WindowManager.LayoutParams) {
        pillParams?.let {
            positionPill(it, bubble)
            wm.updateViewLayout(pillView, it)
        }
        trackParams?.let {
            positionTrack(it, bubble)
            wm.updateViewLayout(trackView, it)
        }
        glyphParams?.let {
            positionGlyphPill(it, bubble)
            wm.updateViewLayout(glyphView, it)
        }
        timerParams?.let {
            positionTimerPill(it, bubble)
            wm.updateViewLayout(timerView, it)
        }
        placeTimerCards(bubble)
    }

    // --- Volume presets ---

    /** Shows or hides the volume pill to match the setting. Safe to call at any time. */
    fun refreshVolume() {
        if (settings.volumePillEnabled && !buttonsHidden()) showVolumePill() else hideVolumePill()
    }

    private fun showVolumePill() {
        if (pillView != null) return
        val bubble = bubble() ?: return
        val params = WindowManager.LayoutParams(
            (VolumePillView.W_DP * dp).toInt(), (VolumePillView.H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionPill(params, bubble)
        val view = VolumePillView(service).apply {
            update(volume.current())
            setOnClickListener {
                onActivity()
                when {
                    trackView != null -> hideTrack()
                    android.os.SystemClock.uptimeMillis() - trackClosedOutsideAt > REOPEN_GUARD_MS -> showTrack()
                }
            }
        }
        view.alpha = pillAlpha
        wm.addView(view, params)
        pillView = view
        pillParams = params
        onPillAdded()
        ContextCompat.registerReceiver(
            service, volumeReceiver,
            IntentFilter().apply {
                addAction(ACTION_VOLUME_CHANGED)
                addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun hideVolumePill() {
        hideTrack()
        val view = pillView ?: return
        try { service.unregisterReceiver(volumeReceiver) } catch (_: IllegalArgumentException) {}
        handler.removeCallbacks(refreshPill)
        wm.removeView(view)
        pillView = null
        pillParams = null
        // Stacked, the timer pill drops into the spot the volume pill left.
        placeTimerPillAgain()
    }

    private fun showTrack() {
        if (trackView != null) return
        if (!volume.hasDndAccess) {
            showFeedback("Grant Do Not Disturb access in Voice Relay to use volume presets", 3000)
            return
        }
        hideTimerCards()
        val bubble = bubble() ?: return
        val params = WindowManager.LayoutParams(
            (VolumeTrackView.W_DP * dp).toInt(), (VolumeTrackView.H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Outside touches are reported (to close the track) and still reach the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionTrack(params, bubble)
        val view = VolumeTrackView(service).apply {
            current = volume.current().preset?.ordinal ?: -1
            onTouch = { handler.removeCallbacks(closeTrack) }
            onCommit = { preset -> applyPreset(preset) }
            onTap = {
                showFeedback("Taps do nothing. Draw a V: dip, slide, rise.", FEEDBACK_MS)
                scheduleTrackClose(TRACK_IDLE_MS)
            }
            onCancel = {
                showFeedback("Cancelled, nothing changed", FEEDBACK_MS)
                scheduleTrackClose(TRACK_IDLE_MS)
            }
            onOutside = {
                trackClosedOutsideAt = android.os.SystemClock.uptimeMillis()
                hideTrack()
            }
        }
        wm.addView(view, params)
        trackView = view
        trackParams = params
        pillView?.open = true
        scheduleTrackClose(TRACK_IDLE_MS)
        onActivity()
    }

    /** Closes the preset track if it is open. Safe to call at any time. */
    fun hideTrack() {
        handler.removeCallbacks(closeTrack)
        val view = trackView ?: return
        wm.removeView(view)
        trackView = null
        trackParams = null
        pillView?.open = false
        onActivity()
    }

    private fun scheduleTrackClose(delayMs: Long) {
        handler.removeCallbacks(closeTrack)
        handler.postDelayed(closeTrack, delayMs)
    }

    private fun applyPreset(preset: VolumePreset) {
        val error = volume.apply(preset)
        pillView?.update(volume.current())
        if (error != null) {
            showFeedback(error, 3000)
        } else {
            val note = if (preset.dnd && !volume.exactTimer) " (ends within 10 min of the hour)" else ""
            showFeedback("${preset.label} applied$note", FEEDBACK_MS)
        }
        scheduleTrackClose(TRACK_CLOSE_AFTER_APPLY_MS)
    }

    private fun positionPill(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val spot = layout().volumePill(bubble.x, bubble.y, params.width, params.height)
        params.x = spot.x
        params.y = spot.y
    }

    private fun positionTrack(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val spot = layout().track(
            bubble.x, bubble.y, params.width, params.height, screenW,
            timerStacked = timerView != null && timerStacked,
        )
        params.x = spot.x
        params.y = spot.y
    }

    // --- Glyph pill ---

    /**
     * Shows the Glyph pill when the setting is on and Glyph is installed (or the built-in
     * canvas is on, which needs no Glyph app), else hides it. Safe to call at any time.
     */
    fun refreshGlyph() {
        val canvasThere = settings.glyphBuiltIn || GlyphPillView.glyphInstalled(service)
        if (settings.glyphPillEnabled && !buttonsHidden() && canvasThere) showGlyphPill() else hideGlyphPill()
    }

    private fun showGlyphPill() {
        if (glyphView != null) return
        val bubble = bubble() ?: return
        val params = WindowManager.LayoutParams(
            (GlyphPillView.W_DP * dp).toInt(), (GlyphPillView.H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionGlyphPill(params, bubble)
        val view = GlyphPillView(service).apply { onFire = { openGlyph() } }
        view.alpha = pillAlpha
        wm.addView(view, params)
        glyphView = view
        glyphParams = params
        onPillAdded()
    }

    private fun hideGlyphPill() {
        val view = glyphView ?: return
        wm.removeView(view)
        glyphView = null
        glyphParams = null
    }

    /**
     * Opens Glyph's canvas in its own task, over whatever is on screen: the built-in one
     * when [AppSettings.glyphBuiltIn] is on, else the Glyph app's.
     */
    private fun openGlyph() {
        onActivity()
        hidePopups()
        if (settings.glyphBuiltIn) {
            service.startActivity(
                Intent(service, CanvasActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            glyphView?.sparkle()
            return
        }
        try {
            service.startActivity(GlyphPillView.openCanvasIntent())
            glyphView?.sparkle()
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "Glyph canvas not found", e)
            showFeedback("Glyph is not installed", FEEDBACK_MS)
            refreshGlyph()
        }
    }

    private fun positionGlyphPill(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val spot = layout().glyphPill(bubble.x, bubble.y, params.width, params.height, screenH)
        params.x = spot.x
        params.y = spot.y
    }

    // --- Timer pill ---

    /**
     * Shows or hides the timer pill to match the setting, and moves it when its position
     * setting changed. Safe to call at any time; the service settles the button afterwards.
     */
    fun refreshTimer() {
        if (settings.timerPillEnabled && !buttonsHidden()) showTimerPill() else hideTimerPill()
    }

    private fun showTimerPill() {
        timerView?.let {
            it.stacked = timerStacked
            placeTimerPillAgain()
            return
        }
        val bubble = bubble() ?: return
        val params = WindowManager.LayoutParams(
            (PillLayout.TIMER_W_DP * dp).toInt(), (PillLayout.TIMER_H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val view = TimerPillView(service).apply {
            stacked = timerStacked
            onPress = { action, rawX, rawY -> onTimerPress(action, rawX, rawY) }
            onTap = {
                hidePopups()
                showListCard()
            }
        }
        timerView = view
        timerParams = params
        positionTimerPill(params, bubble)
        view.alpha = pillAlpha
        wm.addView(view, params)
        renderTimers()
        onPillAdded()
    }

    private fun hideTimerPill() {
        val view = timerView ?: return
        handler.removeCallbacks(holdTimer)
        // The tick keeps going (slower) while a timer runs: the button's gold arc still shows it.
        press.cancel()
        closeFan()
        hideTimerCards()
        wm.removeView(view)
        timerView = null
        timerParams = null
    }

    private fun positionTimerPill(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val spot = layout().timerPill(
            bubble.x, bubble.y, params.width, params.height, screenW,
            stacked = timerStacked, volumeShown = pillView != null,
        )
        params.x = spot.x
        params.y = spot.y
    }

    /** Puts the timer pill, and the track above it, where they belong now: after a setting or the volume pill changed. */
    private fun placeTimerPillAgain() {
        val bubble = bubble() ?: return
        timerParams?.let {
            positionTimerPill(it, bubble)
            wm.updateViewLayout(timerView, it)
        }
        trackParams?.let {
            positionTrack(it, bubble)
            wm.updateViewLayout(trackView, it)
        }
    }

    /**
     * Redraws the pill, the chips, the list and the button's gold arc from the timers as they
     * are now, and keeps ticking while one runs (slower when only the button's ring shows it).
     */
    private fun renderTimers() {
        handler.removeCallbacks(tickTimers)
        // The alarm rings a timer on time; this catches one whose alarm Android held back.
        timers.checkDue()
        val now = System.currentTimeMillis()
        val sorted = timers.timers()
        onTimers(sorted.firstOrNull { !it.done(now) }?.fractionLeft(now), sorted.any { it.done(now) })
        val view = timerView
        if (view == null) {
            if (sorted.any { !it.done(now) }) handler.postDelayed(tickTimers, RING_TICK_MS)
            return
        }
        view.update(TimerText.face(sorted, now), TimerText.describe(sorted, now))
        fanView?.showChips(sorted.map { TimerFanView.Chip(it.label, TimerText.clock(it.secondsLeft(now)), it.done(now)) })
        listCard?.let { card ->
            card.show(sorted, now)
            bubble()?.let { placeTimerCards(it) }
        }
        if (sorted.any { !it.done(now) }) handler.postDelayed(tickTimers, TIMER_TICK_MS)
    }

    /**
     * Every touch on the timer pill. A tap lists the timers; a hold or a drag opens the
     * fan, and letting go on a bubble starts that timer ([TimerPress] decides which).
     */
    private fun onTimerPress(action: Int, rawX: Float, rawY: Float) {
        val view = timerView ?: return
        val params = timerParams ?: return
        val bubble = bubble() ?: return
        // From screen coordinates to the ones the windows are placed in.
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        val x = rawX - at[0] + params.x
        val y = rawY - at[1] + params.y
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                onActivity()
                hidePopups()
                fan = layout().timerFan(
                    PillLayout.Spot(params.x, params.y), params.width, params.height, timerStacked,
                    bubble.x, screenW, screenH,
                )
                press.down(x, y)
                handler.postDelayed(holdTimer, TimerPress.HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val was = press.hot
                // Once the fan is on screen, the finger is matched against where its bubbles
                // really are, in case Android nudged the window from where it was asked for.
                val shown = fanView?.centresOnScreen()
                val opened = if (shown != null) press.move(rawX, rawY, shown) else press.move(x, y, fan?.centres.orEmpty())
                if (opened) {
                    handler.removeCallbacks(holdTimer)
                    openFan()
                }
                if (press.hot != was) {
                    fanView?.hot = press.hot
                    if (press.hot >= 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdTimer)
                val outcome = press.up()
                closeFan()
                when (outcome) {
                    TimerPress.Outcome.Tap -> showListCard()
                    is TimerPress.Outcome.Pick -> {
                        val minutes = TimerText.FAN_MINUTES[outcome.index]
                        if (minutes == 0) showOwnCard() else startTimer(minutes)
                    }
                    TimerPress.Outcome.None -> {}
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdTimer)
                press.cancel()
                closeFan()
            }
        }
    }

    private fun startTimer(minutes: Int) {
        val seconds = TimerText.clampMinutes(minutes) * 60L
        timers.start(seconds)
        timerView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        showFeedback(TimerText.started(seconds), TIMER_FEEDBACK_MS)
    }

    private fun stopTimer(id: Int) {
        val now = System.currentTimeMillis()
        val stopped = timers.stop(id) ?: return
        showFeedback(TimerText.stopped(stopped, now), TIMER_FEEDBACK_MS)
        if (timers.timers().isEmpty()) hideTimerCards()
    }

    /** The fan only draws: the press that opened it stays with the pill. */
    private fun openFan() {
        if (fanView != null) return
        val fan = fan ?: return
        val view = TimerFanView(service).apply { place(fan) }
        val params = WindowManager.LayoutParams(
            fan.width, fan.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = fan.window.x
            y = fan.window.y
        }
        wm.addView(view, params)
        fanView = view
        onActivity()
        timerView?.active = true
        timerView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        renderTimers()
    }

    private fun closeFan() {
        fan = null
        val view = fanView ?: return
        wm.removeView(view)
        fanView = null
        timerView?.active = listCard != null || ownCard != null
        onActivity()
    }

    private fun showListCard() {
        if (listCard != null) return
        val bubble = bubble() ?: return
        val card = TimerListCard(service).apply {
            onPlus = { id -> timers.addMinute(id) }
            onStop = { id -> stopTimer(id) }
            onOutside = { hideTimerCards() }
            // As many rows as the screen has room for (few in landscape), six at most.
            maxRows = ((screenH / dp).toInt() - 2 * PillLayout.TIMER_CARD_MARGIN_DP - TimerListCard.EXTRA_DP) / TimerListCard.ROW_DP
            show(timers.timers(), System.currentTimeMillis())
        }
        val params = cardParams(
            TimerListCard.W_DP,
            // Outside touches are reported (to close the card) and still reach the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        )
        listCard = card
        listParams = params
        placeTimerCards(bubble, update = false)
        wm.addView(card, params)
        timerView?.active = true
        onActivity()
    }

    private fun showOwnCard() {
        if (ownCard != null) return
        val bubble = bubble() ?: return
        val card = TimerOwnCard(service, ownMinutes).apply {
            onStart = { minutes ->
                ownMinutes = minutes
                hideTimerCards()
                startTimer(minutes)
            }
            onCancel = { hideTimerCards() }
        }
        // Focusable, so the minutes can be typed; touches outside still reach the app and close the card.
        val params = cardParams(
            TimerOwnCard.W_DP,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        ownCard = card
        ownParams = params
        placeTimerCards(bubble, update = false)
        wm.addView(card, params)
        timerView?.active = true
        onActivity()
    }

    private fun cardParams(widthDp: Int, flags: Int) = WindowManager.LayoutParams(
        minOf((widthDp * dp).toInt(), screenW - 2 * (PillLayout.TIMER_CARD_MARGIN_DP * dp).toInt()),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Closes the running-timers card and the "your own time" card. Safe to call at any time. */
    private fun hideTimerCards() {
        if (listCard != null || ownCard != null) onActivity()
        listCard?.let { wm.removeView(it) }
        listCard = null
        listParams = null
        ownCard?.let { wm.removeView(it) }
        ownCard = null
        ownParams = null
        timerView?.active = fanView != null
    }

    /** Puts the open cards beside the button, measured first so their bottom lands on the button's top. */
    private fun placeTimerCards(bubble: WindowManager.LayoutParams, update: Boolean = true) {
        placeCard(listCard, listParams, bubble, update)
        placeCard(ownCard, ownParams, bubble, update)
    }

    private fun placeCard(card: View?, params: WindowManager.LayoutParams?, bubble: WindowManager.LayoutParams, update: Boolean) {
        if (card == null || params == null) return
        card.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val spot = layout().timerCard(bubble.x, bubble.y, params.width, card.measuredHeight, screenW, screenH)
        if (spot.x == params.x && spot.y == params.y) return
        params.x = spot.x
        params.y = spot.y
        if (update) wm.updateViewLayout(card, params)
    }

    private companion object {
        const val TAG = "VoiceRelay"
        const val TIMER_TICK_MS = 250L
        /** With no timer pill, only the button's gold arc moves: once a second is plenty. */
        const val RING_TICK_MS = 1000L
        const val TIMER_FEEDBACK_MS = 2400L
        /** The service's default feedback duration, for the messages that used it. */
        const val FEEDBACK_MS = 2000L
        /** An open track closes itself after this long untouched, so it cannot linger under a pocket. */
        const val TRACK_IDLE_MS = 6000L
        const val TRACK_CLOSE_AFTER_APPLY_MS = 700L
        const val PILL_REFRESH_MS = 150L
        const val REOPEN_GUARD_MS = 800L
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    }
}
