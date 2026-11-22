package com.wae.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 六個基礎動作的中文觸發詞測試（語言層資料，與 App 無關）。
 */
class ActionKeywordsTest {

    @Test
    fun `scroll up keywords`() {
        listOf("往上滑", "上滑", "回頂", "回到頂部").forEach {
            assertEquals("$it 應為 SCROLL_UP", ActionKind.SCROLL_UP, ActionKeywords.detect(it)?.kind)
        }
    }

    @Test
    fun `horizontal swipe keywords`() {
        listOf("左滑", "右滑", "往左滑", "橫向滑").forEach {
            assertEquals("$it 應為 SWIPE_HORIZONTAL", ActionKind.SWIPE_HORIZONTAL, ActionKeywords.detect(it)?.kind)
        }
    }

    @Test
    fun `long press keywords`() {
        assertEquals(ActionKind.LONG_PRESS, ActionKeywords.detect("長按這個圖片")?.kind)
        assertEquals(ActionKind.LONG_PRESS, ActionKeywords.detect("长按")?.kind)
    }

    @Test
    fun `submit keywords`() {
        assertEquals(ActionKind.SUBMIT, ActionKeywords.detect("送出")?.kind)
        assertEquals(ActionKind.SUBMIT, ActionKeywords.detect("直接搜尋")?.kind)
    }

    @Test
    fun `clear keywords`() {
        assertEquals(ActionKind.CLEAR_TEXT, ActionKeywords.detect("清除搜尋框")?.kind)
        assertEquals(ActionKind.CLEAR_TEXT, ActionKeywords.detect("清空")?.kind)
    }

    @Test
    fun `replace carries payload`() {
        val hit = ActionKeywords.detect("替換成台北101")
        assertEquals(ActionKind.REPLACE_TEXT, hit?.kind)
        assertEquals("台北101", hit?.payload)
    }

    @Test
    fun `ordinary commands are not overridden`() {
        assertNull(ActionKeywords.detect("搜尋台北101"))
        assertNull(ActionKeywords.detect("點開第一封信"))
        assertNull(ActionKeywords.detect("打開設定"))
    }

    @Test
    fun `decomposed goal uses the new action kind`() {
        val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals("往上滑")
        assertTrue(goals.isNotEmpty())
        assertEquals(ActionKind.SCROLL_UP, goals.first().actionKind)
    }

    @Test
    fun `replace command keeps the payload slot`() {
        val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals("替換成invoice")
        val g = goals.firstOrNull { it.actionKind == ActionKind.REPLACE_TEXT }
        assertTrue("應拆出 REPLACE_TEXT 目標", g != null)
        assertEquals("invoice", g!!.inputPayload)
    }
}
