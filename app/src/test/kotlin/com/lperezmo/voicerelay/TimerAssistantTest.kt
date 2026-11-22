package com.lperezmo.voicerelay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimerAssistantTest {

    // ---- building blocks for scripted turns ----

    private fun thinking(signature: String = "sig") =
        JSONObject().put("type", "thinking").put("thinking", "").put("signature", signature)

    private fun text(s: String) = JSONObject().put("type", "text").put("text", s)

    private fun use(id: String, name: String, input: JSONObject = JSONObject()) =
        JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", input)

    private fun setTimer(id: String, seconds: Any?, label: String? = null): JSONObject {
        val input = JSONObject()
        if (seconds != null) input.put("seconds", seconds)
        if (label != null) input.put("label", label)
        return use(id, TimerAssistant.TOOL_SET_TIMER, input)
    }

    private fun ask(id: String, question: String) =
        use(id, TimerAssistant.TOOL_ASK_USER, JSONObject().put("question", question))

    private fun turn(stop: String, vararg blocks: JSONObject, input: Int = 0, output: Int = 0) =
        Turn(JSONArray().apply { blocks.forEach { put(it) } }, stop, inputTokens = input, outputTokens = output)

    /** Hands out the scripted turns in order and keeps a copy of each request's messages as sent. */
    private class Script(vararg turns: Turn) {
        val queue = ArrayDeque(turns.toList())
        val sent = mutableListOf<String>()
        fun send(messages: JSONArray): Turn {
            sent += messages.toString()
            if (queue.isEmpty()) fail("an unexpected request: ${messages}")
            return queue.removeFirst()
        }
        fun messages(call: Int) = JSONArray(sent[call])
    }

    private class FakeTools(
        answers: List<String> = emptyList(),
        val throwOnSet: Boolean = false,
        /** The timers on the phone, by id, for cancel_timer. */
        onPhone: Map<Int, String> = emptyMap(),
    ) : TimerAssistant.Tools {
        val timers = mutableListOf<Pair<Long, String?>>()
        val phone = LinkedHashMap(onPhone)
        val cancelled = mutableListOf<Int>()
        var timeCalls = 0
        val questions = mutableListOf<String>()
        private val answers = ArrayDeque(answers)

        override fun setTimer(seconds: Long, label: String?): String {
            if (throwOnSet) throw IllegalStateException("alarm manager gone")
            timers += seconds to label
            return """{"set":true,"seconds":$seconds}"""
        }

        override fun getTime(): String {
            timeCalls++
            return "Saturday, 21 November 2026, 10:27:05 AM"
        }

        override fun askUser(question: String): String {
            questions += question
            return answers.removeFirstOrNull() ?: ""
        }

        override fun cancelTimer(id: Int): String? {
            val label = phone.remove(id) ?: return null
            cancelled += id
            return label
        }

        override fun cancelAllTimers(): List<String> {
            cancelled += phone.keys
            return phone.values.toList().also { phone.clear() }
        }

        override fun listTimers(): String =
            JSONArray().apply { phone.forEach { (id, label) -> put(JSONObject().put("id", id).put("label", label)) } }.toString()
    }

    private fun cancel(id: String, timerId: Any? = null, all: Boolean? = null): JSONObject {
        val input = JSONObject()
        if (timerId != null) input.put("id", timerId)
        if (all != null) input.put("all", all)
        return use(id, TimerAssistant.TOOL_CANCEL_TIMER, input)
    }

    private fun assistant(script: Script, tools: FakeTools, maxRounds: Int = TimerAssistant.MAX_ROUNDS) =
        TimerAssistant({ script.send(it) }, tools, maxRounds)

    /** The tool_result blocks of the last message in call [call]. */
    private fun results(script: Script, call: Int): JSONArray {
        val messages = script.messages(call)
        val last = messages.getJSONObject(messages.length() - 1)
        assertEquals("user", last.getString("role"))
        return last.getJSONArray("content")
    }

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 11, 21, 10, 27, 5, 0, ZoneId.of("America/Los_Angeles"))

    // ---- the loop ----

    @Test fun `ten minutes sets one timer and says Claude's last line`() {
        val script = Script(
            turn("tool_use", thinking(), setTimer("toolu_1", 600), input = 100, output = 20),
            turn("end_turn", thinking(), text("Timer set for 10 minutes."), input = 150, output = 10),
        )
        val tools = FakeTools()
        val out = assistant(script, tools).run("set a timer for 10 minutes")

        assertEquals("Timer set for 10 minutes.", out.spoken)
        assertFalse(out.failed)
        assertEquals(1, out.timersSet)
        assertEquals(2, out.rounds)
        assertEquals(250, out.inputTokens)
        assertEquals(30, out.outputTokens)
        assertEquals(listOf(600L to null), tools.timers)

        val first = script.messages(0)
        assertEquals(1, first.length())
        assertEquals("user", first.getJSONObject(0).getString("role"))
        assertEquals("set a timer for 10 minutes", first.getJSONObject(0).getString("content"))
    }

    @Test fun `the second request echoes Claude's turn unchanged, thinking included, then one user turn of results`() {
        val claudeTurn = turn("tool_use", thinking("sig-abc"), text("Setting it."), setTimer("toolu_1", 600))
        val expectedEcho = JSONArray(claudeTurn.content.toString())
        val script = Script(claudeTurn, turn("end_turn", text("Done.")))
        assistant(script, FakeTools()).run("10 minutes")

        val second = script.messages(1)
        assertEquals(3, second.length())
        val echoed = second.getJSONObject(1)
        assertEquals("assistant", echoed.getString("role"))
        assertEquals(expectedEcho.toString(), echoed.getJSONArray("content").toString())
        val firstBlock = echoed.getJSONArray("content").getJSONObject(0)
        assertEquals("thinking", firstBlock.getString("type"))
        assertEquals("sig-abc", firstBlock.getString("signature"))

        val results = results(script, 1)
        assertEquals(1, results.length())
        val result = results.getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("toolu_1", result.getString("tool_use_id"))
        assertEquals("""{"set":true,"seconds":600}""", result.getString("content"))
        assertFalse(result.has("is_error"))
    }

    @Test fun `get_time then set_timer runs both tools across rounds`() {
        val script = Script(
            turn("tool_use", thinking(), use("toolu_1", TimerAssistant.TOOL_GET_TIME)),
            turn("tool_use", thinking(), setTimer("toolu_2", 1375)),
            turn("end_turn", text("Timer set for 10:50, 23 minutes from now.")),
        )
        val tools = FakeTools()
        val out = assistant(script, tools).run("timer for 10:50")

        assertEquals("Timer set for 10:50, 23 minutes from now.", out.spoken)
        assertEquals(3, out.rounds)
        assertEquals(1, out.timersSet)
        assertEquals(1, tools.timeCalls)
        assertEquals(listOf(1375L to null), tools.timers)

        val timeResult = results(script, 1).getJSONObject(0)
        assertEquals("toolu_1", timeResult.getString("tool_use_id"))
        assertEquals("Saturday, 21 November 2026, 10:27:05 AM", timeResult.getString("content"))

        val third = script.messages(2)
        assertEquals(5, third.length())
        assertEquals(listOf("user", "assistant", "user", "assistant", "user"),
            (0 until third.length()).map { third.getJSONObject(it).getString("role") })
        assertEquals("toolu_2", results(script, 2).getJSONObject(0).getString("tool_use_id"))
    }

    @Test fun `two tool calls in one turn get both results in a single user turn, in order`() {
        val script = Script(
            turn("tool_use", thinking(), setTimer("toolu_1", 540, "Pasta"), setTimer("toolu_2", 300, "Sauce")),
            turn("end_turn", text("Pasta and sauce timers set.")),
        )
        val tools = FakeTools()
        val out = assistant(script, tools).run("pasta 9 minutes and sauce 5 minutes")

        assertEquals(2, out.timersSet)
        assertEquals(listOf(540L to "Pasta", 300L to "Sauce"), tools.timers)
        val second = script.messages(1)
        assertEquals(3, second.length())
        val results = results(script, 1)
        assertEquals(2, results.length())
        assertEquals("toolu_1", results.getJSONObject(0).getString("tool_use_id"))
        assertEquals("toolu_2", results.getJSONObject(1).getString("tool_use_id"))
    }

    @Test fun `one request starts three timers at most`() {
        val script = Script(
            turn(
                "tool_use", setTimer("toolu_1", 60), setTimer("toolu_2", 120),
                setTimer("toolu_3", 180), setTimer("toolu_4", 240),
            ),
            turn("end_turn", text("Three timers set.")),
        )
        val tools = FakeTools()
        val out = assistant(script, tools).run("set a lot of timers")

        assertEquals(TimerAssistant.MAX_TIMERS, out.timersSet)
        assertEquals(3, tools.timers.size)
        val results = results(script, 1)
        assertFalse(results.getJSONObject(2).optBoolean("is_error"))
        assertTrue(results.getJSONObject(3).getBoolean("is_error"))
    }

    @Test fun `the label is trimmed and passed through, and a blank label becomes null`() {
        val script = Script(
            turn("tool_use", setTimer("toolu_1", 60, "  Eggs "), setTimer("toolu_2", 120, "   "), setTimer("toolu_3", 180)),
            turn("end_turn", text("Set.")),
        )
        val tools = FakeTools()
        assistant(script, tools).run("eggs")
        assertEquals(listOf(60L to "Eggs", 120L to null, 180L to null), tools.timers)
    }

    @Test fun `seconds missing, zero, over a day or not a number is an error and nothing is set`() {
        for (seconds in listOf(null, 0, -5, 0.4, 86_401, "ten")) {
            val script = Script(
                turn("tool_use", setTimer("toolu_1", seconds)),
                turn("end_turn", text(TimerAssistant.SAY_NO_TIME)),
            )
            val tools = FakeTools()
            val out = assistant(script, tools).run("a timer")

            assertTrue("seconds=$seconds", tools.timers.isEmpty())
            assertEquals("seconds=$seconds", 0, out.timersSet)
            assertEquals(TimerAssistant.SAY_NO_TIME, out.spoken)
            val result = results(script, 1).getJSONObject(0)
            assertEquals("toolu_1", result.getString("tool_use_id"))
            assertTrue("seconds=$seconds", result.getBoolean("is_error"))
        }
    }

    @Test fun `a whole day is allowed and fractional seconds are rounded`() {
        val script = Script(
            turn("tool_use", setTimer("toolu_1", 86_400), setTimer("toolu_2", 1), setTimer("toolu_3", 599.6)),
            turn("end_turn", text("Set.")),
        )
        val tools = FakeTools()
        val out = assistant(script, tools).run("x")
        assertEquals(listOf(86_400L to null, 1L to null, 600L to null), tools.timers)
        assertEquals(3, out.timersSet)
    }

    @Test fun `an unknown tool is an error result`() {
        val script = Script(
            turn("tool_use", use("toolu_1", "delete_everything")),
            turn("end_turn", text("I can't do that.")),
        )
        val tools = FakeTools()
        val out = assistant(script, tools).run("x")
        val result = results(script, 1).getJSONObject(0)
        assertTrue(result.getBoolean("is_error"))
        assertTrue(result.getString("content").contains("delete_everything"))
        assertEquals(0, out.timersSet)
        assertFalse(out.failed)
        assertEquals("I can't do that.", out.spoken)
    }

    @Test fun `ask_user says the question and returns the answer`() {
        val script = Script(
            turn("tool_use", thinking(), ask("toolu_1", "That's tomorrow at 10:50, 23 hours from now. Set it?")),
            turn("tool_use", thinking(), setTimer("toolu_2", 84_000)),
            turn("end_turn", text("Timer set for tomorrow at 10:50.")),
        )
        val tools = FakeTools(answers = listOf("  yes please "))
        val out = assistant(script, tools).run("timer for 10:50")

        assertEquals(listOf("That's tomorrow at 10:50, 23 hours from now. Set it?"), tools.questions)
        val answer = results(script, 1).getJSONObject(0)
        assertEquals("toolu_1", answer.getString("tool_use_id"))
        assertEquals("yes please", answer.getString("content"))
        assertFalse(answer.has("is_error"))
        assertEquals(1, out.timersSet)
    }

    @Test fun `an empty answer becomes no answer was heard`() {
        val script = Script(
            turn("tool_use", ask("toolu_1", "Set it?")),
            turn("end_turn", text("No timer set.")),
        )
        val tools = FakeTools(answers = listOf("   "))
        assistant(script, tools).run("x")
        val answer = results(script, 1).getJSONObject(0)
        assertEquals("(no answer was heard)", answer.getString("content"))
        assertFalse(answer.has("is_error"))
    }

    @Test fun `an empty question is an error and nothing is said`() {
        val script = Script(
            turn("tool_use", ask("toolu_1", "  ")),
            turn("end_turn", text("No timer set.")),
        )
        val tools = FakeTools()
        assistant(script, tools).run("x")
        assertTrue(tools.questions.isEmpty())
        assertTrue(results(script, 1).getJSONObject(0).getBoolean("is_error"))
    }

    @Test fun `a third question is refused`() {
        assertEquals(2, TimerAssistant.MAX_QUESTIONS)
        val script = Script(
            turn("tool_use", ask("toolu_1", "First?")),
            turn("tool_use", ask("toolu_2", "Second?")),
            turn("tool_use", ask("toolu_3", "Third?")),
            turn("end_turn", text("No timer set.")),
        )
        val tools = FakeTools(answers = listOf("what", "huh", "eh"))
        val out = assistant(script, tools).run("x")

        assertEquals(listOf("First?", "Second?"), tools.questions)
        assertFalse(results(script, 1).getJSONObject(0).has("is_error"))
        assertFalse(results(script, 2).getJSONObject(0).has("is_error"))
        val third = results(script, 3).getJSONObject(0)
        assertEquals("toolu_3", third.getString("tool_use_id"))
        assertTrue(third.getBoolean("is_error"))
        assertEquals(4, out.rounds)
    }

    @Test fun `a tool that throws gives an error result and the loop goes on`() {
        val script = Script(
            turn("tool_use", setTimer("toolu_1", 600)),
            turn("end_turn", text("I couldn't set that timer.")),
        )
        val out = assistant(script, FakeTools(throwOnSet = true)).run("10 minutes")
        val result = results(script, 1).getJSONObject(0)
        assertTrue(result.getBoolean("is_error"))
        assertEquals("The tool failed: IllegalStateException", result.getString("content"))
        assertEquals(0, out.timersSet)
        assertEquals(2, out.rounds)
        assertFalse(out.failed)
        assertEquals("I couldn't set that timer.", out.spoken)
    }

    @Test fun `no time in the request sets nothing and is not a failure`() {
        val script = Script(turn("end_turn", thinking(), text(TimerAssistant.SAY_NO_TIME), input = 50, output = 8))
        val tools = FakeTools()
        val out = assistant(script, tools).run("what's for dinner")
        assertEquals(TimerAssistant.SAY_NO_TIME, out.spoken)
        assertFalse(out.failed)
        assertEquals(0, out.timersSet)
        assertEquals(1, out.rounds)
        assertEquals(50, out.inputTokens)
        assertEquals(8, out.outputTokens)
        assertTrue(tools.timers.isEmpty())
        assertEquals(0, tools.timeCalls)
    }

    @Test fun `an empty last turn says no time, or timer set when one was set`() {
        val none = assistant(Script(turn("end_turn", thinking())), FakeTools()).run("x")
        assertEquals(TimerAssistant.SAY_NO_TIME, none.spoken)
        assertFalse(none.failed)

        val blank = assistant(Script(turn("end_turn", text("   "))), FakeTools()).run("x")
        assertEquals(TimerAssistant.SAY_NO_TIME, blank.spoken)

        val set = assistant(
            Script(turn("tool_use", setTimer("toolu_1", 600)), turn("end_turn", thinking())),
            FakeTools(),
        ).run("x")
        assertEquals(TimerAssistant.SAY_SET, set.spoken)
        assertFalse(set.failed)
        assertEquals(1, set.timersSet)
    }

    @Test fun `request errors are spoken by kind and are failures`() {
        val cases = mapOf(
            0 to TimerAssistant.SAY_NO_NETWORK,
            401 to "The Anthropic key was not accepted. Check it in Voice Relay.",
            403 to "The Anthropic key was not accepted. Check it in Voice Relay.",
            429 to "Claude is busy right now. Try again in a moment.",
            500 to TimerAssistant.SAY_PROBLEM,
            529 to TimerAssistant.SAY_PROBLEM,
            400 to TimerAssistant.SAY_PROBLEM,
        )
        for ((code, spoken) in cases) {
            val script = Script(Turn(error = "boom", httpCode = code))
            val out = assistant(script, FakeTools()).run("10 minutes")
            assertEquals("code $code", spoken, out.spoken)
            assertTrue("code $code", out.failed)
            assertEquals(0, out.timersSet)
            assertEquals(1, out.rounds)
        }
    }

    @Test fun `an error after a timer was set does not say that no timer was set`() {
        // A timer already rings; saying "no timer was set" would be wrong. Same answer as the round cap.
        val script = Script(
            turn("tool_use", setTimer("toolu_1", 600)),
            Turn(error = "timeout", httpCode = 0),
        )
        val tools = FakeTools()
        val out = assistant(script, tools).run("10 minutes")
        assertEquals(1, out.timersSet)
        assertEquals(listOf(600L to null), tools.timers)
        assertEquals(TimerAssistant.SAY_SET, out.spoken)
    }

    @Test fun `a refused or cut-off turn never runs its tools`() {
        val refused = Script(turn("refusal", setTimer("toolu_1", 600), input = 30, output = 4))
        val tools = FakeTools()
        val out = assistant(refused, tools).run("x")
        assertEquals(TimerAssistant.SAY_DECLINED, out.spoken)
        assertTrue(out.failed)
        assertEquals(1, out.rounds)
        assertEquals(30, out.inputTokens)
        assertTrue(tools.timers.isEmpty())

        val cut = Script(turn("max_tokens", thinking(), setTimer("toolu_1", 600)))
        val tools2 = FakeTools()
        val out2 = assistant(cut, tools2).run("x")
        assertEquals(TimerAssistant.SAY_PROBLEM, out2.spoken)
        assertTrue(out2.failed)
        assertTrue(tools2.timers.isEmpty())
        assertEquals(1, cut.sent.size)
    }

    @Test fun `a tool_use block in a turn that ended normally is not run`() {
        val tools = FakeTools()
        val out = assistant(Script(turn("end_turn", setTimer("toolu_1", 600), text("Okay."))), tools).run("x")
        assertEquals("Okay.", out.spoken)
        assertTrue(tools.timers.isEmpty())
        assertEquals(0, out.timersSet)
    }

    @Test fun `the round cap stops an endless tool loop`() {
        val sent = mutableListOf<String>()
        val tools = FakeTools()
        val endless = TimerAssistant({ m ->
            sent += m.toString()
            turn("tool_use", use("toolu_${sent.size}", TimerAssistant.TOOL_GET_TIME), input = 10, output = 1)
        }, tools, maxRounds = 3)
        val out = endless.run("x")
        assertEquals(3, sent.size)
        assertEquals(3, tools.timeCalls)
        assertEquals(3, out.rounds)
        assertTrue(out.failed)
        assertEquals(TimerAssistant.SAY_PROBLEM, out.spoken)
        assertEquals(30, out.inputTokens)
        assertEquals(3, out.outputTokens)
    }

    @Test fun `the round cap after a timer was set says timer set and is not a failure`() {
        val sent = mutableListOf<String>()
        val tools = FakeTools()
        val endless = TimerAssistant({ m ->
            sent += m.toString()
            if (sent.size == 1) turn("tool_use", setTimer("toolu_1", 600))
            else turn("tool_use", use("toolu_${sent.size}", TimerAssistant.TOOL_GET_TIME))
        }, tools, maxRounds = 3)
        val out = endless.run("x")
        assertEquals(3, out.rounds)
        assertEquals(1, out.timersSet)
        assertFalse(out.failed)
        assertEquals(TimerAssistant.SAY_SET, out.spoken)
    }

    @Test fun `the default round cap is MAX_ROUNDS`() {
        var calls = 0
        val out = TimerAssistant({ _ ->
            calls++
            turn("tool_use", use("toolu_$calls", TimerAssistant.TOOL_GET_TIME))
        }, FakeTools()).run("x")
        assertEquals(TimerAssistant.MAX_ROUNDS, calls)
        assertEquals(TimerAssistant.MAX_ROUNDS, out.rounds)
    }

    // ---- tools, prompt and time text ----

    @Test fun `definitions are the four tools, and set_timer requires only seconds`() {
        val defs = TimerAssistant.definitions()
        assertEquals(4, defs.length())
        assertEquals(
            listOf(TimerAssistant.TOOL_SET_TIMER, TimerAssistant.TOOL_GET_TIME, TimerAssistant.TOOL_ASK_USER, TimerAssistant.TOOL_CANCEL_TIMER),
            (0 until defs.length()).map { defs.getJSONObject(it).getString("name") },
        )
        for (i in 0 until defs.length()) {
            val tool = defs.getJSONObject(i)
            assertTrue(tool.getString("description").isNotBlank())
            assertEquals("object", tool.getJSONObject("input_schema").getString("type"))
            assertTrue(tool.getJSONObject("input_schema").has("properties"))
        }
        val set = defs.getJSONObject(0).getJSONObject("input_schema")
        assertEquals(1, set.getJSONArray("required").length())
        assertEquals("seconds", set.getJSONArray("required").getString(0))
        assertEquals("integer", set.getJSONObject("properties").getJSONObject("seconds").getString("type"))
        assertEquals("string", set.getJSONObject("properties").getJSONObject("label").getString("type"))

        assertEquals(0, defs.getJSONObject(1).getJSONObject("input_schema").getJSONObject("properties").length())
        assertFalse(defs.getJSONObject(1).getJSONObject("input_schema").has("required"))

        val askSchema = defs.getJSONObject(2).getJSONObject("input_schema")
        assertEquals("question", askSchema.getJSONArray("required").getString(0))

        val cancelSchema = defs.getJSONObject(3).getJSONObject("input_schema")
        assertEquals("integer", cancelSchema.getJSONObject("properties").getJSONObject("id").getString("type"))
        assertEquals("boolean", cancelSchema.getJSONObject("properties").getJSONObject("all").getString("type"))
        assertFalse(cancelSchema.has("required"))
    }

    @Test fun `the system prompt holds the phone's date, time, zone and the no-time answer`() {
        val prompt = TimerAssistant.systemPrompt(now)
        assertTrue(prompt.contains("Saturday, 21 November 2026"))
        assertTrue(prompt.contains("10:27:05 AM"))
        assertTrue(prompt.contains("America/Los_Angeles"))
        assertTrue(prompt.contains(TimerAssistant.describe(now)))
        assertTrue(prompt.contains("answer exactly: ${TimerAssistant.SAY_NO_TIME}"))
        assertTrue(prompt.contains("set_timer"))
        assertTrue(prompt.contains("get_time"))
        assertTrue(prompt.contains("ask_user"))
        assertTrue(prompt.contains("cancel_timer"))
        assertTrue(prompt.contains("answer exactly: ${TimerAssistant.SAY_NONE_TO_CANCEL}"))
        assertTrue(prompt.contains("The timers on the phone now, soonest first (a finished one has 0 seconds left and may be ringing): []"))
    }

    @Test fun `the system prompt lists the timers on the phone`() {
        val ms = now.toInstant().toEpochMilli()
        val timers = listOf(TimerEntry(4, "Pasta", ms + 600_000))
        val prompt = TimerAssistant.systemPrompt(now, timers)
        assertTrue(prompt.contains(TimerAssistant.describeTimers(timers, now)))
        assertTrue(prompt.contains("\"label\":\"Pasta\""))
    }

    @Test fun `describeTimers gives id, label, state, seconds left and end time, soonest first`() {
        val ms = now.toInstant().toEpochMilli()
        val list = JSONArray(
            TimerAssistant.describeTimers(
                listOf(
                    TimerEntry(7, "Eggs", ms + 9 * 3600_000L),
                    TimerEntry(3, "Pasta", ms + 600_000 - 500),
                    TimerEntry(5, "Timer 1", ms - 60_000, rung = true),
                ),
                now,
            )
        )
        assertEquals(3, list.length())
        val done = list.getJSONObject(0)
        assertEquals(5, done.getInt("id"))
        assertEquals("finished", done.getString("state"))
        assertEquals(0, done.getInt("seconds_left"))
        assertEquals("10:26 AM today", done.getString("ends_at"))
        val pasta = list.getJSONObject(1)
        assertEquals(3, pasta.getInt("id"))
        assertEquals("Pasta", pasta.getString("label"))
        assertEquals("running", pasta.getString("state"))
        assertEquals(600, pasta.getInt("seconds_left"))
        assertEquals("10:37 AM today", pasta.getString("ends_at"))
        assertEquals("7:27 PM today", list.getJSONObject(2).getString("ends_at"))

        assertEquals("[]", TimerAssistant.describeTimers(emptyList(), now))
    }

    @Test fun `describe gives day, clock time, zone and offset`() {
        assertEquals(
            "Saturday, 21 November 2026, 10:27:05 AM, time zone America/Los_Angeles (PT, UTC-08:00)",
            TimerAssistant.describe(now),
        )
        val utc = ZonedDateTime.of(2026, 11, 21, 18, 27, 5, 0, ZoneId.of("UTC"))
        assertEquals("Saturday, 21 November 2026, 6:27:05 PM, time zone UTC (UTC, UTC+00:00)", TimerAssistant.describe(utc))
    }

    @Test fun `describeSet says today or tomorrow and the end time, as JSON`() {
        val today = JSONObject(TimerAssistant.describeSet("Pasta", 600, now))
        assertTrue(today.getBoolean("set"))
        assertEquals("Pasta", today.getString("label"))
        assertEquals(600L, today.getLong("seconds"))
        assertEquals("10:37 AM today", today.getString("ends_at"))

        val tomorrow = JSONObject(TimerAssistant.describeSet("", 14 * 3600L, now))
        assertEquals("", tomorrow.getString("label"))
        assertEquals("12:27 AM tomorrow", tomorrow.getString("ends_at"))

        assertEquals("10:27 AM tomorrow", JSONObject(TimerAssistant.describeSet("Bread", 86_400, now)).getString("ends_at"))

        val later = JSONObject(TimerAssistant.describeSet("x", 2 * 86_400L, now))
        assertEquals("10:27 AM Monday, 23 November 2026", later.getString("ends_at"))

        val quoted = JSONObject(TimerAssistant.describeSet("Mom's \"special\" eggs", 60, now))
        assertEquals("Mom's \"special\" eggs", quoted.getString("label"))

        assertFalse(today.has("id"))
        assertEquals(9, JSONObject(TimerAssistant.describeSet("Pasta", 600, now, id = 9)).getInt("id"))
    }

    // ---- cancelling ----

    @Test fun `cancel the pasta timer cancels it by id and says Claude's line`() {
        val script = Script(
            turn("tool_use", thinking(), cancel("toolu_1", 3)),
            turn("end_turn", text("Pasta timer cancelled.")),
        )
        val tools = FakeTools(onPhone = mapOf(3 to "Pasta", 4 to "Eggs"))
        val out = assistant(script, tools).run("cancel the pasta timer")

        assertEquals("Pasta timer cancelled.", out.spoken)
        assertFalse(out.failed)
        assertEquals(1, out.timersCancelled)
        assertEquals(0, out.timersSet)
        assertEquals(listOf(3), tools.cancelled)
        val result = results(script, 1).getJSONObject(0)
        assertFalse(result.optBoolean("is_error", false))
        val body = JSONObject(result.getString("content"))
        assertEquals("Pasta", body.getJSONArray("cancelled").getString(0))
        assertEquals(1, body.getJSONArray("timers_now").length())
        assertEquals("Eggs", body.getJSONArray("timers_now").getJSONObject(0).getString("label"))
    }

    @Test fun `an unknown id is an error with the timers now, and nothing is counted`() {
        val script = Script(
            turn("tool_use", cancel("toolu_1", 99)),
            turn("end_turn", text("There's no such timer.")),
        )
        val tools = FakeTools(onPhone = mapOf(3 to "Pasta"))
        val out = assistant(script, tools).run("cancel the bread")

        assertEquals(0, out.timersCancelled)
        assertTrue(tools.cancelled.isEmpty())
        val result = results(script, 1).getJSONObject(0)
        assertTrue(result.getBoolean("is_error"))
        assertTrue(result.getString("content").contains("no timer with id 99"))
        assertTrue(result.getString("content").contains("Pasta"))
    }

    @Test fun `an id missing or not whole is an error`() {
        for (input in listOf(cancel("toolu_1"), cancel("toolu_1", "pasta"), cancel("toolu_1", 2.5), cancel("toolu_1", 3, all = false))) {
            val script = Script(turn("tool_use", input), turn("end_turn", text("ok")))
            val tools = FakeTools(onPhone = mapOf(2 to "Eggs"))
            val out = assistant(script, tools).run("cancel")
            val result = results(script, 1).getJSONObject(0)
            if (input.getJSONObject("input").has("all")) {
                // all false with an id is a plain cancel by id (3 is not on the phone).
                assertTrue(result.getString("content").contains("no timer with id 3"))
            } else {
                assertTrue(result.getString("content").startsWith("id is required"))
            }
            assertTrue(result.getBoolean("is_error"))
            assertEquals(0, out.timersCancelled)
            assertTrue(tools.cancelled.isEmpty())
        }
    }

    @Test fun `cancel all stops every timer and counts each`() {
        val script = Script(
            turn("tool_use", cancel("toolu_1", all = true)),
            turn("end_turn", text("All three timers cancelled.")),
        )
        val tools = FakeTools(onPhone = mapOf(1 to "Pasta", 2 to "Eggs", 5 to "Timer 1"))
        val out = assistant(script, tools).run("cancel all timers")

        assertEquals("All three timers cancelled.", out.spoken)
        assertEquals(3, out.timersCancelled)
        assertEquals(listOf(1, 2, 5), tools.cancelled)
        assertTrue(tools.phone.isEmpty())
        val body = JSONObject(results(script, 1).getJSONObject(0).getString("content"))
        assertEquals(listOf("Pasta", "Eggs", "Timer 1"), (0 until 3).map { body.getJSONArray("cancelled").getString(it) })
        assertEquals(0, body.getJSONArray("timers_now").length())
    }

    @Test fun `all wins over an id`() {
        val script = Script(turn("tool_use", cancel("toolu_1", 1, all = true)), turn("end_turn", text("ok")))
        val tools = FakeTools(onPhone = mapOf(1 to "Pasta", 2 to "Eggs"))
        assertEquals(2, assistant(script, tools).run("cancel everything").timersCancelled)
    }

    @Test fun `cancel all with no timers says there were none and counts nothing`() {
        val script = Script(
            turn("tool_use", cancel("toolu_1", all = true)),
            turn("end_turn", text(TimerAssistant.SAY_NONE_TO_CANCEL)),
        )
        val out = assistant(script, FakeTools()).run("cancel all timers")
        assertEquals(TimerAssistant.SAY_NONE_TO_CANCEL, out.spoken)
        assertEquals(0, out.timersCancelled)
        assertFalse(out.failed)
        val result = results(script, 1).getJSONObject(0)
        assertFalse(result.optBoolean("is_error", false))
        assertEquals("There were no timers to cancel.", result.getString("content"))
    }

    @Test fun `with no timers on the phone Claude answers without a tool and nothing is cancelled`() {
        val script = Script(turn("end_turn", text(TimerAssistant.SAY_NONE_TO_CANCEL)))
        val tools = FakeTools()
        val out = assistant(script, tools).run("cancel the timer")
        assertEquals(TimerAssistant.SAY_NONE_TO_CANCEL, out.spoken)
        assertEquals(1, out.rounds)
        assertEquals(0, out.timersCancelled)
        assertTrue(tools.cancelled.isEmpty())
    }

    @Test fun `when it is unclear which, Claude asks and cancels the one named in the answer`() {
        val script = Script(
            turn("tool_use", ask("toolu_1", "Pasta or Eggs?")),
            turn("tool_use", cancel("toolu_2", 4)),
            turn("end_turn", text("Eggs timer cancelled.")),
        )
        val tools = FakeTools(answers = listOf("the eggs"), onPhone = mapOf(3 to "Pasta", 4 to "Eggs"))
        val out = assistant(script, tools).run("cancel the timer")

        assertEquals(listOf("Pasta or Eggs?"), tools.questions)
        assertEquals("the eggs", results(script, 1).getJSONObject(0).getString("content"))
        assertEquals(listOf(4), tools.cancelled)
        assertEquals(1, out.timersCancelled)
        assertEquals("Eggs timer cancelled.", out.spoken)
    }

    @Test fun `set and cancel in one request count both`() {
        val script = Script(
            turn("tool_use", cancel("toolu_1", 3), setTimer("toolu_2", 300, "Pasta")),
            turn("end_turn", text("Pasta timer set for 5 minutes.")),
        )
        val tools = FakeTools(onPhone = mapOf(3 to "Pasta"))
        val out = assistant(script, tools).run("make the pasta 5 minutes instead")
        assertEquals(1, out.timersSet)
        assertEquals(1, out.timersCancelled)
    }

    @Test fun `an empty last turn after cancelling says timer cancelled, timers cancelled or done`() {
        fun spoken(vararg first: JSONObject, phone: Map<Int, String>) =
            assistant(Script(turn("tool_use", *first), turn("end_turn")), FakeTools(onPhone = phone)).run("x").spoken
        assertEquals(TimerAssistant.SAY_CANCELLED, spoken(cancel("t1", 1), phone = mapOf(1 to "Pasta")))
        assertEquals(TimerAssistant.SAY_CANCELLED_ALL, spoken(cancel("t1", all = true), phone = mapOf(1 to "Pasta", 2 to "Eggs")))
        assertEquals(TimerAssistant.SAY_DONE, spoken(cancel("t1", 1), setTimer("t2", 60), phone = mapOf(1 to "Pasta")))
    }

    @Test fun `an error after a timer was cancelled does not say that nothing happened`() {
        val script = Script(
            turn("tool_use", cancel("toolu_1", 1)),
            Turn(error = "overloaded", httpCode = 529),
        )
        val out = assistant(script, FakeTools(onPhone = mapOf(1 to "Pasta"))).run("cancel the pasta")
        assertEquals(TimerAssistant.SAY_CANCELLED, out.spoken)
        assertFalse(out.failed)
        assertEquals(1, out.timersCancelled)
    }

    @Test fun `a cancel that throws is an error result and nothing is counted`() {
        val tools = object : TimerAssistant.Tools by FakeTools() {
            override fun cancelTimer(id: Int): String? = throw IllegalStateException("stopped")
        }
        val script = Script(turn("tool_use", cancel("toolu_1", 1)), turn("end_turn", text("ok")))
        val out = TimerAssistant({ script.send(it) }, tools).run("cancel")
        assertEquals(0, out.timersCancelled)
        val result = results(script, 1).getJSONObject(0)
        assertTrue(result.getBoolean("is_error"))
        assertEquals("The tool failed: IllegalStateException", result.getString("content"))
    }
}
