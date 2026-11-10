package com.lperezmo.voicerelay

import android.text.TextUtils
import android.widget.LinearLayout

/** OpenAI: the key, live or uploaded transcription, the model, languages and vocabulary. */
class SpeechCard(private val env: SettingsEnv, parent: LinearLayout) : SettingsCard {
    private val settings = env.settings
    private val dialogs = env.dialogs

    private val keyRow: SettingsUi.Row
    private val liveRow: SettingsUi.SwitchRow
    private val modelRow: SettingsUi.Row
    private val languagesRow: SettingsUi.Row
    private val vocabularyRow: SettingsUi.Row

    init {
        val ui = env.ui
        val card = ui.card(parent, "OpenAI")
        keyRow = ui.row("API key", "Tap to set") { dialogs.promptApiKey() }
        card.addView(keyRow.view)
        liveRow = ui.switchRow("Live transcription", "") {
            settings.liveTranscription = !settings.liveTranscription
            env.refresh()
        }
        card.addView(liveRow.view)
        modelRow = ui.row("Transcription model", "") { dialogs.pickTranscriptionModel() }
        card.addView(modelRow.view)
        languagesRow = ui.row("Languages", "") { dialogs.promptLanguages() }
        card.addView(languagesRow.view)
        vocabularyRow = ui.row("Vocabulary", "") { dialogs.promptVocabulary() }
        vocabularyRow.subtitle.maxLines = 2
        vocabularyRow.subtitle.ellipsize = TextUtils.TruncateAt.END
        card.addView(vocabularyRow.view)
    }

    override fun refresh() {
        val apiKey = env.secrets.getApiKey()
        keyRow.subtitle.text = when {
            apiKey.isBlank() -> "Tap to set. Stored encrypted on this device."
            apiKey.length > 8 -> "${apiKey.take(3)}...${apiKey.takeLast(4)}"
            else -> "Set"
        }
        liveRow.switch.isChecked = settings.liveTranscription
        liveRow.subtitle.text = if (settings.liveTranscription) {
            "On: streams while you talk with ${settings.liveModel} (about \$0.017/min), so text is ready " +
                "right after you stop. Falls back to the upload below if the stream fails."
        } else {
            "Off: uploads the recording after you stop, using the model below. Turn on to stream while you talk."
        }
        modelRow.subtitle.text = if (settings.liveTranscription) {
            "${settings.transcriptionModel} (fallback and retries)"
        } else settings.transcriptionModel
        languagesRow.subtitle.text = settings.languages.joinToString(", ").ifEmpty { "Auto-detect" }
        vocabularyRow.subtitle.text = vocabularySummary()
    }

    private fun vocabularySummary(): String {
        val terms = settings.vocabulary
        if (terms.isEmpty()) return "Names and terms to spell exactly. Tap to add, or paste a list."
        val heard = Vocabulary.forTranscription(terms).size
        val count = if (heard < terms.size) "${terms.size} terms, the first $heard steer transcription: " else ""
        return count + terms.joinToString(", ")
    }
}
