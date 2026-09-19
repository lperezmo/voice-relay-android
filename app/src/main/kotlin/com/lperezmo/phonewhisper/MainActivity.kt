package com.lperezmo.phonewhisper

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var secrets: SecretStore

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var transcribeModelSub: TextView
    private lateinit var languagesSub: TextView
    private lateinit var vocabularySub: TextView
    private lateinit var cleanupSwitch: MaterialSwitch
    private lateinit var cleanupModelSub: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var cleanupContainer: LinearLayout
    private lateinit var liveSwitch: MaterialSwitch
    private lateinit var liveSub: TextView
    private lateinit var usageMonthSub: TextView
    private lateinit var usageTotalSub: TextView
    private lateinit var usage: UsageStore

    private val promptRows = mutableMapOf<String, PromptRowViews>()

    private data class PromptRowViews(val radio: MaterialRadioButton, val subtitle: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        secrets = SecretStore(this)
        usage = UsageStore(this)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // --- Setup ---
        root.addView(sectionHeader("Setup"))

        val audioRow = settingsRow("Microphone permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") { showAccessibilityDisclosure() }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        // --- OpenAI ---
        root.addView(sectionHeader("OpenAI"))

        val keyRow = settingsRow("API key", "Tap to set") { promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        root.addView(keyRow)

        liveSwitch = MaterialSwitch(this).apply { isClickable = false }
        val liveRow = settingsRow("Live transcription", "", liveSwitch) {
            settings.liveTranscription = !settings.liveTranscription
            refresh()
        }
        liveSub = liveRow.findViewWithTag("subtitle")
        root.addView(liveRow)

        val transcribeRow = settingsRow("Transcription model", "") {
            pickModel("Transcription model", AppSettings.DEFAULT_TRANSCRIPTION_MODEL, OpenAiClient::transcriptionModels) {
                settings.transcriptionModel = it
            }
        }
        transcribeModelSub = transcribeRow.findViewWithTag("subtitle")
        root.addView(transcribeRow)

        val langRow = settingsRow("Languages", "") { promptLanguages() }
        languagesSub = langRow.findViewWithTag("subtitle")
        root.addView(langRow)

        val vocabRow = settingsRow("Vocabulary", "") { promptVocabulary() }
        vocabularySub = vocabRow.findViewWithTag("subtitle")
        vocabularySub.maxLines = 2
        vocabularySub.ellipsize = TextUtils.TruncateAt.END
        root.addView(vocabRow)

        // --- Cleanup ---
        root.addView(sectionHeader("Cleanup"))

        cleanupSwitch = MaterialSwitch(this).apply { isClickable = false }
        root.addView(settingsRow("Clean up transcript", "Fixes grammar and punctuation. Sends the transcript text to OpenAI.", cleanupSwitch) {
            settings.cleanupEnabled = !settings.cleanupEnabled
            refresh()
        })

        cleanupContainer = vertical(0)
        val cleanupModelRow = settingsRow("Cleanup model", "") {
            pickModel("Cleanup model", AppSettings.DEFAULT_CLEANUP_MODEL, OpenAiClient::cleanupModels) {
                settings.cleanupModel = it
            }
        }
        cleanupModelSub = cleanupModelRow.findViewWithTag("subtitle")
        cleanupContainer.addView(cleanupModelRow)
        for (preset in promptPresets()) cleanupContainer.addView(buildPromptRow(preset))
        val promptRow = settingsRow("Edit current prompt", "") { promptCleanupPrompt() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = TextUtils.TruncateAt.END
        cleanupContainer.addView(promptRow)
        root.addView(cleanupContainer)

        // --- Usage ---
        root.addView(sectionHeader("Usage (estimate)"))
        val monthRow = settingsRow("This month", "")
        usageMonthSub = monthRow.findViewWithTag("subtitle")
        root.addView(monthRow)
        val totalRow = settingsRow("Since reset", "") { confirmUsageReset() }
        usageTotalSub = totalRow.findViewWithTag("subtitle")
        root.addView(totalRow)
        root.addView(TextView(this).apply {
            text = "Counted on this phone from list prices. Your OpenAI usage page is the actual bill. " +
                "Tap Since reset to start over."
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(32))
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- State ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val apiKey = secrets.getApiKey()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"
        keyRowSub.text = when {
            apiKey.isBlank() -> "Tap to set. Stored encrypted on this device."
            apiKey.length > 8 -> "${apiKey.take(3)}...${apiKey.takeLast(4)}"
            else -> "Set"
        }
        liveSwitch.isChecked = settings.liveTranscription
        liveSub.text = if (settings.liveTranscription) {
            "On: streams while you talk with ${settings.liveModel} (about \$0.017/min), so text is ready " +
                "right after you stop. Falls back to the upload below if the stream fails."
        } else {
            "Off: uploads the recording after you stop, using the model below. Turn on to stream while you talk."
        }
        transcribeModelSub.text = if (settings.liveTranscription) {
            "${settings.transcriptionModel} (fallback and retries)"
        } else settings.transcriptionModel
        languagesSub.text = settings.languages.joinToString(", ").ifEmpty { "Auto-detect" }
        vocabularySub.text = settings.vocabulary.joinToString(", ").ifEmpty { "Names and terms to spell exactly. Tap to add." }
        cleanupSwitch.isChecked = settings.cleanupEnabled
        cleanupContainer.visibility = if (settings.cleanupEnabled) View.VISIBLE else View.GONE
        cleanupModelSub.text = settings.cleanupModel
        promptRowSub.text = settings.cleanupPrompt
        usageMonthSub.text = usage.thisMonth().summary()
        val since = usage.since.takeIf { it > 0 }?.let {
            " (since " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)) + ")"
        }.orEmpty()
        usageTotalSub.text = usage.allTime().summary() + since

        val ready = audio && acc && apiKey.isNotBlank()
        statusSubtitle.text = when {
            ready -> "Ready. Tap the overlay button to dictate."
            apiKey.isBlank() -> "Set your OpenAI API key to start"
            else -> "Setup required"
        }
        statusSubtitle.setTextColor(
            if (ready) attrColor(com.google.android.material.R.attr.colorPrimary)
            else attrColor(android.R.attr.textColorSecondary)
        )
        refreshPromptRows()
    }

    // --- Dialogs ---

    /** Explains the accessibility use before sending the user to system settings. */
    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility access")
            .setMessage(
                "Whisper LP uses the accessibility service to show the floating dictation button " +
                    "and to type your dictated text into the text field you are using.\n\n" +
                    "It only reads the screen at the moment it inserts text, to find the focused field. " +
                    "It does not record, store, or send screen content anywhere.\n\n" +
                    "Your audio and transcript go only to OpenAI, using your own API key."
            )
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun confirmUsageReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset usage?")
            .setMessage("Clears this month and the running total on this phone. Your OpenAI account is not affected.")
            .setPositiveButton("Reset") { _, _ -> usage.reset(); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptApiKey() {
        val input = editText("sk-...", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        AlertDialog.Builder(this)
            .setTitle("OpenAI API key")
            .setMessage("Leave empty and save to remove the stored key.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                secrets.setApiKey(input.text.toString().trim())
                refresh()
                refreshModelCache(silent = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptLanguages() {
        val input = editText("en, es", settings.languages.joinToString(", "), InputType.TYPE_CLASS_TEXT)
        AlertDialog.Builder(this)
            .setTitle("Languages")
            .setMessage("Two-letter codes of the languages you dictate in, comma-separated. Leave empty to auto-detect.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                settings.languages = AppSettings.splitList(input.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptVocabulary() {
        val input = editText("One term per line", settings.vocabulary.joinToString("\n"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle("Vocabulary")
            .setMessage("Names, products, and jargon to spell exactly. Sent with every transcription and cleanup request.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                settings.vocabulary = AppSettings.splitList(input.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCleanupPrompt() {
        val input = editText("Prompt", settings.cleanupPrompt,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle("Edit current prompt")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim().ifBlank { Prompts.DEFAULT }
                settings.customPrompt = text
                settings.cleanupPrompt = text
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Model picker filled from the key's own /v1/models list (cached), so only
     * models that exist for this key are offered. "Other" accepts any slug.
     */
    private fun pickModel(title: String, default: String, filter: (List<String>) -> List<String>, onPick: (String) -> Unit) {
        fun show(ids: List<String>) {
            val options = (listOf(default) + filter(ids)).distinct() + OTHER
            AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options.toTypedArray()) { _, which ->
                    if (options[which] == OTHER) promptModelSlug(title) { onPick(it); refresh() }
                    else { onPick(options[which]); refresh() }
                }
                .show()
        }
        val cached = settings.cachedModelIds
        if (cached.isNotEmpty()) {
            show(cached)
            refreshModelCache(silent = true)
        } else {
            refreshModelCache(silent = false) { show(it) }
        }
    }

    private fun promptModelSlug(title: String, onSave: (String) -> Unit) {
        val input = editText("model id", "", InputType.TYPE_CLASS_TEXT)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(padded(input))
            .setPositiveButton("Use") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onSave) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshModelCache(silent: Boolean, then: ((List<String>) -> Unit)? = null) {
        val key = secrets.getApiKey()
        if (key.isBlank()) {
            if (!silent) toast("Set your API key first")
            then?.invoke(emptyList())
            return
        }
        if (!silent) toast("Loading models...")
        thread {
            val ids = OpenAiClient(key).listModels()
            runOnUiThread {
                if (ids != null) settings.cachedModelIds = ids
                else if (!silent) toast("Could not load models; showing defaults")
                then?.invoke(ids ?: emptyList())
            }
        }
    }

    // --- Prompt presets ---

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = listOf(
        PromptPreset("simple", "Simple cleanup", "Grammar, punctuation, filler words", Prompts.SIMPLE),
        PromptPreset("dev", "Dev cleanup", "Also turns spoken commands into shell commands", Prompts.DEV),
        PromptPreset("custom", "Custom", customPromptSummary(), settings.customPrompt),
    )

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val row = settingsRow(preset.title, preset.subtitle, radio) {
            settings.cleanupPrompt = if (preset.key == "custom") settings.customPrompt else preset.prompt
            refresh()
        }
        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        return row
    }

    private fun refreshPromptRows() {
        val current = settings.cleanupPrompt
        for (preset in promptPresets()) {
            val views = promptRows[preset.key] ?: continue
            views.radio.isChecked = when (preset.key) {
                "custom" -> current != Prompts.SIMPLE && current != Prompts.DEV
                else -> current == preset.prompt
            }
            views.subtitle.text = if (preset.key == "custom") customPromptSummary() else preset.subtitle
        }
    }

    private fun customPromptSummary(): String {
        val prompt = settings.customPrompt
        return if (prompt == Prompts.DEFAULT) "Your own prompt. Tap Edit current prompt." else prompt.replace("\n", " ")
    }

    // --- UI helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
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
        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        textContainer.addView(TextView(this).apply {
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

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun editText(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setText(value)
    }

    private fun padded(view: View) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(view)
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    companion object {
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val OTHER = "Other (type a model id)"
    }
}
