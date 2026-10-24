package com.lperezmo.voicerelay

/**
 * The text model behind dictation cleanup and the grammar fix: OpenAI's, or
 * Claude when it is chosen in settings. Speech to text is not here; it is
 * always OpenAI. Usage is counted against whichever model answered.
 */
class TextService(
    private val settings: AppSettings,
    private val secrets: SecretStore,
    private val usage: UsageStore,
) {
    /** What to tell the user before a grammar fix can run, or null when the key it needs is set. */
    fun missingKey(): String? = when {
        settings.claudeCleanup && secrets.getAnthropicKey().isBlank() -> "Set your Anthropic API key in Voice Relay"
        !settings.claudeCleanup && secrets.getApiKey().isBlank() -> "Set your OpenAI API key in Voice Relay"
        else -> null
    }

    /**
     * A cleaner for [prompt], with the vocabulary appended. With Claude chosen
     * but no Anthropic key, every call fails, so dictation keeps the raw
     * transcript instead of sending the text to a service you did not pick.
     */
    fun cleaner(prompt: String): DictationPipeline.Cleaner {
        val instructions = Prompts.forRequest(prompt, settings.vocabulary)
        val model = settings.activeCleanupModel
        if (settings.claudeCleanup) {
            val key = secrets.getAnthropicKey()
            if (key.isBlank()) return DictationPipeline.Cleaner { OpenAiClient.Result(null, "no Anthropic API key") }
            val claude = AnthropicClient(key)
            return DictationPipeline.Cleaner { text -> claude.cleanup(text, model, instructions).also { record(model, it) } }
        }
        val openAi = OpenAiClient(secrets.getApiKey())
        return DictationPipeline.Cleaner { text -> openAi.cleanup(text, model, instructions).also { record(model, it) } }
    }

    private fun record(model: String, result: OpenAiClient.Result) {
        if (result.ok) usage.recordText(model, result.inputTokens, result.outputTokens)
    }
}
