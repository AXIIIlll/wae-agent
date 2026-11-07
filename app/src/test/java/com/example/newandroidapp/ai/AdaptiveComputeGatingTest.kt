package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.ClosedLoopVerifier
import com.example.newandroidapp.stability.InAppOperationStage
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage1OccludedNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdaptiveComputeGatingTest {

    @Before
    fun setUp() {
        InAppWorkingMemory.resetForNewGoal("")
        AdaptiveComputeGate.reset()
        TriBrainCoordinatorEngine.resetTelemetryCounters()
    }

    private fun makeRect(l: Int, t: Int, r: Int, b: Int): Rect {
        return Rect(l, t, r, b).apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    private fun makePoint(px: Int, py: Int): Point {
        return Point(px, py).apply {
            x = px
            y = py
        }
    }

    private fun makeNode(
        id: String,
        markId: Int,
        tag: String,
        label: String,
        x: Int = 540,
        y: Int = 420,
        pkg: String = "com.google.android.youtube",
        nanoDetCategory: String = ""
    ): Stage2ConvergedNode {
        val bounds = makeRect(x - 100, y - 40, x + 100, y + 40)
        val gx = ((x / 1080f) * 999f).toInt().coerceIn(0, 999)
        val gy = ((y / 2400f) * 999f).toInt().coerceIn(0, 999)
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = if (tag == "INP") "android.widget.EditText" else "android.widget.Button",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = bounds,
            convergedBounds = bounds,
            finalTapPx = makePoint(x, y),
            gridTapPoint = makePoint(gx, gy),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE,
            packageName = pkg,
            nanoDetCategory = nanoDetCategory
        )
    }

    private fun makeReport(
        nodes: List<Stage2ConvergedNode>,
        stage1Nodes: List<Stage1OccludedNode> = emptyList(),
        screenPosture: String = "POSTURE_GENERAL"
    ): VisionPipelineReport {
        return VisionPipelineReport(
            timestampMs = 1000L,
            screenWidth = 1080,
            screenHeight = 2400,
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
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = stage1Nodes,
            detectedRoiVoids = emptyList(),
            finalNodes = nodes,
            markIdToPixelMap = nodes.associate { it.shortMarkId to it.finalTapPx },
            compressedPromptText = "",
            deltaPromptText = "",
            rawCharCount = 100,
            compressedCharCount = 50,
            compressionRatioPercent = 50,
            pipelineLatencyMs = 4L,
            screenPosture = screenPosture
        )
    }

    @Test
    fun `test 1 - Routine screen with high confidence and margin uses 0ms Fast-Path without CoT MCTS or PDDL`() {
        val searchBox = makeNode("yt_search_inp", 1, "INP", "搜尋 YouTube", x = 480, y = 180)
        val homeTab = makeNode("yt_tab_home", 2, "BTN", "首頁", x = 180, y = 2240)
        val subsTab = makeNode("yt_tab_subs", 3, "BTN", "訂閱內容", x = 540, y = 2240)
        val report = makeReport(listOf(searchBox, homeTab, subsTab))

        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "搜尋 周杰倫",
            report = report,
            context = null,
            currentForegroundPackage = "com.google.android.youtube",
            alreadyLaunchedApp = true,
            lockToCurrentApp = true
        )

        assertEquals(AiActionType.INPUT_TEXT, decision.actionType)
        assertEquals("周杰倫", decision.inputTextValue)
        assertTrue("常規高把握畫面必須觸發 0ms Fast-Path", decision.cooperativeLog?.fastPathTriggered == true)
        assertEquals(AdaptiveComputeGate.ComputeTier.TIER_0_FAST_PATH, AdaptiveComputeGate.currentTier)
        assertEquals(AdaptiveComputeGate.EscalationReason.NONE, AdaptiveComputeGate.lastEscalationReason)

        // 嚴格驗證：0ms 極速通道階段嚴禁啟動 CoT 語言推理、MCTS 蒙地卡羅樹搜尋或 PDDL/SAT-SMT 形式化規劃求解器
        assertEquals("Fast-Path 嚴禁喚醒三腦深層管線", 0, TriBrainCoordinatorEngine.triBrainEvaluationCount)
        assertEquals("Fast-Path 嚴禁啟動 CoT 推理", 0, TriBrainCoordinatorEngine.cotInvocationCount)
        assertEquals("Fast-Path 嚴禁啟動 MCTS 樹搜尋", 0, TriBrainCoordinatorEngine.mctsInvocationCount)
        assertEquals("Fast-Path 嚴禁啟動 PDDL 規劃求解器", 0, TriBrainCoordinatorEngine.pddlInvocationCount)
        assertEquals("Fast-Path 嚴禁啟動 SAT/SMT 約束求解器", 0, TriBrainCoordinatorEngine.satSmtInvocationCount)
    }

    @Test
    fun `test 2A - On-Demand Escalation when screen fingerprint does not change for 2 consecutive steps`() {
        val targetBtn = makeNode("btn_confirm", 1, "BTN", "確認結帳", x = 540, y = 820)
        val cancelBtn = makeNode("btn_cancel", 2, "BTN", "返回首頁", x = 540, y = 1120)
        val report = makeReport(listOf(targetBtn, cancelBtn))

        // 第 1 步：首次見到該畫面指紋，信心與領先幅度皆達標 -> 走 0ms TIER_0_FAST_PATH
        val step1Decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "點擊 確認結帳",
            report = report,
            context = null,
            currentForegroundPackage = "com.example.shop",
            alreadyLaunchedApp = true,
            lockToCurrentApp = true
        )
        assertTrue(step1Decision.cooperativeLog?.fastPathTriggered == true)
        assertEquals(AdaptiveComputeGate.ComputeTier.TIER_0_FAST_PATH, AdaptiveComputeGate.currentTier)
        assertEquals(0, TriBrainCoordinatorEngine.triBrainEvaluationCount)

        // 第 2 步：閉環驗證發現畫面結構指紋連續第 2 次未發生有效改變（疑似點擊無效或卡死）
        // 自動解鎖並喚醒高階協調引擎 (CoT + MCTS + PDDL/SAT-SMT)
        val step2Decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "點擊 確認結帳",
            report = report,
            context = null,
            currentForegroundPackage = "com.example.shop",
            alreadyLaunchedApp = true,
            lockToCurrentApp = true,
            consecutiveStuckCount = 2
        )

        assertFalse("連續兩次畫面指紋未改變時必須禁止 Fast-Path 並升維深層推理", step2Decision.cooperativeLog?.fastPathTriggered == true)
        assertEquals(AdaptiveComputeGate.ComputeTier.TIER_1_ESCALATED_DEEP, AdaptiveComputeGate.currentTier)
        assertEquals(AdaptiveComputeGate.EscalationReason.FINGERPRINT_STUCK_2X, AdaptiveComputeGate.lastEscalationReason)
        assertEquals("必須按需喚醒三腦深層協調管線", 1, TriBrainCoordinatorEngine.triBrainEvaluationCount)
        assertEquals(1, TriBrainCoordinatorEngine.cotInvocationCount)
        assertEquals(1, TriBrainCoordinatorEngine.mctsInvocationCount)
        assertEquals(1, TriBrainCoordinatorEngine.pddlInvocationCount)
    }

    @Test
    fun `test 2B - On-Demand Escalation when candidate confidence is low or top-2 margin is ambiguous`() {
        // 畫面上有兩個語義高度相近的按鈕，導致前兩名候選分數接近 (Margin < 0.15f)
        val btn1 = makeNode("btn_route_1", 1, "BTN", "查詢路線方案一", x = 540, y = 500)
        val btn2 = makeNode("btn_route_2", 2, "BTN", "查詢路線方案二", x = 540, y = 720)
        val report = makeReport(listOf(btn1, btn2))

        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "點擊 查詢路線",
            report = report,
            context = null,
            currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true,
            lockToCurrentApp = true
        )

        assertFalse("前兩名候選分數過於接近時必須攔截 Fast-Path", decision.cooperativeLog?.fastPathTriggered == true)
        assertEquals(AdaptiveComputeGate.ComputeTier.TIER_1_ESCALATED_DEEP, AdaptiveComputeGate.currentTier)
        assertEquals(
            AdaptiveComputeGate.EscalationReason.LOW_CONFIDENCE_OR_AMBIGUOUS_MARGIN,
            AdaptiveComputeGate.lastEscalationReason
        )
        assertEquals("語義模糊時必須喚醒 CoT + MCTS + PDDL 進行深層裁決", 1, TriBrainCoordinatorEngine.triBrainEvaluationCount)
    }

    @Test
    fun `test 2C - On-Demand Escalation on unknown modal occlusion or cross-app complex branch transition`() {
        // 情境 1：未知彈窗遮擋狀態（存在 Modal Scrim 遮罩，且非標準關閉字眼）
        val rawScrim = RawVisionNode(
            id = "modal_scrim_1",
            windowId = 2,
            windowLayer = 2,
            zOrderRank = 0,
            className = "android.view.View",
            bounds = makeRect(0, 0, 1080, 2400),
            text = "",
            isClickable = false,
            isModalScrim = true
        )
        val stage1Scrim = Stage1OccludedNode(
            raw = rawScrim,
            visibleRatio = 1.0f,
            isCulled = false,
            wasPartiallyOccluded = false,
            safeTapPointPx = makePoint(540, 1200),
            visibleBoundingBox = makeRect(0, 0, 1080, 2400)
        )
        val modalActionBtn = makeNode("btn_modal_opt", 1, "BTN", "繼續使用新版介面", x = 540, y = 1320)
        val bgBtn = makeNode("btn_bg", 2, "BTN", "首頁", x = 180, y = 2240)
        val modalReport = makeReport(
            nodes = listOf(modalActionBtn, bgBtn),
            stage1Nodes = listOf(stage1Scrim)
        )

        val modalDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "點擊 繼續使用新版介面",
            report = modalReport,
            context = null,
            currentForegroundPackage = "com.example.app",
            alreadyLaunchedApp = true,
            lockToCurrentApp = true
        )
        assertFalse(modalDecision.cooperativeLog?.fastPathTriggered == true)
        assertEquals(AdaptiveComputeGate.ComputeTier.TIER_1_ESCALATED_DEEP, AdaptiveComputeGate.currentTier)
        assertEquals(AdaptiveComputeGate.EscalationReason.UNKNOWN_MODAL_OCCLUSION, AdaptiveComputeGate.lastEscalationReason)

        // 情境 2：跨 App 複雜邏輯分支切換點
        AdaptiveComputeGate.reset()
        TriBrainCoordinatorEngine.resetTelemetryCounters()

        val crossAppSubGoal = CooperativeSubGoal(
            stepIndex = 3,
            totalSteps = 4,
            intentCategory = "TAP",
            targetAppHint = "Google 地圖",
            targetSemanticPhrase = "規劃路線",
            simplifiedMotorGoal = "點擊 規劃路線",
            appPhaseIndex = 2,
            totalAppBlocks = 2
        )
        val mapRouteBtn = makeNode("maps_dir", 1, "BTN", "規劃路線", x = 320, y = 980, pkg = "com.google.android.apps.maps")
        val mapStartBtn = makeNode("maps_nav", 2, "BTN", "開始", x = 680, y = 980, pkg = "com.google.android.apps.maps")
        val mapsReport = makeReport(listOf(mapRouteBtn, mapStartBtn))
        val fastGatePass = DeterminismHardeningEngine.FastPathGateEvaluation(
            allowed = true,
            top1Score = 0.92f,
            top2Score = 0.60f,
            scoreMargin = 0.32f,
            confidencePassed = true,
            marginPassed = true,
            attributeCompatible = true,
            blockReason = "PASSED"
        )

        val gateDec = AdaptiveComputeGate.evaluateGate(
            report = mapsReport,
            screenHash = "maps_branch_fp",
            activeSubGoal = crossAppSubGoal,
            inAppStage = InAppOperationStage.IN_APP_NAVIGATION,
            fastPathGate = fastGatePass,
            foregroundPackage = "com.google.android.apps.maps"
        )
        assertEquals(AdaptiveComputeGate.ComputeTier.TIER_1_ESCALATED_DEEP, gateDec.tier)
        assertEquals(AdaptiveComputeGate.EscalationReason.CROSS_APP_COMPLEX_BRANCH, gateDec.escalationReason)
    }

    @Test
    fun `test 3 - Compute reclamation and immediate auto-deescalation to Fast-Path after screen transition verified`() {
        val multiStepGoal = "點擊 開啟設定項 A 然後 搜尋 藍牙"
        val stuckBtn1 = makeNode("btn_ambig_1", 1, "BTN", "開啟設定項 A", x = 540, y = 500, pkg = "com.android.settings")
        val stuckBtn2 = makeNode("btn_ambig_2", 2, "BTN", "開啟設定項 B", x = 540, y = 720, pkg = "com.android.settings")
        val stuckReport = makeReport(listOf(stuckBtn1, stuckBtn2))

        // Step 1: 當前處於卡死狀態 (consecutiveStuckCount = 2) -> 按需升維至 TIER_1_ESCALATED_DEEP
        val escalatedDec = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = multiStepGoal,
            report = stuckReport,
            context = null,
            currentForegroundPackage = "com.android.settings",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 0,
            lockToCurrentApp = true,
            consecutiveStuckCount = 2
        )
        assertFalse(escalatedDec.cooperativeLog?.fastPathTriggered == true)
        assertEquals(AdaptiveComputeGate.ComputeTier.TIER_1_ESCALATED_DEEP, AdaptiveComputeGate.currentTier)
        assertEquals(1, TriBrainCoordinatorEngine.triBrainEvaluationCount)

        // Step 2: 深層推理突破卡點，ClosedLoopVerifier 驗證動作前後畫面結構指紋已發生有效跳轉！
        val nextScreenSearchInp = makeNode("settings_search_inp", 1, "INP", "搜尋設定", x = 480, y = 180, pkg = "com.android.settings")
        val nextScreenBackBtn = makeNode("settings_back", 2, "BTN", "返回", x = 120, y = 180, pkg = "com.android.settings")
        val nextScreenReport = makeReport(listOf(nextScreenSearchInp, nextScreenBackBtn))

        val beforeFp = ClosedLoopVerifier.computeScreenFingerprint("com.android.settings", stuckReport)
        val afterFp = ClosedLoopVerifier.computeScreenFingerprint("com.android.settings", nextScreenReport)
        val verifiedChanged = AdaptiveComputeGate.verifyTransitionAndReclaimCompute(beforeFp, afterFp)

        assertTrue("突破卡點後畫面結構指紋必須判定為有效跳轉", verifiedChanged)
        assertEquals(
            "通過畫面跳轉驗證後，必須立即自動降級回 TIER_0_FAST_PATH",
            AdaptiveComputeGate.ComputeTier.TIER_0_FAST_PATH,
            AdaptiveComputeGate.currentTier
        )
        assertEquals(AdaptiveComputeGate.EscalationReason.NONE, AdaptiveComputeGate.lastEscalationReason)
        assertEquals(1, AdaptiveComputeGate.autoDeescalationCount)

        // Step 3: 緊接著下一步在新畫面上執行下一子目標常規操作，驗證 100% 走 0ms 快速通道，不再喚醒 CoT/MCTS/PDDL！
        val countBeforeNextStep = TriBrainCoordinatorEngine.triBrainEvaluationCount
        val nextStepDec = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = multiStepGoal,
            report = nextScreenReport,
            context = null,
            currentForegroundPackage = "com.android.settings",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 1,
            lockToCurrentApp = true,
            consecutiveStuckCount = 0
        )

        assertEquals(AiActionType.INPUT_TEXT, nextStepDec.actionType)
        assertTrue("降級後的下一步必須立即恢復 0ms Fast-Path", nextStepDec.cooperativeLog?.fastPathTriggered == true)
        assertEquals(AdaptiveComputeGate.ComputeTier.TIER_0_FAST_PATH, AdaptiveComputeGate.currentTier)
        assertEquals(
            "降級回 Fast-Path 後嚴禁再次喚醒高耗能三腦協調器",
            countBeforeNextStep,
            TriBrainCoordinatorEngine.triBrainEvaluationCount
        )
    }
}
