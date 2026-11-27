package com.lperezmo.voicerelay.glyph

import android.content.Context
import android.content.pm.PackageManager
import com.lperezmo.voicerelay.glyph.recognizer.PDollar
import com.lperezmo.voicerelay.glyph.recognizer.Pt
import com.lperezmo.voicerelay.glyph.recognizer.Samples
import com.lperezmo.voicerelay.glyph.recognizer.Starters
import com.lperezmo.voicerelay.glyph.recognizer.Template
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** What a recognised shape does. */
sealed class Action {
    data object Nothing : Action()
    data class OpenApp(val pkg: String, val name: String) : Action()
    data class Url(val url: String) : Action()
    /** Phone Relay's "Send to Claude" share target, fed the clipboard text. */
    data object RelayClipboard : Action()
    /** Voice Relay's own wake word, on if it was off and off if it was on. */
    data object WakeToggle : Action()
    /** Fridge Labels prints one label with today's date. */
    data object PrintDateLabel : Action()
    /** Ask the Page: the canvas stays open as a page for a handwritten question to Claude. */
    data object Ask : Action()
    /** Holds the screen the other way from how it shows now (see [Rotate]). */
    data object RotateFlip : Action()
    /** Lets go of that hold. */
    data object RotateAuto : Action()

    val label: String
        get() = when (this) {
            Nothing -> "Does nothing yet"
            is OpenApp -> "Open $name"
            is Url -> "Open $url"
            RelayClipboard -> "Send clipboard to Phone Relay"
            WakeToggle -> "Toggle wake word"
            PrintDateLabel -> "Print date label"
            Ask -> "Ask the Page"
            RotateFlip -> "Flip the screen"
            RotateAuto -> "Rotation back to auto"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@Action) {
            Nothing -> put("type", "none")
            is OpenApp -> put("type", "app").put("package", pkg).put("label", name)
            is Url -> put("type", "url").put("url", url)
            RelayClipboard -> put("type", "relay_clipboard")
            WakeToggle -> put("type", "wake_toggle")
            PrintDateLabel -> put("type", "print_date_label")
            Ask -> put("type", "ask")
            RotateFlip -> put("type", "rotate_flip")
            RotateAuto -> put("type", "rotate_auto")
        }
    }

    companion object {
        /**
         * Tasker tasks that only ever sent one intent, now sent by Glyph itself. Luis's task
         * "Wake Word On" really sends WAKE_TOGGLE, despite its name. A function, not a map: a
         * map built in Action's static init holds null for an object whose own init started it.
         * Running Tasker tasks is gone; any other stored Tasker task loads as [Nothing].
         */
        fun replacingTasker(task: String): Action? = when (task) {
            "Wake Word On" -> WakeToggle
            "Print Date Label" -> PrintDateLabel
            else -> null
        }

        fun fromJson(o: JSONObject?): Action = when (o?.optString("type")) {
            "app" -> OpenApp(o.getString("package"), o.optString("label", o.getString("package")))
            "tasker" -> replacingTasker(o.optString("task")) ?: Nothing
            "url" -> Url(o.getString("url"))
            "relay_clipboard" -> RelayClipboard
            "wake_toggle" -> WakeToggle
            "print_date_label" -> PrintDateLabel
            "ask" -> Ask
            "rotate_flip" -> RotateFlip
            "rotate_auto" -> RotateAuto
            else -> Nothing
        }
    }
}

/**
 * A trained shape: a name, what it does, and the samples it was drawn as (screen pixels).
 * The first [originals] samples were drawn on the shape's page (or shipped); the rest were
 * learned from matches, oldest first. See [Samples] for the rules.
 */
data class Shape(
    val id: String,
    val name: String,
    val action: Action,
    val samples: List<List<Pt>>,
    val originals: Int = samples.size,
) {
    val sampleSet: Samples get() = Samples.of(samples, originals)

    fun with(set: Samples) = copy(samples = set.all, originals = set.originals)
}

/** Shapes live as JSON in filesDir ([FILE]); settings in the "glyph" shared preferences. */
object Store {
    private const val FILE = "glyph-shapes.json"
    private const val SAMPLE_POINTS = 64

    @Volatile private var cache: List<Shape>? = null
    @Volatile private var templates: List<Template>? = null

    fun shapes(context: Context): List<Shape> {
        cache?.let { return it }
        val f = File(context.filesDir, FILE)
        val loaded = if (f.exists()) runCatching { fromJson(f.readText()) }.getOrNull() else null
        val prefs = Prefs(context)
        var shapes = loaded ?: starters(context).also { write(context, it) }
        if (!prefs.askShapeAdded) {
            // installs from before Ask the Page get the "?" shape once; deleting it later sticks
            if (loaded != null && shapes.none { it.action == Action.Ask }) {
                shapes = shapes + starters(context).filter { it.name == Starters.QUESTION }
                write(context, shapes)
            }
            prefs.askShapeAdded = true
        }
        cache = shapes
        return shapes
    }

    fun templates(context: Context): List<Template> {
        templates?.let { return it }
        val t = shapes(context).flatMap { s -> s.samples.map { PDollar.template(s.id, it) } }
        templates = t
        return t
    }

    fun save(context: Context, shapes: List<Shape>) {
        write(context, shapes)
        cache = shapes
        templates = null
    }

    fun upsert(context: Context, shape: Shape) {
        val list = shapes(context).toMutableList()
        val i = list.indexOfFirst { it.id == shape.id }
        if (i >= 0) list[i] = shape else list += shape
        save(context, list)
    }

    fun delete(context: Context, id: String) = save(context, shapes(context).filterNot { it.id == id })

    /**
     * Add a matched drawing to shape [id] as a learned sample. At [Samples.MAX] the oldest
     * learned sample makes room; originals stay. Returns the sample as stored (the undo X uses
     * it to find and remove it again), or null when there was no room or no shape.
     */
    fun learn(context: Context, id: String, points: List<Pt>): List<Pt>? {
        val shape = shapes(context).firstOrNull { it.id == id } ?: return null
        val sample = compact(points)
        val next = shape.sampleSet.addLearned(sample) ?: return null
        upsert(context, shape.with(next))
        return sample
    }

    /** Write back the sample sets [Undo.apply] changed, keyed by shape id. */
    fun replaceSamples(context: Context, changed: Map<String, Samples>) {
        if (changed.isEmpty()) return
        save(context, shapes(context).map { s -> changed[s.id]?.let { s.with(it) } ?: s })
    }

    fun restoreStarters(context: Context) {
        val starterNames = starters(context)
        val kept = shapes(context).filterNot { s -> starterNames.any { it.name == s.name } }
        save(context, starterNames + kept)
    }

    private fun write(context: Context, shapes: List<Shape>) {
        val f = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(toJson(shapes, null))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** Keep samples small: a fixed number of points along each drawing. */
    fun compact(points: List<Pt>): List<Pt> = PDollar.resample(points, SAMPLE_POINTS)

    fun newId(): String = UUID.randomUUID().toString()

    private fun starters(context: Context): List<Shape> {
        val claude = "com.anthropic.claude"
        val heartAction = if (installed(context, claude)) Action.OpenApp(claude, "Claude") else Action.Nothing
        val actions = mapOf(
            "Heart" to heartAction,
            "V" to Action.OpenApp("com.android.settings", "Settings"),
            Starters.QUESTION to Action.Ask,
        )
        return Starters.library().map { (name, samples) ->
            Shape(newId(), name, actions[name] ?: Action.Nothing, samples.map { compact(it) })
        }
    }

    private fun installed(context: Context, pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    // ---- JSON ------------------------------------------------------------------------

    /** The file format, shared by storage and export. [settings] rides along on export. */
    fun toJson(shapes: List<Shape>, settings: JSONObject?): String {
        val root = JSONObject().put("format", "glyph-shapes").put("version", 1)
        if (settings != null) root.put("settings", settings)
        root.put("shapes", JSONArray().apply {
            for (s in shapes) put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("action", s.action.toJson())
                put("originals", s.originals)
                put("samples", JSONArray().apply {
                    for (sample in s.samples) put(JSONArray().apply {
                        for (p in sample) put(JSONArray().put(round(p.x)).put(round(p.y)).put(p.stroke))
                    })
                })
            })
        })
        return root.toString()
    }

    fun fromJson(text: String): List<Shape> {
        val root = JSONObject(text)
        val arr = root.getJSONArray("shapes")
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val raw = o.getJSONArray("samples")
            val all = List(raw.length()) { j ->
                val pts = raw.getJSONArray(j)
                List(pts.length()) { k ->
                    val p = pts.getJSONArray(k)
                    Pt(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.optInt(2, 0))
                }
            }
            // files without "originals" predate learning: every sample was drawn by hand
            val originalCount = o.optInt("originals", all.size).coerceIn(0, all.size)
            val keep = all.indices.filter { all[it].size >= 2 }
            val samples = keep.map { all[it] }.take(Samples.MAX)
            Shape(
                id = o.optString("id").ifBlank { newId() },
                name = o.getString("name"),
                action = Action.fromJson(o.optJSONObject("action")),
                samples = samples,
                originals = keep.take(Samples.MAX).count { it < originalCount },
            )
        }.filter { it.samples.isNotEmpty() }
    }

    fun settingsOf(text: String): JSONObject? = runCatching { JSONObject(text).optJSONObject("settings") }.getOrNull()

    private fun round(v: Float): Double = Math.round(v * 10.0) / 10.0
}

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("glyph", Context.MODE_PRIVATE)

    var threshold: Float
        get() = p.getFloat("threshold", PDollar.DEFAULT_THRESHOLD)
        set(v) = p.edit().putFloat("threshold", v).apply()

    var glitter: Boolean
        get() = p.getBoolean("glitter", true)
        set(v) = p.edit().putBoolean("glitter", v).apply()

    var timeoutMs: Long
        get() = p.getLong("timeout_ms", 600L)
        set(v) = p.edit().putLong("timeout_ms", v).apply()

    /** Add every matched drawing to its shape without asking. */
    var learnSilently: Boolean
        get() = p.getBoolean("learn_silently", false)
        set(v) = p.edit().putBoolean("learn_silently", v).apply()

    init {
        // "Offer Learn this after a match" is gone: matches now run at once and a miss asks instead
        if (p.contains("offer_learn")) p.edit().remove("offer_learn").apply()
    }

    /** Set once the "?" (Ask the Page) shape has been added to the shapes file, so deleting it sticks. */
    var askShapeAdded: Boolean
        get() = p.getBoolean("ask_shape_added", false)
        set(v) = p.edit().putBoolean("ask_shape_added", v).apply()

    /** How many times the canvas has shown the "Cog opens shapes and settings" toast (stops at 3). */
    var cogHints: Int
        get() = p.getInt("cog_hints", 0)
        set(v) = p.edit().putInt("cog_hints", v).apply()

    fun toJson(): JSONObject = JSONObject().put("threshold", threshold.toDouble()).put("glitter", glitter).put("timeout_ms", timeoutMs)
        .put("learn_silently", learnSilently)

    fun apply(o: JSONObject) {
        if (o.has("threshold")) threshold = o.getDouble("threshold").toFloat().coerceIn(0.5f, 0.98f)
        if (o.has("glitter")) glitter = o.getBoolean("glitter")
        if (o.has("timeout_ms")) timeoutMs = o.getLong("timeout_ms").coerceIn(250L, 2000L)
        if (o.has("learn_silently")) learnSilently = o.getBoolean("learn_silently")
        // older exports carry "offer_learn"; it no longer applies and is ignored
    }
}
