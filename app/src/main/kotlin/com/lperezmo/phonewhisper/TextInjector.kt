package com.lperezmo.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Puts dictated text where the user is typing.
 *
 * Order of attempts on the focused input:
 *  1. ACTION_SET_TEXT at the cursor, for ordinary editable fields. Does not
 *     touch the clipboard.
 *  2. Paste (the node's own "paste" custom action, then ACTION_PASTE) for
 *     surfaces such as Termux's TerminalView or password fields. This needs
 *     the text on the clipboard, marked sensitive.
 *
 * With no focused input at all, the text is copied to the clipboard so it can
 * be pasted by hand.
 */
class TextInjector(private val service: AccessibilityService) {

    enum class Outcome { INSERTED, PASTED, COPIED_NO_FIELD, COPIED_FAILED }

    fun inject(text: String): Outcome {
        val target = findFocusedInput()
        if (target == null) {
            copyToClipboard(text)
            return Outcome.COPIED_NO_FIELD
        }
        logNode("Target", target)
        if (trySetText(target, text)) return Outcome.INSERTED
        copyToClipboard(text)
        return if (tryPaste(target)) Outcome.PASTED else Outcome.COPIED_FAILED
    }

    /** The node with input focus in the active window, if it can take text. */
    private fun findFocusedInput(): AccessibilityNodeInfo? {
        val roots = buildList {
            service.rootInActiveWindow?.let { add(it) }
            service.windows?.filter { it.isActive || it.isFocused }?.mapNotNull { it.root }?.let { addAll(it) }
        }
        for (root in roots) {
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (acceptsText(focus)) return focus
        }
        return null
    }

    private fun acceptsText(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        return node.isEditable || cls.contains("EditText") || cls.contains("TerminalView") || pasteAction(node) != null
    }

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Password fields report masked text, so rebuilding their content would corrupt it.
        if (!node.isEditable || node.isPassword) return false
        // An empty field reports its hint as its text; treat that as empty.
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val updated = insertAtSelection(current, node.textSelectionStart, node.textSelectionEnd, text)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        // Put the cursor after the inserted text instead of wherever the app resets it.
        val cursor = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, updated.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, updated.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursor)
        return true
    }

    private fun tryPaste(node: AccessibilityNodeInfo): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        pasteAction(node)?.let { if (node.performAction(it.id)) return true }
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun pasteAction(node: AccessibilityNodeInfo) = node.actionList.firstOrNull {
        it.label?.toString()?.contains("paste", ignoreCase = true) == true
    }

    private fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("Phone Whisper", text)
        // Keeps the dictation out of clipboard previews and keyboard clipboard history.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    /** Structure only: never logs the field's text, which is the user's own writing. */
    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} editable=${node.isEditable} " +
                "password=${node.isPassword} actions=${node.actionList.joinToString { it.label?.toString() ?: it.id.toString() }}"
        )
    }

    data class Insertion(val text: String, val cursor: Int)

    companion object {
        private const val TAG = "PhoneWhisper"

        /**
         * Replace the selection (or insert at the cursor, or append when there is
         * none). Adds a separating space when dictation lands right after a word.
         */
        fun insertAtSelection(current: String, selStart: Int, selEnd: Int, text: String): Insertion {
            val start = if (selStart in 0..current.length) selStart else current.length
            val end = if (selEnd in 0..current.length) selEnd else start
            val lo = minOf(start, end)
            val hi = maxOf(start, end)
            val before = current.getOrNull(lo - 1)
            val piece = if (before != null && !before.isWhitespace() && text.firstOrNull()?.isLetterOrDigit() == true) " $text" else text
            return Insertion(current.replaceRange(lo, hi, piece), lo + piece.length)
        }
    }
}
