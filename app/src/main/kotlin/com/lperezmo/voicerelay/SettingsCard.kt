package com.lperezmo.voicerelay

import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** What every settings card needs: the stores, the view builders, the dialogs and a way to redraw the screen. */
class SettingsEnv(
    val activity: AppCompatActivity,
    val settings: AppSettings,
    val secrets: SecretStore,
    val usage: UsageStore,
    val ui: SettingsUi,
    val dialogs: SettingsDialogs,
    /** Redraws every card from the current settings. */
    val refresh: () -> Unit,
) {
    fun hasPerm(p: String) = ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED
    fun open(intent: Intent) = activity.startActivity(intent)
}

/** One card of the settings screen. It adds itself to the column when built, and redraws its rows in [refresh]. */
interface SettingsCard {
    fun refresh()
}
