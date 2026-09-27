package com.lperezmo.voicerelay

import android.Manifest
import android.app.Activity
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
 * Exported, so any app can send these three actions. What that allows is
 * bounded: turning on only works after you turned the wake word on once in
 * Voice Relay (which is where the microphone permission is granted), and the
 * only effect is listening on the phone and opening Claude's assistant.
 */
class WakeControlActivity : Activity() {

    companion object {
        private const val TAG = "VoiceRelay"
        const val ACTION_WAKE_ON = "com.lperezmo.voicerelay.WAKE_ON"
        const val ACTION_WAKE_OFF = "com.lperezmo.voicerelay.WAKE_OFF"
        const val ACTION_WAKE_TOGGLE = "com.lperezmo.voicerelay.WAKE_TOGGLE"
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
        when (action) {
            ACTION_WAKE_ON -> on(settings)
            ACTION_WAKE_OFF -> off(settings)
            ACTION_WAKE_TOGGLE -> if (settings.wakeWordEnabled) off(settings) else on(settings)
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
