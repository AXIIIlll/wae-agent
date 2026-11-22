package com.wae.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3 近同分（NEAR_TIE）澄清規則的單元測試。
 * 全部走 JVM 後備算子（WaeNativeBridge 在無 so 時自動降級），不需要 Android 執行期。
 */
class WaeNearTieAmbiguityTest {

    private fun opt(
        markId: Int,
        text: String,
        sim: Float,
        clickable: Boolean = true,
        editable: Boolean = false,
        viewId: String = "",
        className: String = "android.widget.TextView"
    ) = CandidateOption(
        markId = markId,
        nodeStableSig = markId.toLong(),
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = intArrayOf(0, 100 * markId, 200, 100 * markId + 80),
        coreText = text,
        weldedDescription = "desc:$text",
        hybridSimScore = sim,
        isEnabled = true,
        isClickable = clickable,
        isEditable = editable,
        viewId = viewId,
        className = className
    )

    private fun goalOf(policy: AmbiguityPolicy = AmbiguityPolicy.AUTO_RESOLVE) = LocalGoal(
        stepIdx = 0,
        goalId = "g1",
        goalText = "搜尋發票",
        canonicalIntentTag = "search_submit",
        actionKind = ActionKind.TAP,
        ambiguityPolicy = policy
    )

    private fun setOf(options: List<CandidateOption>) = ImmutableOptionSet(
        frameId = 1L,
        windowIdx = 0,
        activeWindowId = 42,
        packageName = "com.demo.app",
        postureState = DualPostureState(PagePosture.DETAIL_PAGE),
        pageSpineSigs = LongArray(0),
        pageSpineHash = 0L,
        rejectedContextPrompts = emptyList(),
        selectableOptions = options
    )

    @Test
    fun `near tie between two actionable candidates asks user instead of hard picking`() {
        val options = listOf(
            opt(1, "發票查詢", sim = 0.82f),
            opt(2, "查詢發票", sim = 0.81f),
            opt(3, "首頁", sim = 0.30f)
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goalOf(), setOf(options))
        assertEquals(DecisionOutcomeType.AMBIGUOUS, decision.outcomeType)
        assertTrue(decision.reason.contains("NEAR_TIE_AMBIGUITY"))
        assertTrue(decision.top3AmbiguousCandidates.size >= 2)
    }

    @Test
    fun `clear winner with big gap is not treated as near tie`() {
        val options = listOf(
            opt(1, "發票查詢", sim = 0.93f),
            opt(2, "查詢明細", sim = 0.55f)
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goalOf(), setOf(options))
        assertEquals(DecisionOutcomeType.CLEAR_WIN, decision.outcomeType)
        assertNotNull(decision.selectedOption)
        assertEquals(1, decision.selectedOption!!.markId)
    }

    @Test
    fun `near tie against a non actionable caption does not ask user`() {
        // Top-2 只是同分的說明文字（不可點、不可輸入、非搜尋入口）→ 不該干擾使用者
        val options = listOf(
            opt(1, "發票查詢", sim = 0.90f, clickable = true),
            opt(2, "發票", sim = 0.90f, clickable = false, editable = false)
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goalOf(), setOf(options))
        assertEquals(DecisionOutcomeType.CLEAR_WIN, decision.outcomeType)
    }

    @Test
    fun `identical labels at same sim prefer first by policy instead of asking`() {
        // 同一標籤重複出現（如列表多個「關注」）→ 不視為歧義，照 PREFER_FIRST 取第一個
        val options = listOf(
            opt(1, "關注", sim = 0.90f),
            opt(2, "關注", sim = 0.90f)
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(
            goalOf(AmbiguityPolicy.PREFER_FIRST),
            setOf(options)
        )
        assertEquals(DecisionOutcomeType.CLEAR_WIN, decision.outcomeType)
    }

    @Test
    fun `both below 080 does not trigger near tie ask`() {
        val options = listOf(
            opt(1, "發票查詢", sim = 0.70f),
            opt(2, "查詢發票", sim = 0.70f)
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goalOf(), setOf(options))
        assertTrue(
            "低於 0.80 的近同分不應強制澄清（維持原 REJECT / CLEAR_WIN 行為）",
            decision.outcomeType != DecisionOutcomeType.AMBIGUOUS
        )
    }
}
