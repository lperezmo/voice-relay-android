package com.lperezmo.voicerelay

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** View builders for the settings screen and its dialogs. */
class SettingsUi(private val context: Context) {

    fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }
        val textContainer = vertical(0).apply { layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f) }
        textContainer.addView(TextView(context).apply {
            tag = "title"
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        textContainer.addView(TextView(context).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textContainer)
        if (widget != null) row.addView(widget)
        return row
    }

    fun sectionHeader(title: String) = TextView(context).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

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

    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun attrColor(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    companion object {
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
