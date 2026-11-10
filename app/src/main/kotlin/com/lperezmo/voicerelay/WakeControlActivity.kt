package com.lperezmo.voicerelay

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Turns the wake word on or off from outside the app: Tasker, adb, or the
 * "tap to listen again" notification. Invisible, and gone as soon as it ran.
 *
 * It is an activity rather than a broadcast receiver on purpose: Android only
 * lets a microphone foreground service start while the app is in the
 * foreground, and a resumed activity is what makes it so. It also shows over
 * the lock screen, so a Tasker profile works while the phone is locked.
 *
 * CLAUDE_VOICE opens Claude's assistant straight into voice mode, the same
 * way the wake word does, for a Tasker gesture or button.
 *
 * Exported, so any app can send these actions. What that allows is bounded:
 * turning on only works after you turned the wake word on once in Voice Relay
 * (which is where the microphone permission is granted), and the only effects
 * are listening on the phone, opening Claude's assistant, and hiding or
 * showing the floating buttons. "Allow other apps to control Voice Relay"
 * ([AppSettings.externalControl]) turns all of it off. The app's own
 * notification comes in through [INTERNAL_ALIAS], which is not exported, so
 * it works either way.
 */
class WakeControlActivity : Activity() {

    companion object {
        private const val TAG = "VoiceRelay"
        /** Non-exported alias of this activity for the app's own PendingIntents. See the manifest. */
        const val INTERNAL_ALIAS = "com.lperezmo.voicerelay.WakeControlInternal"
        const val ACTION_WAKE_ON = "com.lperezmo.voicerelay.WAKE_ON"
        const val ACTION_WAKE_OFF = "com.lperezmo.voicerelay.WAKE_OFF"
        const val ACTION_WAKE_TOGGLE = "com.lperezmo.voicerelay.WAKE_TOGGLE"
        const val ACTION_CLAUDE_VOICE = "com.lperezmo.voicerelay.CLAUDE_VOICE"
        const val ACTION_BUTTONS_HIDE = "com.lperezmo.voicerelay.BUTTONS_HIDE"
        const val ACTION_BUTTONS_SHOW = "com.lperezmo.voicerelay.BUTTONS_SHOW"
        const val ACTION_BUTTONS_TOGGLE = "com.lperezmo.voicerelay.BUTTONS_TOGGLE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
    }

    override fun onResume() {
        super.onResume()
        handle(intent?.action)
        finish()
    }

    private fun handle(action: String?) {
        val settings = AppSettings(this)
        val internal = intent?.component?.className == INTERNAL_ALIAS
        if (!internal && !settings.externalControl) {
            Log.w(TAG, "Control intent ignored: other apps may not control Voice Relay (setting is off)")
            return
        }
        when (action) {
            ACTION_WAKE_ON -> on(settings)
            ACTION_WAKE_OFF -> off(settings)
            ACTION_WAKE_TOGGLE -> if (settings.wakeWordEnabled) off(settings) else on(settings)
            ACTION_CLAUDE_VOICE -> claudeVoice()
            ACTION_BUTTONS_HIDE -> buttons { true }
            ACTION_BUTTONS_SHOW -> buttons { false }
            ACTION_BUTTONS_TOGGLE -> buttons { !it }
            else -> Log.w(TAG, "Wake word: unknown control action")
        }
    }

    private fun on(settings: AppSettings) {
        if (!settings.wakeWordSetUp) {
            Log.w(TAG, "Wake word: external WAKE_ON ignored, not set up in the app yet")
            toast("Turn on the wake word in Voice Relay first")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Wake word: external WAKE_ON ignored, no microphone permission")
            toast("Voice Relay needs the microphone permission")
            return
        }
        settings.wakeWordEnabled = true
        WakeWordService.start(this, "control intent")
    }

    private fun off(settings: AppSettings) {
        settings.wakeWordEnabled = false
        WakeWordService.stop(this)
        Log.i(TAG, "Wake word: turned off by control intent")
    }

    private fun claudeVoice() {
        try {
            ClaudeVoice.open(this)
            Log.i(TAG, "Wake word: opened Claude from a control intent")
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "Wake word: CLAUDE_VOICE ignored, Claude is not installed")
            toast("Install the Claude app first")
        }
    }

    /** Hides or shows the floating buttons; [hide] gets whether they are hidden now. */
    private fun buttons(hide: (Boolean) -> Boolean) {
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Buttons: control intent ignored, the accessibility service is off")
            toast("Turn on Voice Relay in Accessibility settings first")
            return
        }
        service.setButtonsHidden(hide(service.buttonsHidden))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
