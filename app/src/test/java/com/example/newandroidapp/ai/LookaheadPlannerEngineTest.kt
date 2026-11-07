package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookaheadPlannerEngineTest {

    private fun pt(x: Int, y: Int): Point = Point(x, y).apply { this.x = x; this.y = y }

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    private fun makeNode(
        id: String,
        markId: Int,
        tag: String,
        label: String,
        gridX: Int = 500,
        gridY: Int = 400,
        prefix: String = ""
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = "android.view.View",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = prefix,
            rawOriginalBounds = rect(100, gridY * 2, 900, gridY * 2 + 100),
            convergedBounds = rect(100, gridY * 2, 900, gridY * 2 + 100),
            finalTapPx = pt(gridX, gridY * 2),
            gridTapPoint = pt(gridX, gridY),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
    }

    private fun makeReport(nodes: List<Stage2ConvergedNode>): VisionPipelineReport {
        return VisionPipelineReport(
            timestampMs = 1000L,
            screenWidth = 1080,
            screenHeight = 2340,
            gridCols = 120,
            gridRows = 240,
            occupancyGridSnapshot = ByteArray(0),
            totalRawNodes = nodes.size,
            culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0,
            transparentOverlayBypassedCount = 0,
            hotspotConvergedCount = 0,
            spatialBoundIconCount = 0,
            geometricIconClassifiedCount = 0,
            roiRecoveredCount = 0,
            nanoDetDetectedCount = 0,
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = emptyList(),
            detectedRoiVoids = emptyList(),
            finalNodes = nodes,
            markIdToPixelMap = nodes.associate { it.shortMarkId to it.finalTapPx },
            compressedPromptText = "",
            deltaPromptText = "",
            rawCharCount = 100,
            compressedCharCount = 50,
            compressionRatioPercent = 50,
            pipelineLatencyMs = 5L
        )
    }

    @Test
    fun testStage1_SearchFirstShortcutWhenTargetNotOnScreen() {
        val goal = "開啟深色模式"
        InAppOperationPlanner.resetForNewGoal(goal)

        val searchSettingsNode = makeNode("search_bar", 1, "BTN", "搜尋設定", gridX = 400, gridY = 120)
        val voiceMicNode = makeNode("voice_search_mic", 2, "ICO", "語音搜尋", gridX = 900, gridY = 120)
        val displayNode = makeNode("display_cat", 3, "BTN", "顯示", gridX = 500, gridY = 450)

        val report = makeReport(listOf(searchSettingsNode, voiceMicNode, displayNode))
        val eval = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = report,
            userGoal = goal,
            screenFp = "settings_home"
        )

        assertNotNull(eval.overrideTargetNode)
        assertEquals("search_bar", eval.overrideTargetNode?.nodeId)
        assertEquals(AiActionType.TAP, eval.overrideActionType)
        assertTrue(eval.stageReasoningNote.contains("搜尋優先捷徑"))
    }

    @Test
    fun testStage2_CrossBrandSynonymExpansionOnNewScreen() {
        val goal = "開啟深色模式"
        InAppOperationPlanner.resetForNewGoal(goal)

        val parentDisplayNode = makeNode("display_cat", 1, "BTN", "顯示", gridX = 500, gridY = 400)
        InAppWorkingMemory.pushBreadcrumb("settings_root", parentDisplayNode)

        // 進入新介面後，按鈕名稱不叫「深色模式」，而叫「暗色主題」
        val brightnessNode = makeNode("brightness", 1, "BTN", "自動調整亮度", gridX = 500, gridY = 300)
        val darkThemeSynonymNode = makeNode("dark_theme", 2, "CHK", "暗色主題", gridX = 500, gridY = 520)
        val subPageReport = makeReport(listOf(brightnessNode, darkThemeSynonymNode))

        // 1. 驗證不會因為字面沒有「深色模式」而誤觸發自動返回
        val backtrack = InAppOperationPlanner.shouldTriggerAutoBacktrack(
            report = subPageReport,
            userGoal = goal,
            activeSubGoal = null,
            bestScore = 0.20f,
            consecutiveScrollNoChangeCount = 1
        )
        assertNull("同義詞「暗色主題」已在畫面上，絕不可誤觸發返回上一頁", backtrack)

        // 2. 驗證同義詞展開直接命中「暗色主題」
        val eval = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = subPageReport,
            userGoal = goal,
            screenFp = "display_sub_page"
        )
        assertEquals("dark_theme", eval.adjustedCandidates.first().first.nodeId)
        assertTrue(eval.stageReasoningNote.contains("同義詞展開"))
    }

    @Test
    fun testStage3_HierarchicalCategoryLookahead_ScrollOnce_AutoBacktrackToRank2() {
        val goal = "開啟深色模式"
        InAppOperationPlanner.resetForNewGoal(goal)

        val networkNode = makeNode("net_cat", 1, "BTN", "網路和網際網路", gridX = 500, gridY = 300)
        val displayRank1Node = makeNode("display_cat", 2, "BTN", "顯示", gridX = 500, gridY = 480)
        val wallpaperRank2Node = makeNode("wallpaper_cat", 3, "BTN", "桌布與樣式", gridX = 500, gridY = 660)

        val rootReport = makeReport(listOf(networkNode, displayRank1Node, wallpaperRank2Node))

        // Step 1: 在沒有搜尋框的設定首頁，3 步前瞻優先選擇第 1 順位父分類「顯示」(+62f)
        val step1Eval = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = rootReport,
            userGoal = goal,
            screenFp = "settings_root"
        )
        assertEquals("display_cat", step1Eval.overrideTargetNode?.nodeId)
        assertEquals("display_cat", step1Eval.adjustedCandidates.first().first.nodeId)

        // 模擬點進「顯示」進入子介面
        InAppWorkingMemory.pushBreadcrumb("settings_root", displayRank1Node)

        // Step 2: 子介面只有「亮度」與「螢幕逾時」，沒有深色模式或其同義詞
        val wrongSubPageReport = makeReport(
            listOf(
                makeNode("sub_1", 1, "BTN", "亮度等級", gridX = 500, gridY = 300),
                makeNode("sub_2", 2, "BTN", "螢幕逾時", gridX = 500, gridY = 480),
                makeNode("sub_3", 3, "BTN", "螢幕保護程式", gridX = 500, gridY = 660)
            )
        )

        // 剛進入子介面還沒下滑探索前 (scrollCount = 0)，不立即退回，先給 1 次下滑機會探索下半部
        val backtrackBeforeScroll = InAppOperationPlanner.shouldTriggerAutoBacktrack(
            report = wrongSubPageReport,
            userGoal = goal,
            activeSubGoal = null,
            bestScore = 0.20f,
            consecutiveScrollNoChangeCount = 0
        )
        assertNull("剛進入新介面尚未下滑前，應先允許下滑 1 次檢查下半部", backtrackBeforeScroll)

        // Step 3: 下滑 1 次後仍無「深色模式」，立即觸發 DFS Auto-Backtrack 按返回鍵退回上一頁！
        InAppWorkingMemory.recordScroll()
        val poppedBreadcrumb = InAppOperationPlanner.shouldTriggerAutoBacktrack(
            report = wrongSubPageReport,
            userGoal = goal,
            activeSubGoal = null,
            bestScore = 0.20f,
            consecutiveScrollNoChangeCount = 0
        )
        assertNotNull("下滑 1 次後仍找不到目標按鈕，必須立即觸發自動返回上一頁", poppedBreadcrumb)
        assertEquals("display_cat", poppedBreadcrumb?.clickedNode?.nodeId)

        // Step 4: 退回設定首頁 ("settings_root") 後，「顯示」已被標記為死路 (-100f)，自動改選第 2 順位「桌布與樣式」！
        val step4EvalAfterBacktrack = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = rootReport,
            userGoal = goal,
            screenFp = "settings_root"
        )
        assertEquals(
            "退回上一頁後，第 1 順位「顯示」已標記死胡同，必須自動改點第 2 順位「桌布與樣式」",
            "wallpaper_cat",
            step4EvalAfterBacktrack.overrideTargetNode?.nodeId
        )
        assertEquals("wallpaper_cat", step4EvalAfterBacktrack.adjustedCandidates.first().first.nodeId)
    }

    @Test
    fun testStage4_PreconditionAgreementCheckboxLookaheadBeforeNext() {
        val goal = "點擊下一步"
        InAppOperationPlanner.resetForNewGoal(goal)

        val agreementChk = makeNode("chk_agree", 1, "CHK", "我已閱讀並同意服務條款", gridX = 200, gridY = 720, prefix = "[未勾選]")
        val nextBtn = makeNode("btn_next", 2, "BTN", "下一步", gridX = 500, gridY = 860)

        val report = makeReport(listOf(agreementChk, nextBtn))
        val eval = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = report,
            userGoal = goal,
            screenFp = "register_page"
        )

        assertEquals("chk_agree", eval.overrideTargetNode?.nodeId)
        assertEquals(AiActionType.TAP, eval.overrideActionType)
        assertTrue(eval.stageReasoningNote.contains("前置解鎖"))
    }
}
