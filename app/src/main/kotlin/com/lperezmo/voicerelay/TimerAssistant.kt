package com.lperezmo.voicerelay

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * What happens after the timer phrase: the transcript of what was said goes to
 * Claude with a few tools, the app runs the tools Claude calls and sends the
 * results back, and this repeats until Claude ends its turn with one line to
 * say out loud. Claude works out how long the timer must be; the app never
 * parses a time itself.
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
    }

    /**
     * How the request ended: [spoken] is the line to say out loud and show. [failed] is
     * true when Claude could not be reached or did not finish; [timersSet] counts the
     * timers started on the way. The token counts are summed over every round.
     */
    class Outcome(
        val spoken: String,
        val failed: Boolean,
        val timersSet: Int,
        val rounds: Int,
        val inputTokens: Int,
        val outputTokens: Int,
    )

    /** Runs the whole exchange for one [transcript]. Blocking; run off the main thread. */
    fun run(transcript: String): Outcome {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", transcript))
        var input = 0
        var output = 0
        var timersSet = 0
        var questions = 0
        // A request that breaks after a timer was already started must not say that none was set.
        fun end(spoken: String, failed: Boolean, rounds: Int) =
            if (failed && timersSet > 0) Outcome(SAY_SET, false, timersSet, rounds, input, output)
            else Outcome(spoken, failed, timersSet, rounds, input, output)

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
                return end(text.ifEmpty { if (timersSet > 0) SAY_SET else SAY_NO_TIME }, failed = false, rounds = round)
            }
            messages.put(AnthropicClient.echoTurn(turn.content))
            // Every result goes back in one user turn, in the order Claude asked.
            val results = JSONArray()
            for (use in uses) {
                val result = run(use, questionsLeft = MAX_QUESTIONS - questions)
                if (use.name == TOOL_ASK_USER && !result.isError) questions++
                if (use.name == TOOL_SET_TIMER && !result.isError) timersSet++
                results.put(AnthropicClient.toolResult(use.id, result.text, result.isError))
            }
            messages.put(JSONObject().put("role", "user").put("content", results))
        }
        return end(SAY_PROBLEM, failed = true, rounds = maxRounds)
    }

    private class ToolResult(val text: String, val isError: Boolean = false)

    private fun run(use: ToolUse, questionsLeft: Int): ToolResult = try {
        when (use.name) {
            TOOL_SET_TIMER -> {
                val seconds = use.input.optDouble("seconds", Double.NaN)
                val label = use.input.optString("label").trim().ifEmpty { null }
                when {
                    seconds.isNaN() -> ToolResult("seconds is required: how long the timer runs, in seconds.", isError = true)
                    seconds < 1 || seconds > Timers.MAX_SECONDS ->
                        ToolResult("seconds must be between 1 and ${Timers.MAX_SECONDS} (24 hours). Nothing was set.", isError = true)
                    else -> ToolResult(tools.setTimer(Math.round(seconds), label))
                }
            }
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

        const val TOOL_SET_TIMER = "set_timer"
        const val TOOL_GET_TIME = "get_time"
        const val TOOL_ASK_USER = "ask_user"

        const val SAY_NO_TIME = "I didn't catch a time."
        const val SAY_SET = "Timer set."
        const val SAY_NO_NETWORK = "I couldn't reach the internet, so no timer was set."
        const val SAY_PROBLEM = "Something went wrong, so no timer was set."
        const val SAY_DECLINED = "Claude declined that, so no timer was set."

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
                    "The phone's current local date, time and time zone. Call it right before working out " +
                        "how long until a clock time.",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                )
            )
            .put(
                AnthropicClient.tool(
                    TOOL_ASK_USER,
                    "Say a short question out loud to the user and wait for their spoken answer, which is returned " +
                        "as text (speech recognition, so it may be rough). Use it to confirm before setting a timer " +
                        "for a clock time that has already passed today.",
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

        /** The system prompt: short, with the phone's time [now] so any wording can be turned into a length. */
        fun systemPrompt(now: ZonedDateTime): String = """
            You set kitchen timers for someone whose hands are busy. They said a wake phrase to their phone, then the request you get as text. It comes from speech recognition, so read past small mistakes.

            The phone's local time is ${describe(now)}.

            Work out how long the timer must run and call set_timer with that many seconds. People say it many ways: "10 minutes", "an hour and a half", "for 10:50 AM", "until quarter past", "the pasta, 9 minutes". When they name what it is for, pass a short label ("Pasta"). For a clock time, call get_time first and count from the time it returns.

            If a clock time has already passed today, it means tomorrow. Do not set it yet: call ask_user to say so and ask ("That's tomorrow at 10:50, 23 hours from now. Set it?"), then set it only if they say yes. Do not use ask_user for anything you can reasonably work out yourself.

            If the request has no time or length in it, set nothing and answer exactly: $SAY_NO_TIME

            Your last message is read out loud, so make it one short plain sentence with no formatting, like "Timer set for 10:50, 23 minutes from now." or "Pasta timer set for 9 minutes." Only say a timer is set after set_timer has returned.
        """.trimIndent()

        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US)
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
        private val SHORT_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        /** "Saturday, 21 November 2026, 10:27:05 AM, time zone America/Los_Angeles (PT, UTC-08:00)". */
        fun describe(now: ZonedDateTime): String =
            "${now.format(DAY)}, ${now.format(CLOCK)}, time zone ${now.zone.id} " +
                "(${now.zone.getDisplayName(TextStyle.SHORT, Locale.US)}, UTC${now.offset.id.replace("Z", "+00:00")})"

        /** What set_timer answers: the label, the length, and the local time it ends, with the day when that is not today. */
        fun describeSet(label: String, seconds: Long, now: ZonedDateTime): String {
            val end = now.plus(Duration.ofSeconds(seconds))
            val day = when (end.toLocalDate()) {
                now.toLocalDate() -> "today"
                now.toLocalDate().plusDays(1) -> "tomorrow"
                else -> end.format(DAY)
            }
            return JSONObject()
                .put("set", true)
                .put("label", label)
                .put("seconds", seconds)
                .put("ends_at", "${end.format(SHORT_CLOCK)} $day")
                .toString()
        }
    }
}
