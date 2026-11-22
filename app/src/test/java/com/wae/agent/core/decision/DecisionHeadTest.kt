package com.wae.agent.core.decision

import com.wae.agent.core.ActionKind
import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import com.wae.agent.core.LocalGoal
import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.runtime.ExtremeRuntimeHooks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * S29 線性決策頭測試。
 *
 * 重點：
 *  1. 權重檔（ui_decision_head.tsv）能被正確解析（model/feature/weight + meta alpha）
 *  2. 總開關關閉時完全回傳 0（行為與舊版一致）
 *  3. 方向正確：可編輯搜尋框 > 底部導覽頁籤
 *  4. 壞掉的權重檔會退回 bootstrap，不會讓行為退化
 *  5. 未知特徵被忽略（不會因為權重檔多一行就亂加）
 *  6. 規劃邊特徵：op 相符才有正向貢獻
 */
class DecisionHeadTest {

    private var savedFlag = false

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    private fun goal(kind: ActionKind = ActionKind.TYPE) = LocalGoal(
        stepIdx = 0,
        goalId = "g1",
        goalText = "開啟 Wi-Fi",
        canonicalIntentTag = "WIFI_ON",
        actionKind = kind
    )

    private fun cand(
        id: Int,
        text: String = "",
        viewId: String = "",
        className: String = "android.view.View",
        editable: Boolean = false,
        clickable: Boolean = true,
        parent: String = "CONTAINER",
        sim: Float = 0.9f
    ) = CandidateOption(
        markId = id,
        nodeStableSig = id.toLong(),
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = intArrayOf(0, id * 100, 200, id * 100 + 80),
        coreText = text,
        weldedDescription = text,
        hybridSimScore = sim,
        isClickable = clickable,
        isEditable = editable,
        viewId = viewId,
        className = className,
        parentContainerType = parent
    )

    @Before
    fun setUp() {
        savedFlag = ExtremeRuntimeHooks.DECISION_HEAD_ENABLED
        DecisionHead.resetForTest()
        DecisionHead.loadFromLines(asset("ui_decision_head.tsv").readLines(Charsets.UTF_8))
    }

    @After
    fun tearDown() {
        ExtremeRuntimeHooks.DECISION_HEAD_ENABLED = savedFlag
        DecisionHead.resetForTest()
    }

    @Test
    fun tsvLoadsWeightsAndMetaAlpha() {
        assertTrue("權重數=${DecisionHead.weightCount()}", DecisionHead.weightCount() > 20)
        assertEquals(0.6f, DecisionHead.alphaOf("candidate"), 0.01f)
        assertEquals(0.25f, DecisionHead.alphaOf("edge"), 0.01f)
    }

    @Test
    fun disabledFlagReturnsZero() {
        ExtremeRuntimeHooks.DECISION_HEAD_ENABLED = false
        val editable = cand(1, "搜尋", viewId = "search_src_text", editable = true)
        assertEquals(0f, DecisionHead.candidateBonus(goal(), editable), 0.0001f)
        val edge = AutomatonEdge("a", "b", "TAP", "id:x", ok = 10, fail = 0)
        assertEquals(0.0, DecisionHead.edgeBonus("搜尋", "TAP", edge), 0.0001)
    }

    @Test
    fun editableSearchScoresAboveBottomNavTab() {
        ExtremeRuntimeHooks.DECISION_HEAD_ENABLED = true
        val editable = cand(
            1, "搜尋", viewId = "search_src_text",
            className = "android.widget.EditText", editable = true
        )
        val tab = cand(2, "首頁", viewId = "tab_strip_button", parent = "BOTTOM_NAV")

        val bEditable = DecisionHead.candidateBonus(goal(), editable)
        val bTab = DecisionHead.candidateBonus(goal(), tab)

        assertTrue("editable=$bEditable 應為正", bEditable > 0f)
        assertTrue("tab=$bTab 應為負（底部導覽不是目標）", bTab < 0f)
        assertTrue("editable=$bEditable 應大於 tab=$bTab", bEditable > bTab)
    }

    @Test
    fun brokenWeightsFallBackToBootstrap() {
        DecisionHead.resetForTest()
        DecisionHead.loadFromLines(listOf("not a valid row", "只有一欄"))
        assertTrue("bootstrap 權重數=${DecisionHead.weightCount()}", DecisionHead.weightCount() > 10)
    }

    @Test
    fun unknownFeatureIsIgnored() {
        DecisionHead.resetForTest()
        DecisionHead.loadFromLines(listOf("candidate\tflag:doesNotExist\t5.0"))
        ExtremeRuntimeHooks.DECISION_HEAD_ENABLED = true
        assertEquals(0f, DecisionHead.candidateBonus(goal(), cand(1, "x")), 0.0001f)
    }

    @Test
    fun edgeOpMatchScoresPositive() {
        DecisionHead.resetForTest()
        DecisionHead.loadFromLines(
            listOf("meta\talpha_edge\t1.0", "edge\tflag:opMatch\t1.0")
        )
        ExtremeRuntimeHooks.DECISION_HEAD_ENABLED = true
        val tapEdge = AutomatonEdge("a", "b", "TAP", "id:x")
        val backEdge = AutomatonEdge("a", "b", "BACK", "id:x")
        assertTrue(DecisionHead.edgeBonus("", "TAP", tapEdge) > 0.0)
        assertEquals(0.0, DecisionHead.edgeBonus("", "TAP", backEdge), 0.0001)
    }

    @Test
    fun resetClearsWeights() {
        DecisionHead.resetForTest()
        assertEquals(0, DecisionHead.weightCount())
    }

    @Test
    fun bonusIsClamped() {
        DecisionHead.resetForTest()
        // 單一特徵權重極大 + alpha 1.0 → 仍被 clamp 在 ±2
        DecisionHead.loadFromLines(
            listOf("meta\talpha_candidate\t1.0", "candidate\tflag:isEditable\t100.0")
        )
        ExtremeRuntimeHooks.DECISION_HEAD_ENABLED = true
        val editable = cand(1, "x", editable = true)
        val bonus = DecisionHead.candidateBonus(goal(), editable)
        assertEquals(2.0f, bonus, 0.0001f)
    }
}
