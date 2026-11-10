package com.lperezmo.voicerelay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * View builders for the settings screen and its dialogs: cards with titles,
 * rows, switch and radio rows, status rows with a dot, and small notes. The
 * colours are the tokens in res/values(-night)/colors.xml; the accent is the
 * theme's teal.
 */
class SettingsUi(private val context: Context) {

    /** A title and subtitle, then an optional widget at the end. */
    open class Row(val view: LinearLayout, val title: TextView, val subtitle: TextView)

    class SwitchRow(view: LinearLayout, title: TextView, subtitle: TextView, val switch: MaterialSwitch) :
        Row(view, title, subtitle)

    class RadioRow(view: LinearLayout, title: TextView, subtitle: TextView, val radio: MaterialRadioButton) :
        Row(view, title, subtitle)

    /** A row with a status dot before the text. */
    inner class StatusRow(view: LinearLayout, title: TextView, subtitle: TextView, private val dot: View) :
        Row(view, title, subtitle) {
        fun show(ok: Boolean, detail: String) {
            subtitle.text = detail
            subtitle.setTextColor(color(if (ok) R.color.text_secondary else R.color.attention))
            (dot.background as GradientDrawable).setColor(color(if (ok) R.color.ok else R.color.attention))
        }
    }

    // --- Layout ---

    /** The screen's column: padded, and capped in width so landscape and tablets stay readable. */
    fun column(): LinearLayout = object : LinearLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val max = dp(MAX_WIDTH_DP)
            val w = if (MeasureSpec.getSize(widthSpec) > max) MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY) else widthSpec
            super.onMeasure(w, heightSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(32))
    }

    /** The app name, with the version in a tag at the end. */
    fun titleRow(title: String, version: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(TextView(context).apply {
            text = title
            textSize = 28f
            typeface = MEDIUM
            setTextColor(color(R.color.text_primary))
            isFocusable = true
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply {
            text = version
            textSize = 12f
            typeface = MEDIUM
            setTextColor(color(R.color.on_primary_container))
            background = rounded(color(R.color.primary_container), 12)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
    }

    /**
     * A rounded card added to [parent], with an optional title. Rows go straight
     * into the returned layout; their ripples are clipped to the corners.
     */
    fun card(parent: LinearLayout, title: String?, fill: Int = color(R.color.card)): LinearLayout {
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(if (title == null) 8 else 4), 0, dp(8))
            background = rounded(fill, CARD_RADIUS_DP)
            clipToOutline = true
        }
        if (title != null) {
            c.addView(TextView(context).apply {
                text = title
                textSize = 17f
                typeface = MEDIUM
                setTextColor(color(R.color.text_primary))
                isAccessibilityHeading = true
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(PAD_H), dp(8), dp(PAD_H), 0)
            })
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        return c
    }

    /** A small heading for a group of rows inside a card, with a rule above it unless it comes first. */
    fun sectionTitle(title: String, rule: Boolean = true) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (rule) addView(View(context).apply { setBackgroundColor(color(R.color.divider)) },
            LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                marginStart = dp(PAD_H)
                marginEnd = dp(PAD_H)
                topMargin = dp(8)
            })
        addView(TextView(context).apply {
            text = title
            textSize = 14f
            typeface = MEDIUM
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            isAccessibilityHeading = true
            setPadding(dp(PAD_H), dp(14), dp(PAD_H), dp(2))
        })
    }

    /** Secondary text at row inset, for a line of explanation under a group. */
    fun note(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.text_secondary))
        setPadding(dp(PAD_H), dp(2), dp(PAD_H), dp(8))
    }

    // --- Rows ---

    fun row(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): Row {
        val (view, titleView, subtitleView) = rowParts(title, subtitle, onClick)
        if (widget != null) view.addView(widget, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        return Row(view, titleView, subtitleView)
    }

    /** A row with a switch at the end; the whole row is the touch target and reads as a switch. */
    fun switchRow(title: String, subtitle: String, onClick: () -> Unit): SwitchRow {
        val switch = MaterialSwitch(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val r = row(title, subtitle, switch, onClick)
        r.view.accessibilityDelegate = checkableDelegate(Switch::class.java.name) { switch.isChecked }
        return SwitchRow(r.view, r.title, r.subtitle, switch)
    }

    /** A row with a radio button at the start, for one choice of several. */
    fun radioRow(title: String, subtitle: String, onClick: () -> Unit): RadioRow {
        val radio = MaterialRadioButton(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val (view, titleView, subtitleView) = rowParts(title, subtitle, onClick)
        // The button's own 48dp box carries its padding: the circle lines up with the dots of status rows.
        view.addView(radio, 0, LinearLayout.LayoutParams(dp(RADIO_TEXT_START - RADIO_START), WRAP))
        view.setPaddingRelative(dp(RADIO_START), view.paddingTop, view.paddingEnd, view.paddingBottom)
        view.accessibilityDelegate = checkableDelegate(RadioButton::class.java.name) { radio.isChecked }
        return RadioRow(view, titleView, subtitleView, radio)
    }

    /** A row with a coloured dot before the text: teal when all is well, amber when it needs a tap. */
    fun statusRow(title: String, subtitle: String, onClick: (() -> Unit)? = null): StatusRow {
        val (view, titleView, subtitleView) = rowParts(title, subtitle, onClick)
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.neutral_dot))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        view.addView(dot, 0, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(16) })
        return StatusRow(view, titleView, subtitleView, dot)
    }

    private data class RowParts(val view: LinearLayout, val title: TextView, val subtitle: TextView)

    private fun rowParts(title: String, subtitle: String, onClick: (() -> Unit)?): RowParts {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(PAD_H), dp(12), dp(PAD_H), dp(12))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleView = TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(color(R.color.text_primary))
        }
        val subtitleView = TextView(context).apply {
            text = subtitle
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
            setLineSpacing(0f, 1.1f)
        }
        texts.addView(titleView)
        texts.addView(subtitleView)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        return RowParts(row, titleView, subtitleView)
    }

    private fun checkableDelegate(className: String, checked: () -> Boolean) = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = className
            info.isCheckable = true
            info.isChecked = checked()
        }
    }

    // --- Dialog content ---

    fun editText(hint: String, value: String, type: Int) = EditText(context).apply {
        this.hint = hint
        inputType = type
        setText(value)
    }

    fun padded(view: View) = FrameLayout(context).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(view)
    }

    fun vertical(padH: Int, padV: Int = padH) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    // --- Basics ---

    fun rounded(fill: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
    }

    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun color(id: Int) = context.getColor(id)
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun attrColor(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    companion object {
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val MAX_WIDTH_DP = 640
        private const val CARD_RADIUS_DP = 20
        /** Horizontal inset of everything inside a card. */
        const val PAD_H = 20
        /** Where a radio row's button box starts, and where its text starts. */
        const val RADIO_START = 8
        const val RADIO_TEXT_START = 60
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
