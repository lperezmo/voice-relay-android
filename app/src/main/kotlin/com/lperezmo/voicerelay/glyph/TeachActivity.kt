package com.lperezmo.voicerelay.glyph

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.lperezmo.voicerelay.AppSettings
import com.lperezmo.voicerelay.glyph.recognizer.Pt
import com.lperezmo.voicerelay.glyph.recognizer.Samples
import kotlin.concurrent.thread

/**
 * Teach (or edit) a shape: name it, draw it a few times (three is a good start, up to
 * [Samples.MAX]), pick what it does. Every sample shows as a thumbnail; tap one to draw it
 * again or delete it, or tap "+" (Add more samples) to draw extra ones at any time.
 * Samples learned from matches are marked with a dot.
 */
class TeachActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
        /** A drawing as x, y, stroke triples: becomes sample 1 of a new shape. */
        const val EXTRA_SAMPLE = "sample"
        private const val GOOD_START = 3
        private const val PER_ROW = 5
    }

    private class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    private var editing: Shape? = null
    private var set = Samples.of(emptyList())

    /** The sample being redrawn, or null to add a new one with the next drawing. */
    private var selected: Int? = null
    private var action: Action = Action.Nothing
    private lateinit var name: EditText
    private lateinit var grid: LinearLayout
    private lateinit var slotHint: TextView
    private lateinit var addButton: Button
    private lateinit var deleteButton: Button
    private lateinit var actionButton: Button
    private lateinit var pad: DrawView
    private var thumbSize = 0

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        editing = intent.getStringExtra(EXTRA_ID)?.let { id -> Store.shapes(this).firstOrNull { it.id == id } }
        editing?.let { s ->
            set = s.sampleSet
            action = s.action
        }
        // from the canvas's "New shape": the missed drawing is sample 1, so only the name, two more and an action remain
        val prefilled = editing == null && intent.getFloatArrayExtra(EXTRA_SAMPLE)?.let { flat ->
            val pts = List(flat.size / 3) { i -> Pt(flat[3 * i], flat[3 * i + 1], flat[3 * i + 2].toInt()) }
            if (pts.size >= 2) set = Samples.of(listOf(Store.compact(pts)))
            pts.size >= 2
        } == true
        if (prefilled) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        if (!canAdd()) selected = 0

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@TeachActivity, 16f)
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.PAGE)
            isFillViewport = true
            addView(page)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }

        page.addView(Ui.text(this, if (editing == null) "Teach a shape" else "Edit shape", 26f, bold = true).apply {
            setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 12f))
        })

        name = EditText(this).apply {
            hint = "Name (heart, V, ...)"
            setText(editing?.name ?: "")
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = Ui.rounded(Ui.CARD, Ui.dp(context, 12f).toFloat())
            val p = Ui.dp(context, 14f)
            setPadding(p, p, p, p)
        }
        page.addView(name, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14f) })

        val gap = dp(8f)
        thumbSize = (resources.displayMetrics.widthPixels - dp(32f) - gap * (PER_ROW - 1)) / PER_ROW
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(grid)
        slotHint = Ui.text(this, "", 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 4f))
        }
        page.addView(slotHint)

        addButton = Ui.button(this, "Add more samples", Ui.Look.TONAL) {
            if (canAdd()) {
                selected = null
                pad.reset()
                showSlots()
            } else {
                Actions.toast(this, "${Samples.MAX} drawn samples is the most. Delete one first.")
            }
        }
        deleteButton = Ui.button(this, "Delete sample", Ui.Look.DANGER) { deleteSelected() }
        page.addView(Ui.row(this, addButton, deleteButton).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8f)
        })

        val prefs = Prefs(this)
        pad = DrawView(this, prefs.glitter, prefs.timeoutMs)
        pad.onDrawing = { pts -> took(pts) }
        val padBox = FrameLayout(this).apply {
            background = Ui.rounded(Ui.CARD, Ui.dp(context, 18f).toFloat())
            clipToOutline = true
            addView(Ui.text(context, "draw here", 13f, Ui.MUTED).apply { alpha = 0.6f },
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dp(context, 10f) })
            addView(pad, FrameLayout.LayoutParams(-1, -1))
        }
        page.addView(padBox, LinearLayout.LayoutParams(-1, dp(340f)).apply { bottomMargin = dp(14f) })

        page.addView(Ui.text(this, "When it is drawn", 13f, Ui.MUTED))
        actionButton = Ui.button(this, "", Ui.Look.TONAL) { pickAction() }
        page.addView(actionButton, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6f)
            bottomMargin = dp(18f)
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        if (editing != null) {
            buttons.addView(Ui.button(this, "Delete", Ui.Look.DANGER) { confirmDelete() })
            buttons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        buttons.addView(Ui.button(this, "Cancel", Ui.Look.TEXT) { finish() })
        buttons.addView(Ui.button(this, "Save", Ui.Look.PRIMARY) { save() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(this@TeachActivity, 8f) })
        page.addView(buttons, LinearLayout.LayoutParams(-1, -2))

        setContentView(scroll)
        if (prefilled) name.requestFocus()
        showSlots()
        showAction()
    }

    /** Room for one more drawn sample (a full set with learned ones still has room). */
    private fun canAdd() = !set.full || set.learned > 0

    private fun took(points: List<Pt>) {
        // origin-free, so the thumbnail and the recogniser see only the shape
        val drawn = Store.compact(points)
        val i = selected
        if (i != null) {
            set = set.replace(i, drawn)
        } else {
            val pushedOut = set.full
            val next = set.addOriginal(drawn)
            if (next == null) {
                Actions.toast(this, "${Samples.MAX} drawn samples is the most. Delete one first.")
            } else {
                set = next
                if (pushedOut) Actions.toast(this, "Replaced the oldest learned sample.")
            }
        }
        selected = if (canAdd()) null else selected
        pad.fadeLine(250) {
            pad.reset()
            showSlots()
        }
        showSlots()
    }

    private fun deleteSelected() {
        val i = selected ?: return
        set = set.remove(i)
        selected = if (canAdd()) null else 0
        pad.reset()
        showSlots()
    }

    private fun showSlots() {
        grid.removeAllViews()
        val tiles = set.all.indices.map { i ->
            ThumbView(this).apply {
                points = set.all[i]
                learned = set.isLearned(i)
                current = selected == i
                contentDescription = "Sample ${i + 1}"
                setOnClickListener {
                    selected = if (selected == i && canAdd()) null else i
                    pad.reset()
                    showSlots()
                }
            }
        }.toMutableList()
        if (canAdd()) {
            tiles += ThumbView(this).apply {
                placeholder = "+"
                current = selected == null
                contentDescription = "Add more samples"
                setOnClickListener {
                    selected = null
                    pad.reset()
                    showSlots()
                }
            }
        }
        val gap = dp(8f)
        tiles.chunked(PER_ROW).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { c, t ->
                row.addView(t, LinearLayout.LayoutParams(thumbSize, thumbSize).apply { if (c > 0) marginStart = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(-2, -2).apply { if (r > 0) topMargin = gap })
        }

        val n = set.all.size
        val i = selected
        slotHint.text = when {
            i != null -> "Draw sample ${i + 1}${if (set.isLearned(i)) " (learned)" else ""} again below, or delete it."
            n < GOOD_START -> "Draw sample ${n + 1} below. Three make a good start; up to ${Samples.MAX}."
            n < Samples.MAX -> "$n samples. Draw below to add more (up to ${Samples.MAX}), or tap one to redo it."
            else -> "Full. A new drawing replaces the oldest learned sample."
        } + if (set.learned > 0) "\nDotted ones were learned from matches." else ""
        deleteButton.isEnabled = i != null
        deleteButton.alpha = if (i != null) 1f else 0.4f
        addButton.isEnabled = canAdd()
        addButton.alpha = if (addButton.isEnabled) 1f else 0.4f
    }

    private fun showAction() {
        actionButton.text = action.label
    }

    private fun save() {
        val n = name.text.toString().trim()
        if (n.isEmpty()) {
            name.error = "Give it a name"
            return
        }
        if (set.all.isEmpty()) {
            Actions.toast(this, "Draw the shape first.")
            return
        }
        val doSave = {
            val base = editing ?: Shape(Store.newId(), n, action, emptyList())
            Store.upsert(this, base.copy(name = n, action = action).with(set))
            finish()
        }
        if (set.all.size < GOOD_START) {
            AlertDialog.Builder(this)
                .setMessage("Only ${set.all.size} of $GOOD_START samples. Three make recognition steadier. Save anyway?")
                .setPositiveButton("Save") { _, _ -> doSave() }
                .setNegativeButton("Keep drawing", null)
                .show()
        } else {
            doSave()
        }
    }

    private fun confirmDelete() {
        val s = editing ?: return
        AlertDialog.Builder(this)
            .setMessage("Delete \"${s.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                Store.delete(this, s.id)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- choosing the action -------------------------------------------------------

    private fun pickAction() {
        val choices = buildList {
            add("Open an app" to { pickApp() })
            add("Open a URL or deep link" to { pickUrl() })
            if (Actions.relayInstalled(this@TeachActivity)) {
                add("Send clipboard to Phone Relay" to { set(Action.RelayClipboard) })
            }
            add("Toggle wake word" to { set(Action.WakeToggle) })
            if (Actions.fridgeLabelsInstalled(this@TeachActivity)) {
                add("Print date label" to { set(Action.PrintDateLabel) })
            }
            if (AppSettings(this@TeachActivity).askPageEnabled) {
                add("Ask the Page" to { set(Action.Ask) })
            }
            add("Flip the screen" to { set(Action.RotateFlip) })
            add("Rotation back to auto" to { set(Action.RotateAuto) })
            add("Nothing" to { set(Action.Nothing) })
        }
        AlertDialog.Builder(this)
            .setTitle("When this shape is drawn")
            .setItems(choices.map { it.first }.toTypedArray()) { _, i -> choices[i].second() }
            .show()
    }

    private fun set(a: Action) {
        action = a
        showAction()
    }

    private fun pickApp() {
        val pm = packageManager
        thread {
            val apps = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0),
            ).map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                // Glyph left itself out here; inside Voice Relay that would drop Voice Relay,
                // which Glyph's picker offered, so nothing is left out.
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { if (!isFinishing) showApps(apps) }
        }
    }

    /** The app list with a search field on top: filters by name or package as you type, Go picks the top hit. */
    private fun showApps(apps: List<AppEntry>) {
        val iconSize = dp(36f)
        var shown = apps
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val tv = (convert as? TextView) ?: Ui.text(this@TeachActivity, "", 16f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    compoundDrawablePadding = Ui.dp(context, 14f)
                    val p = Ui.dp(context, 12f)
                    setPadding(Ui.dp(context, 20f), p, p, p)
                }
                val a = shown[i]
                tv.text = a.label
                a.icon.setBounds(0, 0, iconSize, iconSize)
                tv.setCompoundDrawablesRelative(a.icon, null, null, null)
                return tv
            }
        }
        val search = EditText(this).apply {
            hint = "Search apps"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val list = ListView(this).apply { this.adapter = adapter }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(context, 20f)
            addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(p, Ui.dp(context, 8f), p, Ui.dp(context, 4f))
            })
            // Fixed height, so the dialog does not jump around as the list shrinks.
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45f).toInt()))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Open which app")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create()
        fun choose(a: AppEntry) {
            set(Action.OpenApp(a.pkg, a.label))
            dialog.dismiss()
        }
        list.setOnItemClickListener { _, _, i, _ -> choose(shown[i]) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim()
                shown = if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
                adapter.notifyDataSetChanged()
            }
        })
        search.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) shown.firstOrNull()?.let(::choose)
            id == EditorInfo.IME_ACTION_GO
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        search.requestFocus()
    }

    private fun pickUrl() = prompt(
        "URL or deep link", (action as? Action.Url)?.url ?: "https://",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
    ) { set(Action.Url(it)) }

    private fun prompt(title: String, initial: String, type: Int, done: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(initial)
            inputType = type
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            val p = Ui.dp(context, 20f)
            setPadding(p, Ui.dp(context, 8f), p, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
