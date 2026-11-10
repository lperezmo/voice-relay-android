package com.lperezmo.voicerelay

import android.Manifest
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * The settings screen: a column of cards. Each card builds and redraws its
 * own rows (see [SettingsCard]); this activity wires them up, owns the
 * permission requests, and redraws everything when it comes back to the front.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var env: SettingsEnv
    private lateinit var cards: List<SettingsCard>
    private lateinit var wakeCard: WakeWordCard

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge on every version, as Android 15+ enforces it: the insets listener below pads for the bars.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = AppSettings(this)
        val secrets = SecretStore(this)
        val usage = UsageStore(this)
        val ui = SettingsUi(this)
        val dialogs = SettingsDialogs(this, settings, secrets, usage, ui) { refresh() }
        env = SettingsEnv(this, settings, secrets, usage, ui, dialogs) { refresh() }

        val column = ui.column()
        column.addView(ui.titleRow(getString(R.string.app_name), "v${BuildConfig.VERSION_NAME}"))
        val top = listOf(StatusCards(env, column), SpeechCard(env, column), CleanupCard(env, column))
        wakeCard = WakeWordCard(env, column) { toggleWakeWord() }
        cards = top + wakeCard + listOf(
            FloatingButtonsCard(env, column),
            OtherAppsCard(env, column),
            UsageCard(env, column),
            AboutCard(env, column),
        )

        val scroll = ScrollView(this).apply {
            // The last card can scroll up from under the navigation bar.
            clipToPadding = false
            addView(FrameLayout(this@MainActivity).apply {
                addView(column, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL,
                ))
            })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ui.color(R.color.page))
            addView(scroll)
            // Keep the cards clear of the system bars and any cutout. The page colour fills the status
            // bar strip, so cards never scroll under the clock; they do pass under the navigation bar.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, 0)
                scroll.setPadding(0, 0, 0, bars.bottom)
                insets
            }
        })

        if (!env.hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == REQ_WAKE) {
            if (env.hasPerm(Manifest.permission.RECORD_AUDIO)) enableWakeWord()
            else env.ui.toast("The wake word needs the microphone")
        }
        refresh()
    }

    private fun refresh() {
        for (card in cards) card.refresh()
    }

    // --- Wake word ---

    private fun toggleWakeWord() {
        val settings = env.settings
        if (settings.wakeWordEnabled) {
            settings.wakeWordEnabled = false
            WakeWordService.stop(this)
            refresh()
            return
        }
        val missing = buildList {
            if (!env.hasPerm(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33 && !env.hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_WAKE)
            return
        }
        enableWakeWord()
    }

    private fun enableWakeWord() {
        env.settings.wakeWordEnabled = true
        env.settings.wakeWordSetUp = true
        WakeWordService.start(this, "settings switch")
        if (!Settings.canDrawOverlays(this)) env.dialogs.showOverlayDisclosure()
        refresh()
        wakeCard.refreshSoon()
    }

    companion object {
        private const val REQ_WAKE = 2
    }
}
