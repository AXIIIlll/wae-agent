package com.wae.agent.core.automaton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 測試：編譯去抖策略（純函式）與抵達驗證的相似度語意。
 */
class CompilePolicyTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `first compile always happens`() {
        assertTrue(AutomatonCompilePolicy.shouldCompile(0, t0, 0L, hasExistingGraph = false))
    }

    @Test
    fun `enough new steps triggers compile`() {
        assertTrue(
            AutomatonCompilePolicy.shouldCompile(
                newSteps = AutomatonCompilePolicy.MIN_NEW_STEPS,
                nowMs = t0 + 1000,
                lastCompileMs = t0,
                hasExistingGraph = true
            )
        )
    }

    @Test
    fun `few steps shortly after last compile are skipped`() {
        assertFalse(
            AutomatonCompilePolicy.shouldCompile(
                newSteps = 2,
                nowMs = t0 + 60_000,
                lastCompileMs = t0,
                hasExistingGraph = true
            )
        )
    }

    @Test
    fun `long interval forces a refresh even with few steps`() {
        assertTrue(
            AutomatonCompilePolicy.shouldCompile(
                newSteps = 1,
                nowMs = t0 + AutomatonCompilePolicy.MIN_INTERVAL_MS,
                lastCompileMs = t0,
                hasExistingGraph = true
            )
        )
    }

    @Test
    fun `arrival threshold matches the 8 lane resolution`() {
        // 8 通道 MinHash 的相似度只會是 0, 0.125, ..., 1.0
        val a = MinHash.fingerprint((0L until 40L).toList())
        val same = MinHash.fingerprint((0L until 40L).toList())
        val different = MinHash.fingerprint((1000L until 1040L).toList())
        assertTrue("同一頁面必須通過抵達驗證", MinHash.similarity(a, same) >= 0.5f)
        assertTrue("完全不同的頁面不可通過", MinHash.similarity(a, different) < 0.5f)
    }

    @Test
    fun `planner returns multi step plans that P1 can execute`() {
        fun fp(b: Long) = MinHash.fingerprint((b until b + 20).toList())
        val states = listOf(
            AutomatonState("A", fp(0), "NAV_HOME"),
            AutomatonState("B", fp(100), "LIST_FEED"),
            AutomatonState("C", fp(200), "DETAIL")
        )
        val edges = listOf(
            AutomatonEdge("A", "B", "TAP", "id:tab", ok = 9, fail = 1, p95Ms = 200, firstTs = 1, lastTs = 1),
            AutomatonEdge("B", "C", "TAP", "id:row", ok = 9, fail = 1, p95Ms = 200, firstTs = 1, lastTs = 1)
        )
        val auto = Automaton(pkg = "p", states = states, edges = edges)
        val plan = AutomatonPlanner.planToSelectorHint(auto, "A", "row")
        assertTrue("應規劃出 1 步（B 上有 id:row）", plan != null && plan.steps == 1)
        val plan2 = AutomatonPlanner.planToSelectorHint(auto, "A", "tab")
        assertTrue("元素在當前頁時為 0 步", plan2 != null && plan2.steps == 0)
    }
}
