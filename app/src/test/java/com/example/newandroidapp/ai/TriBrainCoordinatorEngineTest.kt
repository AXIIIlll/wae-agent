package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppOperationStage
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriBrainCoordinatorEngineTest {

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
        gridY: Int = 300,
        visibleRatio: Float = 1.0f,
        sourceType: NodeSourceType = NodeSourceType.UI_TREE,
        visualConf: Float = 0.95f
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = if (tag == "INP") "android.widget.EditText" else "android.widget.Button",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = rect(100, gridY * 2, 900, gridY * 2 + 100),
            convergedBounds = rect(100, gridY * 2, 900, gridY * 2 + 100),
            finalTapPx = pt(gridX, gridY * 2),
            gridTapPoint = pt(gridX, gridY),
            visibleRatio = visibleRatio,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = sourceType,
            visualConfidence = visualConf
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
    fun `test MiniSat and SMT solver hard-prunes voice mic, toolbar, and occluded nodes`() {
        InAppWorkingMemory.resetForNewGoal("搜尋 今天股票")
        val nSearch = makeNode("n_search", 1, "INP", "搜尋或輸入網址", gridX = 450, gridY = 110)
        val nVoice = makeNode("n_voice", 2, "BTN", "語音搜尋", gridX = 820, gridY = 110)
        val nToolbar = makeNode("n_incog", 3, "BTN", "新增無痕分頁", gridX = 910, gridY = 110)
        val nOccluded = makeNode("n_occ", 4, "BTN", "今天股票", gridX = 500, gridY = 400, visibleRatio = 0.10f)
        val nPayRisk = makeNode("n_pay", 5, "BTN", "確認付款", gridX = 500, gridY = 850)

        val audit = TriBrainCoordinatorEngine.runSatSmtConstraintSolver(
            candidates = listOf(nSearch, nVoice, nToolbar, nOccluded, nPayRisk),
            userGoal = "搜尋 今天股票",
            screenHash = "fp_chrome_home"
        )

        assertTrue(audit.isSatisfiable)
        assertEquals(1.toByte(), audit.legalMask[0]) // 搜尋框合法
        assertEquals(0.toByte(), audit.legalMask[1]) // 語音搜尋被 SAT 剪枝
        assertEquals(0.toByte(), audit.legalMask[2]) // 無痕分頁被 SAT 剪枝
        assertEquals(0.toByte(), audit.legalMask[3]) // 露出率 0.10 被 SMT 幾何約束剪枝
        assertEquals(1.toByte(), audit.legalMask[4]) // 付款按鈕合法但標記高風險
        assertEquals(1.toByte(), audit.highRiskMask[4])
        assertEquals(1, audit.prunedVoiceCameraCount)
        assertEquals(1, audit.prunedToolbarCount)
        assertEquals(1, audit.prunedGeometryCount)
    }

    @Test
    fun `test PDDL automated planner derives multi-step search and cross-app extraction plan`() {
        InAppWorkingMemory.resetForNewGoal("去google瀏覽器查一下今天股票然後把文字丟給line的一個人")

        // 情境 1：尚未展開搜尋輸入框 (只有按鈕) -> PDDL A* 應推導出 2 步計畫: EXPAND_SEARCH_ENTRY -> TYPE_AND_SUBMIT_QUERY
        val homeReport = makeReport(
            listOf(makeNode("n_btn", 1, "BTN", "搜尋", gridX = 880, gridY = 100))
        )
        val searchSubGoal = CooperativeSubGoal(
            stepIndex = 2,
            totalSteps = 6,
            intentCategory = "INPUT_SEARCH",
            targetSemanticPhrase = "搜尋 今天股票",
            simplifiedMotorGoal = "搜尋 今天股票",
            inputPayload = "今天股票",
            targetAppHint = "Chrome",
            appPhaseIndex = 1,
            totalAppBlocks = 2
        )

        val plan1 = TriBrainCoordinatorEngine.runPddlAutomatedPlanner(
            report = homeReport,
            activeSubGoal = searchSubGoal,
            inAppStage = InAppOperationStage.STAGE_A_EXPAND_SEARCH,
            hasBlocker = false
        )
        assertTrue(plan1.planFound)
        assertEquals("EXPAND_SEARCH_ENTRY", plan1.immediateOperator)
        assertEquals(listOf("EXPAND_SEARCH_ENTRY", "TYPE_AND_SUBMIT_QUERY"), plan1.operatorSequence)

        // 情境 2：畫面已出現 INP 輸入框 -> PDDL A* 應推導出 1 步計畫: TYPE_AND_SUBMIT_QUERY
        val inputReport = makeReport(
            listOf(makeNode("n_inp", 1, "INP", "搜尋或輸入網址", gridX = 450, gridY = 110))
        )
        val plan2 = TriBrainCoordinatorEngine.runPddlAutomatedPlanner(
            report = inputReport,
            activeSubGoal = searchSubGoal,
            inAppStage = InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT,
            hasBlocker = false
        )
        assertTrue(plan2.planFound)
        assertEquals("TYPE_AND_SUBMIT_QUERY", plan2.immediateOperator)
        assertEquals(1, plan2.operatorSequence.size)
    }

    @Test
    fun `test TRM 3-step recursive model and 64-rollout MCTS with visual cortex 16D state tensor`() {
        InAppWorkingMemory.resetForNewGoal("打開 藍牙")
        val btLabel = makeNode("lbl_bt", 1, "TXT", "藍牙", gridX = 220, gridY = 320)
        // 同水平列 (gridY = 320) 由 NanoDet 視覺偵測出之無文字 Switch 開關
        val btSwitch = makeNode(
            id = "sw_bt",
            markId = 2,
            tag = "CHK",
            label = "開關",
            gridX = 880,
            gridY = 320,
            sourceType = NodeSourceType.NANODET_UI_DETECT,
            visualConf = 0.92f
        )
        val wifiSwitch = makeNode("sw_wifi", 3, "CHK", "Wi-Fi 開關", gridX = 880, gridY = 180)
        val report = makeReport(listOf(btLabel, btSwitch, wifiSwitch))

        val subGoal = CooperativeSubGoal(
            stepIndex = 2,
            totalSteps = 2,
            intentCategory = "TOGGLE_SWITCH",
            targetSemanticPhrase = "藍牙",
            simplifiedMotorGoal = "開啟 藍牙",
            targetAppHint = "設定",
             desiredToggleState = true
        )

        val eval = TriBrainCoordinatorEngine.evaluateTriBrainPipeline(
            candidates = listOf(btSwitch, wifiSwitch),
            report = report,
            activeSubGoal = subGoal,
            isolatedMotorGoal = "開啟 藍牙",
            inAppStage = InAppOperationStage.IN_APP_NAVIGATION,
            plannerRanked = listOf(btSwitch to 0.78f, wifiSwitch to 0.42f),
            neuralRanked = listOf(btSwitch to 0.75f, wifiSwitch to 0.40f),
            foregroundPackage = "com.android.settings",
            screenHash = "fp_settings",
            context = null
        )

        assertNotNull(eval.bestCandidate)
        assertEquals("sw_bt", eval.bestCandidate?.nodeId)
        assertTrue(eval.cotThoughtChain.startsWith("<cot>[Micro-Llama Q4 CoT]"))
        assertTrue(eval.trmMctsDiagnostic.contains("TRM(3步遞迴)+MCTS(64次模擬)"))
    }

    @Test
    fun `test pure YouTube search stops immediately after query submission and revokes motor permission`() {
        val cmd = "去YouTube查周杰倫"
        InAppWorkingMemory.resetForNewGoal(cmd)
        val token = InAppWorkingMemory.grantMotorPermission(cmd)
        assertTrue(InAppWorkingMemory.hasMotorPermission(token))

        // 1. 純搜尋（未指定播放/點第一個）不得多塞 SELECT_RESULT
        assertFalse(InAppOperationPlanner.requiresResultSelectionAfterSearch(cmd, "YouTube", ""))
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(cmd, null, false)
        assertEquals(2, subGoals.size)
        assertEquals("LAUNCH_APP", subGoals[0].intentCategory)
        assertEquals("INPUT_SEARCH", subGoals[1].intentCategory)

        // 2. 模擬已送出「周杰倫」並顯示 YouTube 結果頁
        InAppWorkingMemory.recordTextTypedAndSubmitted("周杰倫", "INP|搜尋")
        val topSearchBar = makeNode("yt_search_bar", 1, "INP", "周杰倫", gridX = 480, gridY = 95)
        val chipShorts = makeNode("chip_shorts", 2, "BTN", "Shorts", gridX = 220, gridY = 175)
        val video1 = makeNode("vid_1", 3, "TXT", "周杰倫 Jay Chou【晴天】Official MV", gridX = 500, gridY = 380)
        val video2 = makeNode("vid_2", 4, "TXT", "周杰倫 Jay Chou【七里香】Official MV", gridX = 500, gridY = 620)
        val resultReport = makeReport(listOf(topSearchBar, chipShorts, video1, video2))

        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = resultReport,
            context = null,
            currentForegroundPackage = "com.google.android.youtube",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 1
        )

        // 3. 必須立即回傳 FINISH_DONE 並當場撤銷動手權限 (hasMotorPermission == false)
        assertEquals(AiActionType.FINISH_DONE, decision.actionType)
        assertFalse("完成後必須立即關閉 AI 動手權限", InAppWorkingMemory.hasMotorPermission())
        assertFalse("舊 Session Token 必須立即失效", InAppWorkingMemory.hasMotorPermission(token))
    }

    @Test
    fun `test same definition query is locked after one search while different definition query B remains open`() {
        val cmd = "去YouTube查周杰倫"
        InAppWorkingMemory.resetForNewGoal(cmd)
        InAppWorkingMemory.recordTextTypedAndSubmitted("周杰倫", "INP|搜尋")

        // 1. 同定義目標（「周杰倫」、「搜尋 周杰倫」、「查一下周杰倫」）皆視為已查過一次
        assertTrue(InAppWorkingMemory.hasSubmittedEquivalentQuery("周杰倫"))
        assertTrue(InAppWorkingMemory.hasSubmittedEquivalentQuery("搜尋 周杰倫"))
        assertTrue(InAppWorkingMemory.hasSubmittedEquivalentQuery("查一下周杰倫"))

        // 2. 不同定義目標（「林俊傑」、「台積電」）不計入封鎖，回傳 false
        assertFalse(InAppWorkingMemory.hasSubmittedEquivalentQuery("林俊傑"))
        assertFalse(InAppWorkingMemory.hasSubmittedEquivalentQuery("搜尋 林俊傑"))
        assertFalse(InAppWorkingMemory.hasSubmittedEquivalentQuery("台積電"))

        val topSearchNode = makeNode("top_search_inp", 1, "INP", "周杰倫", gridX = 480, gridY = 95)
        val resultCardNode = makeNode("res_card", 2, "TXT", "周杰倫 精選輯", gridX = 500, gridY = 420)
        val candidates = listOf(topSearchNode, resultCardNode)

        // 3. 當目標仍是同定義的「搜尋 周杰倫」時，SAT/SMT 硬性封鎖頂部搜尋框 (legalMask[0] == 0)
        val satSameQuery = TriBrainCoordinatorEngine.runSatSmtConstraintSolver(
            candidates = candidates,
            userGoal = "搜尋 周杰倫",
            screenHash = "fp_yt_results",
            activeTargetQuery = "周杰倫"
        )
        assertEquals("同定義目標已查過一次，頂部搜尋框必須被鎖死", 0, satSameQuery.legalMask[0].toInt())
        assertEquals(1, satSameQuery.legalMask[1].toInt())

        // 4. 當推進至不同定義的新目標 B「搜尋 林俊傑」時，SAT/SMT 開放頂部搜尋框 (legalMask[0] == 1)，且 PDDL 規劃重新輸入搜尋！
        val satDifferentQuery = TriBrainCoordinatorEngine.runSatSmtConstraintSolver(
            candidates = candidates,
            userGoal = "搜尋 林俊傑",
            screenHash = "fp_yt_results",
            activeTargetQuery = "林俊傑"
        )
        assertEquals("不同定義的新目標 B 不計入封鎖，頂部搜尋框必須開放", 1, satDifferentQuery.legalMask[0].toInt())

        val queryBSubGoal = CooperativeSubGoal(
            stepIndex = 2,
            totalSteps = 2,
            intentCategory = "INPUT_SEARCH",
            targetSemanticPhrase = "搜尋 林俊傑",
            simplifiedMotorGoal = "搜尋 林俊傑",
            inputPayload = "林俊傑"
        )
        val pddlForQueryB = TriBrainCoordinatorEngine.runPddlAutomatedPlanner(
            report = makeReport(candidates),
            activeSubGoal = queryBSubGoal,
            inAppStage = InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT,
            hasBlocker = false
        )
        assertEquals("TYPE_AND_SUBMIT_QUERY", pddlForQueryB.immediateOperator)
    }

    @Test
    fun `test scroll down when target button is not on screen and say cannot do it when reached bottom`() {
        val cmd = "點擊「進階匯出報表」"
        InAppWorkingMemory.grantMotorPermission(cmd)

        val unrelatedScreenReport = makeReport(
            listOf(
                makeNode("item_1", 1, "BTN", "個人資料編輯", gridX = 500, gridY = 300),
                makeNode("item_2", 2, "BTN", "通知偏好設定", gridX = 500, gridY = 480),
                makeNode("item_3", 3, "BTN", "語言與地區", gridX = 500, gridY = 660)
            )
        )

        // 1. 畫面上沒有「進階匯出報表」且尚未滑到底部：AI 必須往下滑動 (SCROLL_DOWN) 尋找，禁止亂點無關元件！
        val step1Decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = unrelatedScreenReport,
            context = null,
            currentForegroundPackage = "com.example.demo",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 0,
            consecutiveScrollNoChangeCount = 0
        )
        assertEquals(AiActionType.SCROLL_DOWN, step1Decision.actionType)

        // 2. 滑動後「進階匯出報表」出現在畫面上：AI 立即鎖定並點擊 (TAP) 該按鍵！
        val targetVisibleReport = makeReport(
            listOf(
                makeNode("item_3", 1, "BTN", "語言與地區", gridX = 500, gridY = 260),
                makeNode("target_export", 2, "BTN", "進階匯出報表", gridX = 500, gridY = 540)
            )
        )
        val step2Decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = targetVisibleReport,
            context = null,
            currentForegroundPackage = "com.example.demo",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 0,
            consecutiveScrollNoChangeCount = 0
        )
        assertEquals(AiActionType.TAP, step2Decision.actionType)
        assertEquals("target_export", step2Decision.targetNode?.nodeId)

        // 3. 若往下滑到底一次 (consecutiveScrollNoChangeCount == 1)，先往上回滑一次 (SCROLL_UP / SU_EXPLORE) 檢查上方是否漏掉
        InAppWorkingMemory.resetForNewGoal(cmd)
        val reverseScrollUpDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = unrelatedScreenReport,
            context = null,
            currentForegroundPackage = "com.example.demo",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 0,
            consecutiveScrollNoChangeCount = 1
        )
        assertEquals(AiActionType.SCROLL_UP, reverseScrollUpDecision.actionType)
        assertEquals("SU_EXPLORE", reverseScrollUpDecision.compactOpcode)

        // 4. 往上回滑補查後仍未找到目標按鍵：立即撤銷動手權限並直接回報「翻到底都沒看到這個按鍵，沒辦法按。」
        val bottomNotFoundDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = unrelatedScreenReport,
            context = null,
            currentForegroundPackage = "com.example.demo",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 0,
            consecutiveScrollNoChangeCount = 1
        )
        assertEquals(AiActionType.ANSWER_QUESTION, bottomNotFoundDecision.actionType)
        assertEquals("ABORT_NOT_FOUND", bottomNotFoundDecision.compactOpcode)
        assertTrue(bottomNotFoundDecision.reasoningTrace.contains("翻到底都沒看到這個按鍵"))
        assertFalse("滑到底找不到目標按鍵後必須立即關閉 AI 動手權限", InAppWorkingMemory.hasMotorPermission())

        // 5. 若畫面具有橫向可滑動分頁/輪播列（同一水平帶 >= 3 個按鈕且橫向跨度 >= 420），垂直滑動到底時自動觸發 SWIPE_LEFT (SL_EXPLORE) 橫向探索！
        InAppWorkingMemory.resetForNewGoal(cmd)
        val horizontalCarouselReport = makeReport(
            listOf(
                makeNode("chip_1", 1, "CHK", "推薦", gridX = 160, gridY = 180),
                makeNode("chip_2", 2, "CHK", "音樂", gridX = 480, gridY = 180),
                makeNode("chip_3", 3, "CHK", "直播", gridX = 820, gridY = 180)
            )
        )
        val horizDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = horizontalCarouselReport,
            context = null,
            currentForegroundPackage = "com.example.demo",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 0,
            consecutiveScrollNoChangeCount = 1
        )
        assertEquals(AiActionType.SWIPE_LEFT, horizDecision.actionType)
        assertEquals("SL_EXPLORE", horizDecision.compactOpcode)
    }

    @Test
    fun `test tri brain visual predicates and 16D visual role scoring and smart visual fallback`() {
        InAppWorkingMemory.resetForNewGoal("搜尋 周杰倫")

        val nanoSearchNode = makeNode(
            id = "nano_search_1",
            markId = 1,
            tag = "BTN",
            label = "搜尋",
            gridX = 888,
            gridY = 68,
            sourceType = NodeSourceType.NANODET_UI_DETECT,
            visualConf = 0.89f
        ).copy(
            predictedFunctionRole = "SEARCH_ENTRY"
        )
        val unrelatedNode = makeNode("other_btn", 2, "BTN", "個人檔案", gridX = 850, gridY = 920)
        val candidates = listOf(nanoSearchNode, unrelatedNode)

        val visualReport = makeReport(candidates).copy(
            roiRecoveredCount = 1,
            nanoDetDetectedCount = 1
        )

        val subGoal = CooperativeSubGoal(
            stepIndex = 1,
            totalSteps = 1,
            intentCategory = "INPUT_SEARCH",
            targetSemanticPhrase = "搜尋 周杰倫",
            simplifiedMotorGoal = "搜尋 周杰倫",
            inputPayload = "周杰倫"
        )

        val eval = TriBrainCoordinatorEngine.evaluateTriBrainPipeline(
            candidates = candidates,
            report = visualReport,
            activeSubGoal = subGoal,
            isolatedMotorGoal = "搜尋 周杰倫",
            inAppStage = InAppOperationStage.STAGE_A_EXPAND_SEARCH,
            plannerRanked = listOf(nanoSearchNode to 0.91f, unrelatedNode to 0.14f),
            neuralRanked = listOf(nanoSearchNode to 0.91f, unrelatedNode to 0.14f),
            foregroundPackage = "com.example.video",
            screenHash = "fp_visual_1",
            context = null
        )

        // 1. 雙通道視覺特徵轉譯橋必須標記 Nano=1, OCR=1 並選出 NanoDet 視覺搜尋圖示
        assertTrue(eval.visualSceneSummary.contains("Nano=1"))
        assertEquals("EXPAND_SEARCH_ENTRY", eval.pddlPlan.immediateOperator)
        assertEquals("nano_search_1", eval.bestCandidate?.nodeId)
        assertTrue(eval.bestConfidence >= 0.70f)
        assertFalse(eval.requiresSmartVisualFallback)

        // 2. 當 Fast-Tree 找不到目標 (bestConfidence < 0.22f) 且尚未執行像素視覺 (nanoDet=0, roi=0) 時，觸發 Smart Visual Fallback
        val emptyTreeCandidates = listOf(unrelatedNode)
        val fastTreeReport = makeReport(emptyTreeCandidates)
        val missSubGoal = CooperativeSubGoal(
            stepIndex = 1,
            totalSteps = 1,
            intentCategory = "TAP",
            targetSemanticPhrase = "點擊「進階匯出報表」",
            simplifiedMotorGoal = "點擊「進階匯出報表」"
        )
        val missEval = TriBrainCoordinatorEngine.evaluateTriBrainPipeline(
            candidates = emptyTreeCandidates,
            report = fastTreeReport,
            activeSubGoal = missSubGoal,
            isolatedMotorGoal = "點擊「進階匯出報表」",
            inAppStage = InAppOperationStage.STAGE_C_SELECT_RESULT,
            plannerRanked = listOf(unrelatedNode to 0.14f),
            neuralRanked = listOf(unrelatedNode to 0.14f),
            foregroundPackage = "com.example.demo",
            screenHash = "fp_miss_1",
            context = null
        )
        assertTrue("Fast-Tree 未命中且尚未跑像素視覺時，必須要求啟動 Smart Visual Fallback", missEval.requiresSmartVisualFallback)
    }
}
