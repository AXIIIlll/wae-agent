package com.wae.agent.core.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** P5 輸入降級鏈 + P6 遥測降權測試。 */
class InputAndTelemetryTest {

    private fun attempt(s: InputStrategy, ok: Boolean) = InputAttempt(s, ok)

    @Before
    fun reset() {
        ActionTelemetry.clear()
    }

    @Test
    fun `input starts with set text`() {
        assertEquals(InputStrategy.SET_TEXT, InputInjector.nextStrategy(emptyList()))
    }

    @Test
    fun `input degrades through all strategies then exhausts`() {
        assertEquals(
            InputStrategy.FOCUS_THEN_SET,
            InputInjector.nextStrategy(listOf(attempt(InputStrategy.SET_TEXT, false)))
        )
        val all = listOf(
            attempt(InputStrategy.SET_TEXT, false),
            attempt(InputStrategy.FOCUS_THEN_SET, false),
            attempt(InputStrategy.CLIPBOARD_PASTE, false),
            attempt(InputStrategy.CHAR_INJECTION, false)
        )
        assertNull(InputInjector.nextStrategy(all))
        assertTrue(InputInjector.exhausted(all))
    }

    @Test
    fun `summary records each attempt`() {
        val s = InputInjector.summarize(
            listOf(attempt(InputStrategy.SET_TEXT, false), attempt(InputStrategy.CLIPBOARD_PASTE, true))
        )
        assertTrue(s.contains("SET_TEXT=FAIL"))
        assertTrue(s.contains("CLIPBOARD_PASTE=OK"))
    }

    @Test
    fun `telemetry demotes flaky selector`() {
        repeat(4) { ActionTelemetry.record("com.a", "id:x", false) }
        repeat(1) { ActionTelemetry.record("com.a", "id:x", true) }
        assertTrue("成功率 20% 應被降權", ActionTelemetry.isDemoted("com.a", "id:x"))
        assertEquals(0.2f, ActionTelemetry.successRate("com.a", "id:x"), 1e-6f)
    }

    @Test
    fun `telemetry keeps healthy selector and requires samples`() {
        repeat(5) { ActionTelemetry.record("com.a", "id:y", true) }
        assertFalse(ActionTelemetry.isDemoted("com.a", "id:y"))
        // 樣本不足不降權
        ActionTelemetry.record("com.a", "id:z", false)
        assertFalse(ActionTelemetry.isDemoted("com.a", "id:z"))
        assertEquals(-1f, ActionTelemetry.successRate("com.a", "none"), 1e-6f)
    }
}