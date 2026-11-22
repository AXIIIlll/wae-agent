package com.wae.agent.core.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S32：可操作元素圖（元素層級的 Beta 後驗加成）。
 *
 * 守住：
 *  1. 沒有樣本 → bonus = 0（讓規則/語意分數主導）
 *  2. 成功樣本多 → 正加成；失敗樣本多 → 負加成
 *  3. 樣本少時加成被 shrink（不會一次成功就翻盤）
 *  4. 不同 App / 不同頁面狀態 / 不同 selector 互不污染
 *  5. 沒有 Context 時 save 安全回 false
 */
class ElementGraphTest {

    @Before
    fun setUp() {
        ElementGraph.resetForTest()
    }

    @Test
    fun `no samples means zero bonus`() {
        assertEquals(0f, ElementGraph.bonus("com.a", "state1", "id:btn"), 1e-6f)
        assertNull(ElementGraph.statOf("com.a", "state1", "id:btn"))
    }

    @Test
    fun `successes give positive bonus and failures negative`() {
        repeat(5) { ElementGraph.record("com.a", "s1", "id:ok", success = true, nowMs = 1L) }
        repeat(5) { ElementGraph.record("com.a", "s1", "id:bad", success = false, nowMs = 1L) }
        assertTrue(ElementGraph.bonus("com.a", "s1", "id:ok") > 0f)
        assertTrue(ElementGraph.bonus("com.a", "s1", "id:bad") < 0f)
    }

    @Test
    fun `single sample is shrunk`() {
        ElementGraph.record("com.a", "s1", "id:one", success = true, nowMs = 1L)
        val one = ElementGraph.bonus("com.a", "s1", "id:one")
        repeat(9) { ElementGraph.record("com.a", "s1", "id:many", success = true, nowMs = 1L) }
        val many = ElementGraph.bonus("com.a", "s1", "id:many")
        assertTrue("單次成功應該比十次成功小：one=$one many=$many", one < many)
        assertTrue(one > 0f)
    }

    @Test
    fun `keys are scoped by app state and selector`() {
        repeat(5) { ElementGraph.record("com.a", "s1", "id:x", success = true, nowMs = 1L) }
        assertTrue(ElementGraph.bonus("com.a", "s1", "id:x") > 0f)
        assertEquals(0f, ElementGraph.bonus("com.b", "s1", "id:x"), 1e-6f)
        assertEquals(0f, ElementGraph.bonus("com.a", "s2", "id:x"), 1e-6f)
        assertEquals(0f, ElementGraph.bonus("com.a", "s1", "id:y"), 1e-6f)
    }

    @Test
    fun `bonus is bounded`() {
        repeat(1000) { ElementGraph.record("com.a", "s1", "id:perfect", success = true, nowMs = 1L) }
        repeat(1000) { ElementGraph.record("com.a", "s1", "id:broken", success = false, nowMs = 1L) }
        assertTrue(ElementGraph.bonus("com.a", "s1", "id:perfect") <= 0.35f)
        assertTrue(ElementGraph.bonus("com.a", "s1", "id:broken") >= -0.35f)
    }

    @Test
    fun `save without context is safe`() {
        ElementGraph.record("com.a", "s1", "id:x", success = true, nowMs = 1L)
        assertFalse(ElementGraph.save())
    }

    @Test
    fun `reset clears stats`() {
        ElementGraph.record("com.a", "s1", "id:x", success = true, nowMs = 1L)
        assertTrue(ElementGraph.size() > 0)
        ElementGraph.resetForTest()
        assertEquals(0, ElementGraph.size())
    }
}
