package com.lperezmo.phonewhisper

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Fix grammar" in the text-selection menu of any app (ACTION_PROCESS_TEXT).
 * Editable selections are replaced in place by the calling app; read-only
 * selections get the fixed text on the clipboard. Needs no accessibility access.
 */
class FixGrammarActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val apiKey = SecretStore(this).getApiKey()
        when {
            text.isNullOrBlank() -> return finishWith("Nothing selected")
            apiKey.isBlank() -> return finishWith("Set your OpenAI API key in Phone Whisper")
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(this@FixGrammarActivity))
            addView(TextView(this@FixGrammarActivity).apply {
                this.text = "Fixing grammar..."
                textSize = 16f
                setPadding(pad / 2, 0, 0, 0)
            })
        })

        val settings = AppSettings(this)
        val client = OpenAiClient(apiKey)
        val model = settings.cleanupModel
        val instructions = Prompts.forRequest(Prompts.TYPED, settings.vocabulary)
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            val outcome = withContext(Dispatchers.IO) {
                DictationPipeline.fixTyped(text!!) { client.cleanup(it, model, instructions) }
            }
            // Metadata only, never the text.
            Log.i("PhoneWhisper", "Selection fix: ${text.length} chars, ${System.currentTimeMillis() - started} ms, " +
                "readOnly=$readOnly, " + when (outcome) {
                    is DictationPipeline.Outcome.Text -> if (outcome.text == text) "unchanged" else "fixed"
                    is DictationPipeline.Outcome.Failed -> "failed"
                    DictationPipeline.Outcome.NoSpeech -> "empty"
                })
            when (outcome) {
                is DictationPipeline.Outcome.Text -> if (readOnly) {
                    copy(outcome.text)
                    finishWith("Fixed text copied to clipboard")
                } else {
                    setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, outcome.text))
                    finish()
                }
                is DictationPipeline.Outcome.Failed -> finishWith("Fix failed: ${outcome.error.take(80)}")
                DictationPipeline.Outcome.NoSpeech -> finishWith("Nothing selected")
            }
        }
    }

    private fun copy(text: String) {
        val clip = ClipData.newPlainText("Phone Whisper", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setResult(RESULT_CANCELED)
        finish()
    }
}
