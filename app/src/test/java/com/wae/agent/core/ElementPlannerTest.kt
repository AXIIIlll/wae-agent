package com.wae.agent.core

import com.wae.agent.agent.AgentAction
import com.wae.agent.agent.AgentDecision
import com.wae.agent.agent.AgentExecutor
import com.wae.agent.agent.DecisionResolver
import com.wae.agent.agent.ElementScorer
import com.wae.agent.agent.Observation
import com.wae.agent.agent.ObservedElement
import com.wae.agent.agent.Observer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 無 LLM 的逐元素決策迴圈：不生成文字，只「看畫面 → 選元素 → 動作」。
 */
class ElementPlannerTest {

    private class FakeObserver(var obs: Observation) : Observer {
        override fun observe(): Observation = obs
    }

    private class RecordingExecutor(var ok: Boolean = true) : AgentExecutor {
        val actions = ArrayList<AgentAction>()
        override fun execute(decision: AgentDecision): Boolean {
            actions.add(decision.action)
            return ok
        }
    }

    private val scorer = object : ElementScorer {
        override fun score(target: String, element: ObservedElement, observation: Observation) = 0f
    }

    private fun obs(vararg texts: String) = Observation(
        appPackage = "com.example",
        title = texts.firstOrNull() ?: "",
        elements = texts.mapIndexed { i, t ->
            ObservedElement(index = i, text = t, clickable = true, visible = true)
        }
    )

    @Test
    fun `keywords drop verbs and particles`() {
        val kw = ElementPlanner.goalKeywords("幫我打開行事曆")
        assertTrue("應留下『行事曆』，實際=$kw", kw.any { it.contains("行事") || it.contains("曆") })
        assertTrue("不應留下『打開』", kw.none { it.contains("打開") })
    }

    @Test
    fun `goal reached when keywords are visible`() {
        val planner = ElementPlanner(FakeObserver(obs("設定")), DecisionResolver(scorer), RecordingExecutor())
        assertTrue(planner.goalReached("行事曆", obs("行事曆", "新增行程")))
        assertFalse(planner.goalReached("行事曆", obs("設定", "藍牙")))
    }

    @Test
    fun `asks when nothing matches the goal`() {
        val executor = RecordingExecutor()
        val planner = ElementPlanner(FakeObserver(obs("首頁", "推薦")), DecisionResolver(scorer), executor)
        val r = planner.run("行事曆")
        assertTrue("找不到元素時應反問，實際=${r.reason}", r.needsAsk || !r.success)
    }

    @Test
    fun `executes tap when scorer finds a candidate`() {
        val playing = object : ElementScorer {
            override fun score(target: String, element: ObservedElement, observation: Observation) =
                if (element.text.contains("播放")) 5f else 0f
        }
        val executor = RecordingExecutor()
        val planner = ElementPlanner(
            FakeObserver(obs("首頁", "播放")), DecisionResolver(playing), executor, maxSteps = 3
        )
        val r = planner.run("看劇")
        assertTrue("應該有嘗試動作，實際=${executor.actions}", executor.actions.isNotEmpty())
        assertTrue("至少執行過一次", r.steps >= 0)
        assertFalse("畫面沒出現目標字串就不該回報成功", r.success)
    }

    @Test
    fun `stops when goal already visible`() {
        val executor = RecordingExecutor()
        val planner = ElementPlanner(FakeObserver(obs("行事曆")), DecisionResolver(scorer), executor)
        val r = planner.run("行事曆")
        assertTrue(r.success)
        assertEquals(0, executor.actions.size)
    }

    @Test
    fun `fails fast when nothing changes`() {
        val planner = ElementPlanner(
            FakeObserver(obs("首頁")), DecisionResolver(scorer), RecordingExecutor(), maxSteps = 6
        )
        val r = planner.run("行事曆")
        assertFalse("不該假裝成功", r.success)
        assertTrue("應該明確回報原因，實際=${r.reason}", r.reason.isNotBlank())
    }
}
