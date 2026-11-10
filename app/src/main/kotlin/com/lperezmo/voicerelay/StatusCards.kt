package com.lperezmo.voicerelay

import android.Manifest
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat

/**
 * The top of the screen: a tinted status card that says whether dictation is
 * ready, then the setup card with the two permissions and the switch that
 * shows or hides the floating buttons.
 */
class StatusCards(private val env: SettingsEnv, parent: LinearLayout) : SettingsCard {
    private val ui = env.ui
    private val settings = env.settings

    private val hero = ui.card(parent, null)
    private val heroDot = View(env.activity).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val heroText = TextView(env.activity).apply {
        text = "Checking..."
        textSize = 18f
        typeface = SettingsUi.MEDIUM
        setLineSpacing(0f, 1.1f)
    }

    private val micRow: SettingsUi.StatusRow
    private val accRow: SettingsUi.StatusRow
    private val buttonsRow: SettingsUi.SwitchRow

    init {
        hero.setPadding(ui.dp(SettingsUi.PAD_H), ui.dp(18), ui.dp(SettingsUi.PAD_H), ui.dp(20))
        hero.addView(TextView(env.activity).apply {
            text = "Status"
            textSize = 13f
            typeface = SettingsUi.MEDIUM
            setTextColor(ui.color(R.color.text_secondary))
            isAccessibilityHeading = true
        })
        hero.addView(LinearLayout(env.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, ui.dp(8), 0, 0)
            addView(heroDot, LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)).apply {
                marginEnd = ui.dp(12)
                topMargin = ui.dp(7)
            })
            addView(heroText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        val setup = ui.card(parent, "Setup")
        micRow = ui.statusRow("Microphone permission", "Checking...") {
            if (!env.hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(env.activity, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        setup.addView(micRow.view)
        accRow = ui.statusRow("Accessibility service", "Checking...") { env.dialogs.showAccessibilityDisclosure() }
        setup.addView(accRow.view)
        // Same toggle as the Quick Settings tile: hides every floating button until tapped again.
        buttonsRow = ui.switchRow("Show the floating buttons", "") {
            val service = WhisperAccessibilityService.instance
            if (service == null) env.dialogs.showAccessibilityDisclosure()
            else service.setButtonsHidden(!service.buttonsHidden)
            env.refresh()
        }
        setup.addView(buttonsRow.view)
    }

    override fun refresh() {
        val audio = env.hasPerm(Manifest.permission.RECORD_AUDIO)
        val service = WhisperAccessibilityService.instance
        val acc = service != null
        val apiKey = env.secrets.getApiKey()
        val anthropicKey = env.secrets.getAnthropicKey()

        micRow.show(audio, if (audio) "Granted" else "Tap to grant permission")
        accRow.show(acc, if (acc) "Enabled" else "Tap to enable in settings")
        buttonsRow.switch.isChecked = service != null && !service.buttonsHidden
        buttonsRow.subtitle.text = when {
            service == null -> "The accessibility service is off. Tap to turn it on."
            service.buttonsHidden -> "Hidden. Tap to bring back the mic and its pills."
            else -> "Tap to hide the mic and its pills, like the Quick Settings tile."
        }

        val ready = audio && acc && apiKey.isNotBlank()
        heroText.text = when {
            ready && settings.claudeCleanup && settings.cleanupEnabled && anthropicKey.isBlank() ->
                "Ready. Set your Anthropic API key below for Claude cleanup; until then the raw transcript is inserted."
            ready -> "Ready. Double-tap the floating button to dictate."
            apiKey.isBlank() -> "Set your OpenAI API key to start"
            else -> "Setup required"
        }
        val fg = ui.color(if (ready) R.color.ok else R.color.attention)
        heroText.setTextColor(fg)
        (heroDot.background as GradientDrawable).setColor(fg)
        hero.background = ui.rounded(ui.color(if (ready) R.color.ok_container else R.color.attention_container), 20)
    }
}
