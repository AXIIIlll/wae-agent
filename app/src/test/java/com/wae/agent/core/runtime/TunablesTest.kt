package com.wae.agent.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 可調參數層：TSV 是預設值、SharedPreferences 是覆寫、程式碼是最後防線。
 * 這個測試保證「改參數不用改程式」真的成立。
 */
class TunablesTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    @Before
    fun setUp() {
        Tunables.resetForTest()
    }

    @Test
    fun `asset defines the documented defaults`() {
        Tunables.loadFromLines(asset("ui_tunables.tsv").readLines(Charsets.UTF_8).asSequence())
        assertEquals(20, Tunables.getInt(null, "budget.max_steps", -1))
        assertEquals(120_000L, Tunables.getLong(null, "budget.max_wallclock_ms", -1))
        assertTrue(Tunables.getFloat(null, "decision.auto_act_threshold", -1f) > 0.4f)
        assertEquals(6, Tunables.getInt(null, "perception.ocr_a11y_threshold", -1))
        assertTrue(Tunables.getBool(null, "voice.wake_word_enabled", false))
    }

    @Test
    fun `missing key falls back to code default`() {
        Tunables.loadFromLines(emptySequence())
        assertEquals(7, Tunables.getInt(null, "does.not.exist", 7))
        assertEquals("x", Tunables.raw("does.not.exist") ?: "x")
    }

    @Test
    fun `later lines override earlier ones so adb prefs can win`() {
        Tunables.loadFromLines(
            sequenceOf(
                "sample.limit\t12\tdefault",
                "sample.limit\t8\toverride"
            )
        )
        assertEquals(8, Tunables.getInt(null, "sample.limit", -1))
    }

    @Test
    fun `comments and malformed lines are ignored`() {
        Tunables.loadFromLines(
            sequenceOf("# comment", "", "sample.limit", "sample.window_ms\t90000\tx")
        )
        assertEquals(90000L, Tunables.getLong(null, "sample.window_ms", -1))
        assertEquals(null, Tunables.raw("sample.limit"))
    }

    @Test
    fun `bool accepts common spellings`() {
        Tunables.loadFromLines(sequenceOf("a\t1", "b\tfalse", "c\ton", "d\tnope"))
        assertTrue(Tunables.getBool(null, "a", false))
        assertFalse(Tunables.getBool(null, "b", true))
        assertTrue(Tunables.getBool(null, "c", false))
        assertFalse(Tunables.getBool(null, "d", false))
    }

    @Test
    fun `all returns the effective values for diagnostics`() {
        Tunables.loadFromLines(sequenceOf("sample.limit\t10"))
        assertEquals("10", Tunables.all()["sample.limit"])
    }
}
