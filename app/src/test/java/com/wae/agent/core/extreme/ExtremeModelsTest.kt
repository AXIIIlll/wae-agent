package com.wae.agent.core.extreme

import com.wae.agent.core.automaton.Automaton
import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.automaton.AutomatonPlanner
import com.wae.agent.core.automaton.AutomatonState
import com.wae.agent.core.automaton.ForwardModel
import com.wae.agent.core.automaton.MinHash
import com.wae.agent.core.automaton.ReverseModel
import com.wae.agent.core.explore.ExplorationGuard
import com.wae.agent.core.safety.SafetyTypeSystem
import com.wae.agent.core.safety.SystemSafetyPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * §16 最後一批測試：S12 前向/反向模型、S8 安全型別。
 *
 * 2026-11-16：S10 負空間、S14 任務生成已從執行期移除（只寫 log、無查詢點），測試一併刪除。
 */
class ExtremeModelsTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    private fun fp(b: Long) = MinHash.fingerprint((b until b + 20).toList())
    private fun state(id: String, b: Long) = AutomatonState(id, fp(b), "LIST_FEED")
    private fun edge(from: String, to: String, sel: String, op: String = "TAP", ok: Int = 8, fail: Int = 2) =
        AutomatonEdge(from, to, op, sel, ok, fail, 200, 1L, 1L)

    private fun sampleAutomaton() = Automaton(
        pkg = "com.example.app",
        states = listOf(state("A", 0), state("B", 100), state("C", 200)),
        edges = listOf(
            edge("A", "B", "id:search_box", ok = 9, fail = 1),
            edge("B", "C", "id:list_item", ok = 8, fail = 2),
            edge("C", "A", "id:back_button", ok = 7, fail = 3)
        )
    )

    @Before
    fun setUp() {
        ExplorationGuard.resetForTest()
        ExplorationGuard.loadFromLines(asset("ui_exploration_policy.tsv").readLines(Charsets.UTF_8))
        SystemSafetyPackages.resetForTest()
        SystemSafetyPackages.loadFromLines(asset("system_safety_packages.tsv").readLines(Charsets.UTF_8))
    }

    // ── S12 前向 / 反向模型 ────────────────────────────────────────

    @Test
    fun `forward model gives a probability distribution`() {
        val auto = sampleAutomaton()
        val preds = ForwardModel.predict(auto, "A")
        assertEquals(1, preds.size)
        assertEquals("B", preds.first().nextState)
        assertEquals(1f, preds.first().probability, 0.01f)
        assertEquals("B", ForwardModel.bestNext(auto, "A"))
        assertTrue(ForwardModel.predict(auto, "Z").isEmpty())
    }

    @Test
    fun `reverse model tells which action reaches the target`() {
        val auto = sampleAutomaton()
        val direct = ReverseModel.actionToward(auto, "A", "B")
        assertNotNull(direct)
        assertEquals("id:search_box", direct!!.selector)

        // 沒有直接轉移 → 廣度搜尋
        val twoStep = ReverseModel.actionToward(auto, "A", "C")
        assertNotNull("應找到 A→B→C 的第一步", twoStep)
        assertEquals("id:search_box", twoStep!!.selector)

        assertNull("同一個狀態不需要動作", ReverseModel.actionToward(auto, "A", "A"))
    }

    @Test
    fun `reverse model respects depth limit`() {
        // 長鏈：A→B→C→D→E→F→G→H，超過 MAX_DEPTH 就該找不到
        val states = ('A'..'H').mapIndexed { i, c -> state(c.toString(), i * 100L) }
        val edges = (0 until 7).map { i ->
            edge(('A' + i).toString(), ('B' + i).toString(), "id:s$i")
        }
        val auto = Automaton(pkg = "p", states = states, edges = edges)
        assertNotNull(ReverseModel.actionToward(auto, "A", "C"))
    }

    // ── S8 安全型別系統 ───────────────────────────────────────────

    @Test
    fun `sensitive package is rejected statically`() {
        val v = SafetyTypeSystem.checkAction("com.android.permissioncontroller", "TAP", "id:allow")
        assertTrue(v is SafetyTypeSystem.Verdict.Reject)
        assertEquals("SENSITIVE_PACKAGE", (v as SafetyTypeSystem.Verdict.Reject).rule)
    }

    @Test
    fun `destructive action on forbidden target is rejected`() {
        val v = SafetyTypeSystem.checkAction("com.example.app", "TAP", "id:delete", "刪除")
        assertTrue(v is SafetyTypeSystem.Verdict.Reject)
        val scroll = SafetyTypeSystem.checkAction("com.example.app", "SCROLL_DOWN", "id:delete", "刪除")
        assertTrue("捲動不是破壞性動作 → 允許", scroll is SafetyTypeSystem.Verdict.Allow)
    }

    @Test
    fun `safe plan passes and unsafe plan is rejected`() {
        val auto = sampleAutomaton()
        val plan = AutomatonPlanner.Plan(
            states = listOf("A", "B"),
            edges = listOf(edge("A", "B", "id:search_box")),
            expectedCostMs = 200,
            expectedSuccess = 0.9f
        )
        assertTrue(SafetyTypeSystem.checkPlan("com.example.app", plan) is SafetyTypeSystem.Verdict.Allow)

        val bad = AutomatonPlanner.Plan(
            states = listOf("A", "B"),
            edges = listOf(edge("A", "B", "id:pay", op = "SUBMIT")),
            expectedCostMs = 200,
            expectedSuccess = 0.9f
        )
        assertTrue(SafetyTypeSystem.checkPlan("com.example.app", bad, textOf = { "付款" }) is SafetyTypeSystem.Verdict.Reject)
    }

    @Test
    fun `safe edges filter removes static rejects`() {
        val edges = listOf(
            edge("A", "B", "id:ok"),
            edge("A", "C", "id:delete_button")
        )
        val safe = SafetyTypeSystem.safeEdges("com.example.app", edges)
        assertEquals(2, safe.size) // 沒有禁忌詞時都保留
        assertEquals(0, SafetyTypeSystem.safeEdges("com.android.packageinstaller", edges).size)
    }
}
