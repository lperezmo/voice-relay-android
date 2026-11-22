package com.lperezmo.voicerelay

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * What happens after the timer phrase: the transcript of what was said goes to
 * Claude with a few tools, the app runs the tools Claude calls and sends the
 * results back, and this repeats until Claude ends its turn with one line to
 * say out loud. Claude works out how long the timer must be, and which timer
 * a "cancel the pasta" means; the app never parses a time or a name itself.
 * The timers already on the phone go in the system prompt ([describeTimers]).
 *
 * Pure Kotlin: [send] is the request to Anthropic ([AnthropicClient.toolTurn])
 * and [tools] is the phone, so the whole loop is unit tested with fakes. To add
 * a tool, add it to [definitions] and to [run].
 */
class TimerAssistant(
    private val send: (messages: JSONArray) -> Turn,
    private val tools: Tools,
    private val maxRounds: Int = MAX_ROUNDS,
) {
    /** What the tools do on the phone. Each returns the text Claude gets back. */
    interface Tools {
        /** Starts a timer and says what was set and when it ends. */
        fun setTimer(seconds: Long, label: String?): String
        /** The phone's local date, time and time zone, now. */
        fun getTime(): String
        /** Says [question] out loud and returns what the user answered, or "" when nothing was heard. */
        fun askUser(question: String): String
        /** Stops timer [id], silencing it if it rings (the same as tapping Stop). Its label, or null when there is no such timer. */
        fun cancelTimer(id: Int): String?
        /** Stops every timer, silencing any that ring. The labels of those stopped. */
        fun cancelAllTimers(): List<String>
        /** The timers on the phone now, as [describeTimers] writes them. */
        fun listTimers(): String
    }

    /**
     * How the request ended: [spoken] is the line to say out loud and show. [failed] is
     * true when Claude could not be reached or did not finish; [timersSet] and
     * [timersCancelled] count the timers started and stopped on the way. The token
     * counts are summed over every round.
     */
    class Outcome(
        val spoken: String,
        val failed: Boolean,
        val timersSet: Int,
        val rounds: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val timersCancelled: Int = 0,
    )

    /** Runs the whole exchange for one [transcript]. Blocking; run off the main thread. */
    fun run(transcript: String): Outcome {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", transcript))
        var input = 0
        var output = 0
        var timersSet = 0
        var timersCancelled = 0
        var questions = 0
        var cancelAsked = false
        // What was done, for a request that ends without a line of its own.
        fun done(): String? = when {
            timersSet > 0 && timersCancelled > 0 -> SAY_DONE
            timersSet > 0 -> SAY_SET
            timersCancelled == 1 -> SAY_CANCELLED
            timersCancelled > 1 -> SAY_CANCELLED_ALL
            else -> null
        }
        // A request that breaks after a timer was already started or stopped must not say that nothing was.
        fun end(spoken: String, failed: Boolean, rounds: Int): Outcome {
            val did = if (failed) done() else null
            return Outcome(did ?: spoken, failed && did == null, timersSet, rounds, input, output, timersCancelled)
        }

        for (round in 1..maxRounds) {
            val turn = send(messages)
            input += turn.inputTokens
            output += turn.outputTokens
            if (turn.error != null) return end(failure(turn), failed = true, rounds = round)
            // A refused or cut-off turn can hold half a tool call: never run its tools.
            when (turn.stopReason) {
                "refusal" -> return end(SAY_DECLINED, failed = true, rounds = round)
                "max_tokens" -> return end(SAY_PROBLEM, failed = true, rounds = round)
            }
            val uses = AnthropicClient.toolUses(turn.content)
            if (turn.stopReason != "tool_use" || uses.isEmpty()) {
                val text = AnthropicClient.turnText(turn.content)
                val fallback = done() ?: if (cancelAsked) SAY_NOTHING_CANCELLED else SAY_NO_TIME
                return end(text.ifEmpty { fallback }, failed = false, rounds = round)
            }
            messages.put(AnthropicClient.echoTurn(turn.content))
            // Every result goes back in one user turn, in the order Claude asked.
            val results = JSONArray()
            for (use in uses) {
                val result = run(use, questionsLeft = MAX_QUESTIONS - questions, timersLeft = MAX_TIMERS - timersSet)
                if (use.name == TOOL_ASK_USER && !result.isError) questions++
                if (use.name == TOOL_SET_TIMER && !result.isError) timersSet++
                if (use.name == TOOL_CANCEL_TIMER) cancelAsked = true
                timersCancelled += result.cancelled
                results.put(AnthropicClient.toolResult(use.id, result.text, result.isError))
            }
            messages.put(JSONObject().put("role", "user").put("content", results))
        }
        return end(SAY_PROBLEM, failed = true, rounds = maxRounds)
    }

    private class ToolResult(val text: String, val isError: Boolean = false, val cancelled: Int = 0)

    private fun run(use: ToolUse, questionsLeft: Int, timersLeft: Int): ToolResult = try {
        when (use.name) {
            TOOL_SET_TIMER -> {
                val seconds = use.input.optDouble("seconds", Double.NaN)
                val label = use.input.optString("label").trim().ifEmpty { null }
                when {
                    seconds.isNaN() -> ToolResult("seconds is required: how long the timer runs, in seconds.", isError = true)
                    seconds < 1 || seconds > Timers.MAX_SECONDS ->
                        ToolResult("seconds must be between 1 and ${Timers.MAX_SECONDS} (24 hours). Nothing was set.", isError = true)
                    timersLeft <= 0 ->
                        ToolResult("That is enough timers for one request ($MAX_TIMERS). Nothing more was set.", isError = true)
                    else -> ToolResult(tools.setTimer(Math.round(seconds), label))
                }
            }
            TOOL_CANCEL_TIMER -> cancel(use.input)
            TOOL_GET_TIME -> ToolResult(tools.getTime())
            TOOL_ASK_USER -> {
                val question = use.input.optString("question").trim()
                when {
                    question.isEmpty() -> ToolResult("question is required.", isError = true)
                    questionsLeft <= 0 -> ToolResult("No more questions this time. Decide with what you know, or set nothing.", isError = true)
                    else -> ToolResult(tools.askUser(question).trim().ifEmpty { "(no answer was heard)" })
                }
            }
            else -> ToolResult("There is no tool named ${use.name}.", isError = true)
        }
    } catch (e: Exception) {
        ToolResult("The tool failed: ${e.javaClass.simpleName}", isError = true)
    }

    /** cancel_timer: one timer by id, or every timer with all set to true. */
    private fun cancel(input: JSONObject): ToolResult {
        if (input.optBoolean("all", false)) {
            val labels = tools.cancelAllTimers()
            if (labels.isEmpty()) return ToolResult("There were no timers to cancel.")
            return ToolResult(
                JSONObject().put("cancelled", JSONArray(labels)).put("timers_now", JSONArray(tools.listTimers())).toString(),
                cancelled = labels.size,
            )
        }
        val id = input.optDouble("id", Double.NaN)
        if (id.isNaN() || id != Math.floor(id)) {
            return ToolResult("id is required: the id of the timer to cancel, or set all to true to cancel every timer.", isError = true)
        }
        val label = tools.cancelTimer(id.toInt())
            ?: return ToolResult("There is no timer with id ${id.toLong()}. Nothing was cancelled. The timers now: ${tools.listTimers()}", isError = true)
        return ToolResult(
            JSONObject().put("cancelled", JSONArray().put(label)).put("timers_now", JSONArray(tools.listTimers())).toString(),
            cancelled = 1,
        )
    }

    private fun failure(turn: Turn): String = when (turn.httpCode) {
        0 -> SAY_NO_NETWORK
        401, 403 -> "The Anthropic key was not accepted. Check it in Voice Relay."
        429 -> "Claude is busy right now. Try again in a moment."
        else -> SAY_PROBLEM
    }

    companion object {
        /** Rounds of asking Claude before giving up: enough for the time, one question and the timer. */
        const val MAX_ROUNDS = 5
        /** Spoken questions per request. */
        const val MAX_QUESTIONS = 2
        /** Timers one request may start, so a misheard sentence cannot start twenty. */
        const val MAX_TIMERS = 3

        const val TOOL_SET_TIMER = "set_timer"
        const val TOOL_GET_TIME = "get_time"
        const val TOOL_ASK_USER = "ask_user"
        const val TOOL_CANCEL_TIMER = "cancel_timer"

        const val SAY_NO_TIME = "I didn't catch a time."
        const val SAY_SET = "Timer set."
        const val SAY_CANCELLED = "Timer cancelled."
        const val SAY_CANCELLED_ALL = "Timers cancelled."
        const val SAY_DONE = "Done."
        const val SAY_NONE_TO_CANCEL = "There are no timers to cancel."
        const val SAY_NOTHING_CANCELLED = "Nothing was cancelled."
        const val SAY_NO_NETWORK = "I couldn't reach the internet, so nothing was changed."
        const val SAY_PROBLEM = "Something went wrong, so nothing was changed."
        const val SAY_DECLINED = "Claude declined that, so nothing was changed."

        /** The tools Claude may call, as the Messages API takes them. */
        fun definitions(): JSONArray = JSONArray()
            .put(
                AnthropicClient.tool(
                    TOOL_SET_TIMER,
                    "Start a timer on the phone that rings after the given number of seconds. " +
                        "Returns what was set and the local time it ends.",
                    JSONObject().put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "seconds",
                                    JSONObject().put("type", "integer")
                                        .put("description", "How long the timer runs, in seconds, from 1 to 86400."),
                                )
                                .put(
                                    "label",
                                    JSONObject().put("type", "string")
                                        .put("description", "A short name when the user says what it is for, like \"Pasta\". Leave out otherwise."),
                                ),
                        )
                        .put("required", JSONArray().put("seconds")),
                )
            )
            .put(
                AnthropicClient.tool(
                    TOOL_GET_TIME,
                    "The phone's current local date, time and time zone. The system prompt has the time the " +
                        "request was made; call this to get it again after time has passed, such as after ask_user.",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                )
            )
            .put(
                AnthropicClient.tool(
                    TOOL_ASK_USER,
                    "Say a short question out loud to the user and wait for their spoken answer, which is returned " +
                        "as text (speech recognition, so it may be rough). Use it to confirm before setting a timer " +
                        "for a clock time that has already passed today, or to ask which timer they mean when that is unclear.",
                    JSONObject().put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "question",
                                JSONObject().put("type", "string").put("description", "One short spoken sentence."),
                            ),
                        )
                        .put("required", JSONArray().put("question")),
                )
            )
            .put(
                AnthropicClient.tool(
                    TOOL_CANCEL_TIMER,
                    "Cancel (stop) a timer on the phone by its id, from the timer list in the system prompt or " +
                        "from set_timer. A finished timer that is ringing stops ringing. Set all to true instead " +
                        "to cancel every timer. Returns what was cancelled and the timers left.",
                    JSONObject().put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "id",
                                    JSONObject().put("type", "integer")
                                        .put("description", "The id of the timer to cancel."),
                                )
                                .put(
                                    "all",
                                    JSONObject().put("type", "boolean")
                                        .put("description", "True to cancel every timer, when the user asks for all of them. Leave out otherwise."),
                                ),
                        ),
                )
            )

        /**
         * The system prompt: short, with the phone's time [now] so any wording can be turned into
         * a length, and the [timers] already on the phone so one can be cancelled by name.
         */
        fun systemPrompt(now: ZonedDateTime, timers: List<TimerEntry> = emptyList()): String = """
            You set and cancel kitchen timers for someone whose hands are busy. They said a wake phrase to their phone, then the request you get as text. It comes from speech recognition, so read past small mistakes.

            The phone's local time is ${describe(now)}.

            The timers on the phone now, soonest first (a finished one has 0 seconds left and may be ringing): ${describeTimers(timers, now)}

            Work out how long the timer must run and call set_timer with that many seconds. People say it many ways: "10 minutes", "an hour and a half", "for 10:50 AM", "until quarter past", "the pasta, 9 minutes". When they name what it is for, pass a short label ("Pasta"). For a clock time, count from the phone's time above; it was read a moment ago. After ask_user, call get_time and count again, since time has passed while they answered.

            If a clock time has already passed today, it means tomorrow. Do not set it yet: call ask_user to say so and ask ("That's tomorrow at 10:50, 23 hours from now. Set it?"), then set it only if they say yes. Do not use ask_user for anything you can reasonably work out yourself.

            To cancel or stop a timer, call cancel_timer with its id; for "cancel all timers" call it once with all set to true. Match what they say to the labels ("the pasta timer" is Pasta, "the 10 minute one" is the one that ran for about that long); "cancel the timer" with only one timer on the phone means that one. If no timer fits, cancel nothing and say so. If there are no timers at all, answer exactly: $SAY_NONE_TO_CANCEL If you cannot tell which timer they mean, call ask_user to ask, naming the choices ("Pasta or Eggs?"). Cancelling a finished timer also stops it ringing.

            If they want a timer but say no time or length, set nothing and answer exactly: $SAY_NO_TIME

            Your last message is read out loud, so make it one short plain sentence with no formatting, like "Timer set for 10:50, 23 minutes from now.", "Pasta timer set for 9 minutes." or "Pasta timer cancelled." Only say a timer is set or cancelled after set_timer or cancel_timer has returned.
        """.trimIndent()

        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US)
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
        private val SHORT_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        /** "Saturday, 21 November 2026, 10:27:05 AM, time zone America/Los_Angeles (PT, UTC-08:00)". */
        fun describe(now: ZonedDateTime): String =
            "${now.format(DAY)}, ${now.format(CLOCK)}, time zone ${now.zone.id} " +
                "(${now.zone.getDisplayName(TextStyle.SHORT, Locale.US)}, UTC${now.offset.id.replace("Z", "+00:00")})"

        /**
         * What set_timer answers: the label, the length, and the local time it ends, with the day
         * when that is not today. With the timer's [id], so it can be cancelled in the same request.
         */
        fun describeSet(label: String, seconds: Long, now: ZonedDateTime, id: Int? = null): String {
            val json = JSONObject()
            if (id != null) json.put("id", id)
            return json
                .put("set", true)
                .put("label", label)
                .put("seconds", seconds)
                .put("ends_at", endsAt(now.plus(Duration.ofSeconds(seconds)), now))
                .toString()
        }

        /**
         * The [timers] as Claude sees them, soonest first, as a JSON array: id, label, running or
         * finished, whole seconds left, and the local time it ends (or ended). "[]" when there are none.
         */
        fun describeTimers(timers: List<TimerEntry>, now: ZonedDateTime): String {
            val ms = now.toInstant().toEpochMilli()
            val list = JSONArray()
            for (t in timers.sortedWith(compareBy({ it.endsAt }, { it.id }))) {
                val end = Instant.ofEpochMilli(t.endsAt).atZone(now.zone)
                list.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("label", t.label)
                        .put("state", if (t.done(ms)) "finished" else "running")
                        .put("seconds_left", t.secondsLeft(ms))
                        .put("ends_at", endsAt(end, now))
                )
            }
            return list.toString()
        }

        /** "10:37 AM today", "12:27 AM tomorrow", or the date further off (or further back). */
        private fun endsAt(end: ZonedDateTime, now: ZonedDateTime): String {
            val day = when (end.toLocalDate()) {
                now.toLocalDate() -> "today"
                now.toLocalDate().plusDays(1) -> "tomorrow"
                now.toLocalDate().minusDays(1) -> "yesterday"
                else -> end.format(DAY)
            }
            return "${end.format(SHORT_CLOCK)} $day"
        }
    }
}
