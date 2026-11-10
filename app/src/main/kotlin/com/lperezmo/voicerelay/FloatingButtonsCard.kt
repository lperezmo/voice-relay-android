package com.lperezmo.voicerelay

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout

/** The extra floating buttons beside the mic: the arrow joystick, the volume presets pill and the Glyph pill. */
class FloatingButtonsCard(private val env: SettingsEnv, parent: LinearLayout) : SettingsCard {
    private val settings = env.settings
    private val activity = env.activity

    private val joystickRow: SettingsUi.SwitchRow
    private val autoSwitchRow: SettingsUi.SwitchRow
    private val volumeRow: SettingsUi.SwitchRow
    private val dndRow: SettingsUi.StatusRow
    private val exactRow: SettingsUi.StatusRow
    private val glyphRow: SettingsUi.SwitchRow

    init {
        val ui = env.ui
        val card = ui.card(parent, "Floating buttons")

        card.addView(ui.sectionTitle("Arrow joystick", rule = false))
        joystickRow = ui.switchRow("Show the arrow pad", "") {
            settings.joystickEnabled = !settings.joystickEnabled
            WhisperAccessibilityService.instance?.refreshJoystick()
            env.refresh()
        }
        card.addView(joystickRow.view)
        autoSwitchRow = ui.switchRow("Switch keyboard automatically", "") {
            settings.joystickAutoSwitch = !settings.joystickAutoSwitch
            env.refresh()
        }
        card.addView(autoSwitchRow.view)
        card.addView(ui.row(
            "Arrows keyboard",
            "Arrow keys can only reach another app through a keyboard. Enable Voice Relay arrows, then switch to it while you need the pad. Long-press the pad to switch.",
        ) { env.open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }.view)

        card.addView(ui.sectionTitle("Volume presets"))
        volumeRow = ui.switchRow("Show the volume pill", "") {
            settings.volumePillEnabled = !settings.volumePillEnabled
            WhisperAccessibilityService.instance?.refreshVolumePill()
            env.refresh()
        }
        card.addView(volumeRow.view)
        dndRow = ui.statusRow("Do Not Disturb access", "") {
            env.open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        card.addView(dndRow.view)
        exactRow = ui.statusRow("End the DND hour on time", "") {
            env.open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
        }
        card.addView(exactRow.view)

        card.addView(ui.sectionTitle("Glyph"))
        glyphRow = ui.switchRow("Show the Glyph pill", "") {
            settings.glyphPillEnabled = !settings.glyphPillEnabled
            WhisperAccessibilityService.instance?.refreshGlyphPill()
            env.refresh()
        }
        card.addView(glyphRow.view)
    }

    override fun refresh() {
        joystickRow.switch.isChecked = settings.joystickEnabled
        joystickRow.subtitle.text = if (settings.joystickEnabled)
            "A draggable pad that sends arrow keys. Push the middle knob; drag the ring to move it."
        else "Off. A floating pad for arrow keys, for remote desktop and terminals."
        autoSwitchRow.switch.isChecked = settings.joystickAutoSwitch
        autoSwitchRow.subtitle.text = if (settings.joystickAutoSwitch)
            "Touching the knob switches to the arrows keyboard, and your own comes back 4 seconds after the last arrow."
        else "Off. Long-press the pad to switch keyboards by hand."

        volumeRow.switch.isChecked = settings.volumePillEnabled
        volumeRow.subtitle.text = if (settings.volumePillEnabled)
            "Tap the pill above the mic, then draw a V on the track: dip down, slide to a preset, rise to apply."
        else "Off. Four one-gesture volume presets: Silent + DND for an hour, Mute, Medium, Loud."
        val volume = VolumeController(activity)
        dndRow.show(volume.hasDndAccess, if (volume.hasDndAccess) "Granted"
        else "Needed for the presets. Tap, then allow Voice Relay.")
        exactRow.show(volume.exactTimer, if (volume.exactTimer) "On: DND ends right at the hour"
        else "Off: DND may end up to 10 minutes late. Tap to allow alarms.")

        val glyphInstalled = GlyphPillView.glyphInstalled(activity)
        glyphRow.switch.isChecked = settings.glyphPillEnabled
        glyphRow.subtitle.text = when {
            !glyphInstalled -> "Glyph is not installed, so the pill is hidden."
            settings.glyphPillEnabled -> "Tap the pill below the mic, or pull it down, to open Glyph's canvas and draw a shape."
            else -> "Off. A pill below the mic that opens Glyph's shape canvas."
        }
    }
}
