package com.lperezmo.voicerelay.glyph

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lperezmo.voicerelay.AnthropicClient
import com.lperezmo.voicerelay.Exchange
import com.lperezmo.voicerelay.OpenAiClient
import com.lperezmo.voicerelay.Prompts
import com.lperezmo.voicerelay.R
import com.lperezmo.voicerelay.UsageStore
import com.lperezmo.voicerelay.glyph.recognizer.Pt
import okhttp3.Dispatcher
import java.io.IOException
import kotlin.math.max
import kotlin.concurrent.thread

/**
 * Ask the Page, once "?" has been drawn: the canvas becomes a page. Handwriting stays on
 * it; a big closed loop is a lens onto the screen underneath (tap a loop to switch it
 * between lens and ink). [PAUSE_S] after the last stroke, while a gold ring fills, the
 * page sends the ink and the lit lenses ([PageImages]) to Claude and writes the answer
 * back in cursive, with a Copy chip under it. More ink asks a follow-up in the same
 * conversation; a tap after an answer, or Back, closes the canvas.
 *
 * The ring only arms once the ink is enough to be a question ([PageGuard]); stray dots or a
 * dash get a gentle [NUDGE] after the same pause instead. The keyboard button ([PageKeys])
 * opens a text field for typing the question: while it is open handwriting still lands on the
 * page but nothing is sent by itself, and Send sends the typed text with the lit lenses (and
 * the ink image too when the ink is itself enough to be a question).
 *
 * The whole-screen button (top left, only when there is a screenshot) lets the page see the
 * entire screen for one question: the dim lifts to [WHOLE_DIM] everywhere, a gold border runs
 * round the edge, and the question goes with one picture of the whole screenshot instead of
 * the lens crops ([PagePlan]). While it is on, new loops are plain ink and a tap inside a loop
 * is a dot like any other; the lenses already lit keep their state for when it is turned off.
 * It turns itself off with every send, so the whole screen never goes twice without a tap.
 *
 * Nothing here is written to disk or to the log: the screenshot ([ScreenGrab]), the app it
 * was taken over ([PageContext], sent as one line of text and named on the hint), the
 * images, the question and the answer live in memory until [dispose].
 */
class PageController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val draw: DrawView,
    private val hint: TextView,
    private val apiKey: String,
    private val model: String,
    glitterOn: Boolean,
    private val motion: Boolean,
    private val close: () -> Unit,
) {
    companion object {
        const val PAUSE_S = 2.5f
        private const val WRITE_DIM = 92 / 255f
        /** The dim with the whole-screen button on: just enough that gold ink still reads over a white app. */
        private const val WHOLE_DIM = 56 / 255f
        private const val SEND_DIM = 170 / 255f
        private const val ANSWER_DIM = 238 / 255f
        private const val COPIED_MS = 1200L
        const val HINT = "Write your question. Circle what the page should see."
        const val HINT_WHOLE = "Write your question. The page sees the whole screen."
        const val NO_SCREEN = "The screen could not be read, so circles stay ink."
        const val FAILED = "The ink did not reach me."
        const val NUDGE = "Write a question first"
        private const val PAUSE_MS = (PAUSE_S * 1000).toLong()
    }

    private enum class Phase { WRITING, SENDING, ANSWERED, FAILED }

    private val density = activity.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val page = PageView(activity, glitterOn, motion)
    private val state get() = page.state
    private val usage = UsageStore(activity.applicationContext)

    /** Its own dispatcher, so closing the canvas can cancel this call and nothing else. */
    private val http = OpenAiClient.sharedHttp.newBuilder().dispatcher(Dispatcher())
        // a request that starts after the cancel (such as a retry) is stopped here instead
        .addInterceptor { chain -> if (disposed) throw IOException("closed") else chain.proceed(chain.request()) }
        .build()

    private var phase = Phase.WRITING
    @Volatile private var disposed = false
    /** Bumped for every send and on [dispose]; a result from an older send is dropped. */
    @Volatile private var generation = 0
    private var lensesOk = false
    private var lastImages: List<ByteArray>? = null
    /** The text block and typed question of the send [lastImages] belong to, for a retry. */
    private var lastText: String? = null
    private var lastTyped: String? = null
    /** The usual hint (app line, how to ask); the page shows [NUDGE] in its place while [nudging]. */
    private var normalHint: CharSequence = ""
    private var nudging = false
    private var afterWrite: (() -> Unit)? = null
    private var dimAnim: ValueAnimator? = null

    private var chipLabel = ""
    private val chipBack = Runnable { if (!disposed) chip.text = chipLabel }

    private val sendLater = Runnable { if (!disposed && phase == Phase.WRITING && !keys.isOpen && enoughInk()) send(null) }
    private val nudgeLater = Runnable { if (!disposed && phase == Phase.WRITING && !keys.isOpen && hasMarks() && !enoughInk()) showNudge() }

    private val keys = PageKeys(
        activity = activity,
        root = root,
        motion = motion,
        onOpen = { keysOpened() },
        onClose = { keysClosed() },
        onSend = { sendTyped(it) },
    )

    /** Top left: lets the page see the whole screen for the next question. Only with a screenshot. */
    private val whole = ImageView(activity).apply {
        imageTintList = ColorStateList.valueOf(Ui.GOLD)
        val pad = (12 * density).toInt()
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
        setOnClickListener { toggleWhole() }
    }

    private val chip = TextView(activity).apply {
        setTextColor(Ui.ON_GOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        background = Ui.rounded(Ui.GOLD, 18 * density)
        val h = (16 * density).toInt()
        val v = (9 * density).toInt()
        setPadding(h, v, h, v)
        minHeight = (40 * density).toInt()
        gravity = Gravity.CENTER
        elevation = 4 * density
        visibility = View.GONE
    }

    private val failure = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    private val reason = TextView(activity).apply {
        setTextColor(Color.argb(200, 255, 236, 200))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }
    private val tryAgain = TextView(activity).apply {
        text = "Tap to try again"
        setTextColor(Ui.GOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, (10 * density).toInt(), 0, 0)
    }

    /** Turn the canvas into the page. The "?" that opened it is fading out on [draw]. */
    fun start() {
        lensesOk = ScreenGrab.shot() != null
        // the page draws the dim layer itself, so lenses can cut holes in it
        page.dim = WRITE_DIM
        root.background = null
        root.addView(page, 0, FrameLayout.LayoutParams(-1, -1))
        draw.passThrough = true
        failure.addView(reason)
        failure.addView(tryAgain)
        root.addView(failure, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        root.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        keys.attach()
        keys.showButton(true)
        root.addView(whole, FrameLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt(), Gravity.TOP or Gravity.START))
        // below the status bar and the cutout, and clear of the back gesture's edge
        whole.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val gestures = insets.getInsets(WindowInsets.Type.systemGestures())
            (v.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = max(bars.left, gestures.left) + (8 * density).toInt()
                topMargin = bars.top + (12 * density).toInt()
            }
            v.requestLayout()
            insets
        }
        syncWhole()
        showWhole(true)
        whole.requestApplyInsets()

        page.onDown = {
            main.removeCallbacks(sendLater)
            main.removeCallbacks(nudgeLater)
            page.stopRing()
            clearNudge()
        }
        page.onWriting = { writing() }
        page.onStroke = { stroke(it) }
        page.onTap = { x, y -> tap(x, y) }
        // a touch that came to nothing still restarts the pause the finger cancelled
        page.onIdle = { if (phase == Phase.WRITING) armPause() }
        page.onWritten = {
            val next = afterWrite
            afterWrite = null
            next?.invoke()
        }

        hint.typeface = Typeface.DEFAULT
        // naming the app here makes it plain that its name goes with the question
        normalHint = hintText()
        hint.text = normalHint
        hint.animate().cancel()
        hint.alpha = 0f
        hint.animate().alpha(1f).setDuration(if (motion) 200 else 0).start()
    }

    // ---- touches -------------------------------------------------------------------------

    /** A stroke has begun: after an answer that starts a follow-up. */
    private fun writing() {
        if (hint.alpha > 0f) hint.animate().alpha(0f).setDuration(150).start()
        when (phase) {
            Phase.ANSWERED -> backToWriting()
            Phase.FAILED -> {
                // the failed question is dropped; the new ink is a fresh question
                clearKeepingWhole()
                lastImages = null
                backToWriting()
            }
            else -> {}
        }
    }

    /** Drop the failed question, but not a whole-screen tap made since for the next one. */
    private fun clearKeepingWhole() {
        val keep = state.wholeScreen
        state.clearQuestion()
        state.wholeScreen = keep
    }

    private fun backToWriting() {
        phase = Phase.WRITING
        afterWrite = null
        chip.visibility = View.GONE
        failure.visibility = View.GONE
        page.clearText()
        dimTo(writeDim())
        keys.showButton(true)
        showWhole(true)
    }

    private fun writeDim() = if (state.wholeScreen) WHOLE_DIM else WRITE_DIM

    private fun stroke(points: List<Pt>) {
        if (phase != Phase.WRITING) return
        // without a screenshot there is nothing to look through, so every loop stays ink;
        // with the whole screen on a loop adds nothing, so it stays ink too
        val lit = if (lensesOk && !state.wholeScreen) state.loops.count { it.lit } else Lens.MAX_LIT
        val loop = Lens.loopOf(points, density, lit)
        if (loop != null) state.loops.add(loop)
        if (loop == null || !loop.lit) state.ink.add(points)
        if (loop?.lit == true) page.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        page.invalidate()
        armPause()
    }

    private fun tap(x: Float, y: Float) {
        when (phase) {
            Phase.WRITING -> {
                var hit: Loop? = null
                // with the whole screen on, lenses are not switched: a tap is a dot like anywhere else
                if (lensesOk && !state.wholeScreen) {
                    val wasLit = state.loops.lastOrNull { Lens.contains(it.stroke, x, y) }?.lit
                    hit = state.toggleAt(x, y)
                    if (hit != null) {
                        // an ink loop that stayed ink: MAX_LIT lenses are already lit
                        val refused = wasLit == false && !hit.lit
                        page.performHapticFeedback(if (refused) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                // a tap outside every loop is a dot of the handwriting (the i, the ?, a full stop)
                if (hit == null) state.ink.add(listOf(Pt(x, y, 0)))
                page.invalidate()
                armPause()
            }
            Phase.SENDING -> {}
            // with the text field open a tap on the page puts the keyboard away, it does not close the canvas
            Phase.ANSWERED -> if (keys.isOpen) keys.close() else if (page.writing) page.finishText() else close()
            Phase.FAILED -> when {
                keys.isOpen -> keys.close()
                page.writing -> page.finishText()
                lastImages != null -> retry()
                else -> close()
            }
        }
    }

    /**
     * Start the ring; when it fills the question goes. Only with enough ink ([PageGuard]):
     * with too little, no ring, and after the same pause the hint nudges instead. Never while
     * the text field is open.
     */
    private fun armPause() {
        main.removeCallbacks(sendLater)
        main.removeCallbacks(nudgeLater)
        if (keys.isOpen || !enoughInk()) {
            page.stopRing()
            if (!keys.isOpen && hasMarks()) main.postDelayed(nudgeLater, PAUSE_MS)
            return
        }
        page.startRing(PAUSE_S)
        main.postDelayed(sendLater, PAUSE_MS)
    }

    private fun enoughInk() = PageGuard.enough(state.ink, density)

    /** Anything drawn for the question: ink, dots or a lit lens. */
    private fun hasMarks() = state.ink.isNotEmpty() || state.loops.any { it.lit }

    private fun showNudge() {
        nudging = true
        hint.animate().cancel()
        hint.typeface = Typeface.DEFAULT
        hint.text = NUDGE
        if (!motion) {
            hint.alpha = 1f
            return
        }
        hint.alpha = 0f
        hint.animate().alpha(1f).setDuration(200).start()
    }

    /** Writing goes on: the nudge goes, and the usual hint text is back (hidden while writing, as before). */
    private fun clearNudge() {
        if (!nudging) return
        nudging = false
        hint.animate().cancel()
        if (!motion) {
            hint.alpha = 0f
            hint.text = normalHint
            return
        }
        hint.animate().alpha(0f).setDuration(150).withEndAction { if (!disposed && !nudging) hint.text = normalHint }.start()
    }

    // ---- the whole screen -----------------------------------------------------------------

    /** The usual hint: the app line, how to ask (or that the whole screen is seen), and a missing screenshot. */
    private fun hintText(): String =
        listOfNotNull(PageContext.about(ScreenGrab.app()), if (state.wholeScreen) HINT_WHOLE else HINT, NO_SCREEN.takeUnless { lensesOk })
            .joinToString("\n")

    private fun toggleWhole() {
        if (disposed || phase == Phase.SENDING || !lensesOk || ScreenGrab.shot() == null) return
        state.wholeScreen = !state.wholeScreen
        whole.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        syncWhole()
        // after an answer the page stays dark for reading; the dim lifts once writing starts
        if (phase == Phase.WRITING) dimTo(writeDim())
        page.invalidate()
        showHint()
        if (phase == Phase.WRITING) armPause()
    }

    /** The hint, shown again with what the page will see now (not while the text field is open). */
    private fun showHint() {
        nudging = false
        normalHint = hintText()
        hint.animate().cancel()
        hint.typeface = Typeface.DEFAULT
        hint.text = normalHint
        if (keys.isOpen) {
            hint.alpha = 0f
            return
        }
        if (!motion) {
            hint.alpha = 1f
            return
        }
        hint.alpha = 0f
        hint.animate().alpha(1f).setDuration(200).start()
    }

    /** The button's look follows [PageState.wholeScreen]: filled and bright when on. */
    private fun syncWhole() {
        val on = state.wholeScreen
        whole.setImageResource(if (on) R.drawable.glyph_ic_whole_screen_on else R.drawable.glyph_ic_whole_screen)
        whole.contentDescription = if (on) "Stop showing the whole screen" else "Show the page the whole screen"
        whole.background = if (on) Ui.rounded(Color.argb(56, 242, 196, 109), 14 * density, (1 * density).toInt(), Ui.GOLD) else null
        whole.isSelected = on
        if (whole.visibility == View.VISIBLE) {
            whole.animate().cancel()
            whole.alpha = if (on) 1f else 0.7f
        }
    }

    /** Show the button (only when there is a screenshot to show), or hide it. */
    private fun showWhole(show: Boolean) {
        if (disposed) return
        val visible = show && lensesOk && ScreenGrab.shot() != null
        whole.animate().cancel()
        if (!visible) {
            whole.visibility = View.GONE
            return
        }
        val target = if (state.wholeScreen) 1f else 0.7f
        if (whole.visibility == View.VISIBLE) {
            whole.alpha = target
            return
        }
        whole.visibility = View.VISIBLE
        if (motion) {
            whole.alpha = 0f
            whole.animate().alpha(target).setDuration(200).start()
        } else {
            whole.alpha = target
        }
    }

    /** The whole screen went with a send (or was dropped): off again, so it never goes twice without a tap. */
    private fun wholeOff() {
        if (!state.wholeScreen) return
        state.wholeScreen = false
        syncWhole()
        normalHint = hintText()
        if (!nudging) hint.text = normalHint
    }

    // ---- the keyboard ----------------------------------------------------------------------

    private fun keysOpened() {
        main.removeCallbacks(sendLater)
        main.removeCallbacks(nudgeLater)
        page.stopRing()
        clearNudge()
        if (hint.alpha > 0f) hint.animate().alpha(0f).setDuration(if (motion) 150 else 0).start()
    }

    /** Back to handwriting: whatever is on the page arms the ring (or the nudge) again. */
    private fun keysClosed() {
        if (phase == Phase.WRITING) armPause()
    }

    private fun sendTyped(raw: String) {
        if (disposed || phase == Phase.SENDING) return
        val typed = Prompts.pageTyped(raw)
        if (typed.isEmpty()) return
        when (phase) {
            Phase.ANSWERED -> backToWriting()
            Phase.FAILED -> {
                // as with new ink: the failed question is dropped
                clearKeepingWhole()
                lastImages = null
                backToWriting()
            }
            else -> {}
        }
        keys.clearDraft()
        keys.close(quiet = true)
        send(typed)
    }

    // ---- sending -------------------------------------------------------------------------

    /**
     * Send the page: the handwriting (when [typed] is null), or the [typed] question with the
     * ink image only when the ink is itself enough to be a question. Lit lenses go either way.
     */
    private fun send(typed: String?) {
        val withInk = typed == null || enoughInk()
        val snapshot = PageState().apply {
            if (withInk) ink.addAll(state.ink)
            loops.addAll(state.loops.map { Loop(it.stroke, it.lit) })
        }
        val shot = if (lensesOk) ScreenGrab.shot() else null
        val plan = PagePlan.of(snapshot.ink.isNotEmpty(), snapshot.loops.count { it.lit }, state.wholeScreen, shot != null)
        val text = PageContext.userText(Prompts.pageUser(typed, plan.ink, plan.lenses, plan.wholeScreen), ScreenGrab.app())
        val origin = IntArray(2).also { page.getLocationOnScreen(it) }
        val history = pastTurns()
        sending()
        // sent once: the next question needs a fresh tap to share the whole screen again
        wholeOff()
        val gen = ++generation
        thread(name = "ask-page") {
            val images = try {
                PageImages.build(snapshot, plan, shot, density, origin[0], origin[1])
            } catch (_: Throwable) {
                null
            }
            // a typed question may go with no images at all; a handwritten one never does
            if (images == null || (images.isEmpty() && typed == null)) {
                deliver(gen, null, OpenAiClient.Result(null, "The page could not be drawn."), text, typed)
            } else {
                deliver(gen, images, call(images, history, text), text, typed)
            }
        }
    }

    private fun retry() {
        val images = lastImages ?: return
        val text = lastText ?: return
        val typed = lastTyped
        val history = pastTurns()
        failure.visibility = View.GONE
        page.clearText()
        // a retry sends the stored images as they were; a whole-screen tap made since is dropped with it
        wholeOff()
        sending()
        val gen = ++generation
        thread(name = "ask-page") { deliver(gen, images, call(images, history, text), text, typed) }
    }

    /** The finished turns for a follow-up; a question Claude did not echo back still keeps its turn. */
    private fun pastTurns() = state.turns.map { Exchange(it.question.ifBlank { "(handwritten question)" }, it.answer) }

    private fun sending() {
        phase = Phase.SENDING
        main.removeCallbacks(sendLater)
        main.removeCallbacks(nudgeLater)
        page.stopRing()
        keys.close(quiet = true)
        keys.showButton(false)
        showWhole(false)
        nudging = false
        hint.animate().cancel()
        hint.alpha = 0f
        hint.text = normalHint
        page.soak()
        dimTo(SEND_DIM)
    }

    /** Blocking; on the worker thread. */
    private fun call(images: List<ByteArray>, history: List<Exchange>, text: String): OpenAiClient.Result {
        if (disposed) return OpenAiClient.Result(null, "closed")
        val result = try {
            AnthropicClient(apiKey, http = http).ask(images, history, model, Prompts.PAGE, text)
        } catch (e: Exception) {
            OpenAiClient.Result(null, e.message ?: "The request failed.")
        }
        // counted the way cleanup counts it: only answers, against the model that answered
        if (result.ok && !disposed) usage.recordText(model, result.inputTokens, result.outputTokens)
        return result
    }

    private fun deliver(gen: Int, images: List<ByteArray>?, result: OpenAiClient.Result, text: String, typed: String?) {
        main.post {
            // a result for a closed canvas, or for a send that was replaced, goes nowhere
            if (disposed || gen != generation || activity.isFinishing || activity.isDestroyed) return@post
            lastImages = images
            lastText = text
            lastTyped = typed
            val parsed = result.text?.takeIf { result.ok }?.let { PageReply.parse(it) }
            // a typed question Claude did not echo back is still the question
            val reply = if (parsed != null && parsed.question.isBlank() && typed != null) parsed.copy(question = typed) else parsed
            when {
                reply == null -> fail(result.error ?: "No answer came back.")
                reply.short.isBlank() -> fail("The answer was empty.")
                else -> answer(reply)
            }
        }
    }

    // ---- the answer ------------------------------------------------------------------------

    private fun answer(reply: PageReply) {
        phase = Phase.ANSWERED
        lastImages = null
        lastText = null
        lastTyped = null
        val long = reply.long
        // kept in the reply format the system prompt asks for, so follow-ups see their own answers that way
        val text = buildString {
            if (reply.question.isNotBlank()) append("Q: ").append(reply.question).append('\n')
            append(reply.short)
            if (long != null) append("\n---\n").append(long)
        }
        state.turns += Turn(reply.question, text)
        state.clearQuestion()
        syncWhole()
        normalHint = hintText()
        dimTo(ANSWER_DIM)
        keys.showButton(true)
        showWhole(true)
        val left = writeText(reply.short)
        afterWrite = { showChip(reply, left) }
    }

    private fun fail(why: String) {
        phase = Phase.FAILED
        dimTo(ANSWER_DIM)
        keys.showButton(true)
        showWhole(true)
        val left = writeText(FAILED)
        reason.text = why.trim().let { if (it.length > 160) it.take(157) + "..." else it }
        tryAgain.visibility = if (lastImages != null) View.VISIBLE else View.GONE
        afterWrite = {
            place(failure, left, page.textBottom + 8 * density)
            failure.visibility = View.VISIBLE
            fadeIn(failure)
        }
    }

    /** Lay [text] out in cursive near the top of the page and write it. Returns the left edge. */
    private fun writeText(text: String): Float {
        val bars = page.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val side = 28 * density
        val left = side + (bars?.left ?: 0)
        val width = (page.width - left - side - (bars?.right ?: 0)).toInt()
        val top = (bars?.top ?: 0) + 88 * density
        page.showText(text, width, top, left)
        return left
    }

    private fun showChip(reply: PageReply, left: Float) {
        chipLabel = if (reply.long != null) "Copy · there is more" else "Copy"
        chip.text = chipLabel
        chip.contentDescription = if (reply.long != null) "Copy the question and the full answer" else "Copy the question and answer"
        chip.setOnClickListener {
            copy(reply.clipboardText())
            chip.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            chip.text = "Copied"
            chip.removeCallbacks(chipBack)
            chip.postDelayed(chipBack, COPIED_MS)
        }
        place(chip, left, page.textBottom + 18 * density)
        chip.visibility = View.VISIBLE
        fadeIn(chip)
    }

    private fun copy(text: String) {
        val clip = ClipData.newPlainText("Ask the Page", text)
        // keeps the answer out of the clipboard preview and history, like dictation's copies
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun place(view: View, left: Float, top: Float) {
        (view.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = left.toInt()
            rightMargin = left.toInt()
            topMargin = top.toInt()
        }
        view.requestLayout()
    }

    private fun fadeIn(view: View) {
        view.animate().cancel()
        if (!motion) {
            view.alpha = 1f
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(200).start()
    }

    private fun dimTo(target: Float) {
        dimAnim?.cancel()
        if (!motion) {
            page.dim = target
            return
        }
        dimAnim = ValueAnimator.ofFloat(page.dim, target).apply {
            duration = 600
            addUpdateListener { page.dim = it.animatedValue as Float }
            start()
        }
    }

    /** Back pressed (legacy path): closes the text field when it is open. True when handled. */
    fun handleBack(): Boolean = !disposed && keys.handleBack()

    /** The canvas is closing: stop everything and forget the page, the images and the screenshot. */
    fun dispose() {
        if (disposed) return
        disposed = true
        generation = -1
        main.removeCallbacks(sendLater)
        main.removeCallbacks(nudgeLater)
        keys.dispose()
        whole.animate().cancel()
        whole.setOnClickListener(null)
        whole.setOnApplyWindowInsetsListener(null)
        whole.visibility = View.GONE
        root.removeView(whole)
        dimAnim?.cancel()
        chip.removeCallbacks(chipBack)
        http.dispatcher.cancelAll()
        lastImages = null
        lastText = null
        lastTyped = null
        afterWrite = null
        state.clearQuestion()
        state.turns.clear()
        page.dispose()
        ScreenGrab.clear()
    }
}
