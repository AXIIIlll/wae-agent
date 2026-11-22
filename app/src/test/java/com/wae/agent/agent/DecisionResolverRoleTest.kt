package com.wae.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 決策層的兩個健壯性修正：
 *  1. `$role:first_result` 找不到「長文字卡」時要退而求其次（短文字 / 無文字的可點元素）。
 *  2. 「返回上一層」的步驟要真的產生 BACK（不然會被當成 no target → DONE）。
 */
class DecisionResolverRoleTest {

    private val scorer = object : ElementScorer {
        override fun score(target: String, element: ObservedElement, observation: Observation) = 0f
    }

    private fun obs(vararg elements: ObservedElement) = Observation(
        appPackage = "com.google.android.youtube",
        title = "YouTube",
        elements = elements.toList()
    )

    private fun el(index: Int, text: String, clickable: Boolean = true) =
        ObservedElement(index = index, text = text, clickable = clickable)

    @Test
    fun `first_result picks long text card when available`() {
        val r = DecisionResolver(scorer).decide(
            AgentStep(2, "挑一部推薦內容", "\$role:first_result", ""),
            obs(el(0, "首頁"), el(1, "這是一支很長的影片標題在這裡"), el(2, "短"))
        )
        assertEquals(AgentAction.TAP, r.action)
        assertEquals(1, r.index)
    }

    @Test
    fun `first_result falls back to short text card`() {
        val r = DecisionResolver(scorer).decide(
            AgentStep(2, "挑一部推薦內容", "\$role:first_result", ""),
            obs(el(0, "首頁"), el(1, "MV"))
        )
        assertEquals(AgentAction.TAP, r.action)
        assertEquals(1, r.index)
    }

    @Test
    fun `first_result falls back to any clickable when text is empty`() {
        val r = DecisionResolver(scorer).decide(
            AgentStep(2, "挑一部推薦內容", "\$role:first_result", ""),
            obs(el(0, "首頁", clickable = false), el(1, "", clickable = true))
        )
        assertEquals(AgentAction.TAP, r.action)
        assertEquals(1, r.index)
    }

    @Test
    fun `back step produces BACK action`() {
        val r = DecisionResolver(scorer).decide(AgentStep(1, "返回上一層", "", "上一頁"), obs(el(0, "首頁")))
        assertEquals(AgentAction.BACK, r.action)
    }

    @Test
    fun `empty verify only passes for tap`() {
        val rules = VerifyRules()
        val step = AgentStep(2, "挑一部推薦內容", "\$role:first_result", "")
        val before = obs(el(0, "A"))
        val after = obs(el(0, "A"), el(1, "B"))

        val tap = rules.check(step, before, after, AgentDecision(action = AgentAction.TAP, index = 1))
        assertEquals(VerifyOutcome.OK, tap.outcome)

        val scroll = rules.check(step, before, after, AgentDecision(action = AgentAction.SCROLL))
        assertEquals("滑動不能冒充成功", VerifyOutcome.FAIL, scroll.outcome)
    }
}
