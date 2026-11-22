package com.wae.agent.core.explore

import com.wae.agent.core.automaton.Automaton
import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.automaton.AutomatonPlanner
import com.wae.agent.core.automaton.AutomatonState
import com.wae.agent.core.automaton.MinHash
import com.wae.agent.core.transfer.TransferEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M6 測試：S5 主動探索（Bandit + 護欄 + 影子執行）與 S6 跨 App 遷移。
 */
class ExplorationAndTransferTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    private fun fp(b: Long) = MinHash.fingerprint((b until b + 20).toList())

    private fun state(id: String, b: Long) = AutomatonState(id, fp(b), "LIST_FEED")

    private fun edge(
        from: String,
        to: String,
        sel: String,
        op: String = "TAP",
        ok: Int = 5,
        fail: Int = 0
    ) = AutomatonEdge(from, to, op, sel, ok, fail, 300, 1L, 1L)

    @Before
    fun setUp() {
        CoverageBandit.resetForTest()
        ExplorationGuard.resetForTest()
        TransferEngine.resetForTest()
        ExplorationGuard.loadFromLines(asset("ui_exploration_policy.tsv").readLines(Charsets.UTF_8))
        TransferEngine.loadFromLines(asset("ui_selector_families.tsv").readLines(Charsets.UTF_8))
    }

    // ── S5.1 Bandit ──────────────────────────────────────────────────

    @Test
    fun `bandit prefers untouched edges first`() {
        val e1 = edge("A", "B", "id:b")
        val e2 = edge("A", "C", "id:c")
        CoverageBandit.update("p", e1, discoveredNewEdge = true)
        val picked = CoverageBandit.pick("p", listOf(e1, e2), totalPicksFromState = 1)
        assertEquals("沒走過的優先（資訊增益最大）", e2, picked)
    }

    @Test
    fun `bandit tie breaks deterministically`() {
        val e1 = edge("A", "B", "id:b")
        val e2 = edge("A", "C", "id:c")
        val a = CoverageBandit.pick("p", listOf(e1, e2), 0)
        val b = CoverageBandit.pick("p", listOf(e2, e1), 0)
        assertEquals("同輸入必得同輸出（可重現）", a, b)
    }

    @Test
    fun `bandit explores less sampled arms after all touched`() {
        val e1 = edge("A", "B", "id:b")
        val e2 = edge("A", "C", "id:c")
        repeat(10) { CoverageBandit.update("p", e1, discoveredNewEdge = false) }
        CoverageBandit.update("p", e2, discoveredNewEdge = true)
        val picked = CoverageBandit.pick("p", listOf(e1, e2), 11)
        assertEquals("樣本少的優先", e2, picked)
    }

    @Test
    fun `coverage rate is discoveries over picks`() {
        val e1 = edge("A", "B", "id:b")
        CoverageBandit.update("p", e1, discoveredNewEdge = true)
        CoverageBandit.update("p", e1, discoveredNewEdge = false)
        assertEquals(0.5f, CoverageBandit.coverageRate(), 0.001f)
    }

    @Test
    fun `bandit state survives json round trip`() {
        val e1 = edge("A", "B", "id:b")
        CoverageBandit.update("p", e1, discoveredNewEdge = true)
        val json = CoverageBandit.toJson()
        CoverageBandit.resetForTest()
        CoverageBandit.loadFromJson(json)
        assertEquals(1, CoverageBandit.armCount())
        assertEquals(1, CoverageBandit.picksOf("p", e1))
    }

    // ── S5.2 護欄（三層）────────────────────────────────────────────

    @Test
    fun `guard requires charging and whitelist and budget`() {
        assertTrue(
            "全部條件滿足才可探索",
            ExplorationGuard.canExploreNow(
                charging = true, screenOn = true, actionsThisRound = 0,
                minutesThisRound = 0f, actionsToday = 0, packageWhitelisted = true
            )
        )
        assertFalse("沒充電不可探索", ExplorationGuard.canExploreNow(true.let { false }, true, 0, 0f, 0, true))
        assertFalse("不在白名單不可探索",
            ExplorationGuard.canExploreNow(true, true, 0, 0f, 0, packageWhitelisted = false))
        assertFalse("預算用完不可探索",
            ExplorationGuard.canExploreNow(true, true, 999, 0f, 0, true))
        assertFalse("今日額度用完不可探索",
            ExplorationGuard.canExploreNow(true, true, 0, 0f, 9999, true))
    }

    @Test
    fun `forbidden keywords block exploration`() {
        assertTrue(ExplorationGuard.isForbidden("id:delete", "刪除"))
        assertTrue(ExplorationGuard.isForbidden("id:pay", "付款"))
        assertTrue("權限對話框是絕對禁區", ExplorationGuard.isForbidden("", "允許存取權限"))
        assertFalse(ExplorationGuard.isForbidden("id:search_box", "搜尋"))
        assertFalse(ExplorationGuard.isForbidden("id:list_item", "第一封信"))
    }

    @Test
    fun `only safe action kinds can be explored`() {
        assertTrue(ExplorationGuard.isSafeAction("TAP"))
        assertTrue(ExplorationGuard.isSafeAction("SCROLL_DOWN"))
        assertTrue(ExplorationGuard.isSafeAction("SCROLL_UP"))
        assertFalse("輸入不探索", ExplorationGuard.isSafeAction("TYPE"))
        assertFalse("數值調整不探索", ExplorationGuard.isSafeAction("ADJUST_VALUE"))
        assertFalse("返回不探索", ExplorationGuard.isSafeAction("BACK"))
    }

    // ── S5.3 影子執行 ───────────────────────────────────────────────

    private fun planOf(states: List<String>, edges: List<AutomatonEdge>) =
        AutomatonPlanner.Plan(states, edges, 600, 0.9f)

    @Test
    fun `shadow execution passes when all selectors locatable`() {
        val auto = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100)),
            edges = listOf(edge("A", "B", "id:tab"))
        )
        val plan = planOf(listOf("A", "B"), listOf(edge("A", "B", "id:tab")))
        val v = ShadowExecutor.simulate(auto, plan, locatable = { it == "id:tab" })
        assertTrue(v.ok)
        assertEquals(-1, v.blockedAt)
        assertTrue(ShadowExecutor.shouldCommit(v))
    }

    @Test
    fun `shadow execution blocks when selector missing`() {
        val auto = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100), state("C", 200)),
            edges = listOf(edge("A", "B", "id:a"), edge("B", "C", "id:b"))
        )
        val plan = planOf(
            listOf("A", "B", "C"),
            listOf(edge("A", "B", "id:a"), edge("B", "C", "id:b"))
        )
        val v = ShadowExecutor.simulate(auto, plan, locatable = { it == "id:a" })
        assertFalse(v.ok)
        assertEquals("應在第二步被擋下", 1, v.blockedAt)
        assertFalse(ShadowExecutor.shouldCommit(v))
        assertTrue("檢查點只驗第一步 → 可放行", ShadowExecutor.checkpointVerdict(v, checkpoints = 1))
        assertFalse(ShadowExecutor.checkpointVerdict(v, checkpoints = 2))
    }

    // ── S6 跨 App 遷移 ──────────────────────────────────────────────

    @Test
    fun `selector family mapping is data driven`() {
        assertEquals("SEARCH_ENTRY", TransferEngine.selectorFamily("id:search_box"))
        assertEquals("TEXT_FIELD", TransferEngine.selectorFamily("id:et_input"))
        assertEquals("LIST_ITEM", TransferEngine.selectorFamily("role:list_item:第一封"))
        assertEquals("TOGGLE", TransferEngine.selectorFamily("id:switch_dark"))
        assertEquals("BUTTON", TransferEngine.selectorFamily("id:btn_ok"))
    }

    @Test
    fun `borrow only signatures the recipient lacks`() {
        val donor = Automaton(
            pkg = "donor",
            states = listOf(state("A", 0), state("B", 100)),
            edges = listOf(
                edge("A", "B", "id:search_box"),
                edge("A", "B", "id:tab_home")
            )
        )
        val recipient = Automaton(
            pkg = "recipient",
            states = listOf(state("X", 200)),
            edges = listOf(edge("X", "X", "id:search_bar"))
        )
        val borrowed = TransferEngine.borrowableEdges("recipient", recipient, "donor", donor)
        assertEquals("同 signature（SEARCH_ENTRY）不可覆蓋自家知識", 1, borrowed.size)
        assertEquals("id:tab_home", borrowed.first().selector)
    }

    @Test
    fun `borrowed edge confidence is discounted and decays on failure`() {
        val e = edge("A", "B", "id:tab_home", ok = 10, fail = 0)
        val full = TransferEngine.confidenceOf("donor", e)
        assertTrue("借用信賴度必須打折（0.6 × 成功率）", full <= TransferEngine.BORROW_CONFIDENCE)
        TransferEngine.recordResult("donor", e, ok = false)
        val after1 = TransferEngine.confidenceOf("donor", e)
        assertTrue("失敗一次要降權", after1 < full)
        TransferEngine.recordResult("donor", e, ok = false)
        TransferEngine.recordResult("donor", e, ok = false)
        assertTrue("失敗三次永久移除", TransferEngine.isPermanentlyRemoved("donor", e))
        assertEquals(0f, TransferEngine.confidenceOf("donor", e), 0.0001f)
    }

    @Test
    fun `successful borrow resets failure count`() {
        val e = edge("A", "B", "id:tab_home")
        TransferEngine.recordResult("donor", e, ok = false)
        assertEquals(1, TransferEngine.failureCount("donor", e))
        TransferEngine.recordResult("donor", e, ok = true)
        assertEquals(0, TransferEngine.failureCount("donor", e))
    }

    @Test
    fun `low reliability donor edges are not borrowed`() {
        val donor = Automaton(
            pkg = "donor",
            states = listOf(state("A", 0), state("B", 100)),
            edges = listOf(edge("A", "B", "id:tab_home", ok = 1, fail = 9))
        )
        val borrowed = TransferEngine.borrowableEdges("recipient", null, "donor", donor)
        assertTrue("成功率太低的邊不可借", borrowed.isEmpty())
    }

    @Test
    fun `edge signature combines op and family`() {
        assertEquals("TAP|SEARCH_ENTRY", TransferEngine.edgeSignature(edge("A", "B", "id:search_box")))
        assertEquals("TYPE|TEXT_FIELD", TransferEngine.edgeSignature(edge("A", "B", "id:et_input", op = "TYPE")))
        assertNotNull(TransferEngine.edgeSignature(edge("A", "B", "id:unknown_thing")))
    }
}
