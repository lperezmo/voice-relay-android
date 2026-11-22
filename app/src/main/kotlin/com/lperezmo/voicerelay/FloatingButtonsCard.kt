package com.lperezmo.voicerelay

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.core.app.ActivityCompat
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import com.lperezmo.voicerelay.glyph.GlyphTileService
import com.lperezmo.voicerelay.glyph.SettingsActivity as GlyphSettingsActivity

/** The extra floating buttons beside the mic: the arrow joystick, the volume presets pill, the timer pill and the Glyph pill. */
class FloatingButtonsCard(private val env: SettingsEnv, parent: LinearLayout) : SettingsCard {
    private val settings = env.settings
    private val activity = env.activity

    private val joystickRow: SettingsUi.SwitchRow
    private val autoSwitchRow: SettingsUi.SwitchRow
    private val volumeRow: SettingsUi.SwitchRow
    private val dndRow: SettingsUi.StatusRow
    private val exactRow: SettingsUi.StatusRow
    private val timerRow: SettingsUi.SwitchRow
    private val timerPositionTitle: View
    private val timerBesideRow: SettingsUi.RadioRow
    private val timerAboveRow: SettingsUi.RadioRow
    private val timerNotifyRow: SettingsUi.StatusRow
    private val timerExactRow: SettingsUi.StatusRow
    private val glyphRow: SettingsUi.SwitchRow
    private val glyphBuiltInRow: SettingsUi.SwitchRow
    private val glyphShapesRow: SettingsUi.Row
    private val askPageRow: SettingsUi.SwitchRow
    private val askPageKeyRow: SettingsUi.Row

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
            openExactAlarms()
        }
        card.addView(exactRow.view)

        card.addView(ui.sectionTitle("Timers"))
        timerRow = ui.switchRow("Timer pill", "") {
            settings.timerPillEnabled = !settings.timerPillEnabled
            WhisperAccessibilityService.instance?.refreshTimerPill()
            env.refresh()
            // A finished timer rings through a notification with a Stop button.
            if (settings.timerPillEnabled && Build.VERSION.SDK_INT >= 33 && !env.hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_TIMER_NOTIFICATIONS)
            }
        }
        card.addView(timerRow.view)
        timerPositionTitle = ui.note("Timer pill position")
        card.addView(timerPositionTitle)
        fun positionRow(title: String, subtitle: String, position: String) = ui.radioRow(title, subtitle) {
            settings.timerPillPosition = position
            WhisperAccessibilityService.instance?.refreshTimerPill()
            env.refresh()
        }.also { card.addView(it.view) }
        timerBesideRow = positionRow(
            "Beside the button", "On the side facing the middle of the screen, level with the button.", AppSettings.TIMER_BESIDE,
        )
        timerAboveRow = positionRow(
            "Above the volume pill", "In the volume pill's column, so nothing sticks out toward the middle.", AppSettings.TIMER_ABOVE,
        )
        timerNotifyRow = ui.statusRow("Ring with a Stop button", "") {
            env.open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            )
        }
        card.addView(timerNotifyRow.view)
        timerExactRow = ui.statusRow("Ring on time", "") {
            openExactAlarms()
        }
        card.addView(timerExactRow.view)

        card.addView(ui.sectionTitle("Glyph"))
        glyphRow = ui.switchRow("Show the Glyph pill", "") {
            settings.glyphPillEnabled = !settings.glyphPillEnabled
            WhisperAccessibilityService.instance?.refreshGlyphPill()
            env.refresh()
        }
        card.addView(glyphRow.view)
        glyphBuiltInRow = ui.switchRow("Use the built-in canvas", "") {
            settings.glyphBuiltIn = !settings.glyphBuiltIn
            GlyphTileService.setEnabled(activity, settings.glyphBuiltIn)
            WhisperAccessibilityService.instance?.refreshGlyphPill()
            env.refresh()
        }
        card.addView(glyphBuiltInRow.view)
        glyphShapesRow = ui.row(
            "Shapes and settings",
            "Teach shapes, pick what each one does, and tune recognition.",
        ) { env.open(Intent(activity, GlyphSettingsActivity::class.java)) }
        card.addView(glyphShapesRow.view)
        askPageRow = ui.switchRow("Ask the Page", "") {
            settings.askPageEnabled = !settings.askPageEnabled
            env.refresh()
            if (settings.askPageEnabled && env.secrets.getAnthropicKey().isBlank()) env.dialogs.promptAnthropicKey()
        }
        card.addView(askPageRow.view)
        askPageKeyRow = ui.row("Anthropic API key", "") { env.dialogs.promptAnthropicKey() }
        card.addView(askPageKeyRow.view)
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

        val timerOn = settings.timerPillEnabled
        timerRow.switch.isChecked = timerOn
        timerRow.subtitle.text = if (timerOn)
            "Hold the pill and slide to 5, 10, 15, 30 or your own time. Tap it to see what is running, add a minute or stop one."
        else "Off. A pill by the mic for kitchen timers: hold to start one, tap to see and stop them."
        val timerDetail = if (timerOn) View.VISIBLE else View.GONE
        timerPositionTitle.visibility = timerDetail
        timerBesideRow.view.visibility = timerDetail
        timerAboveRow.view.visibility = timerDetail
        timerBesideRow.radio.isChecked = settings.timerPillPosition == AppSettings.TIMER_BESIDE
        timerAboveRow.radio.isChecked = settings.timerPillPosition == AppSettings.TIMER_ABOVE
        val timers = TimerController.get(activity)
        timerNotifyRow.view.visibility = timerDetail
        timerNotifyRow.show(timers.canNotify, if (timers.canNotify) "On: a finished timer rings until you tap Stop"
        else "Notifications are off or silenced for timers, so a finished one only rings for a minute. Tap to allow them.")
        timerExactRow.view.visibility = timerDetail
        timerExactRow.show(timers.exactAlarms, if (timers.exactAlarms) "On: timers ring right when they end"
        else "Off: with the screen off a timer may ring late, by many minutes for a long one. Tap to allow alarms.")

        val glyphInstalled = GlyphPillView.glyphInstalled(activity)
        val builtIn = settings.glyphBuiltIn
        glyphRow.switch.isChecked = settings.glyphPillEnabled
        glyphRow.subtitle.text = when {
            builtIn && settings.glyphPillEnabled -> "Tap the pill below the mic, or pull it down, to open the shape canvas and draw a shape."
            builtIn -> "Off. A pill below the mic that opens the shape canvas."
            !glyphInstalled -> "Glyph is not installed, so the pill is hidden."
            settings.glyphPillEnabled -> "Tap the pill below the mic, or pull it down, to open Glyph's canvas and draw a shape."
            else -> "Off. A pill below the mic that opens Glyph's shape canvas."
        }
        glyphBuiltInRow.switch.isChecked = builtIn
        glyphBuiltInRow.subtitle.text = if (builtIn)
            "On (the default). The pill opens Voice Relay's own canvas; the Glyph app is not needed."
        else "Off. The pill opens the Glyph app's canvas. Turn on to draw shapes without it."
        glyphShapesRow.view.visibility = if (builtIn) View.VISIBLE else View.GONE
        // The launcher icon and the tile follow the switch; this also catches installs
        // where the switch was turned on before they existed.
        GlyphTileService.setEnabled(activity, builtIn)

        val askPage = settings.askPageEnabled
        val anthropicKey = env.secrets.getAnthropicKey()
        askPageRow.view.visibility = if (builtIn) View.VISIBLE else View.GONE
        askPageRow.switch.isChecked = askPage
        askPageRow.subtitle.text = when {
            !askPage -> "Off. Draw ? on the canvas and write a question; Claude answers on the page."
            anthropicKey.isBlank() -> "On, but it needs your Anthropic API key below."
            else -> "Draw ? on the canvas, write a question and circle what it is about. " +
                "Your handwriting, the circled parts of the screen (or the whole screen, if you tap its button) and the app's name go to Anthropic."
        }
        askPageKeyRow.view.visibility = if (builtIn && askPage) View.VISIBLE else View.GONE
        askPageKeyRow.subtitle.text = when {
            anthropicKey.isBlank() -> "Tap to set. Ask the Page uses your own Anthropic key."
            anthropicKey.length > 12 -> "${anthropicKey.take(7)}...${anthropicKey.takeLast(4)}"
            else -> "Set"
        }
    }

    /** The system screen that allows exact alarms. Only Android 12 has anything to allow; later it is already on. */
    private fun openExactAlarms() {
        if (TimerController.get(activity).exactAlarms) return
        try {
            env.open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }

    private companion object {
        /** Clear of MainActivity's own request codes. */
        const val REQ_TIMER_NOTIFICATIONS = 31
    }
}
