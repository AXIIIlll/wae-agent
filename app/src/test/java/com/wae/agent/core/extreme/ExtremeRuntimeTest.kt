package com.wae.agent.core.extreme

import com.wae.agent.core.program.ProgramCompiler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §16 其餘子系統測試：S17 零感知、S21 熱路徑。
 *
 * 2026-11-16：S9 推測執行、S11 時間旅行、S26 AOT 已從執行期移除（只寫 log、無查詢點），
 * 對應測試也一併刪除。
 */
class ExtremeRuntimeTest {

    @Before
    fun setUp() {
        HotPathCache.resetForTest()
    }

    // ── S17 零感知 ────────────────────────────────────────────────

    private fun program(ops: List<ProgramCompiler.Op>) = ProgramCompiler.Program(
        pkg = "p", entryState = "A", goalState = "B", ops = ops,
        executions = 20, successRate = 0.98f
    )

    @Test
    fun `perception free requires verify after each action`() {
        val good = program(
            listOf(
                ProgramCompiler.Op.Guard("id:a"),
                ProgramCompiler.Op.Tap("id:a"),
                ProgramCompiler.Op.Verify("SPINE_CHANGED")
            )
        )
        val e = PerceptionFreeMode.evaluate(good)
        assertTrue(e.ok)
        assertTrue("每步應省下約 28ms", e.estimatedSavingMsPerStep >= 28)

        val bad = program(listOf(ProgramCompiler.Op.Tap("id:a")))
        assertFalse("沒有 VERIFY 不准零感知", PerceptionFreeMode.evaluate(bad).ok)
    }

    @Test
    fun `thirty step task cost stays under target`() {
        val free = PerceptionFreeMode.totalCostEstimate(30, perceptionFree = true)
        assertTrue("零感知長任務成本必須 < 100ms（實際=${free}ms）", free < 100)
        assertTrue("一般模式明顯較貴", PerceptionFreeMode.totalCostEstimate(30, false) > free * 10)
    }

    // ── S21 熱路徑 ────────────────────────────────────────────────

    @Test
    fun `hot path becomes hot after repeated use`() {
        val path = listOf("id:search", "id:row")
        repeat(2) { HotPathCache.record("p", path) }
        assertFalse(HotPathCache.isHot("p", path))
        HotPathCache.record("p", path)
        assertTrue("三次以上即為熱路徑", HotPathCache.isHot("p", path))
    }

    @Test
    fun `hot path cache is bounded and ranks by usage`() {
        repeat(40) { i -> repeat(3) { HotPathCache.record("p", listOf("id:p$i")) } }
        assertTrue("容量必須有上限", HotPathCache.size() <= 32)
        val top = HotPathCache.top("p", 3)
        assertEquals(3, top.size)
        assertTrue(top[0].hits >= top[1].hits)
    }

    @Test
    fun `hot path survives json round trip`() {
        repeat(3) { HotPathCache.record("p", listOf("id:a", "id:b")) }
        val json = HotPathCache.toJson()
        HotPathCache.resetForTest()
        HotPathCache.loadFromJson(json)
        assertTrue(HotPathCache.isHot("p", listOf("id:a", "id:b")))
    }
}
