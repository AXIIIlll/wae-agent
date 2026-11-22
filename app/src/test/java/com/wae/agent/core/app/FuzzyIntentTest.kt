package com.wae.agent.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 模糊語義 → 意圖框的行為測試。核心案例來自使用者的原句與常見口語。
 */
class FuzzyIntentTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    @Before
    fun setUp() {
        FuzzyIntent.resetForTest()
        FuzzyIntent.loadFrames(asset("ui_intent_frames.tsv").readLines(Charsets.UTF_8).asSequence())
        FuzzyIntent.loadSlots(asset("ui_intent_slots.tsv").readLines(Charsets.UTF_8).asSequence())
    }

    @Test
    fun `tables parse`() {
        assertEquals(5, FuzzyIntent.frameCount())
        assertEquals(2, FuzzyIntent.slotCount())
    }

    @Test
    fun `watching drama resolves to play_media without slots`() {
        val r = FuzzyIntent.resolve("我想看劇")
        assertTrue(r is FuzzyIntent.Result.Resolved)
        r as FuzzyIntent.Result.Resolved
        assertEquals("play_media", r.frame.intent)
        assertEquals("video", r.frame.template)
    }

    @Test
    fun `navigation extracts destination`() {
        val r = FuzzyIntent.resolve("導航到台北車站")
        assertTrue(r is FuzzyIntent.Result.Resolved)
        r as FuzzyIntent.Result.Resolved
        assertEquals("navigate", r.frame.intent)
        assertEquals("台北車站", r.slots["destination"])
        assertEquals("maps", r.frame.template)
    }

    @Test
    fun `navigation without destination asks`() {
        val r = FuzzyIntent.resolve("帶我去")
        assertTrue(r is FuzzyIntent.Result.Ask)
        r as FuzzyIntent.Result.Ask
        assertEquals("navigate", r.frame.intent)
        assertTrue(r.missing.contains("destination"))
    }

    @Test
    fun `search extracts query`() {
        val r = FuzzyIntent.resolve("幫我查一下今天匯率")
        assertTrue(r is FuzzyIntent.Result.Resolved)
        r as FuzzyIntent.Result.Resolved
        assertEquals("search_web", r.frame.intent)
        assertEquals("今天匯率", r.slots["query"])
    }

    @Test
    fun `unknown sentence returns null`() {
        assertNull(FuzzyIntent.resolve("把藍牙關掉"))
        assertNull(FuzzyIntent.resolve(""))
    }

    @Test
    fun `longest trigger wins`() {
        // 「看影片」比「看劇」長，且都在 play_media；這裡確認不會被更短的詞干擾
        val r = FuzzyIntent.resolve("我想看影片")
        assertTrue(r is FuzzyIntent.Result.Resolved)
        assertEquals("play_media", (r as FuzzyIntent.Result.Resolved).frame.intent)
    }
}
