package com.wae.agent.core.nlu

import com.wae.agent.core.ActionKind
import com.wae.agent.core.LocalGoal
import com.wae.agent.core.action.GoalSegmentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S27（開關狀態推理）與 S28（條件依賴）測試。
 */
class ToggleAndDependencyTest {

    // ── S27：開關狀態解析 ─────────────────────────────────────────────

    @Test
    fun `parse off intent`() {
        val i = ToggleIntent.parse("關閉藍牙")
        assertEquals(false, i?.desired)
        assertEquals("藍牙", i?.target)
    }

    @Test
    fun `parse on intent`() {
        val i = ToggleIntent.parse("打開藍牙")
        assertEquals(true, i?.desired)
        assertEquals("藍牙", i?.target)
    }

    @Test
    fun `later word wins in long phrase`() {
        // 「關閉飛航再開啟藍牙」→ 以最後出現的「開啟」為準
        val i = ToggleIntent.parse("關閉飛航再開啟藍牙")
        assertEquals(true, i?.desired)
    }

    @Test
    fun `non toggle command returns null`() {
        assertEquals(null, ToggleIntent.parse("搜尋發票"))
        // 「打開設定」含「開」字，但它是啟動類目標 → 不可被當成開關意圖
        assertEquals(null, ToggleIntent.parseForToggle("打開設定", ActionKind.LAUNCH_APP))
        assertTrue(ToggleIntent.parseForToggle("關閉藍牙", ActionKind.TAP) != null)
    }

    // ── S27：狀態已成立就不該動作 ─────────────────────────────────────

    private fun toggle(checked: Boolean, text: String = "藍牙", checkable: Boolean = true) =
        com.wae.agent.core.CandidateOption(
            markId = 1,
            nodeStableSig = 1L,
            source = com.wae.agent.core.CandidateSource.A11Y_TREE,
            detectorConfidence = 1f,
            grid = intArrayOf(0, 0, 400, 120),
            coreText = text,
            weldedDescription = text,
            hybridSimScore = 0.5f,
            isCheckable = checkable,
            isChecked = checked
        )

    @Test
    fun `already satisfied when state matches`() {
        assertTrue(ToggleIntent.alreadySatisfied(toggle(checked = false), desired = false))
        assertTrue(ToggleIntent.alreadySatisfied(toggle(checked = true), desired = true))
        assertFalse(ToggleIntent.alreadySatisfied(toggle(checked = true), desired = false))
    }

    @Test
    fun `only checkable elements count as toggles`() {
        assertTrue(ToggleIntent.isToggleCandidate(toggle(true)))
        assertFalse(ToggleIntent.isToggleCandidate(toggle(true, checkable = false)))
    }

    // ── S27：後置條件（狀態驗證）──────────────────────────────────────

    @Test
    fun `toggle postcondition verifies real state`() {
        val cond = com.wae.agent.core.goal.PostCondition.ToggleState("藍牙", false)
        val offFacts = com.wae.agent.core.goal.PageFacts(
            toggles = listOf(com.wae.agent.core.goal.ToggleFact("id:sw", "藍牙", false))
        )
        val onFacts = com.wae.agent.core.goal.PageFacts(
            toggles = listOf(com.wae.agent.core.goal.ToggleFact("id:sw", "藍牙", true))
        )
        assertTrue("關閉成功 → 成立", cond.evaluate(offFacts))
        assertFalse("還在開 → 不成立", cond.evaluate(onFacts))
        assertTrue("沒有開關資訊 → 不可判成功", !cond.evaluate(com.wae.agent.core.goal.PageFacts()))
    }

    // ── S28：連接詞語意 ───────────────────────────────────────────────

    @Test
    fun `sequential and independent connectors`() {
        assertTrue(GoalConnectors.endsWithSequentialConnector("關閉飛航模式再"))
        assertTrue(GoalConnectors.endsWithSequentialConnector("打開設定然後"))
        assertFalse("並且是獨立", GoalConnectors.endsWithSequentialConnector("打開設定並且"))
        assertFalse(GoalConnectors.endsWithSequentialConnector(""))
        assertTrue(GoalConnectors.endsWithIndependentConnector("打開設定並且"))
    }

    // ── S28：失敗時依序停止 ───────────────────────────────────────────

    private fun goal(
        idx: Int,
        text: String,
        pkg: String,
        depends: Boolean = false
    ) = LocalGoal(
        stepIdx = idx,
        goalId = "g$idx",
        goalText = text,
        canonicalIntentTag = "explicit_tap",
        actionKind = ActionKind.TAP,
        targetPackageHint = pkg,
        dependsOnPrevious = depends
    )

    @Test
    fun `chained goals stop after failure`() {
        val goals = listOf(
            goal(0, "關閉飛航模式", "com.android.settings"),
            goal(1, "開啟藍牙", "com.android.settings", depends = true),
            goal(2, "返回", "com.android.settings", depends = true)
        )
        val out = GoalSegmentPolicy.afterFailure(goals, 0)
        assertFalse("序列指令失敗 → 不可繼續", out.continueExecution)
        assertEquals(2, out.skippedGoals.size)
        assertTrue(out.reason.contains("依賴"))
    }

    @Test
    fun `independent segments still continue`() {
        val goals = listOf(
            goal(0, "關閉飛航模式", "com.android.settings"),
            goal(1, "在gmail寄信", "com.google.android.gm", depends = false)
        )
        val out = GoalSegmentPolicy.afterFailure(goals, 0)
        assertTrue("獨立段應繼續", out.continueExecution)
    }

    // ── S28：拆解標記（端到端）────────────────────────────────────────

    @Test
    fun `decompose marks dependencies for sequential commands`() {
        val goals = com.wae.agent.core.WaeV26PipelineOrchestrator
            .decomposeCommandIntoLocalGoals("關閉飛航模式再開啟藍牙再返回")
        assertTrue("應拆成多步（實際=${goals.size}）", goals.size >= 3)
        assertTrue("第二步依賴第一步", goals[1].dependsOnPrevious)
        assertTrue("第三步依賴前一步", goals[2].dependsOnPrevious)
    }

    @Test
    fun `decompose keeps single goal independent`() {
        val goals = com.wae.agent.core.WaeV26PipelineOrchestrator
            .decomposeCommandIntoLocalGoals("打開設定")
        assertEquals(1, goals.size)
        assertFalse(goals[0].dependsOnPrevious)
    }
}
