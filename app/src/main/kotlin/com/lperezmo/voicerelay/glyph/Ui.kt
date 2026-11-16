package com.lperezmo.voicerelay.glyph

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lperezmo.voicerelay.glyph.recognizer.Pt
import kotlin.math.max
import kotlin.math.min

/** Framework views only, built in code, so the APK stays tiny. One dark palette. */
object Ui {
    val PAGE = Color.parseColor("#121316")
    val CARD = Color.parseColor("#1D1E22")
    val INSET = Color.parseColor("#26272C")
    val TEXT = Color.parseColor("#E8E6E1")
    val MUTED = Color.parseColor("#A6A4A0")
    val GOLD = Color.parseColor("#F2C46D")
    val ON_GOLD = Color.parseColor("#2A1E05")
    val DANGER = Color.parseColor("#F2B8B5")

    fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).toInt()

    fun text(context: Context, s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(context, 18f).toFloat())
        val p = dp(context, 16f)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 14f) }
    }

    enum class Look { PRIMARY, TONAL, TEXT, DANGER }

    @SuppressLint("SetTextI18n")
    fun button(context: Context, label: String, look: Look = Look.TONAL, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val (bg, fg) = when (look) {
            Look.PRIMARY -> GOLD to ON_GOLD
            Look.TONAL -> INSET to TEXT
            Look.TEXT -> Color.TRANSPARENT to GOLD
            Look.DANGER -> Color.TRANSPARENT to DANGER
        }
        setTextColor(fg)
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(50, 255, 255, 255)),
            rounded(bg, dp(context, 22f).toFloat()), null,
        )
        minHeight = dp(context, 44f)
        val h = dp(context, 18f)
        setPadding(h, 0, h, 0)
        setOnClickListener { onClick() }
    }

    fun row(context: Context, vararg views: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((i, v) in views.withIndex()) {
            addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(context, 8f) })
        }
    }
}

/** A drawing shrunk into a box: the shape list's icons and the teach screen's samples. */
@SuppressLint("ViewConstructor")
class ThumbView(context: Context) : View(context) {
    var points: List<Pt>? = null
        set(v) {
            field = v
            invalidate()
        }
    var current = false
        set(v) {
            field = v
            invalidate()
        }
    /** Learned from a match rather than drawn on the shape's page: a dot and a softer line. */
    var learned = false
        set(v) {
            field = v
            invalidate()
        }
    var placeholder: String? = null

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f * density
        color = Ui.GOLD
    }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.INSET }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED
        textAlign = Paint.Align.CENTER
        textSize = 12 * density
    }
    private val bigLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.GOLD
        textAlign = Paint.Align.CENTER
        textSize = 26 * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GOLD }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 12 * density
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bg)
        if (current) {
            frame.color = Ui.GOLD
            frame.strokeWidth = 2 * density
            canvas.drawRoundRect(density, density, w - density, h - density, r, r, frame)
        }
        val pts = points
        if (pts.isNullOrEmpty()) {
            placeholder?.let {
                val paint = if (it == "+") bigLabel else label
                canvas.drawText(it, w / 2, h / 2 + paint.textSize / 3, paint)
            }
            return
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        val pad = min(w, h) * 0.18f
        val size = max(maxX - minX, maxY - minY).takeIf { it > 0f } ?: 1f
        val k = min(w - 2 * pad, h - 2 * pad) / size
        val ox = (w - (maxX - minX) * k) / 2 - minX * k
        val oy = (h - (maxY - minY) * k) / 2 - minY * k
        path.reset()
        var stroke = -1
        for (p in pts) {
            val x = ox + p.x * k
            val y = oy + p.y * k
            if (p.stroke != stroke) {
                path.moveTo(x, y)
                stroke = p.stroke
            } else {
                path.lineTo(x, y)
            }
        }
        line.alpha = if (learned) 150 else 255
        canvas.drawPath(path, line)
        if (learned) canvas.drawCircle(w - 9 * density, 9 * density, 3.5f * density, dot)
    }
}
