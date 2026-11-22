package com.wae.agent.core.automaton

import com.wae.agent.core.action.NegativeEdgeStore
import com.wae.agent.core.action.NegativeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2 精化測試：
 *  1. 16 通道 MinHash（狀態區分度）
 *  2. 以「動作型別」當規劃提示（取代純文字比對）
 *  3. 負邊加上狀態維度（避免跨頁誤殺）
 *  4. 延遲直方圖估計 P95（不再被單次慢動作帶偏）
 */
class P2RefinementTest {

    @Before
    fun setUp() {
        NegativeEdgeStore.resetForTest()
    }

    private fun fp(b: Long) = MinHash.fingerprint((b until b + 20).toList())

    private fun state(id: String, b: Long, posture: String = "LIST_FEED") =
        AutomatonState(id = id, fingerprint = fp(b), posture = posture)

    private fun edge(
        from: String,
        to: String,
        sel: String,
        op: String = "TAP",
        ok: Int = 9,
        fail: Int = 1,
        p95: Int = 300,
        lat: List<Int> = listOf(0, 0, 0, 0)
    ) = AutomatonEdge(from, to, op, sel, ok, fail, p95, 1L, 1L, lat)

    // ── 1. MinHash 16 通道 ────────────────────────────────────────────

    @Test
    fun `minhash now uses sixteen lanes`() {
        assertEquals(16, MinHash.LANES)
        val a = MinHash.fingerprint((0L until 40L).toList())
        assertEquals(16, a.size)
        assertEquals(1f, MinHash.similarity(a, MinHash.fingerprint((0L until 40L).toList())), 0.001f)
    }

    @Test
    fun `sixteen lanes separate similar pages better than coarse ones`() {
        // 兩個只重疊一半的頁面，相似度應落在中間，而不是被 1/8 的粗粒度吸成同一格
        val a = (0L until 40L).toList()
        val b = (20L until 60L).toList()   // 與 a 重疊 20 個
        val sim = MinHash.similarity(MinHash.fingerprint(a), MinHash.fingerprint(b))
        assertTrue("重疊一半的頁面不該被當成完全相同（sim=$sim）", sim < 0.95f)
        assertTrue("也不該被當成完全不同（sim=$sim）", sim > 0.05f)
    }

    @Test
    fun `schema bump discards stale graphs`() {
        val legacy = """{"schema":1,"pkg":"p","states":[],"edges":[],"tier":"SIMPLE"}"""
        assertNull("舊 schema（8 通道）必須失效重編，避免指紋長度不符", Automaton.fromJson(legacy))
    }

    // ── 2. 以動作型別當提示 ──────────────────────────────────────────

    @Test
    fun `goal op hint finds the state that can type`() {
        val auto = Automaton(
            pkg = "p",
            states = listOf(state("A", 0, "NAV_HOME"), state("B", 100, "SEARCH_RESULTS")),
            edges = listOf(edge("A", "B", "id:search_box", op = "TYPE"))
        )
        // 文字提示（invoice）完全對不上選擇器 → 舊方法無解
        assertNull(AutomatonPlanner.planToSelectorHint(auto, "A", "invoice"))
        // 以動作型別當提示 → 目標狀態就是當前頁（元素在 A 上）
        val byOp = AutomatonPlanner.planToGoalHint(auto, "A", "invoice", "TYPE")
        assertNotNull("動作型別提示應命中", byOp)
        assertEquals(0, byOp!!.steps)
    }

    @Test
    fun `goal op hint also navigates when target is deeper`() {
        val auto = Automaton(
            pkg = "p",
            states = listOf(state("S", 500, "NAV_HOME"), state("A", 0, "LIST_FEED"), state("B", 100)),
            edges = listOf(
                edge("S", "A", "id:tab"),
                edge("A", "B", "id:search_box", op = "TYPE")
            )
        )
        val plan = AutomatonPlanner.planToGoalHint(auto, "S", "invoice", "TYPE")
        assertNotNull(plan)
        assertEquals(listOf("S", "A"), plan!!.states)
    }

    // ── 3. 負邊的狀態維度 ─────────────────────────────────────────────

    @Test
    fun `state scoped negative edge only blocks that state`() {
        NegativeEdgeStore.record("p", "id:x", "TAP", NegativeReason.WRONG_EFFECT, state = "A")
        assertTrue("同一狀態要擋", NegativeEdgeStore.isBlocked("p", "id:x", "TAP", state = "A"))
        assertFalse("其他狀態不該被牽連", NegativeEdgeStore.isBlocked("p", "id:x", "TAP", state = "B"))
        assertFalse("通用查詢（無狀態）不該命中狀態限定紀錄",
            NegativeEdgeStore.isBlocked("p", "id:x", "TAP"))
    }

    @Test
    fun `generic negative edge blocks every state`() {
        NegativeEdgeStore.record("p", "id:y", "TAP", NegativeReason.WRONG_EFFECT)
        assertTrue(NegativeEdgeStore.isBlocked("p", "id:y", "TAP", state = "A"))
        assertTrue(NegativeEdgeStore.isBlocked("p", "id:y", "TAP", state = "B"))
    }

    @Test
    fun `penalty takes the higher of state and generic`() {
        NegativeEdgeStore.record("p", "id:z", "TAP", NegativeReason.NO_EFFECT, state = "A")
        NegativeEdgeStore.record("p", "id:z", "TAP", NegativeReason.NO_EFFECT, state = "A")
        NegativeEdgeStore.record("p", "id:z", "TAP", NegativeReason.NO_EFFECT)
        val p1 = NegativeEdgeStore.penalty("p", "id:z", "TAP", state = "A")
        val p2 = NegativeEdgeStore.penalty("p", "id:z", "TAP", state = "B")
        assertTrue(p1 >= p2)
        assertTrue(p2 > 0f)
    }

    @Test
    fun `state scoped json round trip`() {
        NegativeEdgeStore.record("p", "id:s", "TAP", NegativeReason.WRONG_EFFECT, state = "A")
        val json = NegativeEdgeStore.toJson()
        NegativeEdgeStore.resetForTest()
        NegativeEdgeStore.loadFromJson(json)
        assertTrue(NegativeEdgeStore.isBlocked("p", "id:s", "TAP", state = "A"))
    }

    // ── 4. 延遲直方圖 ─────────────────────────────────────────────────

    @Test
    fun `histogram ignores a single slow outlier`() {
        // 99 次很快、1 次很慢：max 會是 2000，但真正的 P95 仍應落在最快那一格
        val e = edge("A", "B", "id:x", lat = listOf(99, 0, 0, 1), p95 = 2000)
        val p95 = e.p95Estimate()
        assertTrue("P95 不該被單次慢動作帶偏（實際=$p95）", p95 <= 500)
        assertTrue("也必須小於舊的 max 值", p95 < 2000)
    }

    @Test
    fun `histogram reflects a genuinely slow edge`() {
        val e = edge("A", "B", "id:slow", lat = listOf(10, 10, 10, 70), p95 = 300)
        assertTrue("常態性慢的邊，P95 應落最後一格（實際=${e.p95Estimate()}）", e.p95Estimate() >= 1000)
    }

    @Test
    fun `histogram falls back to legacy max when empty`() {
        val legacy = edge("A", "B", "id:x", lat = listOf(0, 0, 0, 0), p95 = 420)
        assertEquals(420, legacy.p95Estimate())
    }

    @Test
    fun `fast edges are cheap in planning cost`() {
        val fast = edge("A", "B", "id:f", lat = listOf(99, 1, 0, 0))
        val slow = edge("A", "C", "id:s", lat = listOf(0, 0, 0, 100))
        assertTrue("快邊的 P95 應低於慢邊", fast.p95Estimate() < slow.p95Estimate())
    }
}
