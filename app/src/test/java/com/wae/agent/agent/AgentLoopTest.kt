package com.wae.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    private class FakePlanner(var plans: MutableList<AgentPlan>, private val replanPlan: AgentPlan? = null) : Planner {
        var planCalls = 0
        override fun plan(goal: String): AgentPlan? {
            planCalls++
            return plans.removeFirstOrNull()
        }

        override fun replan(goal: String, failedStep: AgentStep?, reason: String): AgentPlan? {
            planCalls++
            return replanPlan
        }
    }

    private class FakeObserver(var obs: Observation) : Observer {
        override fun observe(): Observation = obs
    }

    private class FakeExecutor(private val succeed: Boolean = true) : AgentExecutor {
        var calls = 0
        override fun execute(decision: AgentDecision): Boolean {
            calls++
            return succeed
        }
    }

    private class FakeAsker(private val answer: String? = "選項A") : UserAsker {
        override fun ask(question: String, options: List<String>): String? = answer
    }

    private class RecordingAsker : UserAsker {
        var calls = 0
        override fun ask(question: String, options: List<String>): String? {
            calls += 1
            return "選項A"
        }
    }

    private object AllowAll : SafetyCheck {
        override fun allows(decision: AgentDecision, observation: Observation): Boolean = true
    }

    private fun step(id: Int, doText: String, target: String, verify: String) =
        AgentStep(id = id, doText = doText, target = target, verify = verify)

    @Test
    fun `一步成功就回傳 SUCCESS`() {
        val plan = AgentPlan(steps = listOf(step(1, "打開設定", "設定", "設定")))
        val obs = Observation(appPackage = "com.android.settings", title = "設定",
            elements = listOf(ObservedElement(0, "設定", clickable = true)))
        val planner = FakePlanner(mutableListOf(plan))
        val executor = FakeExecutor()
        val loop = AgentLoop(planner, FakeObserver(obs), DecisionResolver(SimpleScorer()),
            VerifyRules(), executor, FakeAsker(), AllowAll)
        val out = loop.run("打開設定")
        assertEquals(AgentResult.SUCCESS, out.result)
        assertTrue(out.stepsExecuted >= 1)
    }

    @Test
    fun `目標不存在時會反問而不硬做`() {
        val plan = AgentPlan(steps = listOf(step(1, "找不到的東西", "不存在的元素", "應該出現")))
        val obs = Observation(appPackage = "com.android.settings", title = "設定",
            elements = listOf(ObservedElement(0, "網路", clickable = true)))
        val planner = FakePlanner(mutableListOf(plan))
        val asker = RecordingAsker()
        val loop = AgentLoop(planner, FakeObserver(obs), DecisionResolver(SimpleScorer()),
            VerifyRules(), FakeExecutor(), asker, AllowAll)
        val out = loop.run("做一件找不到的事")
        assertTrue("應該至少反問一次", asker.calls > 0)
        assertTrue("不應假裝成功", out.result == AgentResult.FAIL || out.result == AgentResult.ASKING)
    }

    @Test
    fun `時間或重試上限會中止`() {
        val plan = AgentPlan(steps = listOf(step(1, "做", "目標", "驗證")))
        val obs = Observation(appPackage = "a", title = "b",
            elements = listOf(ObservedElement(0, "目標", clickable = true)))
        val planner = FakePlanner(mutableListOf(plan))
        val loop = AgentLoop(planner, FakeObserver(obs), DecisionResolver(SimpleScorer()),
            VerifyRules(), FakeExecutor(succeed = false), FakeAsker(), AllowAll,
            AgentLimits(maxSteps = 1, maxRetriesPerStep = 0, maxReplans = 0))
        val out = loop.run("永遠失敗")
        assertEquals(AgentResult.FAIL, out.result)
    }
}

/** 測試用簡單打分：完全相同=1.0、包含=0.7、否則 0。 */
private class SimpleScorer : ElementScorer {
    override fun score(target: String, element: ObservedElement, observation: Observation): Float {
        if (element.text == target) return 1f
        if (element.text.contains(target) || target.contains(element.text)) return 0.7f
        return 0f
    }
}
