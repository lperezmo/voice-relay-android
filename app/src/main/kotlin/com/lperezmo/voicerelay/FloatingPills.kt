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
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.lperezmo.voicerelay.glyph.CanvasActivity

/**
 * The optional pills that ride with the dictate button: the volume pill
 * ([VolumePillView]) above it, which opens the preset track ([VolumeTrackView]),
 * the Glyph pill ([GlyphPillView]) below it, and the timer pill ([TimerPillView])
 * upright beside it, toward the middle of the screen, with its panel ([TimerPanel]: the
 * running timers over the timer track), its minutes card ([TimerOwnCard]) and its rename
 * card ([TimerRenameCard]). Where they
 * sit is [PillLayout].
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
    /** True when an OpenAI key is set, read once each time the timer panel opens (it is a decrypt). */
    private val openAiKeySet: () -> Boolean = { false },
    /** The microphone in a timer's row was tapped: listen for its new name ([TimerRenameVoice]). */
    private val onRenameVoice: (id: Int) -> Unit = {},
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
    private var panel: TimerPanel? = null
    private var panelParams: WindowManager.LayoutParams? = null
    /** A tap on the timer pill closes an open panel through its outside touch first; this keeps it from reopening. */
    private var panelClosedOutsideAt = 0L
    /** After a V on the timer track, the panel stays a moment so the picked stop shows. */
    private var afterPick: Runnable? = null
    private var ownCard: TimerOwnCard? = null
    private var ownParams: WindowManager.LayoutParams? = null
    private var renameCard: TimerRenameCard? = null
    private var renameParams: WindowManager.LayoutParams? = null
    /** The timer [renameCard] renames. */
    private var renameId = -1
    /** What "your own time" starts at: the last time picked there, until the service restarts. */
    private var ownMinutes = TimerText.DEFAULT_OWN_MINUTES
    private val tickTimers = Runnable { renderTimers() }
    /** Between [start] and [stop]: the timers are watched for the ring. */
    private var watching = false
    /** A timer started, stopped, extended or ended, from here, from "hey timer" or from its notification. */
    private val timersChanged: () -> Unit = { renderTimers() }

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
        watching = true
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
        watching = false
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

    /** Closes what the pills have open (the preset track, the timer panel and the minutes card). Safe to call at any time. */
    fun hidePopups() {
        hideTrack()
        hideTimerCards()
    }

    /**
     * Closes the rename card if it is open. Dictation starting calls this: the card's own
     * field cannot take dictated text, so it would land in the app behind, out of sight.
     */
    fun hideRenameCard() {
        if (renameCard != null) hideTimerCards()
    }

    /** True while the preset track, the timer panel or the minutes card is open. */
    fun popupsOpen(): Boolean = trackView != null || panel != null || ownCard != null || renameCard != null

    /** Fades the three pills to [alpha] over [durationMs], for dimming when idle; popups are never faded. */
    fun fade(alpha: Float, durationMs: Long) {
        for (view in listOf(pillView, glyphView, timerView)) view?.animate()?.alpha(alpha)?.setDuration(durationMs)?.start()
        pillAlpha = alpha
    }

    /** What [fade] last asked for, so a pill shown while dimmed starts dimmed. */
    private var pillAlpha = 1f

    /** How far the shown pills reach over and under the button's ring window, for [BubbleBounds]. */
    fun above(): Int = layout().above(pillView != null, timerView != null)
    fun below(): Int = layout().below(glyphView != null, timerView != null)

    /** How far the timer pill reaches beside the button, toward the middle of the screen (0 when it is not there). */
    fun beside(): Int = layout().beside(timerView != null)

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
            (VTrackView.W_DP * dp).toInt(), (VTrackView.H_DP * dp).toInt(),
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
        val spot = layout().track(bubble.x, bubble.y, params.width, params.height, screenW)
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

    /** Shows or hides the timer pill to match the setting. Safe to call at any time; the service settles the button afterwards. */
    fun refreshTimer() {
        if (settings.timerPillEnabled && !buttonsHidden()) showTimerPill() else hideTimerPill()
    }

    private fun showTimerPill() {
        if (timerView != null) return
        val bubble = bubble() ?: return
        val params = WindowManager.LayoutParams(
            (PillLayout.TIMER_W_DP * dp).toInt(), (PillLayout.TIMER_H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val view = TimerPillView(service).apply {
            onTap = {
                onActivity()
                when {
                    panel != null || ownCard != null || renameCard != null -> hideTimerCards()
                    android.os.SystemClock.uptimeMillis() - panelClosedOutsideAt > REOPEN_GUARD_MS -> {
                        hidePopups()
                        showPanel()
                    }
                }
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
        // The tick keeps going (slower) while a timer runs: the button's gold arc still shows it.
        hideTimerCards()
        wm.removeView(view)
        timerView = null
        timerParams = null
        // Without the pill the ring shows a finished timer only while it rings.
        if (watching) renderTimers()
    }

    private fun positionTimerPill(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val layout = layout()
        val spot = layout.timerPill(bubble.x, bubble.y, params.width, params.height, screenW, screenH)
        params.x = spot.x
        params.y = spot.y
        timerView?.onRight = layout.onRight(bubble.x, screenW)
    }

    /**
     * Redraws the pill, the panel's list and the button's gold arc from the timers as they
     * are now, and keeps ticking while one runs (slower when only the button's ring shows it).
     */
    private fun renderTimers() {
        handler.removeCallbacks(tickTimers)
        // The alarm rings a timer on time; this catches one whose alarm Android held back.
        timers.checkDue()
        val now = System.currentTimeMillis()
        val sorted = timers.timers()
        // The timer being renamed was stopped or dismissed meanwhile: nothing left to rename.
        if (renameCard != null && sorted.none { it.id == renameId }) hideTimerCards()
        val view = timerView
        onTimers(sorted.firstOrNull { !it.done(now) }?.fractionLeft(now), TimerText.ringDone(sorted, now, view != null))
        if (view == null) {
            val goldEndsIn = TimerText.ringDoneEndsIn(sorted, now, pillShown = false)
            when {
                sorted.any { !it.done(now) } -> handler.postDelayed(tickTimers, RING_TICK_MS)
                // Without the pill a finished timer shows on the ring only while it rings: take it off then.
                goldEndsIn != null -> handler.postDelayed(tickTimers, goldEndsIn)
            }
            return
        }
        view.update(TimerText.face(sorted, now), TimerText.describe(sorted, now))
        panel?.let { p ->
            p.list.show(sorted, now)
            bubble()?.let { placeTimerCards(it) }
        }
        if (sorted.any { !it.done(now) }) handler.postDelayed(tickTimers, TIMER_TICK_MS)
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
    }

    /** Opens the timer panel: the running timers over the timer track. */
    private fun showPanel() {
        if (panel != null) return
        val bubble = bubble() ?: return
        val view = TimerPanel(service).apply {
            onOutside = {
                panelClosedOutsideAt = android.os.SystemClock.uptimeMillis()
                hideTimerCards()
            }
            list.onPlus = { id -> timers.addMinute(id) }
            list.onStop = { id -> stopTimer(id) }
            list.onRename = { id ->
                hideTimerCards()
                showRenameCard(id)
            }
            list.onRenameVoice = { id ->
                hideTimerCards()
                onRenameVoice(id)
            }
            // Read here, not on every tick: the key check is a decrypt.
            list.icons = TimerRename.icons(settings.timerRename, settings.timerRename && openAiKeySet())
            // As many rows as the screen has room for under the track (few in landscape), six at most.
            list.maxRows = ((screenH / dp).toInt() - 2 * PillLayout.TIMER_CARD_MARGIN_DP - TimerListCard.EXTRA_DP -
                TimerPanel.GAP_DP - VTrackView.H_DP) / TimerListCard.rowDp(list.icons)
            list.show(timers.timers(), System.currentTimeMillis())
            track.onStart = { minutes ->
                startTimer(minutes)
                closePanelSoon { hideTimerCards() }
            }
            track.onOwn = { closePanelSoon { hideTimerCards(); showOwnCard() } }
            track.onTap = { showFeedback("Taps do nothing. Draw a V: dip, slide to a time, rise.", FEEDBACK_MS) }
            track.onCancel = { showFeedback("Cancelled, no timer started", FEEDBACK_MS) }
        }
        val params = cardParams(
            TimerPanel.W_DP,
            // Outside touches are reported (to close the panel) and still reach the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        )
        panel = view
        panelParams = params
        placeTimerCards(bubble, update = false)
        wm.addView(view, params)
        timerView?.active = true
        onActivity()
    }

    /** After a V on the track: [then] runs a moment later, so the picked stop shows lit first. */
    private fun closePanelSoon(then: () -> Unit) {
        afterPick?.let { handler.removeCallbacks(it) }
        val run = Runnable {
            afterPick = null
            then()
        }
        afterPick = run
        handler.postDelayed(run, TIMER_CLOSE_AFTER_PICK_MS)
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
            onCancel = {
                // A touch outside cancels the card: when that touch is a tap on the pill, it should not open the panel.
                panelClosedOutsideAt = android.os.SystemClock.uptimeMillis()
                hideTimerCards()
            }
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

    /** Opens the card that renames timer [id], with the keyboard up: the pencil was tapped to type. */
    private fun showRenameCard(id: Int) {
        if (renameCard != null) return
        val bubble = bubble() ?: return
        val timer = timers.timers().firstOrNull { it.id == id } ?: return
        val card = TimerRenameCard(service, timer.label).apply {
            onSave = { name ->
                hideTimerCards()
                // Gone meanwhile, or the name was blank or the same: just close.
                timers.rename(id, name)?.let { showFeedback(TimerRename.renamed(it.label), TIMER_FEEDBACK_MS) }
            }
            onCancel = {
                // A touch outside cancels the card: when that touch is a tap on the pill, it should not open the panel.
                panelClosedOutsideAt = android.os.SystemClock.uptimeMillis()
                hideTimerCards()
            }
        }
        // Focusable, so the name can be typed. Touches outside still reach the app; they close
        // the card once the keyboard is hidden (while it is up, its keys are outside touches too).
        val params = cardParams(
            TimerRenameCard.W_DP,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        renameCard = card
        renameParams = params
        renameId = id
        placeTimerCards(bubble, update = false)
        wm.addView(card, params)
        card.post { if (renameCard === card) card.startTyping() }
        timerView?.active = true
        onActivity()
    }

    private fun cardParams(widthDp: Int, flags: Int) = WindowManager.LayoutParams(
        minOf((widthDp * dp).toInt(), screenW - 2 * (PillLayout.TIMER_CARD_MARGIN_DP * dp).toInt()),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Closes the timer panel, the "your own time" card and the rename card. Safe to call at any time. */
    private fun hideTimerCards() {
        afterPick?.let { handler.removeCallbacks(it) }
        afterPick = null
        if (panel != null || ownCard != null || renameCard != null) onActivity()
        panel?.let { wm.removeView(it) }
        panel = null
        panelParams = null
        ownCard?.let { wm.removeView(it) }
        ownCard = null
        ownParams = null
        renameCard?.let { wm.removeView(it) }
        renameCard = null
        renameParams = null
        renameId = -1
        timerView?.active = false
    }

    /** Puts the panel or the card beside the button, measured first so its bottom lands on the button's top. */
    private fun placeTimerCards(bubble: WindowManager.LayoutParams, update: Boolean = true) {
        placeCard(panel, panelParams, bubble, update)
        placeCard(ownCard, ownParams, bubble, update)
        placeCard(renameCard, renameParams, bubble, update)
    }

    private fun placeCard(card: View?, params: WindowManager.LayoutParams?, bubble: WindowManager.LayoutParams, update: Boolean) {
        if (card == null || params == null) return
        card.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val spot = layout().timerPanel(bubble.x, bubble.y, params.width, card.measuredHeight, screenW, screenH)
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
        /** How long the timer panel stays after a V picks a stop, so the lit stop shows. */
        const val TIMER_CLOSE_AFTER_PICK_MS = 350L
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
