package com.wae.core.predicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字折疊測試。
 *
 * 這個物件是所有「不分大小寫」比對的基礎（App 名稱、標籤、選單、返回鍵…），
 * 它錯一次，所有比對都會錯。實測踩過的 bug：半形大寫被整段丟掉，
 * `Back` 折疊成 `ack`，於是「返回」偵測與 App 名稱比對全部失效。
 */
class TextFoldTest {

    @Test
    fun `ascii uppercase is lowercased not dropped`() {
        assertEquals("back", TextFold.fold("Back"))
        assertEquals("chrome", TextFold.fold("Chrome"))
        assertEquals("youtube", TextFold.fold("YouTube"))
    }

    @Test
    fun `fullwidth and ascii produce the same fold`() {
        assertEquals(TextFold.fold("Wi-Fi"), TextFold.fold("ｗｉ－ｆｉ"))
        assertEquals("wifi", TextFold.fold("Wi-Fi"))
        assertEquals("disney", TextFold.fold("Disney+"))
    }

    @Test
    fun `cjk is preserved and spaces and punctuation removed`() {
        assertEquals("在設定裡搜尋藍牙", TextFold.fold("在設定裡 搜尋，藍牙。"))
        assertEquals("上一頁", TextFold.fold("上一頁"))
    }

    @Test
    fun `containsFold is case insensitive in both directions`() {
        assertTrue(TextFold.containsFold("Back", "back"))
        assertTrue(TextFold.containsFold("Chrome", "chrome"))
        assertFalse(TextFold.containsFold("", "back"))
        // contains 是子字串語意（呼叫端要自己確保邊界，例如 BaselineGuard 用整段比對）
        assertTrue(TextFold.containsFold("backup", "back"))
    }

    @Test
    fun `empty needle never matches`() {
        assertFalse(TextFold.containsFold("anything", ""))
        assertEquals("", TextFold.fold(""))
    }
}
