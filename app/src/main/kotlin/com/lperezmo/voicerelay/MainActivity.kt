package com.lperezmo.voicerelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var secrets: SecretStore

    private lateinit var statusSubtitle: TextView
    private lateinit var buttonsSwitch: MaterialSwitch
    private lateinit var buttonsSub: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var transcribeModelSub: TextView
    private lateinit var languagesSub: TextView
    private lateinit var vocabularySub: TextView
    private lateinit var cleanupSwitch: MaterialSwitch
    private lateinit var cleanupSwitchSub: TextView
    private lateinit var cleanupServiceSub: TextView
    private lateinit var anthropicKeyRow: LinearLayout
    private lateinit var anthropicKeySub: TextView
    private lateinit var cleanupModelSub: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var fixPromptSub: TextView
    private lateinit var joystickSwitch: MaterialSwitch
    private lateinit var joystickSub: TextView
    private lateinit var autoSwitchSwitch: MaterialSwitch
    private lateinit var autoSwitchSub: TextView
    private lateinit var volumeSwitch: MaterialSwitch
    private lateinit var volumeSub: TextView
    private lateinit var glyphSwitch: MaterialSwitch
    private lateinit var glyphSub: TextView
    private lateinit var dndAccessSub: TextView
    private lateinit var exactTimerSub: TextView
    private lateinit var cleanupContainer: LinearLayout
    private lateinit var liveSwitch: MaterialSwitch
    private lateinit var liveSub: TextView
    private lateinit var usageMonthSub: TextView
    private lateinit var wakeSwitch: MaterialSwitch
    private lateinit var wakeTitle: TextView
    private lateinit var wakeSub: TextView
    private lateinit var wakePhraseSub: TextView
    private lateinit var wakeSensitivitySub: TextView
    private lateinit var wakeSlider: Slider
    private lateinit var wakeResumeSwitch: MaterialSwitch
    private lateinit var wakeOverlaySub: TextView
    private lateinit var wakeContainer: LinearLayout
    private lateinit var externalControlSwitch: MaterialSwitch
    private lateinit var usageTotalSub: TextView
    private lateinit var usage: UsageStore
    private lateinit var ui: SettingsUi
    private lateinit var dialogs: SettingsDialogs

    private val promptRows = mutableMapOf<String, PromptRowViews>()

    private data class PromptRowViews(val radio: MaterialRadioButton, val subtitle: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        secrets = SecretStore(this)
        usage = UsageStore(this)
        ui = SettingsUi(this)
        dialogs = SettingsDialogs(this, settings, secrets, usage, ui) { refresh() }

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 32f
            setPadding(dp(24), dp(32), dp(24), dp(24))
        })

        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // Same toggle as the Quick Settings tile: hides every floating button until tapped again.
        buttonsSwitch = MaterialSwitch(this).apply { isClickable = false }
        val buttonsRow = settingsRow("Show the floating buttons", "", buttonsSwitch) {
            val service = WhisperAccessibilityService.instance
            if (service == null) dialogs.showAccessibilityDisclosure()
            else service.setButtonsHidden(!service.buttonsHidden)
            refresh()
        }
        buttonsSub = buttonsRow.findViewWithTag("subtitle")
        root.addView(buttonsRow)

        // --- Setup ---
        root.addView(sectionHeader("Setup"))

        val audioRow = settingsRow("Microphone permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") { dialogs.showAccessibilityDisclosure() }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        // --- Wake word ---
        root.addView(sectionHeader("Wake word"))
        wakeSwitch = MaterialSwitch(this).apply { isClickable = false }
        val wakeRow = settingsRow("Wake word", "", wakeSwitch) { toggleWakeWord() }
        wakeTitle = wakeRow.findViewWithTag("title")
        wakeSub = wakeRow.findViewWithTag("subtitle")
        root.addView(wakeRow)
        wakeContainer = vertical(0)
        val phraseRow = settingsRow("Wake phrase", "") { dialogs.promptWakePhrase() }
        wakePhraseSub = phraseRow.findViewWithTag("subtitle")
        wakeContainer.addView(phraseRow)
        val sensitivityRow = settingsRow("Sensitivity", "")
        sensitivityRow.setPadding(dp(24), dp(16), dp(24), 0)
        wakeSensitivitySub = sensitivityRow.findViewWithTag("subtitle")
        wakeContainer.addView(sensitivityRow)
        wakeSlider = Slider(this).apply {
            valueFrom = WakeWord.MIN_SENSITIVITY.toFloat()
            valueTo = WakeWord.MAX_SENSITIVITY.toFloat()
            stepSize = 1f
            value = settings.wakeSensitivity.toFloat()
            setPadding(dp(8), 0, dp(8), 0)
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    settings.wakeSensitivity = v.toInt()
                    refreshWakeRows()
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) = WakeWordService.reload(this@MainActivity)
            })
        }
        wakeContainer.addView(wakeSlider)
        wakeResumeSwitch = MaterialSwitch(this).apply { isClickable = false }
        wakeContainer.addView(settingsRow(
            "Resume music after Claude",
            "If music was playing when you said the wake word, press play again once Claude is done.",
            wakeResumeSwitch,
        ) {
            settings.wakeResumeMedia = !settings.wakeResumeMedia
            refresh()
        })
        val overlayRow = settingsRow("Display over other apps", "") { dialogs.showOverlayDisclosure() }
        wakeOverlaySub = overlayRow.findViewWithTag("subtitle")
        wakeContainer.addView(overlayRow)
        root.addView(wakeContainer)
        // Shown with the wake word off too: the same intents also hide and show the buttons.
        externalControlSwitch = MaterialSwitch(this).apply { isClickable = false }
        root.addView(settingsRow(
            "Allow other apps to control Voice Relay",
            "Lets Tasker, adb or any other app turn the wake word on or off, open Claude in voice mode, " +
                "and hide or show the floating buttons.",
            externalControlSwitch,
        ) {
            settings.externalControl = !settings.externalControl
            refresh()
        })

        // --- OpenAI ---
        root.addView(sectionHeader("OpenAI"))

        val keyRow = settingsRow("API key", "Tap to set") { dialogs.promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        root.addView(keyRow)

        liveSwitch = MaterialSwitch(this).apply { isClickable = false }
        val liveRow = settingsRow("Live transcription", "", liveSwitch) {
            settings.liveTranscription = !settings.liveTranscription
            refresh()
        }
        liveSub = liveRow.findViewWithTag("subtitle")
        root.addView(liveRow)

        val transcribeRow = settingsRow("Transcription model", "") { dialogs.pickTranscriptionModel() }
        transcribeModelSub = transcribeRow.findViewWithTag("subtitle")
        root.addView(transcribeRow)

        val langRow = settingsRow("Languages", "") { dialogs.promptLanguages() }
        languagesSub = langRow.findViewWithTag("subtitle")
        root.addView(langRow)

        val vocabRow = settingsRow("Vocabulary", "") { dialogs.promptVocabulary() }
        vocabularySub = vocabRow.findViewWithTag("subtitle")
        vocabularySub.maxLines = 2
        vocabularySub.ellipsize = TextUtils.TruncateAt.END
        root.addView(vocabRow)

        // --- Cleanup (and the grammar fix: both use the service and model chosen here) ---
        root.addView(sectionHeader("Cleanup"))

        val serviceRow = settingsRow("Cleanup service", "") { dialogs.pickCleanupService() }
        cleanupServiceSub = serviceRow.findViewWithTag("subtitle")
        root.addView(serviceRow)

        anthropicKeyRow = settingsRow("Anthropic API key", "") { dialogs.promptAnthropicKey() }
        anthropicKeySub = anthropicKeyRow.findViewWithTag("subtitle")
        root.addView(anthropicKeyRow)

        val cleanupModelRow = settingsRow("Cleanup model", "") { dialogs.pickCleanupModel() }
        cleanupModelSub = cleanupModelRow.findViewWithTag("subtitle")
        root.addView(cleanupModelRow)

        cleanupSwitch = MaterialSwitch(this).apply { isClickable = false }
        val cleanupRow = settingsRow("Clean up transcript", "", cleanupSwitch) {
            settings.cleanupEnabled = !settings.cleanupEnabled
            refresh()
        }
        cleanupSwitchSub = cleanupRow.findViewWithTag("subtitle")
        root.addView(cleanupRow)

        cleanupContainer = vertical(0)
        for (preset in promptPresets()) cleanupContainer.addView(buildPromptRow(preset))
        val promptRow = settingsRow("Edit custom prompt", "") { dialogs.promptCustomPrompt() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = TextUtils.TruncateAt.END
        cleanupContainer.addView(promptRow)
        root.addView(cleanupContainer)

        // --- Arrow joystick ---
        root.addView(sectionHeader("Arrow joystick"))
        joystickSwitch = MaterialSwitch(this).apply { isClickable = false }
        val joystickRow = settingsRow("Show the arrow pad", "", joystickSwitch) {
            settings.joystickEnabled = !settings.joystickEnabled
            WhisperAccessibilityService.instance?.refreshJoystick()
            refresh()
        }
        joystickSub = joystickRow.findViewWithTag("subtitle")
        root.addView(joystickRow)
        autoSwitchSwitch = MaterialSwitch(this).apply { isClickable = false }
        val autoSwitchRow = settingsRow("Switch keyboard automatically", "", autoSwitchSwitch) {
            settings.joystickAutoSwitch = !settings.joystickAutoSwitch
            refresh()
        }
        autoSwitchSub = autoSwitchRow.findViewWithTag("subtitle")
        root.addView(autoSwitchRow)
        root.addView(settingsRow(
            "Arrows keyboard",
            "Arrow keys can only reach another app through a keyboard. Enable Voice Relay arrows, then switch to it while you need the pad. Long-press the pad to switch.",
        ) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })

        // --- Volume presets ---
        root.addView(sectionHeader("Volume presets"))
        volumeSwitch = MaterialSwitch(this).apply { isClickable = false }
        val volumeRow = settingsRow("Show the volume pill", "", volumeSwitch) {
            settings.volumePillEnabled = !settings.volumePillEnabled
            WhisperAccessibilityService.instance?.refreshVolumePill()
            refresh()
        }
        volumeSub = volumeRow.findViewWithTag("subtitle")
        root.addView(volumeRow)
        val dndRow = settingsRow("Do Not Disturb access", "") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        dndAccessSub = dndRow.findViewWithTag("subtitle")
        root.addView(dndRow)
        val exactRow = settingsRow("End the DND hour on time", "") {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        exactTimerSub = exactRow.findViewWithTag("subtitle")
        root.addView(exactRow)

        // --- Glyph ---
        root.addView(sectionHeader("Glyph"))
        glyphSwitch = MaterialSwitch(this).apply { isClickable = false }
        val glyphRow = settingsRow("Show the Glyph pill", "", glyphSwitch) {
            settings.glyphPillEnabled = !settings.glyphPillEnabled
            WhisperAccessibilityService.instance?.refreshGlyphPill()
            refresh()
        }
        glyphSub = glyphRow.findViewWithTag("subtitle")
        root.addView(glyphRow)

        // --- Grammar fix (its own prompt: this text was typed, not dictated) ---
        root.addView(sectionHeader("Grammar fix"))
        val fixRow = settingsRow("Fix prompt", "") { dialogs.promptFixPrompt() }
        fixPromptSub = fixRow.findViewWithTag("subtitle")
        fixPromptSub.maxLines = 2
        fixPromptSub.ellipsize = TextUtils.TruncateAt.END
        root.addView(fixRow)

        // --- Usage ---
        root.addView(sectionHeader("Usage (estimate)"))
        val monthRow = settingsRow("This month", "")
        usageMonthSub = monthRow.findViewWithTag("subtitle")
        root.addView(monthRow)
        val totalRow = settingsRow("Since reset", "") { dialogs.confirmUsageReset() }
        usageTotalSub = totalRow.findViewWithTag("subtitle")
        root.addView(totalRow)
        root.addView(TextView(this).apply {
            text = "Counted on this phone from list prices. Your OpenAI usage page is the actual bill. " +
                "Tap Since reset to start over."
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        // --- About ---
        root.addView(sectionHeader("About"))
        root.addView(settingsRow(
            "Open-source licenses",
            "Voice Relay ${BuildConfig.VERSION_NAME}. Apache License 2.0, and the notices for the software it includes.",
        ) { startActivity(Intent(this, LicensesActivity::class.java)) })
        root.addView(View(this).apply { minimumHeight = dp(32) })

        setContentView(ScrollView(this).apply {
            // Edge to edge is enforced on Android 15+: keep rows out from under the system bars.
            fitsSystemWindows = true
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
        super.onRequestPermissionsResult(c, p, r)
        if (c == REQ_WAKE) {
            if (hasPerm(Manifest.permission.RECORD_AUDIO)) enableWakeWord()
            else toast("The wake word needs the microphone")
        }
        refresh()
    }

    // --- Wake word ---

    private fun toggleWakeWord() {
        if (settings.wakeWordEnabled) {
            settings.wakeWordEnabled = false
            WakeWordService.stop(this)
            refresh()
            return
        }
        val missing = buildList {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_WAKE)
            return
        }
        enableWakeWord()
    }

    private fun enableWakeWord() {
        settings.wakeWordEnabled = true
        settings.wakeWordSetUp = true
        WakeWordService.start(this, "settings switch")
        if (!Settings.canDrawOverlays(this)) dialogs.showOverlayDisclosure()
        refresh()
        // The service reports in a moment later; show that in the subtitle.
        wakeSub.postDelayed({ refreshWakeRows() }, 1000)
    }

    private fun refreshWakeRows() {
        val on = settings.wakeWordEnabled
        // What the listener really uses: the default if the saved phrase no longer fits the model.
        val wake = WakeWordService.effectiveWake(this)
        val phrase = wake.phrase
        val keywords = wake.keywords
        wakeSwitch.isChecked = on
        wakeContainer.visibility = if (on) View.VISIBLE else View.GONE
        wakeTitle.text = "Wake word: $phrase"
        wakeSub.text = when {
            !on -> "Off. Say $phrase anywhere to open Claude's voice assistant. " +
                "Listens on the phone only; no audio leaves it."
            WakeWordService.running -> "On: listening everywhere." +
                (if (WakeWord.hasAccentVariants(keywords)) " Cloud and clod for claude work too." else "") +
                " A notification shows while it listens."
            else -> "On, but not listening right now. Turn it off and on again to restart."
        }
        wakePhraseSub.text = (if (wake.fellBack) "\"${settings.wakePhrase}\" no longer fits the wake word model, " +
            "so it listens for $phrase. " else "") +
            "\"$phrase\": ${keywords.first().phones.joinToString(" ")}" +
            (if (keywords.size > 1) " and ${keywords.size - 1} more way${if (keywords.size > 2) "s" else ""} to say it" else "") +
            (if (!wake.fellBack && settings.wakePhones.isNotEmpty()) ", phones typed by hand" else "") + ". Tap to change."
        val level = settings.wakeSensitivity
        wakeSensitivitySub.text = "Level $level of ${WakeWord.MAX_SENSITIVITY} (threshold %.2f). ".format(WakeWord.threshold(level)) +
            "Higher catches more, and triggers by mistake more."
        if (wakeSlider.value.toInt() != level) wakeSlider.value = level.toFloat()
        wakeResumeSwitch.isChecked = settings.wakeResumeMedia
        wakeOverlaySub.text = if (Settings.canDrawOverlays(this)) "Allowed: the wake word can open Claude from anywhere"
        else "Needed to open Claude from other apps and the lock screen. Tap to allow."
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
        vocabularySub.text = vocabularySummary()
        val claude = settings.claudeCleanup
        val anthropicKey = secrets.getAnthropicKey()
        cleanupServiceSub.text = if (claude) {
            "Claude, by Anthropic, for cleanup and the grammar fix. Speech to text stays on OpenAI."
        } else "OpenAI. Tap to use Claude, by Anthropic, for cleanup and the grammar fix instead."
        anthropicKeyRow.visibility = if (claude) View.VISIBLE else View.GONE
        anthropicKeySub.text = when {
            anthropicKey.isBlank() -> "Tap to set. Until then, dictation inserts the raw transcript."
            anthropicKey.length > 12 -> "${anthropicKey.take(7)}...${anthropicKey.takeLast(4)}"
            else -> "Set"
        }
        cleanupModelSub.text = if (claude) AnthropicClient.displayName(settings.claudeModel) else settings.cleanupModel
        cleanupSwitch.isChecked = settings.cleanupEnabled
        cleanupSwitchSub.text = "Fixes grammar and punctuation. Sends the transcript text to ${if (claude) "Anthropic" else "OpenAI"}."
        cleanupContainer.visibility = if (settings.cleanupEnabled) View.VISIBLE else View.GONE
        promptRowSub.text = settings.customPrompt
        fixPromptSub.text = settings.fixPrompt
        joystickSwitch.isChecked = settings.joystickEnabled
        autoSwitchSwitch.isChecked = settings.joystickAutoSwitch
        autoSwitchSub.text = if (settings.joystickAutoSwitch)
            "Touching the knob switches to the arrows keyboard, and your own comes back 4 seconds after the last arrow."
        else "Off. Long-press the pad to switch keyboards by hand."
        joystickSub.text = if (settings.joystickEnabled)
            "A draggable pad that sends arrow keys. Push the middle knob; drag the ring to move it."
        else "Off. A floating pad for arrow keys, for remote desktop and terminals."
        externalControlSwitch.isChecked = settings.externalControl
        volumeSwitch.isChecked = settings.volumePillEnabled
        volumeSub.text = if (settings.volumePillEnabled)
            "Tap the pill above the mic, then draw a V on the track: dip down, slide to a preset, rise to apply."
        else "Off. Four one-gesture volume presets: Silent + DND for an hour, Mute, Medium, Loud."
        val service = WhisperAccessibilityService.instance
        buttonsSwitch.isChecked = service != null && !service.buttonsHidden
        buttonsSub.text = when {
            service == null -> "The accessibility service is off. Tap to turn it on."
            service.buttonsHidden -> "Hidden. Tap to bring back the mic and its pills."
            else -> "Tap to hide the mic and its pills, like the Quick Settings tile."
        }
        val glyphInstalled = GlyphPillView.glyphInstalled(this)
        glyphSwitch.isChecked = settings.glyphPillEnabled
        glyphSub.text = when {
            !glyphInstalled -> "Glyph is not installed, so the pill is hidden."
            settings.glyphPillEnabled -> "Tap the pill below the mic, or pull it down, to open Glyph's canvas and draw a shape."
            else -> "Off. A pill below the mic that opens Glyph's shape canvas."
        }
        val volume = VolumeController(this)
        dndAccessSub.text = if (volume.hasDndAccess) "Granted"
        else "Needed for the presets. Tap, then allow Voice Relay."
        exactTimerSub.text = if (volume.exactTimer) "On: DND ends right at the hour"
        else "Off: DND may end up to 10 minutes late. Tap to allow alarms."
        refreshWakeRows()
        usageMonthSub.text = usage.thisMonth().summary()
        val since = usage.since.takeIf { it > 0 }?.let {
            " (since " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)) + ")"
        }.orEmpty()
        usageTotalSub.text = usage.allTime().summary() + since

        val ready = audio && acc && apiKey.isNotBlank()
        statusSubtitle.text = when {
            ready && settings.claudeCleanup && settings.cleanupEnabled && anthropicKey.isBlank() ->
                "Ready. Set your Anthropic API key below for Claude cleanup; until then the raw transcript is inserted."
            ready -> "Ready. Double-tap the floating button to dictate."
            apiKey.isBlank() -> "Set your OpenAI API key to start"
            else -> "Setup required"
        }
        statusSubtitle.setTextColor(
            if (ready) attrColor(com.google.android.material.R.attr.colorPrimary)
            else attrColor(android.R.attr.textColorSecondary)
        )
        refreshPromptRows()
    }

    private fun vocabularySummary(): String {
        val terms = settings.vocabulary
        if (terms.isEmpty()) return "Names and terms to spell exactly. Tap to add, or paste a list."
        val heard = Vocabulary.forTranscription(terms).size
        val count = if (heard < terms.size) "${terms.size} terms, the first $heard steer transcription: " else ""
        return count + terms.joinToString(", ")
    }

    // --- Prompt presets ---

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = listOf(
        PromptPreset(Prompts.SIMPLE_KEY, "Simple cleanup", "Grammar, punctuation, filler words", Prompts.SIMPLE),
        PromptPreset(Prompts.DEV_KEY, "Dev cleanup", "Also turns spoken commands into shell commands", Prompts.DEV),
        PromptPreset(Prompts.CUSTOM_KEY, "Custom", customPromptSummary(), settings.customPrompt),
    )

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val row = settingsRow(preset.title, preset.subtitle, radio) {
            settings.promptPreset = preset.key
            refresh()
        }
        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        return row
    }

    private fun refreshPromptRows() {
        val current = settings.promptPreset
        for (preset in promptPresets()) {
            val views = promptRows[preset.key] ?: continue
            views.radio.isChecked = preset.key == current
            views.subtitle.text = if (preset.key == Prompts.CUSTOM_KEY) customPromptSummary() else preset.subtitle
        }
    }

    private fun customPromptSummary(): String {
        val prompt = settings.customPrompt
        return if (prompt == Prompts.DEFAULT) "Your own prompt. Tap Edit custom prompt." else prompt.replace("\n", " ")
    }

    // --- Helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null) =
        ui.settingsRow(title, subtitle, widget, onClick)
    private fun sectionHeader(title: String) = ui.sectionHeader(title)
    private fun vertical(padH: Int, padV: Int = padH) = ui.vertical(padH, padV)
    private fun dp(n: Int) = ui.dp(n)
    private fun attrColor(attr: Int) = ui.attrColor(attr)
    private fun toast(msg: String) = ui.toast(msg)
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQ_WAKE = 2
    }
}
