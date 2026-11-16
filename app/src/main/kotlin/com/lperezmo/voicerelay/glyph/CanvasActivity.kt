package com.lperezmo.voicerelay.glyph

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lperezmo.voicerelay.AppSettings
import com.lperezmo.voicerelay.R
import com.lperezmo.voicerelay.SecretStore
import com.lperezmo.voicerelay.glyph.recognizer.Miss
import com.lperezmo.voicerelay.glyph.recognizer.PDollar
import com.lperezmo.voicerelay.glyph.recognizer.Pt
import com.lperezmo.voicerelay.glyph.recognizer.Samples

/**
 * The drawing surface: a see-through full-screen window over whatever is open. Inside
 * Voice Relay it is not exported: the Glyph pill opens it with an explicit intent (when
 * "Use the built-in canvas" is on), and so do Glyph settings' "Open the canvas" and
 * "Test drawing".
 *
 * With [EXTRA_TEST] it shows every shape's score and launches nothing.
 *
 * Settings are reachable from the cog (gold for [COG_BRIGHT_MS], then a faint dot that still
 * takes taps). The "Shapes and settings" handle at the bottom shows for launches from the
 * "Glyph" launcher icon (see [openedFromLauncher]).
 *
 * A match runs its action at once (and, with [Prefs.learnSilently], adds the drawing to the
 * shape) and offers the undo X ([UndoX]). A miss keeps the canvas open for [MISS_CARD_MS] with a "Not sure." card: "Learn as"
 * each near shape (see [Miss]), "New shape" and "Dismiss". Touching the card restarts the wait;
 * a tap anywhere else closes the canvas.
 *
 * With Ask the Page on ([AppSettings.askPageEnabled]) the canvas opens invisible, has the
 * accessibility service take one screenshot ([ScreenGrab]) of the app underneath (and note
 * which app that is, [PageContext]; not from the tile, where neither is taken), and only
 * then shows itself (at most [GRAB_WAIT_MS] later). Drawing "?" ([Action.Ask]) then turns the
 * canvas into a page instead of running an action; see [PageController].
 */
class CanvasActivity : Activity() {
    companion object {
        const val EXTRA_TEST = "test"
        /** Set by [GlyphTileService]: the canvas was opened from the Quick Settings shade. */
        const val EXTRA_FROM_TILE = "from_tile"
        private const val CELEBRATE_MS = 420L
        private const val MISS_CARD_MS = 3000L
        private const val COG_BRIGHT_MS = 2000L
        private const val COG_HINT_OPENS = 3
        /** Longest the canvas waits for the Ask the Page screenshot before showing itself. */
        private const val GRAB_WAIT_MS = 150L
    }

    private lateinit var prefs: Prefs
    private lateinit var draw: DrawView
    private lateinit var root: FrameLayout
    private lateinit var hint: TextView
    private lateinit var card: LinearLayout
    private lateinit var gear: ImageView
    private lateinit var settings: AppSettings
    private var test = false
    private var closing = false
    private var shown = false
    private var page: PageController? = null

    /** When the miss card's wait runs out: close the canvas, or in test mode just put the card away. */
    private val missTimeout = Runnable { if (test) hideMissCard() else close(fade = true) }

    /** Off when the system's animations are off (Remove animations / reduced motion). */
    private val motion get() = ValueAnimator.areAnimatorsEnabled()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        settings = AppSettings(this)
        // the pill can open the canvas before the settings screen ever ran: add the launcher
        // icon and tile if the (default-on) setting wants them. Only ever enables here, so the
        // alias this activity may have been started through is never switched off under it.
        GlyphTileService.sync(this, enableOnly = true)
        test = intent.getBooleanExtra(EXTRA_TEST, false)
        window.setDecorFitsSystemWindows(false)
        val density = resources.displayMetrics.density

        root = FrameLayout(this).apply { setBackgroundColor(Color.argb(if (test) 150 else 92, 0, 0, 0)) }
        draw = DrawView(this, prefs.glitter, prefs.timeoutMs)
        root.addView(draw, FrameLayout.LayoutParams(-1, -1))

        hint = TextView(this).apply {
            text = if (test) "Test mode: draw a shape. Nothing will run.\nTap to close." else "Draw a shape"
            setTextColor(Color.argb(200, 255, 236, 200))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (test) 16f else 15f)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (120 * density).toInt()
        })

        card = object : LinearLayout(this) {
            // any touch on the card restarts its wait
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN && visibility == View.VISIBLE) {
                    removeCallbacks(missTimeout)
                    postDelayed(missTimeout, MISS_CARD_MS)
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.GOLD, 20 * density)
            val p = (14 * density).toInt()
            setPadding(p, p, p, (8 * density).toInt())
            elevation = 6 * density
            visibility = View.GONE
            isClickable = true // taps on the card's padding do not close the canvas
        }
        root.addView(card, FrameLayout.LayoutParams((280 * density).toInt(), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (72 * density).toInt()
        })

        gear = ImageView(this).apply {
            setImageResource(R.drawable.glyph_ic_gear)
            imageTintList = ColorStateList.valueOf(Ui.GOLD)
            contentDescription = "Shapes and settings"
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { openSettings() }
        }
        // 28 dp cog inside a 48 dp touch target; after a moment it shrinks to a faint dot that still takes taps
        val gearSize = (48 * density).toInt()
        root.addView(gear, FrameLayout.LayoutParams(gearSize, gearSize, Gravity.BOTTOM or Gravity.END))
        gear.postDelayed({
            gear.animate().alpha(0.35f).scaleX(0.4f).scaleY(0.4f).setDuration(400).start()
        }, COG_BRIGHT_MS)

        val handle = if (!test && openedFromLauncher()) settingsHandle(density) else null
        handle?.let { root.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)) }

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (gear.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bars.bottom + (12 * density).toInt()
                rightMargin = bars.right + (12 * density).toInt()
            }
            gear.requestLayout()
            handle?.let {
                (it.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + (4 * density).toInt()
                it.requestLayout()
            }
            (card.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + (72 * density).toInt()
            card.requestLayout()
            insets
        }

        if (!test && prefs.cogHints < COG_HINT_OPENS) {
            prefs.cogHints = prefs.cogHints + 1
            Toast.makeText(this, "Cog opens shapes and settings", Toast.LENGTH_SHORT).show()
        }

        // keep Android's back swipe off the middle of each edge, where strokes often start
        draw.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val mid = v.height / 2
            val band = (100 * density).toInt()
            val edge = (28 * density).toInt()
            v.systemGestureExclusionRects = listOf(Rect(0, mid - band, edge, mid + band), Rect(w - edge, mid - band, w, mid + band))
        }

        draw.onFirstTouch = {
            hint.animate().alpha(0f).setDuration(150).start()
            handle?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction { handle.visibility = View.GONE }?.start()
        }
        draw.onStrokeStart = { hideMissCard() }
        // a touch outside the miss card while it is up closes the canvas
        draw.onBusyTouch = { if (card.visibility == View.VISIBLE && !test) close(fade = true) }
        draw.onTap = { close(fade = true) }
        draw.onDrawing = { handle(it) }
        setContentView(root)
        // From the Quick Settings tile the shade may still be closing, so a screenshot now could
        // hold the shade instead of the app: no screenshot then, and circles stay ink.
        val fromTile = intent.getBooleanExtra(EXTRA_FROM_TILE, false)
        if (settings.askPageEnabled && !test && !fromTile) {
            // the screenshot must show the app underneath, not this canvas: stay invisible until it is taken
            root.visibility = View.INVISIBLE
            ScreenGrab.take { reveal() }
            root.postDelayed({ reveal() }, GRAB_WAIT_MS)
        } else {
            reveal()
        }
    }

    /** Fade the canvas in: at once, or once the Ask the Page screenshot is taken (or has taken too long). */
    private fun reveal() {
        if (shown || isFinishing || isDestroyed) return
        shown = true
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(110).start()
    }

    /**
     * Started from the "Glyph" launcher icon: through the [GlyphTileService.LAUNCHER_ALIAS]
     * alias, or with MAIN + LAUNCHER as launchers send it. Those launches get the "Shapes and
     * settings" handle, as in the Glyph app; the pill, the tile and settings' "Open the canvas"
     * use explicit intents without them and get only the cog.
     */
    private fun openedFromLauncher(): Boolean {
        val i = intent ?: return false
        if (i.getBooleanExtra(EXTRA_FROM_TILE, false)) return false
        if (i.component?.className == GlyphTileService.LAUNCHER_ALIAS) return true
        return i.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_LAUNCHER)
    }

    private fun openSettings() {
        if (closing) return
        startActivity(Intent(this, SettingsActivity::class.java))
        close(fade = false)
    }

    /** A thin bottom-sheet handle labelled "Shapes and settings": tap or swipe up opens settings. */
    private fun settingsHandle(density: Float): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val h = (28 * density).toInt()
        val v = (10 * density).toInt()
        setPadding(h, v, h, v)
        contentDescription = "Shapes and settings"
        addView(View(context).apply { background = Ui.rounded(Ui.GOLD, 2 * density) },
            LinearLayout.LayoutParams((36 * density).toInt(), (4 * density).toInt()).apply { bottomMargin = (6 * density).toInt() })
        addView(TextView(context).apply {
            text = "Shapes and settings"
            setTextColor(Ui.GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        })
        val swipe = 24 * density
        var downY = 0f
        setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_MOVE -> if (downY - e.rawY > swipe) { openSettings(); return@setOnTouchListener true }
                MotionEvent.ACTION_UP -> if (downY - e.rawY < swipe) { view.performClick(); openSettings() }
            }
            true
        }
    }

    private fun handle(points: List<Pt>) {
        val density = resources.displayMetrics.density
        val shapes = Store.shapes(this)
        val tooSmall = extent(points) < 28 * density
        // with Ask the Page off the "?" shape is not recognised, so it cannot take matches from other shapes
        val asks = if (settings.askPageEnabled) emptySet() else shapes.filter { it.action == Action.Ask }.map { it.id }.toSet()
        val templates = Store.templates(this).let { all -> if (asks.isEmpty()) all else all.filterNot { it.key in asks } }
        val ranked = if (tooSmall) emptyList() else PDollar.rank(points, templates)
        val best = ranked.firstOrNull()
        val shape = best?.let { m -> shapes.firstOrNull { it.id == m.key } }
        val threshold = prefs.threshold
        Log.i(Actions.TAG, "drawn ${points.size} pts; best ${shape?.name} ${best?.score}; threshold $threshold")
        val matched = shape != null && best.score >= threshold
        val near = Miss.learnChoices(ranked, threshold).mapNotNull { m -> shapes.firstOrNull { it.id == m.key }?.let { it to m.score } }

        if (test) {
            showTestResult(ranked.mapNotNull { m -> shapes.firstOrNull { it.id == m.key }?.let { it to m.score } }, threshold, tooSmall)
            if (matched) {
                draw.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                draw.celebrate()
                draw.postDelayed({ draw.reset() }, 750)
            } else {
                if (!tooSmall) showMissCard(near, points)
                draw.fadeLine(350) { draw.reset() }
            }
            return
        }

        if (matched && shape!!.action == Action.Ask && settings.askPageEnabled) {
            askThePage()
            return
        }

        if (matched) {
            draw.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            draw.celebrate()
            val learned = if (prefs.learnSilently) Store.learn(this, shape!!.id, points) else null
            Actions.run(this, shape!!.action)
            // a small X for a few seconds to say "wrong shape" (see UndoX)
            UndoX.offer(this, LastMatch.Entry(points, ranked, shape.id, shape.name, UndoX.pastTense(shape.action), learned))
            // the burst plays while the action starts; anything that covers the canvas closes it sooner
            draw.postDelayed({ close(fade = false) }, if (prefs.glitter && motion) CELEBRATE_MS else 0L)
        } else {
            draw.performHapticFeedback(HapticFeedbackConstants.REJECT)
            if (tooSmall) {
                draw.fadeLine(260) {}
                close(fade = true)
            } else {
                draw.fadeLine(260) {}
                showMissCard(near, points)
            }
        }
    }

    // ---- Ask the Page ------------------------------------------------------------------

    /** "?" was drawn: no action, no undo X; the canvas stays open as the page. */
    private fun askThePage() {
        val key = SecretStore(this).getAnthropicKey()
        if (key.isBlank()) {
            draw.performHapticFeedback(HapticFeedbackConstants.REJECT)
            Actions.toast(this, "Ask the Page needs an Anthropic API key. Add it in Voice Relay's settings.")
            draw.fadeLine(260) {}
            close(fade = true)
            return
        }
        draw.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        draw.celebrate()
        // the "?" fades with the celebration; then the view is cleared under the page
        draw.postDelayed({ if (!closing) draw.reset() }, 750)
        hideMissCard()
        // turning the phone would recreate the canvas and lose the page (and the screenshot no longer lines up)
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } catch (_: Exception) {}
        gear.animate().cancel()
        gear.visibility = View.GONE
        page = PageController(
            activity = this,
            root = root,
            draw = draw,
            hint = hint,
            apiKey = key,
            model = settings.askPageModel,
            glitterOn = prefs.glitter,
            motion = motion,
            close = { close(fade = true) },
        ).also { it.start() }
    }

    // ---- the miss card ---------------------------------------------------------------

    /** "Not sure.", then "Learn as" each near shape (best first, score shown faintly), "New shape" and "Dismiss". */
    private fun showMissCard(near: List<Pair<Shape, Float>>, points: List<Pt>) {
        card.removeAllViews()
        card.addView(TextView(this).apply {
            text = "Not sure."
            setTextColor(Ui.ON_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Ui.dp(context, 4f), 0, 0, Ui.dp(context, 6f))
        })
        for ((shape, score) in near) {
            val label = "Learn as ${shape.name}"
            val full = "%s  %.2f".format(label, score)
            val text = SpannableString(full).apply {
                setSpan(ForegroundColorSpan(Color.argb(120, 42, 30, 5)), label.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            card.addView(choice(text, "$label, score %.2f".format(score)) { learnAs(shape, points) })
        }
        card.addView(choice("New shape", "New shape") { newShape(points) })
        card.addView(choice("Dismiss", "Dismiss", plain = true) { if (test) hideMissCard() else close(fade = true) })

        card.removeCallbacks(missTimeout)
        card.animate().cancel()
        card.visibility = View.VISIBLE
        if (motion) {
            card.alpha = 0f
            card.translationY = 12 * resources.displayMetrics.density
            card.animate().alpha(1f).translationY(0f).setDuration(160).start()
        } else {
            card.alpha = 1f
            card.translationY = 0f
        }
        card.postDelayed(missTimeout, MISS_CARD_MS)
    }

    private fun choice(text: CharSequence, description: String, plain: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        contentDescription = description
        setTextColor(Ui.ON_GOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        if (!plain) {
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.rounded(Color.argb(40, 42, 30, 5), Ui.dp(context, 12f).toFloat())
        }
        val h = Ui.dp(context, 12f)
        val v = Ui.dp(context, 10f)
        setPadding(h, v, h, v)
        minHeight = Ui.dp(context, 44f)
        gravity = if (plain) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 6f) }
        setOnClickListener { onClick() }
    }

    /** Add the drawing to [shape] as a learned sample, sparkle, and close (in test mode, stay). */
    private fun learnAs(shape: Shape, points: List<Pt>) {
        if (closing) return
        card.removeCallbacks(missTimeout)
        for (i in 0 until card.childCount) card.getChildAt(i).isEnabled = false
        val ok = Store.learn(this, shape.id, points) != null
        val title = card.getChildAt(0) as TextView
        title.text = if (ok) "Learned as ${shape.name}" else "${shape.name} is full: ${Samples.MAX} drawn samples"
        card.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val sparkle = ok && prefs.glitter && motion
        if (sparkle) draw.burst(points)
        val wait = if (!ok) 1200L else if (sparkle) CELEBRATE_MS + 180 else 450L
        card.postDelayed({ if (test) hideMissCard() else close(fade = !sparkle) }, wait)
    }

    /** Teach a new shape that starts with this drawing as sample 1. */
    private fun newShape(points: List<Pt>) {
        if (closing) return
        card.removeCallbacks(missTimeout)
        val flat = FloatArray(points.size * 3)
        points.forEachIndexed { i, p ->
            flat[3 * i] = p.x
            flat[3 * i + 1] = p.y
            flat[3 * i + 2] = p.stroke.toFloat()
        }
        startActivity(Intent(this, TeachActivity::class.java).putExtra(TeachActivity.EXTRA_SAMPLE, flat))
        close(fade = false)
    }

    private fun hideMissCard() {
        card.removeCallbacks(missTimeout)
        if (card.visibility != View.VISIBLE) return
        card.animate().cancel()
        if (motion) {
            card.animate().alpha(0f).setDuration(150).withEndAction { card.visibility = View.GONE }.start()
        } else {
            card.visibility = View.GONE
        }
    }

    /** Every shape's best score, best first, so close pairs (heart and circle) show up. */
    private fun showTestResult(all: List<Pair<Shape, Float>>, threshold: Float, tooSmall: Boolean) {
        val text = when {
            tooSmall -> "Too small to read. Draw bigger."
            all.isEmpty() -> "No shapes to compare with."
            else -> {
                val (shape, score) = all.first()
                val verdict = if (score >= threshold) "would: ${shape.action.label}" else "No match"
                val width = all.maxOf { it.first.name.length }.coerceAtMost(14)
                val rows = all.mapIndexed { i, (s, sc) ->
                    val mark = if (i == 0 && sc >= threshold) "  <" else "   "
                    "%-${width}s  %.2f%s".format(s.name.take(14), sc, mark)
                }
                rows.joinToString("\n") + "\nthreshold %.2f\n%s".format(threshold, verdict)
            }
        }
        hint.typeface = Typeface.MONOSPACE
        hint.text = text
        hint.animate().cancel()
        hint.alpha = 1f
    }

    private fun extent(points: List<Pt>): Float {
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        return maxOf(xs.max() - xs.min(), ys.max() - ys.min())
    }

    private fun close(fade: Boolean) {
        if (closing) return
        closing = true
        card.removeCallbacks(missTimeout)
        endPage()
        if (!fade) {
            finishQuietly()
            return
        }
        root.animate().alpha(0f).setDuration(240).withEndAction { finishQuietly() }.start()
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Drop the page, its in-flight call and the screenshot. Safe to call more than once. */
    private fun endPage() {
        page?.dispose()
        page = null
        ScreenGrab.clear()
    }

    /**
     * Back closes the canvas, except while Ask the Page's text field is open: then it closes
     * the field. This is the legacy path; with predictive back the field registers its own
     * callback ([PageKeys]) while it is open.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (page?.handleBack() == true) return
        super.onBackPressed()
    }

    // Leaving the canvas for anything else (home, recents, another app) closes it.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            endPage()
            finish()
        }
    }

    override fun onDestroy() {
        endPage()
        super.onDestroy()
    }
}
