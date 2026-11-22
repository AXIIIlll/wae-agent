package com.wae.core

import com.wae.core.step.LegacyStepMapping
import com.wae.core.step.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 舊式一句一步 → 封閉詞彙的轉換（三層兩憶計畫 §5 的橋）。
 *
 * 這層的價值：大模型不必改輸出格式，也能被新的單步政策使用。
 */
class LegacyStepMappingTest {

    @Test
    fun `search step expands into four goals`() {
        val goals = LegacyStepMapping.toGoals("在設定裡搜尋藍牙", target = "藍牙")
        assertEquals(
            listOf(StepKind.QUERY_FIELD, StepKind.SET_TEXT, StepKind.SUBMIT, StepKind.DONE),
            goals.map { it.kind }
        )
        assertEquals("藍牙", goals[1].hint)
    }

    @Test
    fun `search without explicit target uses the rest of the sentence`() {
        val goals = LegacyStepMapping.toGoals("搜尋台北車站")
        assertEquals(StepKind.SET_TEXT, goals[1].kind)
        assertTrue("要從句子裡抓到搜尋字", goals[1].hint.contains("台北車站"))
    }

    @Test
    fun `tap step becomes list item then done`() {
        val goals = LegacyStepMapping.toGoals("點第一支影片")
        assertEquals(StepKind.LIST_ITEM, goals.first().kind)
        assertEquals(StepKind.DONE, goals.last().kind)
    }

    @Test
    fun `scroll step keeps direction`() {
        val goals = LegacyStepMapping.toGoals("往上滑找更多")
        assertEquals(StepKind.SCROLL, goals.first().kind)
        assertEquals("up", goals.first().hint)
    }

    @Test
    fun `unknown step degrades to done instead of guessing an action`() {
        val goals = LegacyStepMapping.toGoals("確認一下現在狀態")
        assertEquals(listOf(StepKind.DONE), goals.map { it.kind })
    }
}
