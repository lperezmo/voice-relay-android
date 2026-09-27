package com.lperezmo.voicerelay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay

/**
 * Puts dictated text where the user is typing.
 *
 * Finding the field: live input focus (service-wide, then every interactive
 * window, retried briefly because web views build their tree lazily). When
 * focus is gone by the time a long dictation is transcribed (the app
 * re-rendered, the keyboard closed), the field that was focused when recording
 * started, or the last one the user typed in, is used if it is still on screen
 * in the app that has focus.
 *
 * Order of attempts on that field:
 *  1. ACTION_SET_TEXT at the cursor, for ordinary editable fields. Does not
 *     touch the clipboard. Retried once after refocusing the field.
 *  2. Paste (the node's own "paste" custom action, then ACTION_PASTE) for
 *     surfaces such as Termux's TerminalView or password fields. This needs
 *     the text on the clipboard, marked sensitive.
 *
 * With no field at all, the text is copied to the clipboard so it can be
 * pasted by hand.
 */
class TextInjector(private val service: AccessibilityService) {

    enum class Outcome { INSERTED, PASTED, COPIED_NO_FIELD, COPIED_FAILED }

    /** Text of the focused editable field and the range to fix: the selection, or all of it. */
    class FieldRange(val node: AccessibilityNodeInfo, val fullText: String, val start: Int, val end: Int) {
        val target get() = fullText.substring(start, end)
    }

    private enum class Source { GLOBAL_FOCUS, WINDOW_FOCUS, FOCUSED_DESCENDANT, LAST_EVENT, START_SNAPSHOT }
    private class Candidate(val node: AccessibilityNodeInfo, val source: Source)
    private class Remembered(val node: AccessibilityNodeInfo, val at: Long)

    /** Last editable node the user focused or moved the cursor in, from accessibility events. */
    @Volatile private var lastEvent: Remembered? = null
    /** Focused input when the current recording started. */
    @Volatile private var startSnapshot: Remembered? = null

    /** Feed every accessibility event here (the service's onAccessibilityEvent). */
    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) return
        if (event.packageName?.toString() == service.packageName) return
        val src = event.source ?: return
        if (!acceptsText(src) || isImeWindow(src)) return
        lastEvent = Remembered(src, SystemClock.uptimeMillis())
    }

    /** Call when recording starts, so the target survives focus loss during a long dictation. */
    fun rememberFocus() {
        val found = findLiveFocus()
        startSnapshot = found?.let { Remembered(it.node, SystemClock.uptimeMillis()) }
        Log.i(TAG, "Focus at start: source=${found?.source} ${describe(found?.node)}")
    }

    fun forgetSnapshot() {
        startSnapshot = null
    }

    /** The range a grammar fix should apply to, or null when no editable, readable field is focused. */
    fun readFocusedRange(): FieldRange? {
        val node = findLiveFocus()?.node ?: return null
        node.refresh()
        if (!node.isEditable || node.isPassword) return null
        val text = fieldText(node)
        if (text.isBlank()) return null
        val (start, end) = fixRange(text.length, node.textSelectionStart, node.textSelectionEnd)
        return FieldRange(node, text, start, end)
    }

    /**
     * Moves the caret in the focused field by one character (left, right) or one
     * line (up, down), for the arrow joystick when no keyboard is carrying keys.
     * Only works in ordinary text fields: a remote desktop session has none.
     */
    fun moveCaret(keyCode: Int): Boolean {
        val node = findLiveFocus()?.node ?: return false
        node.refresh()
        if (!node.isEditable) return false
        val granularity = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
            else -> return false
        }
        val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        val action = if (forward) AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        else AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
        }
        return node.performAction(action, args)
    }

    /** Swaps [range] for [replacement]; only if the field still holds the text that was read. */
    fun replace(range: FieldRange, replacement: String): Boolean {
        lastEdit = null
        range.node.refresh()
        if (fieldText(range.node) != range.fullText) return false
        val updated = range.fullText.replaceRange(range.start, range.end, replacement)
        if (!setTextAndCursor(range.node, updated, range.start + replacement.length)) return false
        lastEdit = Edit(range.node, range.fullText, updated, range.start, range.end)
        return true
    }

    suspend fun inject(text: String): Outcome {
        lastEdit = null
        var target: Candidate? = null
        var attempts = 0
        while (target == null && attempts < LOOKUP_ATTEMPTS) {
            if (attempts > 0) delay(LOOKUP_RETRY_MS)
            attempts++
            target = findLiveFocus()
        }
        if (target == null) target = rememberedFallback()
        startSnapshot = null
        Log.i(TAG, "Insert: attempts=$attempts source=${target?.source} ${describe(target?.node)} windows=[${windowsSummary()}]")

        if (target == null) {
            copyToClipboard(text)
            return Outcome.COPIED_NO_FIELD
        }
        val node = target.node
        logNode("Target", node)
        if (trySetText(node, text)) return Outcome.INSERTED
        // Some fields refuse SET_TEXT unless focused (a remembered node that lost focus).
        if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            if (trySetText(node, text)) {
                Log.i(TAG, "Insert: set-text succeeded after refocus")
                return Outcome.INSERTED
            }
        }
        copyToClipboard(text)
        val pasted = tryPaste(node)
        Log.i(TAG, "Insert: set-text failed, paste=$pasted")
        return if (pasted) Outcome.PASTED else Outcome.COPIED_FAILED
    }

    // --- Live typing ---

    /**
     * Words typed into a field while the user is still talking (live mode). Each
     * [update] rewrites the dictated span, and [finish] swaps in the final text.
     * If the field changes under it (the user typed, the app reset it), the draft
     * stops touching the field and whatever was typed so far stays there.
     */
    inner class LiveDraft internal constructor(
        private val node: AccessibilityNodeInfo,
        private val original: String,
        private val selStart: Int,
        private val selEnd: Int,
    ) {
        private var written = original
        var active = true
            private set
        /** Characters of dictation currently in the field. */
        var typed = 0
            private set

        fun update(text: String): Boolean {
            if (!active) return false
            node.refresh()
            if (fieldText(node) != written) {
                active = false
                Log.i(TAG, "Live typing stopped: the field changed")
                return false
            }
            val ins = insertAtSelection(original, selStart, selEnd, text)
            if (!setTextAndCursor(node, ins.text, ins.cursor)) {
                active = false
                Log.i(TAG, "Live typing stopped: set-text refused")
                return false
            }
            written = ins.text
            typed = text.length
            return true
        }

        /** Replaces the partial words with [text]; undo then restores the field as it was before dictating. */
        fun finish(text: String): Boolean {
            if (!update(text)) return false
            active = false
            lastEdit = Edit(node, original, written, selStart, selEnd)
            return true
        }
    }

    /** Starts live typing into the field that was focused when recording started, if it takes text. */
    fun beginDraft(): LiveDraft? {
        val node = startSnapshot?.node ?: return null
        node.refresh()
        if (!node.isEditable || node.isPassword) return null
        val text = fieldText(node)
        fun valid(i: Int) = if (i in 0..text.length) i else text.length
        return LiveDraft(node, text, valid(node.textSelectionStart), valid(node.textSelectionEnd))
    }

    // --- Undo ---

    /** The last change made to a field, kept so it can be reverted. */
    private class Edit(val node: AccessibilityNodeInfo, val before: String, val after: String, val selStart: Int, val selEnd: Int)

    private var lastEdit: Edit? = null

    enum class UndoResult { UNDONE, CHANGED_SINCE, NOTHING }

    val canUndo get() = lastEdit != null

    /**
     * Puts the field back the way it was before the last dictation or grammar
     * fix, with the original selection. Refused when the field has been edited
     * since, so undo never throws away later typing.
     */
    fun undo(): UndoResult {
        val edit = lastEdit ?: return UndoResult.NOTHING
        lastEdit = null
        if (!edit.node.refresh() || fieldText(edit.node) != edit.after) return UndoResult.CHANGED_SINCE
        return if (setTextAndSelection(edit.node, edit.before, edit.selStart, edit.selEnd)) UndoResult.UNDONE
        else UndoResult.CHANGED_SINCE
    }

    // --- Finding the field ---

    /** Live input focus: service-wide first, then every interactive window, focused and active first. */
    private fun findLiveFocus(): Candidate? {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { f ->
            if (!isOwnOrIme(f)) resolveEditable(f)?.let {
                return Candidate(it, if (it == f) Source.GLOBAL_FOCUS else Source.FOCUSED_DESCENDANT)
            }
        }
        val roots = buildList {
            rankedWindows().forEach { w -> w.root?.let { add(it) } }
            service.rootInActiveWindow?.let { add(it) }
        }
        for (root in roots) {
            if (root.packageName?.toString() == service.packageName) continue
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            resolveEditable(f)?.let {
                return Candidate(it, if (it == f) Source.WINDOW_FOCUS else Source.FOCUSED_DESCENDANT)
            }
        }
        return null
    }

    private fun rankedWindows(): List<AccessibilityWindowInfo> =
        service.windows.orEmpty()
            .filter {
                it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    it.type != AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
            }
            .sortedBy {
                when {
                    it.isFocused -> 0
                    it.isActive -> 1
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION -> 2
                    else -> 3 // System windows last; notification quick reply lives there.
                }
            }

    /** [focus] itself if it takes text, else a focused editable inside it (web and custom editors). */
    private fun resolveEditable(focus: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (acceptsText(focus)) return focus
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until focus.childCount) focus.getChild(i)?.let { queue.add(it) }
        var seen = 0
        while (queue.isNotEmpty() && seen < SUBTREE_LIMIT) {
            val n = queue.removeFirst()
            seen++
            if (n.isFocused && acceptsText(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /**
     * A field the user was in, when live focus is gone at insert time. Only used
     * if the node still exists, is visible, and belongs to the app whose window
     * has focus now.
     */
    private fun rememberedFallback(): Candidate? {
        val options = listOfNotNull(
            lastEvent?.let { it to Source.LAST_EVENT },
            startSnapshot?.let { it to Source.START_SNAPSHOT },
        ).sortedByDescending { it.first.at }
        val windows = service.windows.orEmpty()
        val focusedPkg = windows.firstOrNull { it.isFocused }?.root?.packageName ?: return null
        val now = SystemClock.uptimeMillis()
        for ((r, source) in options) {
            val n = r.node
            if (now - r.at > REMEMBER_MAX_AGE_MS) continue
            if (!n.refresh()) continue
            if (!n.isVisibleToUser || !n.isEnabled || !acceptsText(n)) continue
            if (windows.none { it.id == n.windowId }) continue
            if (n.packageName != focusedPkg) continue
            return Candidate(n, source)
        }
        return null
    }

    private fun acceptsText(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        return node.isEditable ||
            cls.contains("EditText") ||
            cls.contains("TerminalView") ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } ||
            pasteAction(node) != null
    }

    private fun isImeWindow(node: AccessibilityNodeInfo) =
        node.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD

    private fun isOwnOrIme(node: AccessibilityNodeInfo) =
        node.packageName?.toString() == service.packageName || isImeWindow(node)

    // --- Writing ---

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Fresh text and selection, so we never rebuild from a stale cached copy.
        node.refresh()
        // Password fields report masked text, so rebuilding their content would corrupt it.
        if (node.isPassword) return false
        if (!node.isEditable && node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return false
        val current = fieldText(node)
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        // Unfocused fields often report selection -1; insertAtSelection then appends.
        val updated = insertAtSelection(current, selStart, selEnd, text)
        if (!setTextAndCursor(node, updated.text, updated.cursor)) return false
        // No reported cursor means the text was appended; undo leaves the cursor at the end.
        fun valid(i: Int) = if (i in 0..current.length) i else current.length
        lastEdit = Edit(node, current, updated.text, valid(selStart), valid(selEnd))
        return true
    }

    /** An empty field reports its hint as its text; treat that as empty. */
    private fun fieldText(node: AccessibilityNodeInfo) =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    private fun setTextAndCursor(node: AccessibilityNodeInfo, text: String, cursorAt: Int) =
        setTextAndSelection(node, text, cursorAt, cursorAt)

    private fun setTextAndSelection(node: AccessibilityNodeInfo, text: String, selStart: Int, selEnd: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        // Put the cursor after the new text instead of wherever the app resets it.
        val cursor = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selEnd)
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

    fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("Voice Relay", text)
        // Keeps the dictation out of clipboard previews and keyboard clipboard history.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    // --- Logging: structure only, never the field's text, hint, description or window titles ---

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} editable=${node.isEditable} " +
                "password=${node.isPassword} actions=${node.actionList.joinToString { it.label?.toString() ?: it.id.toString() }}"
        )
    }

    private fun describe(node: AccessibilityNodeInfo?): String =
        if (node == null) "node=none"
        else "pkg=${node.packageName} cls=${node.className} editable=${node.isEditable} " +
            "focused=${node.isFocused} visible=${node.isVisibleToUser} win=${node.windowId} " +
            "sel=${node.textSelectionStart},${node.textSelectionEnd}"

    private fun windowsSummary(): String =
        service.windows.orEmpty().joinToString(" ") {
            "${it.id}:t${it.type}${if (it.isActive) "A" else ""}${if (it.isFocused) "F" else ""}"
        } + " activeRoot=${service.rootInActiveWindow?.let { "${it.windowId}/${it.packageName}" }}"

    data class Insertion(val text: String, val cursor: Int)

    companion object {
        private const val TAG = "VoiceRelay"
        private const val LOOKUP_ATTEMPTS = 3
        private const val LOOKUP_RETRY_MS = 120L
        private const val REMEMBER_MAX_AGE_MS = 10 * 60_000L
        private const val SUBTREE_LIMIT = 300

        /** A non-empty valid selection, otherwise the whole text. */
        fun fixRange(length: Int, selStart: Int, selEnd: Int): Pair<Int, Int> {
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            return if (lo in 0 until hi && hi <= length) lo to hi else 0 to length
        }

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
