package com.wae.agent.core.action

import com.wae.core.util.TextHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 選擇器鍵的契約。
 *
 * 這裡最重要的一條不是「鍵要對得上」，而是**鍵不能含畫面原文**——
 * 它會被寫進自動機圖、收據、負面知識（見 `SelectorKeys` 類別說明）。
 */
class SelectorKeysTest {

    private val secrets = listOf("王小明", "今晚八點見", "我的銀行帳號", "secret message")

    @Test
    fun `viewId wins over text`() {
        val key = SelectorKeys.of(
            viewId = "com.android.settings:id/search_src_text",
            roleTag = "EDIT",
            text = "搜尋設定",
            centerX = 10, centerY = 20
        )
        assertEquals("id:search_src_text", key)
    }

    @Test
    fun `role with text uses a hash not the raw text`() {
        val key = SelectorKeys.of("", "BTN", "王小明", 0, 0)
        assertTrue(key.startsWith("role:BTN:"))
        assertFalse("鍵不得含原文", key.contains("王小明"))
        assertEquals("role:BTN:" + SelectorKeys.hashOf("王小明"), key)
    }

    @Test
    fun `text-only selector uses a hash not the raw text`() {
        val key = SelectorKeys.of("", "", "今晚八點見", 0, 0)
        assertTrue(key.startsWith("text:"))
        assertFalse(key.contains("今晚八點見"))
    }

    @Test
    fun `grid is the last resort`() {
        assertEquals("grid:540,1200", SelectorKeys.of("", "", "", 540, 1200))
    }

    @Test
    fun `same text gives same key so matching still works`() {
        val a = SelectorKeys.of("", "ROW", "爸爸", 1, 2)
        val b = SelectorKeys.of("", "ROW", "爸爸", 999, 888)
        assertEquals(a, b)
    }

    @Test
    fun `different text gives different key`() {
        val a = SelectorKeys.of("", "ROW", "爸爸", 1, 2)
        val b = SelectorKeys.of("", "ROW", "媽媽", 1, 2)
        assertNotEquals(a, b)
    }

    @Test
    fun `normalisation makes case and spacing irrelevant`() {
        val a = SelectorKeys.of("", "ROW", "Wi-Fi", 0, 0)
        val b = SelectorKeys.of("", "ROW", "  wi-fi  ", 0, 0)
        assertEquals(a, b)
    }

    @Test
    fun `no secret from the screen ever appears in any key shape`() {
        for (s in secrets) {
            val shapes = listOf(
                SelectorKeys.of("", "BTN", s, 0, 0),
                SelectorKeys.of("", "", s, 0, 0),
                SelectorKeys.of("", "EDIT", s, 10, 10)
            )
            for (key in shapes) {
                assertFalse("鍵含原文：$key", key.contains(s))
                assertFalse("鍵含原文：$key", key.contains(s.take(4)))
            }
        }
    }

    @Test
    fun `hash length is short and hex`() {
        val h = SelectorKeys.hashOf("任何文字")
        assertEquals(8, h.length)
        assertTrue(h.all { it in "0123456789abcdef" })
        assertEquals(TextHash.of("任何文字").take(8), h)
    }
}
