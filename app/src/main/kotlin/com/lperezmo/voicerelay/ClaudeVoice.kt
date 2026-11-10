package com.lperezmo.voicerelay

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens the Claude app ready to talk: a new chat by link ([WakeWord.CLAUDE_NEW_CHAT]), then
 * the accessibility service presses the control that starts voice. Claude offers no way for
 * another app to start voice directly.
 */
object ClaudeVoice {
    /** Throws ActivityNotFoundException when the Claude app is not installed. */
    fun open(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(WakeWord.CLAUDE_NEW_CHAT))
                .setPackage(WakeWord.CLAUDE_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        WhisperAccessibilityService.instance?.tapClaudeVoiceMode()
    }
}
