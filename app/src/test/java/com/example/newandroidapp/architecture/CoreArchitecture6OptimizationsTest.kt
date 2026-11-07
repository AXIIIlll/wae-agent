package com.example.newandroidapp.architecture

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.ai.AdaptiveComputeGate
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.ai.AppIdentityConfig
import com.example.newandroidapp.ai.CooperativeSubGoal
import com.example.newandroidapp.ai.DeterminismHardeningEngine
import com.example.newandroidapp.ai.DualLocalAiCoordinator
import com.example.newandroidapp.ai.InstructionCompilerPipeline
import com.example.newandroidapp.ai.LocalAiBackendMode
import com.example.newandroidapp.ai.NeedleNativeBridge
import com.example.newandroidapp.ai.TriBrainCoordinatorEngine
import com.example.newandroidapp.stability.ActionExecutionRouter
import com.example.newandroidapp.stability.ClosedLoopVerifier
import com.example.newandroidapp.stability.EpisodicAppMemory
import com.example.newandroidapp.stability.ExecutionChannelType
import com.example.newandroidapp.stability.FallbackTriggerReason
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppOperationStage
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.stability.SmartDailyAssistantHelper
import com.example.newandroidapp.stability.SmartLoadingDetector
import com.example.newandroidapp.stability.VoiceCommandAndTtsManager
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage1OccludedNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import com.example.newandroidapp.vision.dataset.Phase3ScreenPostureClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 6 項核心架構最佳化與細節打磨驗收測試套件 (CoreArchitecture6OptimizationsTest)
 *
 * 涵蓋：
 * 1. 三腦與規劃器的「動態算力路由與剪枝（Early Exit & Pruning：Level 0 / Level 1 / Level 2 + Phase 1~3 MCTS Prior Policy）」
 * 2. 視覺管線「全鏈路零拷貝（Zero-Copy）」與硬體加速探測（Vulkan / OpenCL / NNAPI NPU Offload + 1024D 向量）
 * 3. 情節記憶的「動態抗改版自癒（Self-Healing Memory：1024D 語義嵌入 + 幾何圖示分類標籤 + dHash64 指紋驗證）」
 * 4. 跨 App 連續任務的「嚴格驗收閘門（SmartLoadingDetector + 填入前禁按返回鍵）」與「純擷取閉環（EXTRACT_TO_MEMORY）」
 * 5. 執行層抽象化與「高權限備援通道（Shizuku / Root Fallback）」
 * 6. 系統互動與產品外殼細節打磨（Audio Ducking 音訊焦點壓制 + 系統邊緣懸浮雜訊過濾 + com.localai.visionassistant 套件名稱規範化）
 */
class CoreArchitecture6OptimizationsTest {

    @Before
    fun setUp() {
        AdaptiveComputeGate.reset()
        InAppWorkingMemory.resetForNewGoal("test_6_optimizations")
        EpisodicAppMemory.clearMemory(null)
        ActionExecutionRouter.resetToDefault()
    }

    private fun makeRect(l: Int, t: Int, r: Int, b: Int): Rect {
        return Rect(l, t, r, b).apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    private fun makePoint(xVal: Int, yVal: Int): Point {
        return Point(xVal, yVal).apply {
            x = xVal
            y = yVal
        }
    }

    private fun makeNode(
        id: String,
        markId: Int,
        tag: String,
        label: String,
        gx: Int,
        gy: Int,
        pxX: Int = gx,
        pxY: Int = gy,
        role: String = "NAV_HOME_TAB",
        nanoDetCategory: String = "",
        packageName: String = "com.google.android.youtube",
        bounds: Rect = makeRect(pxX - 40, pxY - 25, pxX + 40, pxY + 25)
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = "android.view.View",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = bounds,
            convergedBounds = bounds,
            finalTapPx = makePoint(pxX, pxY),
            gridTapPoint = makePoint(gx, gy),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE,
            predictedFunctionRole = role,
            functionRoleConfidence = 0.90f,
            nanoDetCategory = nanoDetCategory,
            packageName = packageName
        )
    }

    private fun makeReport(
        nodes: List<Stage2ConvergedNode>,
        rawNodes: List<RawVisionNode> = emptyList(),
        screenPosture: String = "HOME_FEED_HUB"
    ): VisionPipelineReport {
        val stage1 = rawNodes.map {
            Stage1OccludedNode(
                raw = it,
                visibleRatio = 1.0f,
                isCulled = false,
                wasPartiallyOccluded = false,
                safeTapPointPx = makePoint((it.bounds.left + it.bounds.right) / 2, (it.bounds.top + it.bounds.bottom) / 2),
                visibleBoundingBox = it.bounds
            )
        }
        return VisionPipelineReport(
            timestampMs = 1000L,
            screenWidth = 1080,
            screenHeight = 2400,
            gridCols = 40,
            gridRows = 80,
            occupancyGridSnapshot = ByteArray(0),
            totalRawNodes = rawNodes.size.coerceAtLeast(nodes.size),
            culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0,
            transparentOverlayBypassedCount = 0,
            hotspotConvergedCount = nodes.size,
            spatialBoundIconCount = 0,
            geometricIconClassifiedCount = 0,
            roiRecoveredCount = 0,
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = stage1,
            detectedRoiVoids = emptyList(),
            finalNodes = nodes,
            markIdToPixelMap = nodes.associate { it.shortMarkId to it.finalTapPx },
            compressedPromptText = "",
            deltaPromptText = "",
            rawCharCount = 100,
            compressedCharCount = 50,
            compressionRatioPercent = 50,
            pipelineLatencyMs = 4L,
            screenPosture = screenPosture,
            screenPostureConfidence = 0.92f
        )
    }

    // =========================================================================
    // 1. 三腦與規劃器的「動態算力路由與剪枝（Early Exit & Pruning）」
    // =========================================================================
    @Test
    fun testOptimization1_ThreeLevelSteppedComputeRoutingAndMctsPriorPruning() {
        val searchNode = makeNode("id_search", 1, "BTN", "搜尋", 880, 80, role = "SEARCH_ENTRY")
        val homeNode = makeNode("id_home", 2, "BTN", "首頁", 200, 920, role = "NAV_HOME_TAB")
        val report = makeReport(listOf(searchNode, homeNode))
        val subGoal = CooperativeSubGoal(
            stepIndex = 1,
            totalSteps = 1,
            intentCategory = "TAP_TARGET",
            targetAppHint = "YouTube",
            targetSemanticPhrase = "搜尋"
        )

        // (A) Level 0：高信心且領先幅度達標 -> 0ms 快取與原生快篩
        val highConfGate = DeterminismHardeningEngine.FastPathGateEvaluation(
            allowed = true,
            top1Score = 0.93f,
            top2Score = 0.40f,
            scoreMargin = 0.53f,
            confidencePassed = true,
            marginPassed = true,
            attributeCompatible = true,
            blockReason = "PASSED"
        )
        val level0Dec = AdaptiveComputeGate.evaluateGate(
            report = report,
            screenHash = "fp_level0",
            activeSubGoal = subGoal,
            inAppStage = InAppOperationStage.IN_APP_NAVIGATION,
            fastPathGate = highConfGate,
            foregroundPackage = "com.google.android.youtube",
            consecutiveStuckCount = 0,
            consecutiveScrollNoChangeCount = 0,
            hasEpisodicMemoryAnchor = true
        )
        assertEquals(AdaptiveComputeGate.ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE, level0Dec.level)
        assertTrue(level0Dec.canTakeFastPath)
        assertFalse(level0Dec.isLevel2HeavyPlanner)

        // (B) Level 1：常規畫面但前兩名候選分數接近（語義微模糊，無卡死/無彈窗/無跨App分支）-> 僅喚醒本地 1024 維度向量餘弦相似度精排
        val closeMarginGate = DeterminismHardeningEngine.FastPathGateEvaluation(
            allowed = false,
            top1Score = 0.78f,
            top2Score = 0.72f,
            scoreMargin = 0.06f,
            confidencePassed = false,
            marginPassed = false,
            attributeCompatible = true,
            blockReason = "MARGIN_TOO_SMALL"
        )
        val level1Dec = AdaptiveComputeGate.evaluateGate(
            report = report,
            screenHash = "fp_level1",
            activeSubGoal = subGoal,
            inAppStage = InAppOperationStage.IN_APP_NAVIGATION,
            fastPathGate = closeMarginGate,
            foregroundPackage = "com.google.android.youtube",
            consecutiveStuckCount = 0,
            consecutiveScrollNoChangeCount = 0,
            hasEpisodicMemoryAnchor = false
        )
        assertEquals(AdaptiveComputeGate.ComputeLevel.LEVEL_1_LIGHT_1024D_VECTOR_RERANK, level1Dec.level)
        assertTrue(level1Dec.isLevel1LightVectorOnly)
        assertFalse(level1Dec.isLevel2HeavyPlanner)

        val level1Rerank = TriBrainCoordinatorEngine.evaluateLevel1LightVector1024Rerank(
            candidates = listOf(searchNode, homeNode),
            report = report,
            activeSubGoal = subGoal,
            isolatedMotorGoal = "點擊 搜尋",
            inAppStage = InAppOperationStage.IN_APP_NAVIGATION,
            plannerRanked = listOf(searchNode to 0.78f, homeNode to 0.72f),
            screenHash = "fp_level1"
        )
        assertEquals("id_search", level1Rerank.rankedCandidates.first().first.nodeId)
        assertTrue(level1Rerank.diagnosticText.contains("Level1-1024D向量精排"))

        // (C) Level 2：畫面連續未改變（卡死）-> 按需喚醒 CoT + MCTS + PDDL 規劃器
        AdaptiveComputeGate.evaluateGate(
            report = report,
            screenHash = "fp_stuck",
            activeSubGoal = subGoal,
            inAppStage = InAppOperationStage.IN_APP_NAVIGATION,
            fastPathGate = highConfGate,
            foregroundPackage = "com.google.android.youtube",
            consecutiveStuckCount = 0,
            consecutiveScrollNoChangeCount = 0,
            hasEpisodicMemoryAnchor = false
        )
        val level2Dec = AdaptiveComputeGate.evaluateGate(
            report = report,
            screenHash = "fp_stuck",
            activeSubGoal = subGoal,
            inAppStage = InAppOperationStage.IN_APP_NAVIGATION,
            fastPathGate = highConfGate,
            foregroundPackage = "com.google.android.youtube",
            consecutiveStuckCount = 1,
            consecutiveScrollNoChangeCount = 0,
            hasEpisodicMemoryAnchor = false
        )
        assertEquals(AdaptiveComputeGate.ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER, level2Dec.level)
        assertTrue(level2Dec.isLevel2HeavyPlanner)

        // 驗證 Phase 1~3 UI 狀態轉移機率矩陣作為 MCTS Prior Policy 並剪枝低機率分支
        val pruningResult = Phase3ScreenPostureClassifier.computeMctsPriorPolicyAndPruneMask(
            currentPostureCode = "MODAL_POPUP_PERMISSION_AD",
            intentCategory = "TAP_TARGET",
            targetSemanticPhrase = "關閉廣告",
            candidateRoles = listOf("CLOSE_DISMISS", "CONTENT_CARD_ITEM"),
            candidateBaseScores = floatArrayOf(0.88f, 0.12f),
            baseLegalMask = byteArrayOf(1, 1)
        )
        assertEquals(2, pruningResult.priorPolicy.size)
        assertTrue("彈窗阻擋姿勢下關閉按鈕的先驗機率應遠高於背景卡片", pruningResult.priorPolicy[0] > pruningResult.priorPolicy[1])
        assertEquals(1.toByte(), pruningResult.prunedLegalMask[0])
    }

    // =========================================================================
    // 2. 視覺管線「全鏈路零拷貝（Zero-Copy）」與硬體加速 + 1024D 嵌入向量
    // =========================================================================
    @Test
    fun testOptimization2_ZeroCopyVisionAnd1024DEmbeddingAndHardwareAcceleration() {
        val vecA = NeedleNativeBridge.embedText1024D("搜尋 搜尋按鈕 SEARCH_ENTRY")
        val vecB = NeedleNativeBridge.embedText1024D("搜尋 查找 SEARCH_ENTRY")
        val vecC = NeedleNativeBridge.embedText1024D("購物車 結帳付款 ADD_TO_CART_BUY")

        assertEquals(1024, vecA.size)
        assertEquals(1024, vecB.size)
        assertEquals(1024, vecC.size)

        var dotAB = 0f
        var dotAC = 0f
        for (i in 0 until 1024) {
            dotAB += vecA[i] * vecB[i]
            dotAC += vecA[i] * vecC[i]
        }
        assertTrue("同語義 1024 維度向量餘弦相似度應高於異語義向量", dotAB > dotAC)

        val hwReport = NeedleNativeBridge.getHardwareAccelerationReport()
        assertTrue(hwReport.isNotBlank())
    }

    // =========================================================================
    // 3. 情節記憶的「動態抗改版自癒（Self-Healing Memory）」
    // =========================================================================
    @Test
    fun testOptimization3_SelfHealingMemoryWith1024DVectorGeometricIconAndDHash64() {
        val pkg = "com.google.android.youtube"
        val oldSearchBtn = makeNode(
            id = "com.google.android.youtube:id/menu_search_v1",
            markId = 1,
            tag = "ICO",
            label = "搜尋影片",
            gx = 910,
            gy = 70,
            pxX = 980,
            pxY = 160,
            role = "SEARCH_ENTRY",
            nanoDetCategory = "UI_SEARCH_BAR"
        )
        EpisodicAppMemory.recordVerifiedSearchEntry(
            context = null,
            packageName = pkg,
            appLabel = "YouTube",
            node = oldSearchBtn,
            screenWidth = 1080,
            screenHeight = 2400
        )

        val storedAnchor = EpisodicAppMemory.getProfile(null, pkg)?.verifiedSearchEntries?.firstOrNull()
        assertNotNull(storedAnchor)
        assertEquals(1024, storedAnchor!!.semanticEmbedding1024.size)
        assertEquals("ICON_SEARCH_MAGNIFIER", storedAnchor.geometricIconTag)
        assertFalse(storedAnchor.isExpired)

        // 模擬點擊後閉環驗證發現 dHash64 與畫面結構指紋完全未改變（App 改版導致舊位置點擊無效）
        val reportBefore = makeReport(listOf(oldSearchBtn))
        val fpBefore = ClosedLoopVerifier.computeScreenFingerprint(pkg, reportBefore)
        val fpAfterUnchanged = ClosedLoopVerifier.computeScreenFingerprint(pkg, reportBefore)
        assertFalse(ClosedLoopVerifier.hasScreenChangedMeaningfully(fpBefore, fpAfterUnchanged))

        val degradedCount = EpisodicAppMemory.onAnchorClickVerificationFailed(
            context = null,
            packageName = pkg,
            clickedNode = oldSearchBtn,
            stage = InAppOperationStage.STAGE_A_EXPAND_SEARCH,
            intentKeyword = "搜尋",
            screenFp = fpBefore.shortCode()
        )
        assertTrue(degradedCount >= 1)
        val expiredAnchor = EpisodicAppMemory.getProfile(null, pkg)?.verifiedSearchEntries?.firstOrNull()
        assertNotNull(expiredAnchor)
        assertTrue("改版驗證失敗後應立即將舊記憶標記為 isExpired = true", expiredAnchor!!.isExpired)
        assertEquals(0.0f, expiredAnchor.confidenceWeight, 0.001f)

        // 模擬 App 改版後，搜尋按鈕移至底部導覽列 (500, 910)，且名稱改為「探索與搜尋」
        val movedNewSearchAvatar = makeNode(
            id = "com.google.android.youtube:id/bottom_search_v2",
            markId = 2,
            tag = "ICO",
            label = "探索與搜尋",
            gx = 500,
            gy = 910,
            pxX = 540,
            pxY = 2180,
            role = "SEARCH_ENTRY",
            nanoDetCategory = "UI_SEARCH_BAR"
        )
        val unrelatedProfileBtn = makeNode(
            id = "com.google.android.youtube:id/tab_profile",
            markId = 3,
            tag = "BTN",
            label = "個人中心",
            gx = 880,
            gy = 910,
            pxX = 950,
            pxY = 2180,
            role = "NAV_PROFILE_ACCOUNT_TAB"
        )
        val redesignedReport = makeReport(listOf(unrelatedProfileBtn, movedNewSearchAvatar))

        val avatarMatch = EpisodicAppMemory.findBestSelfHealingAvatar(
            context = null,
            packageName = pkg,
            candidates = redesignedReport.finalNodes,
            stage = InAppOperationStage.STAGE_A_EXPAND_SEARCH,
            query = "搜尋",
            subGoalKeyword = "搜尋"
        )
        assertNotNull("應透過 1024D 餘弦相似度與幾何圖示特徵匹配出改版後的新替身節點", avatarMatch)
        assertEquals("com.google.android.youtube:id/bottom_search_v2", avatarMatch!!.first.nodeId)

        // 模擬點擊新替身節點並通過畫面跳轉驗證後，背景靜默覆寫舊記憶並恢復信心值
        EpisodicAppMemory.recordVerifiedSearchEntry(
            context = null,
            packageName = pkg,
            appLabel = "YouTube",
            node = movedNewSearchAvatar,
            screenWidth = 1080,
            screenHeight = 2400
        )
        val healedAnchor = EpisodicAppMemory.getProfile(null, pkg)?.verifiedSearchEntries?.firstOrNull()
        assertNotNull(healedAnchor)
        assertFalse(healedAnchor!!.isExpired)
        assertEquals(1.0f, healedAnchor.confidenceWeight, 0.001f)
        assertEquals(500, healedAnchor.gridX)
        assertEquals(910, healedAnchor.gridY)
    }

    // =========================================================================
    // 4. 跨 App 連續任務的「嚴格驗收閘門」與「純擷取閉環」
    // =========================================================================
    @Test
    fun testOptimization4_StrictSubTaskGateLoadingGuardPreFillBackGuardAndInternalExtract() {
        val searchSubGoal = CooperativeSubGoal(
            stepIndex = 1,
            totalSteps = 2,
            intentCategory = "INPUT_SEARCH",
            targetAppHint = "Chrome",
            targetSemanticPhrase = "搜尋 台北天氣",
            inputPayload = "台北天氣",
            appPhaseIndex = 1,
            totalAppBlocks = 2
        )

        // (A) 在文字填入並送出前，嚴禁誤觸返回鍵 (shouldTriggerAutoBacktrack 必須回傳 null)
        val dummyPrevNode = makeNode("id_prev", 1, "BTN", "某按鈕", 300, 300)
        InAppWorkingMemory.pushBreadcrumb("fp_prev", dummyPrevNode)
        val sparseReportBeforeFill = makeReport(
            listOf(makeNode("id_inp", 1, "INP", "搜尋或輸入網址", 500, 120))
        )
        val backtrackBeforeFill = InAppOperationPlanner.shouldTriggerAutoBacktrack(
            report = sparseReportBeforeFill,
            userGoal = "搜尋 台北天氣",
            activeSubGoal = searchSubGoal,
            bestScore = 0.10f,
            consecutiveScrollNoChangeCount = 2
        )
        assertNull("在文字填入並送出前，絕對禁止誤觸返回鍵", backtrackBeforeFill)

        // (B) 送出文字後，若畫面仍在載入動畫中 (ProgressBar / 載入中)，嚴格驗收閘門禁止搶跑切換至下一個 App
        InAppWorkingMemory.recordTextTypedAndSubmitted("台北天氣", "搜尋或輸入網址")
        val loadingRawNode = RawVisionNode(
            id = "id_loading",
            windowId = 1,
            windowLayer = 1,
            zOrderRank = 0,
            className = "android.widget.ProgressBar",
            packageName = "com.android.chrome",
            bounds = makeRect(400, 800, 680, 920),
            text = "正在載入搜尋結果...",
            contentDescription = "",
            viewIdResourceName = "com.android.chrome:id/progress"
        )
        val loadingSpinnerStage2 = makeNode(
            id = "com.android.chrome:id/progress_spinner",
            markId = 1,
            tag = "TXT",
            label = "載入中",
            gx = 500,
            gy = 400,
            packageName = "com.android.chrome"
        )
        val resultNodeWhileLoading = makeNode(
            id = "id_res1",
            markId = 2,
            tag = "TXT",
            label = "台北天氣 今日 26度 晴時多雲",
            gx = 500,
            gy = 500,
            packageName = "com.android.chrome"
        )
        val loadingReport = makeReport(
            nodes = listOf(loadingSpinnerStage2, resultNodeWhileLoading),
            rawNodes = listOf(loadingRawNode)
        )
        assertTrue(SmartLoadingDetector.isScreenCurrentlyLoading(loadingReport))
        val gateWhileLoading = InstructionCompilerPipeline.evaluateExitCondition(
            subGoal = searchSubGoal,
            report = loadingReport,
            currentForegroundPackage = "com.android.chrome",
            alreadyLaunchedApp = true
        )
        assertFalse("當畫面仍在載入動畫中，嚴禁搶跑解鎖下一跨 App 子任務", gateWhileLoading.satisfied)

        // (C) 當載入動畫結束且非輸入框結果區實際出現目標關鍵字時，驗收閘門解鎖通過
        val loadedReport = makeReport(nodes = listOf(resultNodeWhileLoading))
        val gateAfterLoaded = InstructionCompilerPipeline.evaluateExitCondition(
            subGoal = searchSubGoal,
            report = loadedReport,
            currentForegroundPackage = "com.android.chrome",
            alreadyLaunchedApp = true
        )
        assertTrue("載入動畫結束且非輸入框結果區出現目標關鍵字後，應解鎖下一子任務", gateAfterLoaded.satisfied)

        // (D) 純擷取內部動作 (Internal Extract Action)：在結果頁直接擷取，不點擊搜尋框或返回
        val extractCmd = "去 Chrome 搜尋 台北天氣 然後把結果記下來"
        InAppWorkingMemory.ensureGoalContext(extractCmd)
        InAppWorkingMemory.recordTextTypedAndSubmitted("台北天氣", "搜尋或輸入網址")
        val extractDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = extractCmd,
            report = loadedReport,
            context = null,
            currentForegroundPackage = "com.android.chrome",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 1
        )
        assertEquals(AiActionType.ANSWER_QUESTION, extractDecision.actionType)
        assertNull("在結果頁執行純擷取內部動作時，嚴禁點擊任何 UI 元件", extractDecision.targetNode)
        assertTrue(extractDecision.compactOpcode.startsWith("EXTRACT_CROSS_APP"))
        assertTrue(extractDecision.inputTextValue.orEmpty().contains("台北天氣"))
    }

    // =========================================================================
    // 5. 執行層抽象化與「高權限備援通道（Shizuku / Root Fallback）」
    // =========================================================================
    @Test
    fun testOptimization5_PrivilegedExecutionChannelFallbackUnderFlagSecureAndBlockedTree() {
        assertEquals(ExecutionChannelType.ACCESSIBILITY_DEFAULT, ActionExecutionRouter.activeChannelType)

        // 模擬偵測到 FLAG_SECURE 防截圖保護或無障礙手勢失敗時，自動切換至高權限備援通道
        ActionExecutionRouter.configureMockPrivilegedChannelsForTest(shizukuAvailable = true, rootAvailable = false)
        val chosenChannel = ActionExecutionRouter.onAnomalyDetectedSwitchToFallback(
            reason = FallbackTriggerReason.FLAG_SECURE_PROTECTED,
            context = null
        )
        assertNotNull(chosenChannel)
        assertEquals(ExecutionChannelType.SHIZUKU_ADB_FALLBACK, ActionExecutionRouter.activeChannelType)
        assertEquals(FallbackTriggerReason.FLAG_SECURE_PROTECTED, ActionExecutionRouter.lastFallbackReason)
        assertTrue(ActionExecutionRouter.fallbackSwitchCount >= 1)

        // 測試 uiautomator dump XML 解析器能將備援通道擷取的 XML 轉為 RawVisionNode 清單
        val sampleUiAutomatorXml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
              <node index="0" text="確認轉帳" resource-id="com.bank.app:id/btn_confirm" class="android.widget.Button" package="com.bank.app" content-desc="確認轉帳" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[120,1600][960,1740]" />
            </hierarchy>
        """.trimIndent()
        val parsedNodes = ActionExecutionRouter.parseUiAutomatorDumpXml(sampleUiAutomatorXml, 1080, 2400)
        assertEquals(1, parsedNodes.size)
        assertEquals("確認轉帳", parsedNodes[0].text)
        assertEquals(120, parsedNodes[0].bounds.left)
        assertEquals(1600, parsedNodes[0].bounds.top)
        assertEquals(960, parsedNodes[0].bounds.right)
        assertEquals(1740, parsedNodes[0].bounds.bottom)
    }

    // =========================================================================
    // 6. 系統互動與產品外殼細節打磨（Audio Ducking + 系統懸浮雜訊過濾 + 套件名稱規範化）
    // =========================================================================
    @Test
    fun testOptimization6_AudioDuckingSystemEdgeNoiseFilterAndPackageIdentityNormalization() {
        // (A) 套件名稱規範化：同時識別正式產品名稱 com.localai.visionassistant 與相容包名
        assertEquals("com.localai.visionassistant", AppIdentityConfig.OFFICIAL_PRODUCT_PACKAGE_ID)
        assertTrue(AppIdentityConfig.isSelfAssistantPackage("com.localai.visionassistant"))
        assertTrue(AppIdentityConfig.isSelfAssistantPackage("com.example.newandroidapp"))
        assertFalse(AppIdentityConfig.isSelfAssistantPackage("com.google.android.youtube"))
        assertEquals("com.localai.visionassistant", AppIdentityConfig.normalizePackageIdentifier("com.example.newandroidapp"))

        // (B) 系統懸浮雜訊過濾：過濾系統側螢幕面板、懸浮球與頂部外來通知橫幅
        val samsungCocktailEdgePanel = makeNode(
            id = "com.samsung.android.app.cocktailbarservice:id/edge_handle",
            markId = 1,
            tag = "BTN",
            label = "側螢幕面板",
            gx = 992,
            gy = 500,
            pxX = 1072,
            pxY = 1200,
            packageName = "com.samsung.android.app.cocktailbarservice",
            bounds = makeRect(1050, 1050, 1080, 1350)
        )
        assertTrue(
            "應識別並過濾系統側螢幕面板覆蓋層",
            DeterminismHardeningEngine.isSystemEdgeOverlayOrFloatingBall(
                node = samsungCocktailEdgePanel,
                screenHeightPx = 2400,
                foregroundAppPackage = "com.google.android.youtube",
                expectedTargetPackage = "com.google.android.youtube"
            )
        )

        val validAppContentNode = makeNode(
            id = "com.google.android.youtube:id/video_title",
            markId = 2,
            tag = "TXT",
            label = "周杰倫 晴天 官方 MV",
            gx = 500,
            gy = 450,
            pxX = 540,
            pxY = 1080,
            packageName = "com.google.android.youtube"
        )
        val reportWithEdgeNoise = makeReport(listOf(samsungCocktailEdgePanel, validAppContentNode))
        val sanitized = DeterminismHardeningEngine.sanitizeVisionReport(
            report = reportWithEdgeNoise,
            expectedTargetPackage = "com.google.android.youtube"
        )
        assertEquals(1, sanitized.finalNodes.size)
        assertEquals("周杰倫 晴天 官方 MV", sanitized.finalNodes.first().label)

        val (_, spokenSummary) = SmartDailyAssistantHelper.buildSmartScreenSummary("YouTube", reportWithEdgeNoise)
        assertFalse("畫面摘要朗讀不得包含系統側螢幕面板雜訊", spokenSummary.contains("側螢幕面板"))
        assertTrue(spokenSummary.contains("周杰倫"))

        // (C) Audio Ducking 音訊焦點申請與釋放驗證
        assertTrue(VoiceCommandAndTtsManager.requestTransientDuckingAudioFocus(null))
        assertTrue(VoiceCommandAndTtsManager.isAudioFocusDuckingHeld)
        VoiceCommandAndTtsManager.abandonTransientDuckingAudioFocus(null)
        assertFalse(VoiceCommandAndTtsManager.isAudioFocusDuckingHeld)
    }
}
