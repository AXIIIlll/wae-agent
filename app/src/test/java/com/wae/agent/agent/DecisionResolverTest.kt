package com.wae.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionResolverTest {

    private fun el(i: Int, text: String, editable: Boolean = false) =
        ObservedElement(i, text, clickable = true, editable = editable)

    @Test
    fun `高信心會直接點擊正確元素`() {
        val obs = Observation(appPackage = "a", title = "t",
            elements = listOf(el(0, "搜尋"), el(1, "網路")))
        val resolver = DecisionResolver(ExactScorer())
        val step = AgentStep(1, "找搜尋", "搜尋", "出現輸入")
        val d = resolver.decide(step, obs)
        assertEquals(AgentAction.TAP, d.action)
        assertEquals(0, d.index)
    }

    @Test
    fun `可輸入元素會產生 TYPE`() {
        val obs = Observation(appPackage = "a", title = "t",
            elements = listOf(el(0, "搜尋框", editable = true)))
        val resolver = DecisionResolver(ExactScorer())
        val d = resolver.decide(AgentStep(1, "輸入關鍵字", "搜尋框", "有文字"), obs)
        assertEquals(AgentAction.TYPE, d.action)
        assertEquals(0, d.index)
        assertEquals("搜尋框", d.text)
    }

    @Test
    fun `找不到且可捲動會先 SCROLL`() {
        val obs = Observation(appPackage = "a", title = "t",
            elements = listOf(el(0, "其他")), scrollBottom = 0.4f)
        val resolver = DecisionResolver(ExactScorer())
        val d = resolver.decide(AgentStep(1, "找目標", "目標", "驗證"), obs)
        assertEquals(AgentAction.SCROLL, d.action)
    }

    @Test
    fun `找不到又不能捲動會 ASK`() {
        val obs = Observation(appPackage = "a", title = "t",
            elements = listOf(el(0, "其他")), scrollBottom = 1f)
        val resolver = DecisionResolver(ExactScorer())
        val d = resolver.decide(AgentStep(1, "找目標", "目標", "驗證"), obs)
        assertEquals(AgentAction.ASK_USER, d.action)
    }

    @Test
    fun `需要反問的步驟直接 ASK`() {
        val obs = Observation(elements = emptyList())
        val resolver = DecisionResolver(ExactScorer())
        val step = AgentStep(1, "選一個", null, "已選", ask = "要看哪個？", options = listOf("A", "B"))
        val d = resolver.decide(step, obs)
        assertEquals(AgentAction.ASK_USER, d.action)
        assertEquals("要看哪個？", d.question)
        assertEquals(listOf("A", "B"), d.options)
    }

    private class ExactScorer : ElementScorer {
        override fun score(target: String, element: ObservedElement, observation: Observation): Float =
            if (element.text == target) 1f else 0f
    }
}
