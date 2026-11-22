package com.wae.agent.core.automaton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S29：AutomatonPlanner 的邊重排序掛鉤（edgeBonus）測試。
 *
 * 驗證三件事：
 *  1. `edgeBonus = { 0.0 }`（預設）與完全不傳參數的計畫**完全一致** → 舊行為不受影響
 *  2. bonus 真的會改變 A* 成本與路線（負 bonus 當懲罰可讓直達改走繞路）
 *  3. bonus 再大都不會產生負成本（MIN_EDGE_COST_SEC 下限）
 */
class AutomatonPlannerEdgeBonusTest {

    private fun state(id: String, sig: Long) = AutomatonState(
        id = id,
        fingerprint = MinHash.fingerprint(listOf(sig)),
        posture = "LIST_FEED"
    )

    private fun edge(from: String, to: String, sel: String, ok: Int, fail: Int) =
        AutomatonEdge(
            from = from, to = to, op = "TAP", selector = sel,
            ok = ok, fail = fail, p95Ms = 100,
            lat = listOf(ok + fail, 0, 0, 0)
        )

    /** s0→s2 直達（1 步）；s0→s1→s2 繞路（2 步） */
    private fun graph(): Automaton = Automaton(
        pkg = "test.pkg",
        states = listOf(state("s0", 1L), state("s1", 2L), state("s2", 3L)),
        edges = listOf(
            edge("s0", "s2", "id:direct", ok = 95, fail = 0),
            edge("s0", "s1", "id:hop1", ok = 95, fail = 0),
            edge("s1", "s2", "id:hop2", ok = 95, fail = 0)
        )
    )

    @Test
    fun zeroBonusIsIdenticalToDefault() {
        val auto = graph()
        val baseline = AutomatonPlanner.planToState(auto, "s0", "s2")
        val withZero = AutomatonPlanner.planToState(auto, "s0", "s2", edgeBonus = { 0.0 })
        assertNotNull(baseline)
        assertNotNull(withZero)
        assertEquals(baseline!!.states, withZero!!.states)
        assertEquals(
            baseline.edges.map { it.selector },
            withZero.edges.map { it.selector }
        )
        assertEquals(baseline.expectedCostMs, withZero.expectedCostMs)
    }

    @Test
    fun penaltyOnDirectEdgeFlipsRouteToDetour() {
        val auto = graph()
        val baseline = AutomatonPlanner.planToState(auto, "s0", "s2")!!
        assertEquals("基線應走直達", 1, baseline.steps)
        assertEquals("id:direct", baseline.edges.first().selector)

        // 對直達邊加懲罰（負 bonus = 成本增加 10 秒）→ 應改走兩步繞路
        val flipped = AutomatonPlanner.planToState(auto, "s0", "s2", edgeBonus = { e ->
            if (e.selector == "id:direct") -10.0 else 0.0
        })!!
        assertEquals("懲罰後應改走繞路", 2, flipped.steps)
        assertTrue("不應再包含直達邊", flipped.edges.none { it.selector == "id:direct" })
    }

    @Test
    fun hugeBonusDoesNotProduceNegativeCost() {
        val auto = graph()
        val plan = AutomatonPlanner.planToState(auto, "s0", "s2", edgeBonus = { 1000.0 })!!
        assertTrue("成本應 >= 下限 10ms，實際=${plan.expectedCostMs}", plan.expectedCostMs >= 10)
    }

    @Test
    fun bonusReducesExpectedCost() {
        val auto = graph()
        val baseline = AutomatonPlanner.planToState(auto, "s0", "s2")!!
        val cheaper = AutomatonPlanner.planToState(auto, "s0", "s2", edgeBonus = { 0.05 })!!
        assertTrue(
            "bonus 後成本應下降：baseline=${baseline.expectedCostMs} cheaper=${cheaper.expectedCostMs}",
            cheaper.expectedCostMs < baseline.expectedCostMs
        )
    }

    @Test
    fun goalHintPlanAcceptsEdgeBonus() {
        val auto = graph()
        // hint "hop2" 命中「有 hop2 出邊的狀態」= s1；goalOp 留空 → 只看 selector token，
        // 從 s0 應規劃 s0→s1（id:hop1，1 步）
        val plan = AutomatonPlanner.planToGoalHint(
            automaton = auto, from = "s0", hint = "hop2", goalOp = "",
            edgeBonus = { 0.0 }
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.steps)
        assertEquals("id:hop1", plan.edges.first().selector)
    }
}
