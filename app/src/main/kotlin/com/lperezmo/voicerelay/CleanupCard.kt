package com.lperezmo.voicerelay

import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout

/**
 * Cleanup and the grammar fix: both use the service and model chosen here.
 * The grammar fix has its own prompt, because that text was typed, not dictated.
 */
class CleanupCard(private val env: SettingsEnv, parent: LinearLayout) : SettingsCard {
    private val settings = env.settings
    private val dialogs = env.dialogs

    private data class PromptPreset(val key: String, val title: String, val subtitle: String)

    private val serviceRow: SettingsUi.Row
    private val anthropicKeyRow: SettingsUi.Row
    private val modelRow: SettingsUi.Row
    private val cleanupRow: SettingsUi.SwitchRow
    private val presetsContainer: LinearLayout
    private val presetRows = mutableMapOf<String, SettingsUi.RadioRow>()
    private val customPromptRow: SettingsUi.Row
    private val fixRow: SettingsUi.Row

    init {
        val ui = env.ui
        val card = ui.card(parent, "Cleanup")
        serviceRow = ui.row("Cleanup service", "") { dialogs.pickCleanupService() }
        card.addView(serviceRow.view)
        anthropicKeyRow = ui.row("Anthropic API key", "") { dialogs.promptAnthropicKey() }
        card.addView(anthropicKeyRow.view)
        modelRow = ui.row("Cleanup model", "") { dialogs.pickCleanupModel() }
        card.addView(modelRow.view)
        cleanupRow = ui.switchRow("Clean up transcript", "") {
            settings.cleanupEnabled = !settings.cleanupEnabled
            env.refresh()
        }
        card.addView(cleanupRow.view)

        presetsContainer = LinearLayout(env.activity).apply { orientation = LinearLayout.VERTICAL }
        for (preset in presets()) {
            val row = ui.radioRow(preset.title, preset.subtitle) {
                settings.promptPreset = preset.key
                env.refresh()
            }
            presetRows[preset.key] = row
            presetsContainer.addView(row.view)
        }
        customPromptRow = ui.row("Edit custom prompt", "") { dialogs.promptCustomPrompt() }
        customPromptRow.subtitle.maxLines = 2
        customPromptRow.subtitle.ellipsize = TextUtils.TruncateAt.END
        // Lined up with the preset names above: it belongs to that group.
        customPromptRow.view.setPaddingRelative(ui.dp(SettingsUi.RADIO_TEXT_START), ui.dp(12), ui.dp(SettingsUi.PAD_H), ui.dp(12))
        presetsContainer.addView(customPromptRow.view)
        card.addView(presetsContainer)

        card.addView(ui.sectionTitle("Grammar fix"))
        fixRow = ui.row("Fix prompt", "") { dialogs.promptFixPrompt() }
        fixRow.subtitle.maxLines = 2
        fixRow.subtitle.ellipsize = TextUtils.TruncateAt.END
        card.addView(fixRow.view)
    }

    override fun refresh() {
        val claude = settings.claudeCleanup
        val anthropicKey = env.secrets.getAnthropicKey()
        serviceRow.subtitle.text = if (claude) {
            "Claude, by Anthropic, for cleanup and the grammar fix. Speech to text stays on OpenAI."
        } else "OpenAI. Tap to use Claude, by Anthropic, for cleanup and the grammar fix instead."
        anthropicKeyRow.view.visibility = if (claude) View.VISIBLE else View.GONE
        anthropicKeyRow.subtitle.text = when {
            anthropicKey.isBlank() -> "Tap to set. Until then, dictation inserts the raw transcript."
            anthropicKey.length > 12 -> "${anthropicKey.take(7)}...${anthropicKey.takeLast(4)}"
            else -> "Set"
        }
        modelRow.subtitle.text = if (claude) AnthropicClient.displayName(settings.claudeModel) else settings.cleanupModel
        cleanupRow.switch.isChecked = settings.cleanupEnabled
        cleanupRow.subtitle.text =
            "Fixes grammar and punctuation. Sends the transcript text to ${if (claude) "Anthropic" else "OpenAI"}."
        presetsContainer.visibility = if (settings.cleanupEnabled) View.VISIBLE else View.GONE
        customPromptRow.subtitle.text = settings.customPrompt
        fixRow.subtitle.text = settings.fixPrompt

        val current = settings.promptPreset
        for (preset in presets()) {
            val row = presetRows[preset.key] ?: continue
            row.radio.isChecked = preset.key == current
            row.subtitle.text = preset.subtitle
        }
    }

    private fun presets() = listOf(
        PromptPreset(Prompts.SIMPLE_KEY, "Simple cleanup", "Grammar, punctuation, filler words"),
        PromptPreset(Prompts.DEV_KEY, "Dev cleanup", "Also turns spoken commands into shell commands"),
        PromptPreset(Prompts.CUSTOM_KEY, "Custom", customPromptSummary()),
    )

    private fun customPromptSummary(): String {
        val prompt = settings.customPrompt
        return if (prompt == Prompts.DEFAULT) "Your own prompt. Tap Edit custom prompt." else prompt.replace("\n", " ")
    }
}
