package com.lperezmo.voicerelay.glyph

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ActionTest {
    private fun roundTrip(a: Action) = Action.fromJson(JSONObject(a.toJson().toString()))

    @Test
    fun everyActionSurvivesJson() {
        val all = listOf(
            Action.Nothing,
            Action.OpenApp("com.android.settings", "Settings"),
            Action.Url("https://example.com"),
            Action.RelayClipboard,
            Action.WakeToggle,
            Action.PrintDateLabel,
            Action.Ask,
        )
        for (a in all) assertEquals(a, roundTrip(a))
    }

    @Test
    fun theDirectActionsHaveTheirOwnTypes() {
        assertEquals("wake_toggle", Action.WakeToggle.toJson().getString("type"))
        assertEquals("print_date_label", Action.PrintDateLabel.toJson().getString("type"))
        assertEquals("Toggle wake word", Action.WakeToggle.label)
        assertEquals("Print date label", Action.PrintDateLabel.label)
    }

    @Test
    fun askThePageIsStoredAsAsk() {
        assertEquals("ask", Action.Ask.toJson().getString("type"))
        assertEquals("Ask the Page", Action.Ask.label)
        assertEquals(Action.Ask, Action.fromJson(JSONObject().put("type", "ask")))
    }

    @Test
    fun theTwoTaskerTasksBecomeDirectActions() {
        val tasker = { task: String -> JSONObject().put("type", "tasker").put("task", task) }
        assertEquals(Action.WakeToggle, Action.fromJson(tasker("Wake Word On")))
        assertEquals(Action.PrintDateLabel, Action.fromJson(tasker("Print Date Label")))
    }

    @Test
    fun otherTaskerTasksLoadAsNothing() {
        val tasker = { task: String -> JSONObject().put("type", "tasker").put("task", task) }
        // running Tasker tasks is gone; only an exact name of the two above moves over
        for (task in listOf("Lights Off", "wake word on", "Wake Word Off", "Print Date Label ", "Print Date Labels", "")) {
            assertEquals(Action.Nothing, Action.fromJson(tasker(task)))
        }
        assertEquals(Action.Nothing, Action.fromJson(JSONObject().put("type", "tasker")))
    }

    @Test
    fun noActionIsStoredAsTasker() {
        val all = listOf(Action.Nothing, Action.OpenApp("p", "P"), Action.Url("u"), Action.RelayClipboard, Action.WakeToggle, Action.PrintDateLabel, Action.Ask)
        for (a in all) assertNotEquals("tasker", a.toJson().getString("type"))
    }

    @Test
    fun missingOrUnknownIsNothing() {
        assertEquals(Action.Nothing, Action.fromJson(null))
        assertEquals(Action.Nothing, Action.fromJson(JSONObject().put("type", "teleport")))
    }
}
