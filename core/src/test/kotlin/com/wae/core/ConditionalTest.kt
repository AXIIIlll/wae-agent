package com.wae.core

import com.wae.core.intent.Conditional
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 條件句的表層處理（C8 最小版）。
 *
 * 設計選擇：只剝語法，不在這裡判斷「開著還是關著」——
 * 狀態要靠執行期的畫面讀，不是靠句子猜。
 */
class ConditionalTest {

    @Test
    fun `strips if clause and keeps the action`() {
        assertEquals("關掉Wi-Fi", Conditional.strip("如果開著就關掉Wi-Fi"))
        assertEquals("關閉藍牙", Conditional.strip("如果藍牙開著就關閉藍牙"))
        assertEquals("打開設定", Conditional.strip("假如還沒開就打開設定"))
        assertEquals("調高音量", Conditional.strip("要是太小聲就調高音量"))
    }

    @Test
    fun `leaves normal commands untouched`() {
        assertEquals("打開設定", Conditional.strip("打開設定"))
        assertEquals("調高音量", Conditional.strip("調高音量"))
        assertEquals("取消", Conditional.strip("取消"))
    }

    @Test
    fun `detects conditional sentences`() {
        assertTrue(Conditional.isConditional("如果開著就關掉Wi-Fi"))
        assertFalse(Conditional.isConditional("打開設定"))
    }

    @Test
    fun `empty condition clause still strips the wrapper`() {
        // 「如果就打開設定」語意上等於「打開設定」：條件子句是空的，剝掉就好
        assertEquals("打開設定", Conditional.strip("如果就打開設定"))
    }
}
