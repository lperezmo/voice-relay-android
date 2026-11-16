package com.lperezmo.voicerelay.glyph

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import com.lperezmo.voicerelay.R
import com.lperezmo.voicerelay.glyph.recognizer.Samples

/**
 * Glyph's one settings page: the shapes and what they do, how strict recognition is
 * (with a test canvas), the glitter and timing, the undo X, and backup to a file.
 */
class SettingsActivity : Activity() {
    private companion object {
        const val REQ_EXPORT = 1
        const val REQ_IMPORT = 2
        const val REQ_NOTIFY = 4
    }

    private lateinit var prefs: Prefs
    private lateinit var shapesBox: LinearLayout
    private lateinit var undoBox: LinearLayout

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.setDecorFitsSystemWindows(false)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@SettingsActivity, 16f)
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

        val mark = ImageView(this).apply { setImageResource(R.drawable.glyph_ic_mark) }
        val title = Ui.text(this, "Glyph", 30f, bold = true)
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
            addView(mark, LinearLayout.LayoutParams(dp(40f), dp(36f)).apply { marginEnd = dp(12f) })
            addView(title)
        })
        page.addView(Ui.text(this, "Draw a shape anywhere to run its action.", 15f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(context, 2f), 0, Ui.dp(context, 14f))
        })
        page.addView(Ui.button(this, "Open the canvas", Ui.Look.PRIMARY) {
            startActivity(Intent(this, CanvasActivity::class.java))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16f) })

        // shapes
        val shapesCard = Ui.card(this)
        shapesCard.addView(heading("Shapes"))
        shapesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shapesCard.addView(shapesBox)
        shapesCard.addView(Ui.button(this, "Teach a shape", Ui.Look.TONAL) {
            startActivity(Intent(this, TeachActivity::class.java))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        page.addView(shapesCard)

        // recognition
        val rec = Ui.card(this)
        rec.addView(heading("Recognition"))
        val thresholdLabel = Ui.text(this, "", 15f)
        fun showThreshold() {
            thresholdLabel.text = "Match threshold: %.2f".format(prefs.threshold)
        }
        showThreshold()
        rec.addView(thresholdLabel)
        rec.addView(Ui.text(this, "Higher is stricter. Lower if your shapes are missed; raise it if the wrong one fires.", 13f, Ui.MUTED))
        rec.addView(slider(50, 98, (prefs.threshold * 100).toInt()) {
            prefs.threshold = it / 100f
            showThreshold()
        })
        rec.addView(Ui.button(this, "Test drawing", Ui.Look.TONAL) {
            startActivity(Intent(this, CanvasActivity::class.java).putExtra(CanvasActivity.EXTRA_TEST, true))
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12f) })

        val timeoutLabel = Ui.text(this, "", 15f)
        fun showTimeout() {
            timeoutLabel.text = "Wait after the last stroke: ${prefs.timeoutMs} ms"
        }
        showTimeout()
        rec.addView(timeoutLabel)
        rec.addView(Ui.text(this, "How long Glyph waits for another stroke before reading the shape.", 13f, Ui.MUTED))
        rec.addView(slider(5, 30, (prefs.timeoutMs / 50).toInt()) {
            prefs.timeoutMs = it * 50L
            showTimeout()
        })

        rec.addView(switch("Glitter", prefs.glitter) { prefs.glitter = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        page.addView(rec)

        // learning
        val learn = Ui.card(this)
        learn.addView(heading("Learning"))
        learn.addView(Ui.text(this,
            "A shape keeps up to ${Samples.MAX} samples. Drawings you teach it from the canvas are added as learned samples; " +
                "when full, the oldest learned one goes. Samples you drew on a shape's page are never dropped.\n\n" +
                "When a drawing is not recognised, the canvas asks: \"Learn as\" each shape that came close, " +
                "\"New shape\", or \"Dismiss\".",
            13f, Ui.MUTED,
        ))
        learn.addView(switch("Learn from matches", prefs.learnSilently) { prefs.learnSilently = it },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        learn.addView(Ui.text(this, "Adds every match to its shape silently.", 13f, Ui.MUTED))
        undoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14f), 0, 0)
        }
        learn.addView(undoBox)
        page.addView(learn)

        // backup
        val backup = Ui.card(this)
        backup.addView(heading("Backup"))
        backup.addView(Ui.text(this, "Shapes are kept on this phone only. Export them to a file to keep them across reinstalls.", 13f, Ui.MUTED).apply {
            setPadding(0, 0, 0, Ui.dp(context, 8f))
        })
        backup.addView(Ui.row(this,
            Ui.button(this, "Export", Ui.Look.TONAL) { export() },
            Ui.button(this, "Import", Ui.Look.TONAL) { import() },
        ))
        backup.addView(Ui.button(this, "Restore starter shapes", Ui.Look.TEXT) {
            AlertDialog.Builder(this)
                .setMessage("Put back the heart, V, circle and Z as shipped? Starters you changed are replaced; your own shapes stay.")
                .setPositiveButton("Restore") { _, _ ->
                    Store.restoreStarters(this)
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        page.addView(backup)

        // setup notes
        val setup = Ui.card(this)
        setup.addView(heading("Ways to open it"))
        setup.addView(Ui.text(this,
            "Tap the Glyph pill below Voice Relay's mic, or pull it down.\n" +
                "The canvas closes on a tap.",
            14f, Ui.MUTED,
        ))
        page.addView(setup)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun switch(label: String, on: Boolean, changed: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextColor(Ui.TEXT)
        textSize = 15f
        isChecked = on
        thumbTintList = ColorStateList.valueOf(Ui.GOLD)
        setOnCheckedChangeListener { _, v -> changed(v) }
    }

    private fun heading(s: String) = Ui.text(this, s, 18f, bold = true).apply { setPadding(0, 0, 0, Ui.dp(context, 8f)) }

    private fun slider(min: Int, max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.min = min
        this.max = max
        progress = value.coerceIn(min, max)
        progressTintList = ColorStateList.valueOf(Ui.GOLD)
        thumbTintList = ColorStateList.valueOf(Ui.GOLD)
        setPadding(Ui.dp(context, 8f), Ui.dp(context, 14f), Ui.dp(context, 8f), Ui.dp(context, 14f))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    /** "Show an undo X after a match": its state, and a way to the system toggle. */
    private fun refreshUndo() {
        undoBox.removeAllViews()
        val overlay = UndoX.overlayAllowed(this)
        undoBox.addView(Ui.text(this, "Show an undo X after a match", 15f))
        undoBox.addView(Ui.text(this,
            "The X needs Android's \"Display over other apps\" so it can sit over the app a shape opened; without it you get a notification instead.",
            13f, Ui.MUTED,
        ).apply { setPadding(0, 0, 0, dp(8f)) })
        undoBox.addView(Ui.button(this, if (overlay) "Display over other apps: on" else "Turn on display over other apps", Ui.Look.TONAL) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        if (!overlay && !UndoX.notificationsAllowed(this)) {
            undoBox.addView(Ui.button(this, "Allow the notification instead", Ui.Look.TEXT) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            })
        }
    }

    private fun refresh() {
        refreshUndo()
        shapesBox.removeAllViews()
        val shapes = Store.shapes(this)
        if (shapes.isEmpty()) shapesBox.addView(Ui.text(this, "No shapes yet.", 14f, Ui.MUTED))
        for (s in shapes) shapesBox.addView(shapeRow(s))
    }

    private fun shapeRow(s: Shape): View {
        val thumb = ThumbView(this).apply { points = s.samples.firstOrNull() }
        val size = dp(52f)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(context, s.name, 16f, bold = true))
            addView(Ui.text(context, s.action.label, 13f, Ui.MUTED))
            val learned = s.samples.size - s.originals
            val count = "${s.samples.size} sample${if (s.samples.size == 1) "" else "s"}" + if (learned > 0) ", $learned learned" else ""
            addView(Ui.text(context, count, 12f, Ui.MUTED))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = Ui.dp(context, 6f)
            setPadding(0, v, 0, v)
            addView(thumb, LinearLayout.LayoutParams(size, size))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(context, 14f) })
            isClickable = true
            setOnClickListener {
                startActivity(Intent(context, TeachActivity::class.java).putExtra(TeachActivity.EXTRA_ID, s.id))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun export() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "glyph-shapes.json"),
            REQ_EXPORT,
        )
    }

    @Suppress("DEPRECATION")
    private fun import() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            REQ_IMPORT,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> try {
                val json = Store.toJson(Store.shapes(this), prefs.toJson())
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                Actions.toast(this, "Exported ${Store.shapes(this).size} shapes.")
            } catch (e: Exception) {
                Actions.toast(this, "Export failed: ${e.message}")
            }
            REQ_IMPORT -> try {
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val incoming = Store.fromJson(text)
                if (incoming.isEmpty()) {
                    Actions.toast(this, "No shapes in that file.")
                    return
                }
                val settings = Store.settingsOf(text)
                AlertDialog.Builder(this)
                    .setTitle("Import ${incoming.size} shapes")
                    .setMessage("Replace your shapes with these, or add them to the ones you have? A shape with the same id is replaced either way.")
                    .setPositiveButton("Replace") { _, _ ->
                        Store.save(this, incoming)
                        settings?.let { prefs.apply(it) }
                        recreate()
                    }
                    .setNeutralButton("Add") { _, _ ->
                        val ids = incoming.map { it.id }.toSet()
                        Store.save(this, Store.shapes(this).filterNot { it.id in ids } + incoming)
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) {
                Actions.toast(this, "That is not a Glyph export: ${e.message}")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
