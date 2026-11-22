package com.wae.agent.core.program

import com.wae.agent.core.automaton.Automaton
import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.automaton.AutomatonPlanner
import com.wae.agent.core.automaton.AutomatonState
import com.wae.agent.core.automaton.MinHash
import com.wae.agent.core.decision.DecisionMemo
import com.wae.agent.core.observe.FailureAttribution
import com.wae.agent.core.observe.GraphHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §16 第二層極限測試：S16 位元碼程式、S13 能力蒸餾、S18 失敗根因、S22 圖健康度。
 */
class SecondLayerTest {

    private fun fp(b: Long) = MinHash.fingerprint((b until b + 20).toList())

    private fun state(id: String, b: Long) = AutomatonState(id, fp(b), "LIST_FEED")

    private fun edge(from: String, to: String, sel: String, op: String = "TAP", ok: Int = 10, fail: Int = 0) =
        AutomatonEdge(from, to, op, sel, ok, fail, 200, 1L, 1L)

    private fun plan(vararg edges: AutomatonEdge) = AutomatonPlanner.Plan(
        states = listOf(edges.first().from) + edges.map { it.to },
        edges = edges.toList(),
        expectedCostMs = 600,
        expectedSuccess = 0.98f
    )

    @Before
    fun setUp() {
        DecisionMemo.resetForTest()
        FailureAttribution.resetForTest()
    }

    // ── S16 位元碼 ──────────────────────────────────────────────────

    @Test
    fun `compile requires enough evidence`() {
        val p = plan(edge("A", "B", "id:tab"))
        assertNull("執行次數不足不可編譯", ProgramCompiler.compile("pkg", p, executions = 9, successRate = 0.99f))
        assertNull("成功率不足不可編譯", ProgramCompiler.compile("pkg", p, executions = 20, successRate = 0.90f))
        assertNotNull("達標才編譯", ProgramCompiler.compile("pkg", p, executions = 10, successRate = 0.95f))
    }

    @Test
    fun `program encodes guard tap and verify`() {
        val p = ProgramCompiler.compile("pkg", plan(edge("A", "B", "id:tab")), 12, 0.97f)
        assertNotNull(p)
        assertEquals("每條邊 = 守衛 + 動作 + 驗證", 3, p!!.ops.size)
        assertTrue(p.ops[0] is ProgramCompiler.Op.Guard)
        assertTrue(p.ops[1] is ProgramCompiler.Op.Tap)
        assertTrue(p.ops[2] is ProgramCompiler.Op.Verify)
        assertNull("程式碼裡不應有任何『決策』指令", p.ops.firstOrNull { it is ProgramCompiler.Op.Wait })
    }

    @Test
    fun `program round trips through compact encoding`() {
        val p = ProgramCompiler.compile("pkg", plan(edge("A", "B", "id:tab"), edge("B", "C", "id:row")), 15, 0.96f)
        assertNotNull(p)
        val json = ProgramCompiler.programsToJson(listOf(p!!))
        val back = ProgramCompiler.programsFromJson(json)
        assertEquals(1, back.size)
        assertEquals(p.encode(), back.first().encode())
        assertEquals(p.ops.size, back.first().ops.size)
    }

    @Test
    fun `multi step program keeps order`() {
        val p = ProgramCompiler.compileFromEdges(
            "pkg", "A", "C",
            listOf(edge("A", "B", "id:tab"), edge("B", "C", "id:row")),
            executions = 15, successRate = 0.96f
        )
        assertNotNull(p)
        val sels = p!!.ops.filterIsInstance<ProgramCompiler.Op.Guard>().map { it.selector }
        assertEquals(listOf("id:tab", "id:row"), sels)
    }

    @Test
    fun `dry run passes when guards and verify hold`() {
        val p = ProgramCompiler.compile("pkg", plan(edge("A", "B", "id:tab")), 12, 0.97f)!!
        val facts = ProgramRunner.Facts(
            presentSelectors = setOf("id:tab"), spineChanged = true, pkg = "pkg"
        )
        val v = ProgramRunner.dryRun(p, facts)
        assertTrue(v.ok)
        assertEquals("位元碼模式下決策次數必須為 0", 0, ProgramRunner.decisionCount(v))
        assertTrue(ProgramRunner.isExecutable(v))
    }

    @Test
    fun `dry run fails fast when guard missing`() {
        val p = ProgramCompiler.compile("pkg", plan(edge("A", "B", "id:tab")), 12, 0.97f)!!
        val v = ProgramRunner.dryRun(p, ProgramRunner.Facts(presentSelectors = emptySet(), pkg = "pkg"))
        assertFalse(v.ok)
        assertEquals(0, v.failedAt)
        assertTrue(v.reason.startsWith("GUARD_FAILED"))
    }

    @Test
    fun `dry run fails when postcondition does not hold`() {
        val p = ProgramCompiler.compile("pkg", plan(edge("A", "B", "id:tab")), 12, 0.97f)!!
        val v = ProgramRunner.dryRun(
            p, ProgramRunner.Facts(presentSelectors = setOf("id:tab"), spineChanged = false, pkg = "pkg")
        )
        assertFalse("後置條件不成立 → 程式必須降級", v.ok)
        assertTrue(v.reason.startsWith("VERIFY_FAILED"))
    }

    // ── S13 能力蒸餾 ────────────────────────────────────────────────

    @Test
    fun `memo needs consistent history before recall`() {
        DecisionMemo.record("pkg", 42L, "search_submit", "id:search_box", ok = true)
        DecisionMemo.record("pkg", 42L, "search_submit", "id:search_box", ok = true)
        assertNull("只有 2 次一致仍不可查表（門檻 3）", DecisionMemo.recall("pkg", 42L, "search_submit"))
        DecisionMemo.record("pkg", 42L, "search_submit", "id:search_box", ok = true)
        assertEquals("id:search_box", DecisionMemo.recall("pkg", 42L, "search_submit"))
    }

    @Test
    fun `memo ignores unreliable history`() {
        repeat(3) { DecisionMemo.record("pkg", 7L, "explicit_tap", "id:x", ok = false) }
        DecisionMemo.record("pkg", 7L, "explicit_tap", "id:x", ok = true)
        assertNull("成功率不足不可查表", DecisionMemo.recall("pkg", 7L, "explicit_tap"))
    }

    @Test
    fun `memo resets when selector changes`() {
        repeat(3) { DecisionMemo.record("pkg", 5L, "explicit_tap", "id:old", ok = true) }
        DecisionMemo.record("pkg", 5L, "explicit_tap", "id:new", ok = true)
        assertNull("換了選擇器就要重新累積", DecisionMemo.recall("pkg", 5L, "explicit_tap"))
    }

    @Test
    fun `memo hit rate and json round trip`() {
        repeat(3) { DecisionMemo.record("pkg", 1L, "launch_app", "id:go", ok = true) }
        DecisionMemo.recall("pkg", 1L, "launch_app")
        DecisionMemo.recall("pkg", 2L, "launch_app")
        assertEquals(0.5f, DecisionMemo.hitRate(), 0.001f)
        val json = DecisionMemo.toJson()
        DecisionMemo.resetForTest()
        DecisionMemo.loadFromJson(json)
        assertEquals("id:go", DecisionMemo.recall("pkg", 1L, "launch_app"))
    }

    // ── S18 失敗根因 ────────────────────────────────────────────────

    @Test
    fun `failure classification covers four root causes`() {
        assertEquals(
            FailureAttribution.Reason.TIMING_ERROR,
            FailureAttribution.classify(FailureAttribution.Facts(timedOut = true))
        )
        assertEquals(
            FailureAttribution.Reason.SELECTOR_DRIFT,
            FailureAttribution.classify(FailureAttribution.Facts(selectorFound = false))
        )
        assertEquals(
            FailureAttribution.Reason.GUARD_ERROR,
            FailureAttribution.classify(FailureAttribution.Facts(actionDispatched = false))
        )
        assertEquals(
            FailureAttribution.Reason.POST_ERROR,
            FailureAttribution.classify(FailureAttribution.Facts(postOk = false))
        )
    }

    @Test
    fun `failure statistics point at the dominant cause`() {
        repeat(6) { FailureAttribution.record("pkg", FailureAttribution.Reason.SELECTOR_DRIFT) }
        repeat(4) { FailureAttribution.record("pkg", FailureAttribution.Reason.TIMING_ERROR) }
        assertEquals(10, FailureAttribution.total("pkg"))
        assertEquals(FailureAttribution.Reason.SELECTOR_DRIFT, FailureAttribution.dominant("pkg"))
        assertTrue(FailureAttribution.summary("pkg").contains("60%"))
    }

    @Test
    fun `failure stats round trip through json`() {
        FailureAttribution.record("pkg", FailureAttribution.Reason.POST_ERROR)
        val json = FailureAttribution.toJson()
        FailureAttribution.resetForTest()
        FailureAttribution.loadFromJson(json)
        assertEquals(1, FailureAttribution.countOf("pkg", FailureAttribution.Reason.POST_ERROR))
    }

    // ── S22 圖健康度 ────────────────────────────────────────────────

    @Test
    fun `empty graph is unhealthy`() {
        val empty = Automaton(pkg = "p")
        val s = GraphHealth.compute(empty, negativeEdges = 0)
        assertEquals(0f, s.health, 0.001f)
        assertTrue(GraphHealth.needsRebuild(s))
    }

    @Test
    fun `fresh successful graph is healthy`() {
        val now = 1_700_000_000_000L
        val auto = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100)),
            edges = listOf(
                AutomatonEdge("A", "B", "TAP", "id:x", 10, 0, 200, now, now)
            )
        )
        val s = GraphHealth.compute(auto, negativeEdges = 0, nowMs = now, budgetNodes = 2)
        assertEquals(1f, s.coverage, 0.001f)
        assertTrue("新鮮且成功的圖要健康（health=${s.health}）", s.health >= 0.9f)
        assertFalse(GraphHealth.needsRebuild(s))
    }

    @Test
    fun `many negative edges make graph unhealthy`() {
        val now = 1_700_000_000_000L
        val auto = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100)),
            edges = listOf(
                AutomatonEdge("A", "B", "TAP", "id:x", 10, 0, 200, now, now)
            )
        )
        val s = GraphHealth.compute(auto, negativeEdges = 5, nowMs = now, budgetNodes = 2)
        assertTrue("負邊密度高 → 判定需重建", GraphHealth.needsRebuild(s))
    }
}
