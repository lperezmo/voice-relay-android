package com.lperezmo.voicerelay

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlin.concurrent.thread

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
class WakePhraseDialog(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val secrets: SecretStore,
    private val usage: UsageStore,
    private val ui: SettingsUi,
    private val refresh: () -> Unit,
) {
    private fun editText(hint: String, value: String, type: Int) = ui.editText(hint, value, type)
    private fun vertical(padH: Int, padV: Int = padH) = ui.vertical(padH, padV)
    private fun dp(n: Int) = ui.dp(n)
    private fun attrColor(attr: Int) = ui.attrColor(attr)
    private fun toast(msg: String) = ui.toast(msg)

    fun show() = with(activity) {
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
                addView(MaterialRadioButton(activity).apply {
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
            addView(TextView(activity).apply {
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
            addView(TextView(activity).apply {
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
}
