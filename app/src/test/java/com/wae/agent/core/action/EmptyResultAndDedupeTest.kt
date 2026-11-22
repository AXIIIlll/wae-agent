package com.wae.agent.core.action

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import com.wae.agent.core.DecisionOutcomeType
import com.wae.agent.core.DualPostureState
import com.wae.agent.core.ImmutableOptionSet
import com.wae.agent.core.LocalGoal
import com.wae.agent.core.ActionKind
import com.wae.agent.core.PagePosture
import com.wae.agent.core.TargetSpecificity
import com.wae.agent.core.WaeV26PipelineOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 空結果頁 + 重試去重測試（修「把同一段文字查三次」）。
 */
class EmptyResultAndDedupeTest {

    @Before
    fun reset() {
        GoalAttemptGuard.reset()
    }

    private fun cand(
        markId: Int,
        text: String,
        viewId: String = "",
        editable: Boolean = false,
        container: String = "LIST_ITEM"
    ) = CandidateOption(
        markId = markId,
        nodeStableSig = markId.toLong(),
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = intArrayOf(0, 0, 100, 100),
        coreText = text,
        weldedDescription = text,
        hybridSimScore = 0.5f,
        isClickable = true,
        isEditable = editable,
        viewId = viewId,
        roleTag = "LIST_ITEM",
        parentContainerType = container
    )

    private fun set(options: List<CandidateOption>) = ImmutableOptionSet(
        frameId = 1L,
        windowIdx = 0,
        activeWindowId = 42,
        packageName = "com.demo.mail",
        postureState = DualPostureState(PagePosture.LIST_FEED),
        pageSpineSigs = LongArray(0),
        pageSpineHash = 0L,
        rejectedContextPrompts = emptyList(),
        selectableOptions = options
    )

    private fun classGoal() = LocalGoal(
        stepIdx = 1,
        goalId = "g2",
        goalText = "點開第一封",
        canonicalIntentTag = "explicit_tap",
        actionKind = ActionKind.TAP,
        inputPayload = "第一封",
        targetSpecificity = TargetSpecificity.CLASS
    )

    @Test
    fun `empty result page rejects instead of tapping search box`() {
        // 空結果頁：只有搜尋框可點
        val options = listOf(
            cand(1, "在郵件中搜尋", viewId = "com.x:id/search", editable = true)
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(classGoal(), set(options))
        assertEquals(DecisionOutcomeType.REJECT, decision.outcomeType)
        assertTrue("應回報空結果：${decision.reason}", decision.reason.contains("EMPTY_CONTENT_SET"))
        assertNull("不得選中搜尋框", decision.selectedOption)
    }

    @Test
    fun `content row still selected when present`() {
        val options = listOf(
            cand(1, "在郵件中搜尋", viewId = "com.x:id/search", editable = true),
            cand(2, "invoice 通知信", container = "LIST_ITEM")
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(classGoal(), set(options))
        assertEquals(DecisionOutcomeType.CLEAR_WIN, decision.outcomeType)
        assertEquals(2, decision.selectedOption?.markId)
    }

    @Test
    fun `duplicate goal is skipped`() {
        assertFalse(GoalAttemptGuard.shouldSkip("TAP", "第一封"))
        assertTrue("第二次同目標應跳過", GoalAttemptGuard.shouldSkip("TAP", "第一封"))
        assertFalse("不同參數不算重複", GoalAttemptGuard.shouldSkip("TAP", "第二封"))
        assertFalse("不同動作不算重複", GoalAttemptGuard.shouldSkip("TYPE", "第一封"))
    }

    @Test
    fun `guard resets per task`() {
        GoalAttemptGuard.shouldSkip("TAP", "第一封")
        GoalAttemptGuard.reset()
        assertFalse(GoalAttemptGuard.shouldSkip("TAP", "第一封"))
        assertEquals(1, GoalAttemptGuard.size())
    }
}