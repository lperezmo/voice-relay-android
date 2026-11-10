package com.lperezmo.voicerelay

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import kotlin.concurrent.thread

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

        // Same toggle as the Quick Settings tile: hides every floating button until tapped again.
        buttonsSwitch = MaterialSwitch(this).apply { isClickable = false }
        val buttonsRow = settingsRow("Show the floating buttons", "", buttonsSwitch) {
            val service = WhisperAccessibilityService.instance
            if (service == null) showAccessibilityDisclosure()
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

        val accRow = settingsRow("Accessibility service", "Checking...") { showAccessibilityDisclosure() }
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
        val phraseRow = settingsRow("Wake phrase", "") { promptWakePhrase() }
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
        val overlayRow = settingsRow("Display over other apps", "") { showOverlayDisclosure() }
        wakeOverlaySub = overlayRow.findViewWithTag("subtitle")
        wakeContainer.addView(overlayRow)
        root.addView(wakeContainer)

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

        // --- Cleanup (and the grammar fix: both use the service and model chosen here) ---
        root.addView(sectionHeader("Cleanup"))

        val serviceRow = settingsRow("Cleanup service", "") { pickCleanupService() }
        cleanupServiceSub = serviceRow.findViewWithTag("subtitle")
        root.addView(serviceRow)

        anthropicKeyRow = settingsRow("Anthropic API key", "") { promptAnthropicKey() }
        anthropicKeySub = anthropicKeyRow.findViewWithTag("subtitle")
        root.addView(anthropicKeyRow)

        val cleanupModelRow = settingsRow("Cleanup model", "") { pickCleanupModel() }
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
        val promptRow = settingsRow("Edit custom prompt", "") { promptCustomPrompt() }
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
        val fixRow = settingsRow("Fix prompt", "") { promptFixPrompt() }
        fixPromptSub = fixRow.findViewWithTag("subtitle")
        fixPromptSub.maxLines = 2
        fixPromptSub.ellipsize = TextUtils.TruncateAt.END
        root.addView(fixRow)

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
        if (!Settings.canDrawOverlays(this)) showOverlayDisclosure()
        refresh()
        // The service reports in a moment later; show that in the subtitle.
        wakeSub.postDelayed({ refreshWakeRows() }, 1000)
    }

    /** Explains the overlay permission before sending the user to system settings. */
    private fun showOverlayDisclosure() {
        if (Settings.canDrawOverlays(this)) {
            toast("Already allowed")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Display over other apps")
            .setMessage(
                "Android does not let an app open another app while you are using something else " +
                    "or the screen is off. Allowing Voice Relay to display over other apps is the " +
                    "exemption that lets the wake word open Claude from anywhere.\n\n" +
                    "Voice Relay does not draw anything with this permission."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /**
     * Edits the wake phrase. As you type, it is spelled as phones with the
     * bundled dictionary, off the main thread (a lookup streams through about
     * 3.5 MB). Words the dictionary lacks can be spelled by hand under Advanced,
     * or suggested there by the cleanup model (Generate with AI: only the
     * phrase's text goes to OpenAI, and the answer is checked and shown for
     * review); those phones count only while Advanced is open, and belong to
     * the phrase they were typed for: changing the phrase's words clears them. Save keeps
     * the dialog open until the phrase checks out, saves exactly what was
     * checked, and restarts the listener with it.
     */
    private fun promptWakePhrase() {
        val tokens = WakeWordService.modelTokens(this)
        val current = WakeWordService.effectiveWake(this)
        val savedPhones = if (current.fellBack) "" else settings.wakePhones
        // Word -> its pronunciations; an empty list means the dictionary lacks it.
        val known = mutableMapOf<String, List<List<String>>>()
        fun resolve(phrase: String, phones: String): WakeWord.Result = synchronized(known) {
            if (phones.isNotBlank()) return WakeWord.fromPhones(phrase, phones, tokens)
            val missing = WakeWord.words(phrase).filter { it !in known }
            if (missing.isNotEmpty()) {
                val found = try {
                    assets.open(WakeWord.DICTIONARY_ASSET).use { WakeWord.lookup(it, missing) }
                } catch (_: java.io.IOException) {
                    emptyMap()
                }
                for (w in missing) known[w] = found[w].orEmpty()
            }
            WakeWord.fromDictionary(phrase, known, tokens)
        }

        val secondary = attrColor(android.R.attr.textColorSecondary)
        val error = attrColor(com.google.android.material.R.attr.colorError)
        val phraseInput = editText(WakeWord.DEFAULT_PHRASE, current.phrase, InputType.TYPE_CLASS_TEXT).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
        }
        val result = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        val phonesInput = editText(
            "HH EY1 JH AA1 R V AH0 S", savedPhones,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        ).apply {
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            filters = arrayOf(android.text.InputFilter.LengthFilter(WakeWord.MAX_PHONES_TEXT))
        }
        // Generate with AI: the cleanup model suggests phones for the phrase, into the field below.
        var aiLanguage = WakeWordAi.Language.AUTO
        val languages = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            WakeWordAi.Language.entries.forEach { lang ->
                addView(MaterialRadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = lang.label
                    isChecked = lang == aiLanguage
                    setOnCheckedChangeListener { _, on -> if (on) aiLanguage = lang }
                })
            }
        }
        val generateButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Generate with AI"
        }
        val aiRow = vertical(0).apply {
            setPadding(0, dp(8), 0, 0)
            addView(languages)
            addView(generateButton)
        }
        val aiStatus = TextView(this).apply {
            text = "Sends only the phrase's text to OpenAI, with your key and cleanup model. You review the result before saving."
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(4), 0, dp(4))
        }
        val advanced = vertical(0).apply {
            visibility = if (savedPhones.isEmpty()) View.GONE else View.VISIBLE
            addView(TextView(this@MainActivity).apply {
                text = "Spell the phrase as phones, for words the dictionary lacks or to change how it is said: " +
                    "ARPAbet as in CMUdict, vowels with a stress digit (0, 1 or 2), one pronunciation per line, " +
                    "at most ${WakeWord.MAX_KEYWORDS}. Used while this section is open; leave it empty or close it " +
                    "to use the dictionary."
                textSize = 13f
                setTextColor(secondary)
            })
            addView(aiRow)
            addView(aiStatus)
            addView(phonesInput)
        }
        /** The hand phones that count right now: none while Advanced is closed. */
        fun activePhones() = if (advanced.visibility == View.VISIBLE) phonesInput.text.toString() else ""

        fun show(r: WakeWord.Result, phones: String) {
            result.setTextColor(if (r is WakeWord.Result.Ready) secondary else error)
            result.text = when (r) {
                is WakeWord.Result.Ready -> {
                    val others = r.keywords.drop(1)
                    "Phones: ${r.keywords.first().phones.joinToString(" ")}" +
                        (if (phones.isNotBlank()) " (typed by hand)" else "") +
                        (if (others.isEmpty()) "" else "\nAlso listens for: " +
                            others.take(3).joinToString("; ") { it.phones.joinToString(" ") } +
                            (if (others.size > 3) "; and ${others.size - 3} more" else "")) +
                        (WakeWord.advice(r.keywords)?.let { "\n$it" } ?: "")
                }
                is WakeWord.Result.UnknownWords -> {
                    advanced.visibility = View.VISIBLE
                    val quoted = r.words.joinToString(", ") { "\"$it\"" }
                    (if (r.words.size == 1) "$quoted is not in the pronunciation dictionary. "
                    else "$quoted are not in the pronunciation dictionary. ") +
                        "Try another spelling, tap Generate with AI, or enter the phones by hand below."
                }
                is WakeWord.Result.UnknownPhones -> "Not phones the wake word model knows: ${r.phones.joinToString(" ")}." +
                    (if (r.suggestions.isEmpty()) "" else " Vowels need a stress digit: " +
                        r.suggestions.entries.joinToString(", ") { "${it.value} for ${it.key}" } + ".")
                is WakeWord.Result.NoVowel -> "Each pronunciation needs a vowel (a phone with a stress digit, " +
                    "such as EY1); without one it would trigger on almost anything: " +
                    r.pronunciations.joinToString("; ") + "."
                is WakeWord.Result.TooMany -> "${r.count} pronunciations; at most ${WakeWord.MAX_KEYWORDS}. " +
                    "Each one adds a way to trigger by accident."
                WakeWord.Result.Empty -> if (phones.isNotBlank()) "Type the phrase too: it names the phones."
                else "Type a phrase."
            }
        }

        val handler = android.os.Handler(mainLooper)
        var generation = 0
        /** Checks the phrase and phones as they are now; [then] gets the result and the phones that were checked. */
        fun check(then: ((WakeWord.Result, String) -> Unit)? = null) {
            val phrase = phraseInput.text.toString()
            val phones = activePhones()
            val gen = ++generation
            thread {
                val r = resolve(phrase, phones)
                runOnUiThread {
                    if (gen == generation) show(r, phones)
                    then?.invoke(r, phones)
                }
            }
        }
        val delayedCheck = Runnable { check() }
        fun checkSoon() {
            handler.removeCallbacks(delayedCheck)
            handler.postDelayed(delayedCheck, 400)
        }

        // Hand phones belong to the phrase they were typed for.
        var phonesFor = if (savedPhones.isEmpty()) "" else WakeWord.tidy(current.phrase)
        phraseInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (phonesInput.text.isNotBlank() && WakeWord.tidy(s.toString()) != phonesFor) phonesInput.setText("")
                checkSoon()
            }
        })
        phonesInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                phonesFor = WakeWord.tidy(phraseInput.text.toString())
                checkSoon()
            }
        })

        var open = true
        generateButton.setOnClickListener {
            val phrase = WakeWord.tidy(phraseInput.text.toString())
            val key = secrets.getApiKey()
            aiStatus.setTextColor(error)
            when {
                phrase.isEmpty() -> aiStatus.text = "Type the phrase first."
                key.isBlank() -> aiStatus.text = "Set your OpenAI API key first."
                else -> {
                    val model = settings.cleanupModel
                    val language = aiLanguage
                    generateButton.isEnabled = false
                    aiStatus.setTextColor(secondary)
                    aiStatus.text = "Asking $model..."
                    thread {
                        val r = OpenAiClient(key).respondJson(
                            phrase, model, WakeWordAi.instructions(tokens, language),
                            WakeWordAi.SCHEMA_NAME, WakeWordAi.SCHEMA,
                        )
                        if (r.ok) usage.recordText(model, r.inputTokens, r.outputTokens)
                        val parsed = r.text?.let { WakeWordAi.parse(it, tokens) }
                        // Metadata only, never the phrase.
                        android.util.Log.i("VoiceRelay", "Wake phrase AI: ${if (r.ok) "ok" else "failed ${r.httpCode}"}, " +
                            "kept ${parsed?.kept?.size ?: 0}, dropped ${parsed?.dropped?.size ?: 0}")
                        runOnUiThread {
                            if (!open) return@runOnUiThread
                            generateButton.isEnabled = true
                            val droppedNote = parsed?.dropped?.takeIf { it.isNotEmpty() }
                                ?.let { "\nLeft out: " + it.joinToString("; ") } ?: ""
                            when {
                                !r.ok -> {
                                    aiStatus.setTextColor(error)
                                    aiStatus.text = "The request failed: ${r.error ?: "no answer"}"
                                }
                                parsed == null || parsed.kept.isEmpty() -> {
                                    aiStatus.setTextColor(error)
                                    aiStatus.text = "No usable pronunciations came back. Try again or type them by hand.$droppedNote"
                                }
                                WakeWord.tidy(phraseInput.text.toString()) != phrase -> {
                                    aiStatus.setTextColor(error)
                                    aiStatus.text = "The phrase changed while waiting. Tap Generate with AI again."
                                }
                                else -> {
                                    phonesInput.setText(parsed.text)
                                    aiStatus.setTextColor(secondary)
                                    val notes = parsed.kept.map { it.note }.filter { it.isNotEmpty() }
                                    aiStatus.text = "${parsed.kept.size} suggested by $model" +
                                        (if (notes.isEmpty()) "" else ": ${notes.joinToString(", ")}") +
                                        ". Check them, edit if needed, then Save.$droppedNote"
                                }
                            }
                        }
                    }
                }
            }
        }

        val advancedToggle = TextView(this).apply {
            text = "Advanced: enter phones by hand or generate them"
            textSize = 14f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                checkSoon()
            }
        }
        val content = vertical(dp(20), dp(8)).apply {
            addView(TextView(this@MainActivity).apply {
                text = "2 to 4 syllables with distinct sounds recognize best. Very short or common phrases " +
                    "trigger by accident. Either way it opens Claude's voice assistant."
                textSize = 13f
                setTextColor(secondary)
            })
            addView(phraseInput)
            addView(result)
            addView(advancedToggle)
            addView(advanced)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Wake phrase")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save", null)
            .setNeutralButton("Reset to default") { _, _ ->
                settings.setWakePhrase(null)
                WakeWordService.reload(this)
                refresh()
                toast("Wake phrase: ${WakeWord.DEFAULT_PHRASE}")
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                handler.removeCallbacks(delayedCheck)
                check { r, phones ->
                    if (r is WakeWord.Result.Ready && dialog.isShowing) {
                        settings.setWakePhrase(r, phones)
                        WakeWordService.reload(this)
                        dialog.dismiss()
                        refresh()
                        toast(if (WakeWordService.running) "Now listening for ${r.phrase}" else "Wake phrase: ${r.phrase}")
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            open = false
            handler.removeCallbacks(delayedCheck)
        }
        dialog.show()
        check()
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
                "Voice Relay uses the accessibility service to show the floating dictation button " +
                    "and to type your dictated text into the text field you are using.\n\n" +
                    "To know where to type, it watches which text field is focused, in every app. " +
                    "It reads a field's text only when you dictate into it, fix its grammar, or undo " +
                    "a change there. It does not record or store screen content.\n\n" +
                    "Your audio goes only to OpenAI. The text it cleans up or fixes goes to OpenAI too, or to " +
                    "Anthropic if you choose Claude for cleanup. Both use your own API keys."
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
                secrets.setApiKey(input.text.toString().filterNot { it.isWhitespace() })
                refresh()
                refreshModelCache(silent = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptAnthropicKey() {
        val input = editText("sk-ant-...", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        AlertDialog.Builder(this)
            .setTitle("Anthropic API key")
            .setMessage("Used only for Claude cleanup and the grammar fix; speech to text still uses your OpenAI key. " +
                "Stored encrypted on this device. Leave empty and save to remove the stored key.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                secrets.setAnthropicKey(input.text.toString().filterNot { it.isWhitespace() })
                refresh()
                refreshModelCache(silent = true, claude = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** OpenAI or Claude for cleanup and the grammar fix. Choosing Claude with no key asks for one. */
    private fun pickCleanupService() {
        val services = listOf(AppSettings.SERVICE_OPENAI, AppSettings.SERVICE_CLAUDE)
        AlertDialog.Builder(this)
            .setTitle("Cleanup service")
            .setSingleChoiceItems(arrayOf("OpenAI", "Claude, by Anthropic"), services.indexOf(settings.cleanupService)) { dialog, which ->
                dialog.dismiss()
                settings.cleanupService = services[which]
                refresh()
                if (settings.claudeCleanup && secrets.getAnthropicKey().isBlank()) promptAnthropicKey()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickCleanupModel() {
        if (settings.claudeCleanup) {
            pickClaudeModel()
        } else {
            pickModel("Cleanup model", AppSettings.DEFAULT_CLEANUP_MODEL, OpenAiClient::cleanupModels) {
                settings.cleanupModel = it
            }
        }
    }

    /** Haiku, Sonnet or Opus in one tap; the last row lists every Claude model the key can use. */
    private fun pickClaudeModel() {
        val tiers = AnthropicClient.TIERS
        val labels = tiers.map { it.label } + "Another Claude model..."
        AlertDialog.Builder(this)
            .setTitle("Claude model")
            .setSingleChoiceItems(labels.toTypedArray(), tiers.indexOfFirst { it.id == settings.claudeModel }) { dialog, which ->
                dialog.dismiss()
                if (which < tiers.size) {
                    settings.claudeModel = tiers[which].id
                    refresh()
                } else {
                    pickModel("Claude model", AppSettings.DEFAULT_CLAUDE_MODEL, AnthropicClient::cleanupModels, claude = true) {
                        settings.claudeModel = it
                    }
                }
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

    /**
     * The vocabulary editor. A whole list can be pasted in: one term per line or
     * comma-separated, list markers dropped. The hint shows a few examples.
     */
    private fun promptVocabulary() {
        val input = editText(Vocabulary.EXAMPLES, settings.vocabulary.joinToString("\n"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 5
            maxLines = 12
            isVerticalScrollBarEnabled = true
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle("Vocabulary")
            .setMessage(
                "Names, products, and jargon to spell exactly, one per line or comma-separated. You can paste a whole list. " +
                    "Put the terms you say most at the top: transcription reads the first ones that fit " +
                    "(about ${Vocabulary.TRANSCRIPTION_CHARS} characters), and cleanup gets all of them, with an instruction " +
                    "never to swap one for a word that sounds like it."
            )
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                settings.vocabulary = Vocabulary.fromPaste(input.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun vocabularySummary(): String {
        val terms = settings.vocabulary
        if (terms.isEmpty()) return "Names and terms to spell exactly. Tap to add, or paste a list."
        val heard = Vocabulary.forTranscription(terms).size
        val count = if (heard < terms.size) "${terms.size} terms, the first $heard steer transcription: " else ""
        return count + terms.joinToString(", ")
    }

    /** Edits your own cleanup prompt. Saving selects Custom; the presets are left alone. */
    private fun promptCustomPrompt() {
        val input = editText("Prompt", settings.customPrompt,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle("Edit custom prompt")
            .setView(padded(input))
            .setPositiveButton("Save and use") { _, _ ->
                val text = input.text.toString().trim().ifBlank { Prompts.DEFAULT }
                settings.customPrompt = text
                settings.promptPreset = Prompts.CUSTOM_KEY
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Edits the grammar-fix prompt, used by the long-press fix and the Fix grammar
     * selection menu. Separate from the cleanup prompt: that text was dictated,
     * this text was typed.
     */
    private fun promptFixPrompt() {
        val input = editText("Prompt", settings.fixPrompt,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle("Grammar fix prompt")
            .setMessage("Sent when you long-press the button or use Fix grammar in the selection menu.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                settings.fixPrompt = input.text.toString().trim().ifBlank { Prompts.TYPED }
                refresh()
            }
            .setNeutralButton("Use default") { _, _ ->
                settings.resetFixPrompt()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Model picker filled from the key's own /v1/models list (cached), so only
     * models that exist for this key are offered. "Other" accepts any slug.
     */
    private fun pickModel(
        title: String,
        default: String,
        filter: (List<String>) -> List<String>,
        claude: Boolean = false,
        onPick: (String) -> Unit,
    ) {
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
        val cached = if (claude) settings.cachedClaudeModelIds else settings.cachedModelIds
        if (cached.isNotEmpty()) {
            show(cached)
            refreshModelCache(silent = true, claude = claude)
        } else {
            refreshModelCache(silent = false, claude = claude) { show(it) }
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

    /** Fetches the model list for OpenAI, or for Anthropic when [claude], into its cache. */
    private fun refreshModelCache(silent: Boolean, claude: Boolean = false, then: ((List<String>) -> Unit)? = null) {
        val key = if (claude) secrets.getAnthropicKey() else secrets.getApiKey()
        if (key.isBlank()) {
            if (!silent) toast(if (claude) "Set your Anthropic API key first" else "Set your API key first")
            then?.invoke(emptyList())
            return
        }
        if (!silent) toast("Loading models...")
        thread {
            val ids = if (claude) AnthropicClient(key).listModels() else OpenAiClient(key).listModels()
            runOnUiThread {
                when {
                    ids != null && claude -> settings.cachedClaudeModelIds = ids
                    ids != null -> settings.cachedModelIds = ids
                    !silent -> toast("Could not load models; showing defaults")
                }
                then?.invoke(ids ?: emptyList())
            }
        }
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
            tag = "title"
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
        private const val REQ_WAKE = 2
    }
}
