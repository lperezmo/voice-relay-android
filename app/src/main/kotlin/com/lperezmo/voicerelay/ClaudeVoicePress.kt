package com.lperezmo.voicerelay

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * After the wake word opens Claude on a new chat: polls Claude's window for the control
 * that starts voice ([WakeWord.VOICE_MODE_LABELS]) and presses it, for up to
 * [WakeWord.VOICE_MODE_WAIT_MS]. If it never shows, Claude stays in text chat.
 *
 * The floating buttons sit where Claude puts its voice button, so they step aside
 * (through [buttonsHidden] and [setButtonsHidden]) until the press is done.
 * [forService] wires it to the accessibility service; the constructor takes plain
 * functions so the timing can be tested on the JVM.
 */
class ClaudeVoicePress(
    /** Presses the voice control: null while it is not on screen, else whether the click took. */
    private val pressVoiceButton: () -> Boolean?,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val now: () -> Long,
    private val buttonsHidden: () -> Boolean,
    private val setButtonsHidden: (Boolean) -> Unit,
    private val logInfo: (String) -> Unit,
    private val logWarn: (String) -> Unit,
) {
    /** True while the buttons are hidden only to keep them clear of the Voice Mode press. */
    private var hiddenForVoicePress = false
    private var deadline = 0L
    private val poll = Runnable { tryVoiceMode() }
    private val restore = Runnable {
        if (hiddenForVoicePress) {
            hiddenForVoicePress = false
            if (buttonsHidden()) setButtonsHidden(false)
        }
    }

    fun start() {
        // The floating buttons sit where Claude puts its voice button, so they step
        // aside until the press is done.
        if (!buttonsHidden()) {
            hiddenForVoicePress = true
            setButtonsHidden(true)
        }
        deadline = now() + WakeWord.VOICE_MODE_WAIT_MS
        postDelayed(poll, 200)
    }

    /** The service is going away: stop polling, and do not touch the buttons later. */
    fun cancel() {
        removeCallbacks(poll)
        removeCallbacks(restore)
        hiddenForVoicePress = false
    }

    private fun tryVoiceMode() {
        val tapped = pressVoiceButton()
        if (tapped != null) {
            logInfo(if (tapped) "Wake word: started Claude voice mode" else "Wake word: Voice Mode button would not click")
            showButtonsAfterVoicePress()
            return
        }
        if (now() >= deadline) {
            logWarn("Wake word: Claude opened but no Voice Mode button showed; it stays in text chat")
            showButtonsAfterVoicePress()
            return
        }
        postDelayed(poll, 150)
    }

    /** Brings back the buttons [start] hid, once Claude's screen has changed. */
    private fun showButtonsAfterVoicePress() {
        postDelayed(restore, 800)
    }

    companion object {
        private const val TAG = "VoiceRelay"

        fun forService(
            service: AccessibilityService,
            handler: Handler,
            buttonsHidden: () -> Boolean,
            setButtonsHidden: (Boolean) -> Unit,
        ) = ClaudeVoicePress(
            pressVoiceButton = { findVoiceModeButton(service)?.let { clickSelfOrParent(it) } },
            postDelayed = { r, ms -> handler.postDelayed(r, ms) },
            removeCallbacks = { handler.removeCallbacks(it) },
            now = SystemClock::uptimeMillis,
            buttonsHidden = buttonsHidden,
            setButtonsHidden = setButtonsHidden,
            logInfo = { Log.i(TAG, it) },
            logWarn = { Log.w(TAG, it) },
        )

        private fun findVoiceModeButton(service: AccessibilityService): AccessibilityNodeInfo? {
            val roots = service.windows.mapNotNull { it.root }.filter { it.packageName == WakeWord.CLAUDE_PACKAGE }
                .ifEmpty { listOfNotNull(service.rootInActiveWindow?.takeIf { it.packageName == WakeWord.CLAUDE_PACKAGE }) }
            // A walk rather than findAccessibilityNodeInfosByText, which misses Compose content
            // descriptions (Claude's screens are Compose).
            for (root in roots) findLabelled(root, 0)?.let { return it }
            return null
        }

        private fun findLabelled(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (WakeWord.isVoiceModeLabel(node.contentDescription) || WakeWord.isVoiceModeLabel(node.text)) return node
            if (depth >= 40) return null
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findLabelled(child, depth + 1)?.let { return it }
            }
            return null
        }

        /** The labelled node is often an icon inside the clickable button, so walk up to it. */
        private fun clickSelfOrParent(node: AccessibilityNodeInfo): Boolean {
            var n: AccessibilityNodeInfo? = node
            var depth = 0
            while (n != null && depth < 4) {
                if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                n = n.parent
                depth++
            }
            return false
        }
    }
}
