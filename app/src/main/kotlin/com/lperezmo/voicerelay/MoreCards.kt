package com.lperezmo.voicerelay

import android.content.Intent
import android.widget.LinearLayout

/** Lets Tasker, adb or another app drive Voice Relay. Shown with the wake word off too: the same intents hide and show the buttons. */
class OtherAppsCard(private val env: SettingsEnv, parent: LinearLayout) : SettingsCard {
    private val row: SettingsUi.SwitchRow

    init {
        val card = env.ui.card(parent, "Other apps")
        row = env.ui.switchRow(
            "Allow other apps to control Voice Relay",
            "Lets Tasker, adb or any other app turn the wake word on or off, open Claude in voice mode, " +
                "and hide or show the floating buttons.",
        ) {
            env.settings.externalControl = !env.settings.externalControl
            env.refresh()
        }
        card.addView(row.view)
    }

    override fun refresh() {
        row.switch.isChecked = env.settings.externalControl
    }
}

/** What dictation has cost this month and since the last reset, estimated on the phone. */
class UsageCard(private val env: SettingsEnv, parent: LinearLayout) : SettingsCard {
    private val monthRow: SettingsUi.Row
    private val totalRow: SettingsUi.Row

    init {
        val ui = env.ui
        val card = ui.card(parent, "Usage (estimate)")
        monthRow = ui.row("This month", "")
        card.addView(monthRow.view)
        totalRow = ui.row("Since reset", "") { env.dialogs.confirmUsageReset() }
        card.addView(totalRow.view)
        card.addView(ui.note(
            "Counted on this phone from list prices. Your OpenAI usage page is the actual bill. " +
                "Tap Since reset to start over."
        ))
    }

    override fun refresh() {
        val usage = env.usage
        monthRow.subtitle.text = usage.thisMonth().summary()
        val since = usage.since.takeIf { it > 0 }?.let {
            " (since " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)) + ")"
        }.orEmpty()
        totalRow.subtitle.text = usage.allTime().summary() + since
    }
}

/** The version and the open-source licenses. */
class AboutCard(private val env: SettingsEnv, parent: LinearLayout) : SettingsCard {
    init {
        val card = env.ui.card(parent, "About")
        card.addView(env.ui.row(
            "Open-source licenses",
            "Voice Relay ${BuildConfig.VERSION_NAME}. Apache License 2.0, and the notices for the software it includes.",
        ) { env.open(Intent(env.activity, LicensesActivity::class.java)) }.view)
    }

    override fun refresh() {}
}
