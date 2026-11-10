package com.lperezmo.voicerelay

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/** The settings screen's dialogs: keys, models, languages, vocabulary, prompts and disclosures. */
class SettingsDialogs(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val secrets: SecretStore,
    private val usage: UsageStore,
    private val ui: SettingsUi,
    private val refresh: () -> Unit,
) {
    private fun editText(hint: String, value: String, type: Int) = ui.editText(hint, value, type)
    private fun padded(view: android.view.View) = ui.padded(view)
    private fun toast(msg: String) = ui.toast(msg)

    fun promptWakePhrase() = WakePhraseDialog(activity, settings, secrets, usage, ui, refresh).show()

    /** Explains the overlay permission before sending the user to system settings. */
    fun showOverlayDisclosure() {
        if (Settings.canDrawOverlays(activity)) {
            toast("Already allowed")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Display over other apps")
            .setMessage(
                "Android does not let an app open another app while you are using something else " +
                    "or the screen is off. Allowing Voice Relay to display over other apps is the " +
                    "exemption that lets the wake word open Claude from anywhere.\n\n" +
                    "Voice Relay does not draw anything with this permission."
            )
            .setPositiveButton("Open settings") { _, _ ->
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
                )
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Explains the accessibility use before sending the user to system settings. */
    fun showAccessibilityDisclosure() {
        AlertDialog.Builder(activity)
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
            .setPositiveButton("Open settings") { _, _ -> activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Not now", null)
            .show()
    }

    fun confirmUsageReset() {
        AlertDialog.Builder(activity)
            .setTitle("Reset usage?")
            .setMessage("Clears this month and the running total on this phone. Your OpenAI account is not affected.")
            .setPositiveButton("Reset") { _, _ -> usage.reset(); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun promptApiKey() {
        val input = editText("sk-...", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        AlertDialog.Builder(activity)
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

    fun promptAnthropicKey() {
        val input = editText("sk-ant-...", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        AlertDialog.Builder(activity)
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
    fun pickCleanupService() {
        val services = listOf(AppSettings.SERVICE_OPENAI, AppSettings.SERVICE_CLAUDE)
        AlertDialog.Builder(activity)
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

    fun pickTranscriptionModel() {
        pickModel("Transcription model", AppSettings.DEFAULT_TRANSCRIPTION_MODEL, OpenAiClient::transcriptionModels) {
            settings.transcriptionModel = it
        }
    }

    fun pickCleanupModel() {
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
        AlertDialog.Builder(activity)
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

    fun promptLanguages() {
        val input = editText("en, es", settings.languages.joinToString(", "), InputType.TYPE_CLASS_TEXT)
        AlertDialog.Builder(activity)
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
    fun promptVocabulary() {
        val input = editText(Vocabulary.EXAMPLES, settings.vocabulary.joinToString("\n"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 5
            maxLines = 12
            isVerticalScrollBarEnabled = true
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(activity)
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

    /** Edits your own cleanup prompt. Saving selects Custom; the presets are left alone. */
    fun promptCustomPrompt() {
        val input = editText("Prompt", settings.customPrompt,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(activity)
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
    fun promptFixPrompt() {
        val input = editText("Prompt", settings.fixPrompt,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(activity)
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
            AlertDialog.Builder(activity)
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
        AlertDialog.Builder(activity)
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
            activity.runOnUiThread {
                when {
                    ids != null && claude -> settings.cachedClaudeModelIds = ids
                    ids != null -> settings.cachedModelIds = ids
                    !silent -> toast("Could not load models; showing defaults")
                }
                then?.invoke(ids ?: emptyList())
            }
        }
    }

    companion object {
        private const val OTHER = "Other (type a model id)"
    }
}
