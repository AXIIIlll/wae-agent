package com.wae.agent.core.action

import com.wae.agent.core.ActionKind
import com.wae.agent.core.LocalGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 部分失敗策略測試：
 *  - 同一階段失敗 → 其餘同階段一起停（不再亂點）
 *  - 不同階段（不同 App）→ 只停失敗那段，其他繼續
 */
class GoalSegmentPolicyTest {

    private fun goal(idx: Int, pkg: String, text: String = "g$idx") = LocalGoal(
        stepIdx = idx,
        goalId = "goal_$idx",
        goalText = text,
        canonicalIntentTag = "general",
        actionKind = ActionKind.TAP,
        targetPackageHint = pkg
    )

    @Test
    fun `same segment stops remaining goals`() {
        val goals = listOf(goal(0, "com.a"), goal(1, "com.a"), goal(2, "com.a"))
        val out = GoalSegmentPolicy.afterFailure(goals, failedIndex = 0)
        assertFalse("同階段應全部停止", out.continueExecution)
        assertEquals(2, out.skippedGoals.size)
    }

    @Test
    fun `different segment continues and skips only its own`() {
        val goals = listOf(goal(0, "com.a"), goal(1, "com.a"), goal(2, "com.b"), goal(3, "com.b"))
        val out = GoalSegmentPolicy.afterFailure(goals, failedIndex = 0)
        assertTrue("有其他階段應繼續", out.continueExecution)
        assertEquals("只跳過 com.a 的剩餘目標", 1, out.skippedGoals.size)
        assertTrue(out.skippedGoals.all { it.targetPackageHint == "com.a" })
    }

    @Test
    fun `failure at last goal stops`() {
        val goals = listOf(goal(0, "com.a"), goal(1, "com.b"))
        val out = GoalSegmentPolicy.afterFailure(goals, failedIndex = 1)
        assertFalse(out.continueExecution)
    }

    @Test
    fun `summary message distinguishes complete partial and failed`() {
        assertTrue(GoalSegmentPolicy.summaryMessage(2, 2, null).contains("任務完成"))
        assertTrue(GoalSegmentPolicy.summaryMessage(2, 0, "第一封").contains("無法完成"))
        val partial = GoalSegmentPolicy.summaryMessage(3, 2, "第一封")
        assertTrue("部分完成必須明確標示", partial.contains("部分完成"))
    }

    @Test
    fun `invalid index is safe`() {
        assertFalse(GoalSegmentPolicy.afterFailure(emptyList(), 0).continueExecution)
    }
}