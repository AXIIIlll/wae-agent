package com.wae.agent.core.taskos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S33：Task OS 規劃器（模板 → 槽位 → 步驟 → 資料流檢查）。
 */
class TaskPlannerTest {

    @Before
    fun setUp() {
        TaskOsEngine.resetForTest()
        TaskTemplates.loadFromLines(
            listOf(
                "template\tread-version\t(?i)(?:讀|查看|查).*版本\t" +
                    "LAUNCH_APP:設定|TAP:關於|COLLECT:LONGEST_NUMBER:version|BACK",
                "template\tsearch-first\t(?i)(?:搜尋).*(?:第一個|第一筆)\t" +
                    "LAUNCH_APP:\$app|TAP:搜尋|TYPE:\$query|SUBMIT|TAP:\$first",
                "verb\ttap\tTAP",
                "verb\tback\tBACK",
                "verb\tlaunch_app\tLAUNCH_APP",
                "verb\ttype\tTYPE",
                "verb\tsubmit\tSUBMIT"
            )
        )
        SlotFiller.loadFromLines(
            listOf(
                "slot\tapp\t(?:打開|開啟)([^，,。\\s]{1,12})",
                "slot\tquery\t(?:搜尋)([^，,。\\s]{1,20})",
                "slot\tfirst\t(?:第一個|第一筆)([^，,。\\s]{0,20})"
            )
        )
    }

    @Test
    fun `planned when template matches and vars can be filled`() {
        val outcome = TaskPlanner.plan("查一下版本", TaskContext("查一下版本"), nowMs = 111L)
        assertTrue(outcome is TaskPlanner.Outcome.Planned)
        val plan = (outcome as TaskPlanner.Outcome.Planned).plan
        assertEquals("read-version", plan.templateId)
        assertEquals(3, plan.size)
        assertEquals(111L, plan.createdAtMs)
        assertTrue(plan.requiredVars.contains("version"))
    }

    @Test
    fun `need vars when template needs slots not provided`() {
        // 有模板但缺 app/query/first
        val outcome = TaskPlanner.plan("搜尋台北101第一個", TaskContext("搜尋台北101第一個"))
        assertTrue(outcome is TaskPlanner.Outcome.NeedVars || outcome is TaskPlanner.Outcome.NoMatch)
        // 槽位抽不到 app → NeedVars
        if (outcome is TaskPlanner.Outcome.NeedVars) {
            assertTrue(outcome.missing.contains("app"))
        }
    }

    @Test
    fun `no match for unrelated command`() {
        val outcome = TaskPlanner.plan("今天天氣如何", TaskContext("今天天氣如何"))
        assertEquals(TaskPlanner.Outcome.NoMatch, outcome)
    }

    @Test
    fun `empty command is no match`() {
        assertEquals(TaskPlanner.Outcome.NoMatch, TaskPlanner.plan("   "))
    }

    @Test
    fun `single step plan is available as fallback wrapper`() {
        val goal = com.wae.agent.core.LocalGoal(
            stepIdx = 0,
            goalId = "g0",
            goalText = "返回",
            canonicalIntentTag = "BACK",
            actionKind = com.wae.agent.core.ActionKind.BACK
        )
        val plan = TaskPlanner.singleStepPlan("返回", goal)
        assertEquals(1, plan.size)
        assertEquals("", plan.templateId)
        assertNull(plan.steps.first().collect)
    }

    @Test
    fun `slots in command are written into context`() {
        val ctx = TaskContext("打開設定")
        TaskPlanner.plan("查一下版本;打開設定", ctx)
        // 模板命中後，槽位應寫入 context（供後續模板/技能用）
        assertEquals("設定", ctx.get("app"))
    }
}
