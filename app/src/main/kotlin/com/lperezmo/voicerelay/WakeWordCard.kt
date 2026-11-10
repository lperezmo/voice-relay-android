package com.lperezmo.voicerelay

import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.slider.Slider

/**
 * The wake word: the main switch, and while it is on the phrase, sensitivity,
 * resuming music and the overlay permission. [onToggle] asks for permissions
 * first, so the activity owns it.
 */
class WakeWordCard(private val env: SettingsEnv, parent: LinearLayout, onToggle: () -> Unit) : SettingsCard {
    private val settings = env.settings
    private val activity = env.activity

    private val wakeRow: SettingsUi.SwitchRow
    private val container: LinearLayout
    private val phraseRow: SettingsUi.Row
    private val sensitivityRow: SettingsUi.Row
    private val slider: Slider
    private val resumeRow: SettingsUi.SwitchRow
    private val overlayRow: SettingsUi.StatusRow

    init {
        val ui = env.ui
        val card = ui.card(parent, "Wake word")
        wakeRow = ui.switchRow("Wake word", "") { onToggle() }
        card.addView(wakeRow.view)

        container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        phraseRow = ui.row("Wake phrase", "") { env.dialogs.promptWakePhrase() }
        container.addView(phraseRow.view)
        sensitivityRow = ui.row("Sensitivity", "")
        sensitivityRow.view.setPadding(ui.dp(SettingsUi.PAD_H), ui.dp(12), ui.dp(SettingsUi.PAD_H), 0)
        container.addView(sensitivityRow.view)
        slider = Slider(activity).apply {
            valueFrom = WakeWord.MIN_SENSITIVITY.toFloat()
            valueTo = WakeWord.MAX_SENSITIVITY.toFloat()
            stepSize = 1f
            value = settings.wakeSensitivity.toFloat()
            contentDescription = "Sensitivity"
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    settings.wakeSensitivity = v.toInt()
                    refresh()
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) = WakeWordService.reload(activity)
            })
        }
        container.addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ui.dp(4)
            marginEnd = ui.dp(4)
        })
        resumeRow = ui.switchRow(
            "Resume music after Claude",
            "If music was playing when you said the wake word, press play again once Claude is done.",
        ) {
            settings.wakeResumeMedia = !settings.wakeResumeMedia
            env.refresh()
        }
        container.addView(resumeRow.view)
        overlayRow = ui.statusRow("Display over other apps", "") { env.dialogs.showOverlayDisclosure() }
        container.addView(overlayRow.view)
        card.addView(container)
    }

    /** The service reports in a moment after it starts; show that in the subtitle. */
    fun refreshSoon() {
        wakeRow.subtitle.postDelayed({ refresh() }, 1000)
    }

    override fun refresh() {
        val on = settings.wakeWordEnabled
        // What the listener really uses: the default if the saved phrase no longer fits the model.
        val wake = WakeWordService.effectiveWake(activity)
        val phrase = wake.phrase
        val keywords = wake.keywords
        wakeRow.switch.isChecked = on
        container.visibility = if (on) View.VISIBLE else View.GONE
        wakeRow.title.text = "Wake word: $phrase"
        wakeRow.subtitle.text = when {
            !on -> "Off. Say $phrase anywhere to open Claude's voice assistant. " +
                "Listens on the phone only; no audio leaves it."
            WakeWordService.running -> "On: listening everywhere." +
                (if (WakeWord.hasAccentVariants(keywords)) " Cloud and clod for claude work too." else "") +
                " A notification shows while it listens."
            else -> "On, but not listening right now. Turn it off and on again to restart."
        }
        phraseRow.subtitle.text = (if (wake.fellBack) "\"${settings.wakePhrase}\" no longer fits the wake word model, " +
            "so it listens for $phrase. " else "") +
            "\"$phrase\": ${keywords.first().phones.joinToString(" ")}" +
            (if (keywords.size > 1) " and ${keywords.size - 1} more way${if (keywords.size > 2) "s" else ""} to say it" else "") +
            (if (!wake.fellBack && settings.wakePhones.isNotEmpty()) ", phones typed by hand" else "") + ". Tap to change."
        val level = settings.wakeSensitivity
        sensitivityRow.subtitle.text = "Level $level of ${WakeWord.MAX_SENSITIVITY} (threshold %.2f). ".format(WakeWord.threshold(level)) +
            "Higher catches more, and triggers by mistake more."
        if (slider.value.toInt() != level) slider.value = level.toFloat()
        resumeRow.switch.isChecked = settings.wakeResumeMedia
        val overlay = Settings.canDrawOverlays(activity)
        overlayRow.show(
            overlay,
            if (overlay) "Allowed: the wake word can open Claude from anywhere"
            else "Needed to open Claude from other apps and the lock screen. Tap to allow.",
        )
    }
}
