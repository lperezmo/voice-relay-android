package com.lperezmo.voicerelay.glyph

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lperezmo.voicerelay.glyph.recognizer.Undo

/**
 * The sheet behind the undo X: "Meant something else?" over the app the match opened, with
 * "Learn as" each other shape that scored at least [Undo.FLOOR] on the drawing, "New shape"
 * and "Just undo". Every choice first takes back the sample "Learn from matches" added to the
 * wrong shape. Nothing touches the opened app: the sheet finishes and it is back in front.
 *
 * With [EXTRA_CHOICE] (a notification action) it does that choice without showing anything.
 */
class UndoActivity : Activity() {
    companion object {
        const val EXTRA_MATCH = "match"
        const val EXTRA_CHOICE = "choice"
        const val LEARN_PREFIX = "learn:"
        const val NEW = "new"
        const val UNDO = "undo"
    }

    private lateinit var entry: LastMatch.Entry
    private lateinit var card: LinearLayout
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val e = LastMatch.get()?.takeIf { it.id == intent.getLongExtra(EXTRA_MATCH, -1) }
        if (e == null) {
            UndoX.cancelNotification(this)
            Actions.toast(this, "That match is too old to undo.")
            finishQuietly()
            return
        }
        entry = e
        intent.getStringExtra(EXTRA_CHOICE)?.let {
            choose(it)
            return
        }
        UndoX.cancelNotification(this)
        window.setDecorFitsSystemWindows(false)
        val density = resources.displayMetrics.density

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            setOnClickListener { finishQuietly() } // a tap outside the card leaves everything as it was
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.GOLD, 20 * density)
            val p = (14 * density).toInt()
            setPadding(p, p, p, (8 * density).toInt())
            elevation = 6 * density
            isClickable = true
        }
        root.addView(card, FrameLayout.LayoutParams((300 * density).toInt(), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            (card.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + (24 * density).toInt()
            card.requestLayout()
            insets
        }

        card.addView(TextView(this).apply {
            text = "Meant something else?"
            setTextColor(Ui.ON_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Ui.dp(context, 4f), 0, 0, Ui.dp(context, 2f))
        })
        card.addView(TextView(this).apply {
            text = "Read as ${e.shapeName}."
            setTextColor(Color.argb(170, 42, 30, 5))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(Ui.dp(context, 4f), 0, 0, Ui.dp(context, 8f))
        })
        val names = Store.shapes(this).associate { it.id to it.name }
        for (m in Undo.alternatives(e.ranked, e.shapeId)) {
            val name = names[m.key] ?: continue
            val label = "Learn as $name"
            val full = "%s  %.2f".format(label, m.score)
            val text = SpannableString(full).apply {
                setSpan(ForegroundColorSpan(Color.argb(120, 42, 30, 5)), label.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            card.addView(choice(text, "$label, score %.2f".format(m.score)) { choose(LEARN_PREFIX + m.key) })
        }
        card.addView(choice("New shape", "New shape") { choose(NEW) })
        card.addView(choice("Just undo", "Just undo", plain = true) { choose(UNDO) })

        setContentView(root)
        if (ValueAnimator.areAnimatorsEnabled()) {
            card.alpha = 0f
            card.translationY = 12 * density
            card.animate().alpha(1f).translationY(0f).setDuration(160).start()
        }
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

    /** Take back the silently learned sample, then do [choice]. */
    private fun choose(choice: String) {
        if (done) return
        done = true
        UndoX.cancelNotification(this)
        val e = entry
        val learnAs = if (choice.startsWith(LEARN_PREFIX)) choice.removePrefix(LEARN_PREFIX) else null
        val shapes = Store.shapes(this)
        val changed = Undo.apply(shapes.associate { it.id to it.sampleSet }, e.shapeId, e.learned, learnAs, Store.compact(e.drawing))
        Store.replaceSamples(this, changed)
        LastMatch.clear(e)
        val target = learnAs?.let { id -> shapes.firstOrNull { it.id == id } }
        when {
            choice == NEW -> {
                val flat = FloatArray(e.drawing.size * 3)
                e.drawing.forEachIndexed { i, p ->
                    flat[3 * i] = p.x
                    flat[3 * i + 1] = p.y
                    flat[3 * i + 2] = p.stroke.toFloat()
                }
                startActivity(Intent(this, TeachActivity::class.java).putExtra(TeachActivity.EXTRA_SAMPLE, flat))
            }
            target != null && target.id in changed -> Actions.toast(this, "Learned as ${target.name}.")
            target != null -> Actions.toast(this, "${target.name} is full: all its samples were drawn by hand.")
            e.learned != null -> Actions.toast(this, "Undone. ${e.shapeName} forgot that drawing.")
            else -> Actions.toast(this, "Nothing was learned from that drawing.")
        }
        finishQuietly()
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
}
