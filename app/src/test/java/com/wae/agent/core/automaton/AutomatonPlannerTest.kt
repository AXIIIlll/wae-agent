package com.wae.agent.core.automaton

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.action.SelectorKeys
import com.wae.agent.core.CandidateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 測試：自動機上的 A* 規劃與選擇器定位。
 *
 * 這些測試刻意用手動建構的小圖，讓「路徑、成本、負邊、成功率」都能逐項驗證。
 */
class AutomatonPlannerTest {

    private fun fp(base: Long) = MinHash.fingerprint((base until base + 20).toList())

    private fun state(id: String, base: Long, posture: String = "LIST_FEED") =
        AutomatonState(id = id, fingerprint = fp(base), posture = posture, visits = 1, lastTs = 1L)

    private fun edge(
        from: String,
        to: String,
        selector: String,
        op: String = "TAP",
        ok: Int = 3,
        fail: Int = 0,
        p95: Int = 300
    ) = AutomatonEdge(
        from = from, to = to, op = op, selector = selector,
        ok = ok, fail = fail, p95Ms = p95, firstTs = 1L, lastTs = 1L
    )

    /** A --(搜尋)--> B --(第一封)--> C，並有一條 A --(壞路)--> D（高失敗） */
    private fun sampleAutomaton(): Automaton {
        val states = listOf(
            state("A", 0),
            state("B", 100, "SEARCH_RESULTS"),
            state("C", 200, "DETAIL"),
            state("D", 300, "MODAL_DIALOG")
        )
        val edges = listOf(
            edge("A", "B", "id:search_box"),
            edge("B", "C", "role:list_item"),
            edge("A", "D", "id:bad", ok = 0, fail = 9, p95 = 2000)
        )
        return Automaton(pkg = "com.example", states = states, edges = edges, tier = "STANDARD")
    }

    @Test
    fun `plans a two hop path`() {
        val a = sampleAutomaton()
        val plan = AutomatonPlanner.planToState(a, "A", "C")
        assertNotNull("應能找到 A→B→C", plan)
        assertEquals(2, plan!!.steps)
        assertEquals(listOf("A", "B", "C"), plan.states)
        assertTrue("成功率應為各邊乘積（Beta 平滑後 0.8×0.8=0.64），實際=${plan.expectedSuccess}",
            plan.expectedSuccess in 0.60f..0.70f)
    }

    @Test
    fun `blocked edge forces alternative or no plan`() {
        // 把 B→C 設為禁區 → 只剩 A→D（但 D 不是目標）→ 無解
        val a = sampleAutomaton()
        val blocked = { e: AutomatonEdge -> e.from == "B" && e.to == "C" }
        assertNull("唯一可行路徑被封鎖時必須回報無解（不可硬走）",
            AutomatonPlanner.planToState(a, "A", "C", blocked))
    }

    @Test
    fun `same state needs no steps`() {
        val a = sampleAutomaton()
        val plan = AutomatonPlanner.planToState(a, "A", "A")
        assertNotNull(plan)
        assertEquals(0, plan!!.steps)
        assertEquals(1f, plan.expectedSuccess, 0.001f)
    }

    @Test
    fun `high failure edges are avoided when a cheaper route exists`() {
        val states = listOf(state("A", 0), state("B", 100), state("C", 200), state("X", 400))
        val edges = listOf(
            // 直接但很貴（一直失敗、很慢）
            edge("A", "C", "id:direct", ok = 1, fail = 9, p95 = 3000),
            // 兩段但很穩
            edge("A", "X", "id:x", ok = 9, fail = 1, p95 = 200),
            edge("X", "C", "id:c", ok = 9, fail = 1, p95 = 200)
        )
        val a = Automaton(pkg = "p", states = states, edges = edges)
        val plan = AutomatonPlanner.planToState(a, "A", "C")
        assertNotNull(plan)
        assertEquals("應選擇較穩的兩段路徑", 2, plan!!.steps)
        assertEquals(listOf("A", "X", "C"), plan.states)
    }

    @Test
    fun `selector hint finds the state that can operate the element`() {
        val a = sampleAutomaton()
        val plan = AutomatonPlanner.planToSelectorHint(a, "A", "搜尋")
        // 提示詞需要出現在選擇器或文字裡；此處 id:search_box 含 search
        val planEn = AutomatonPlanner.planToSelectorHint(a, "A", "search")
        assertNull("中文提示未出現在選擇器時應回報無解（保守）", plan)
        assertNotNull("英文提示可命中 search_box", planEn)
        assertEquals("元素就在當前頁時不需移動（0 步）", 0, planEn!!.steps)
        assertEquals(listOf("A"), planEn.states)
    }

    @Test
    fun `unreachable goal returns null`() {
        val a = Automaton(pkg = "p", states = listOf(state("A", 0), state("Z", 900)), edges = emptyList())
        assertNull(AutomatonPlanner.planToState(a, "A", "Z"))
        assertNull(AutomatonPlanner.planToState(a, "missing", "Z"))
    }

    @Test
    fun `plan is not trustworthy when long or low success`() {
        val a = sampleAutomaton()
        val plan = AutomatonPlanner.planToState(a, "A", "C")!!
        assertFalse("兩步路徑預設不直接跳過決策", AutomatonRuntime.isTrustworthy(plan))
        assertTrue("放寬到兩步則可", AutomatonRuntime.isTrustworthy(plan, maxSteps = 2))
        assertFalse("成功率太低不可信", AutomatonRuntime.isTrustworthy(plan, minSuccess = 0.999f))
    }

    // ── 選擇器定位 ────────────────────────────────────────────────────

    private fun cand(
        id: Int,
        text: String = "",
        viewId: String = "",
        role: String = "",
        grid: IntArray = intArrayOf(0, 0, 100, 100)
    ) = CandidateOption(
        markId = id,
        nodeStableSig = id.toLong(),
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = grid,
        coreText = text,
        weldedDescription = text,
        hybridSimScore = 0.5f,
        viewId = viewId,
        roleTag = role
    )

    @Test
    fun `locate matches id role text and grid`() {
        val options = listOf(
            cand(1, viewId = "com.x:id/search_box"),
            cand(2, text = "第一封信", role = "list_item"),
            cand(3, grid = intArrayOf(400, 800, 500, 900))
        )
        assertEquals(1, AutomatonRuntime.locate("id:search_box", options)?.markId)
        // 文字型 selector 存的是雜湊（隱私），所以要用 hashOf 建鍵
        assertEquals(2, AutomatonRuntime.locate("role:list_item", options)?.markId)
        assertEquals(2, AutomatonRuntime.locate("text:" + SelectorKeys.hashOf("第一封信"), options)?.markId)
        assertEquals(3, AutomatonRuntime.locate("grid:450,850", options)?.markId)
        assertNull("找不到就回 null，不可亂猜", AutomatonRuntime.locate("id:missing", options))
    }

    @Test
    fun `tokenize keeps useful tokens`() {
        val t = AutomatonPlanner.tokenize("搜尋 invoice 第一封")
        assertTrue(t.contains("搜尋"))
        assertTrue(t.contains("invoice"))
        assertTrue(t.contains("第一封"))
    }
}
