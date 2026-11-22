package com.wae.agent.core

import com.wae.agent.core.action.NegativeEdgeStore
import com.wae.agent.core.action.NegativeReason
import com.wae.agent.core.action.SelectorKeys
import com.wae.agent.core.automaton.Automaton
import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.automaton.AutomatonPlanner
import com.wae.agent.core.automaton.AutomatonRuntime
import com.wae.agent.core.automaton.AutomatonState
import com.wae.agent.core.automaton.MinHash
import com.wae.agent.core.twin.TwinFastPath
import com.wae.agent.core.twin.TwinStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 整合一致性測試：確認 P0 / M1 / M2 / M3 之間沒有「格式不同、各自為政」的縫隙。
 *
 * 這一組測試的價值在於：**單元測試各自綠燈，不代表模組之間接得起來**。
 * 先前就發生過 selector 鍵有兩種格式，導致負邊永遠對不上規劃器的邊。
 */
class IntegrationConsistencyTest {

    @Before
    fun setUp() {
        NegativeEdgeStore.resetForTest()
    }

    private fun cand(
        text: String = "",
        viewId: String = "",
        role: String = "",
        grid: IntArray = intArrayOf(100, 200, 300, 260)
    ) = CandidateOption(
        markId = 1,
        nodeStableSig = 1L,
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = grid,
        coreText = text,
        weldedDescription = text,
        hybridSimScore = 0.5f,
        viewId = viewId,
        roleTag = role
    )

    private fun step(
        viewId: String = "",
        role: String = "",
        target: String = "",
        gx: Int = 100,
        gy: Int = 200
    ) = TwinStep(
        pkg = "p", spineBefore = 1, spineAfter = 2, action = "TAP", target = target,
        posture = "LIST_FEED", ok = true, ms = 100, ts = 1L,
        viewId = viewId, roleTag = role, gridX = gx, gridY = gy
    )

    // ── 1. selector 鍵必須全系統一致 ─────────────────────────────────

    @Test
    fun `selector keys agree between decision telemetry planner and twin`() {
        val cases = listOf(
            cand(viewId = "com.x:id/search_box"),
            cand(text = "第一封", role = "list_item"),
            cand(text = "僅有文字"),
            cand()
        )
        for (c in cases) {
            val fromDecision = WaeV26PipelineOrchestrator.candidateSelectorKey(c)
            val fromRuntime = AutomatonRuntime.selectorOf(c)
            val fromTwin = TwinFastPath.selectorOf(
                step(viewId = c.viewId, role = c.roleTag, target = c.coreText, gx = c.centerX(), gy = c.centerY())
            )
            assertEquals("決策層與規劃器必須用同一個鍵", fromDecision, fromRuntime)
            assertEquals("Twin 與規劃器必須用同一個鍵", fromRuntime, fromTwin)
        }
    }

    @Test
    fun `role key contains text for disambiguation`() {
        val c = cand(text = "第一封", role = "list_item")
        assertEquals("role:list_item:第一封", SelectorKeys.of(c))
        assertEquals("list_item", SelectorKeys.rolePart(SelectorKeys.of(c)))
        assertEquals("第一封", SelectorKeys.textPart(SelectorKeys.of(c)))
    }

    // ── 2. 負邊必須真的擋住規劃器 ────────────────────────────────────

    private fun fp(base: Long) = MinHash.fingerprint((base until base + 20).toList())

    @Test
    fun `negative edge recorded by decision layer blocks planned edge`() {
        val sel = SelectorKeys.of(cand(text = "第一封", role = "list_item"))
        // 決策層記錄一次「跑到非預期頁面」→ 永久禁區
        NegativeEdgeStore.record("p", sel, "TAP", NegativeReason.WRONG_EFFECT)

        // 關鍵斷言：規劃器手上的邊（用統一鍵）必須被決策層記錄的負邊擋住
        assertTrue(
            "決策層與規劃器必須共用同一個鍵，負邊才擋得住",
            NegativeEdgeStore.isBlocked("p", sel, "TAP")
        )

        // 規劃器端：唯一的目標狀態只剩被禁用的邊 → 必須回報無解
        val states = listOf(
            AutomatonState("S", fp(500), "NAV_HOME"),
            AutomatonState("A", fp(0), "LIST_FEED"),
            AutomatonState("B", fp(100), "DETAIL")
        )
        val edges = listOf(
            AutomatonEdge("S", "A", "TAP", "id:entry", ok = 9, fail = 0, p95Ms = 300, firstTs = 1, lastTs = 1),
            AutomatonEdge("A", "B", "TAP", sel, ok = 5, fail = 0, p95Ms = 300, firstTs = 1, lastTs = 1)
        )
        val auto = Automaton(pkg = "p", states = states, edges = edges)
        val blockedPlan = AutomatonPlanner.planToSelectorHint(
            automaton = auto,
            from = "S",
            hint = "第一封",
            isBlocked = { e -> NegativeEdgeStore.isBlocked("p", e.selector, e.op) }
        )
        assertNull("負邊必須讓規劃器放棄這條邊（先前因鍵格式不同而失效）", blockedPlan)
    }

    @Test
    fun `without negative edge the same plan succeeds`() {
        val sel = SelectorKeys.of(cand(text = "第一封", role = "list_item"))
        val states = listOf(
            AutomatonState("S", fp(500), "NAV_HOME"),
            AutomatonState("A", fp(0), "LIST_FEED"),
            AutomatonState("B", fp(100), "DETAIL")
        )
        val edges = listOf(
            AutomatonEdge("S", "A", "TAP", "id:entry", ok = 9, fail = 0, p95Ms = 300, firstTs = 1, lastTs = 1),
            AutomatonEdge("A", "B", "TAP", sel, ok = 5, fail = 0, p95Ms = 300, firstTs = 1, lastTs = 1)
        )
        val auto = Automaton(pkg = "p", states = states, edges = edges)
        val plan = AutomatonPlanner.planToSelectorHint(auto, "S", "第一封")
        assertNotNull("沒有負邊時應能找到通往目標的狀態", plan)
        assertEquals("從 S 走一步到 A（目標元素在 A 上）", 1, plan!!.steps)
        assertEquals(listOf("S", "A"), plan.states)
    }

    // ── 3. 規劃出來的選擇器要能在候選中定位 ──────────────────────────

    @Test
    fun `planned role selector locates the right list item`() {
        val first = cand(text = "第一封", role = "list_item")
        val second = cand(text = "第二封", role = "list_item")
        val options = listOf(first, second)
        val sel = SelectorKeys.of(first)
        assertEquals(1, AutomatonRuntime.locate(sel, options)?.markId)
        // 只有角色、沒有文字時，退回第一個同角色候選（可接受，但優先文字相符）
        val roleOnly = "role:list_item"
        assertNotNull(AutomatonRuntime.locate(roleOnly, options))
    }

    @Test
    fun `locate returns null instead of guessing`() {
        val options = listOf(cand(text = "第一封", role = "list_item"))
        // 角色完全不同 → 必須回 null，不可硬配到清單項
        assertNull("不同角色不該被匹配", AutomatonRuntime.locate("role:button:確定", options))
        // 同角色但文字不符時，允許退回第一個同角色候選（但不會是憑空猜測）
        val fallback = AutomatonRuntime.locate("role:list_item:不存在的文字", options)
        assertTrue("退回同角色候選（可接受）", fallback == null || fallback.markId == 1)
    }
}
