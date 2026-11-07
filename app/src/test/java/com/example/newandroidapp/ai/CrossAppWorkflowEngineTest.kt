package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 100% 原創自研：跨 App 多階段流程編排與深度跨 App 指令理解引擎 (CrossAppWorkflowEngine) 單元與端到端測試
 */
class CrossAppWorkflowEngineTest {

    @Before
    fun setUp() {
        CrossAppWorkflowEngine.resetClipboard()
        InAppWorkingMemory.resetForNewGoal("")
    }

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
        gridY: Int = 400
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = if (tag == "INP") "android.widget.EditText" else "android.view.View",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
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
    fun `test explicit dual app sequential command decomposition`() {
        val cmd = "先打開設定開啟藍牙，然後打開 YouTube 搜尋周杰倫"
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(
            rawGoal = cmd,
            context = null,
            lockToCurrentApp = false
        )

        assertTrue("應拆解出至少 4 個跨 App 子目標", subGoals.size >= 4)
        // Phase 1: 設定
        val phase1Goals = subGoals.filter { it.appPhaseIndex == 1 }
        val phase2Goals = subGoals.filter { it.appPhaseIndex == 2 }
        assertTrue(phase1Goals.isNotEmpty())
        assertTrue(phase2Goals.isNotEmpty())
        assertEquals("設定", phase1Goals.first().targetAppHint)
        assertEquals("YouTube", phase2Goals.first().targetAppHint)
        assertTrue(phase2Goals.any { it.intentCategory == "INPUT_SEARCH" && it.inputPayload == "周杰倫" })
    }

    @Test
    fun `test cross app search and note taking decomposition`() {
        val cmd = "去 Chrome 搜尋今日美金匯率，然後打開備忘錄記下來"
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(
            rawGoal = cmd,
            context = null,
            lockToCurrentApp = false
        )

        // 確認包含：1. LAUNCH_APP(Chrome) -> 2. INPUT_SEARCH(今日美金匯率) -> 3. EXTRACT_SCREEN_INFO -> 4. LAUNCH_APP(備忘錄) -> 5. TAP_TARGET(新增) -> 6. INPUT_SEARCH({{CROSS_APP_CLIPBOARD}})
        assertTrue(subGoals.any { it.appPhaseIndex == 1 && it.intentCategory == "LAUNCH_APP" && it.targetAppHint == "Chrome" })
        assertTrue(subGoals.any { it.appPhaseIndex == 1 && it.intentCategory == "INPUT_SEARCH" && it.inputPayload == "今日美金匯率" })
        assertTrue(subGoals.any { it.appPhaseIndex == 1 && it.intentCategory == "EXTRACT_SCREEN_INFO" })
        assertTrue(subGoals.any { it.appPhaseIndex == 2 && it.intentCategory == "LAUNCH_APP" && it.targetAppHint == "備忘錄" })
        assertTrue(subGoals.any { it.appPhaseIndex == 2 && it.inputPayload == CrossAppWorkflowEngine.CLIPBOARD_PLACEHOLDER })
    }

    @Test
    fun `test cross app screen extraction and dynamic clipboard injection into second app`() {
        val cmd = "去 Chrome 搜尋今日美金匯率，然後打開備忘錄記下來"
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(cmd, null, false)

        val extractSubGoalIndex = subGoals.indexOfFirst { it.intentCategory == "EXTRACT_SCREEN_INFO" }
        assertTrue(extractSubGoalIndex >= 0)

        // 模擬在 Chrome (App A) 畫面上出現查詢結果「美金匯率 1 USD = 32.15 元」
        val chromeResultScreen = makeReport(
            listOf(
                makeNode("c1", 1, "TXT", "首頁", 100, 920),
                makeNode("c2", 2, "TXT", "美金匯率 1 USD = 32.15 元", 500, 350),
                makeNode("c3", 3, "BTN", "更多搜尋結果", 500, 680)
            )
        )

        val extractDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = chromeResultScreen,
            context = null,
            currentForegroundPackage = "com.android.chrome",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = extractSubGoalIndex
        )
        assertTrue(extractDecision.compactOpcode.startsWith("EXTRACT_CROSS_APP"))
        assertNotNull(CrossAppWorkflowEngine.latestClipboardEntry)
        assertTrue(CrossAppWorkflowEngine.latestClipboardEntry!!.extractedText.contains("32.15"))

        // 接著模擬進入備忘錄 (App B) 的輸入子目標，驗證 {{CROSS_APP_CLIPBOARD}} 自動注入「32.15」
        val inputInNoteIndex = subGoals.indexOfFirst { it.inputPayload == CrossAppWorkflowEngine.CLIPBOARD_PLACEHOLDER }
        assertTrue(inputInNoteIndex >= 0)

        val noteEditorScreen = makeReport(
            listOf(
                makeNode("n1", 1, "INP", "記事內容輸入框", 500, 380),
                makeNode("n2", 2, "BTN", "儲存", 850, 120)
            )
        )

        val noteInputDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = noteEditorScreen,
            context = null,
            currentForegroundPackage = "com.google.android.keep",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = inputInNoteIndex
        )
        assertEquals(AiActionType.INPUT_TEXT, noteInputDecision.actionType)
        assertTrue(noteInputDecision.inputTextValue.orEmpty().contains("32.15"))
    }

    @Test
    fun `test phase 2 launch app unlocked even when alreadyLaunchedApp is true and updates anti hijack package`() {
        val cmd = "去 Chrome 搜尋台北天氣，然後打開備忘錄記下來"
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(cmd, null, false)
        val phase2LaunchIndex = subGoals.indexOfFirst { it.appPhaseIndex == 2 && it.intentCategory == "LAUNCH_APP" }
        assertTrue(phase2LaunchIndex >= 0)

        InAppWorkingMemory.setExpectedTargetPackage("com.android.chrome")
        val dummyScreen = makeReport(listOf(makeNode("x1", 1, "TXT", "台北今日 25°C", 500, 350)))

        // 即使 alreadyLaunchedApp = true（因為 Phase 1 已經啟動過 Chrome），進入 Phase 2 的 LAUNCH_APP 仍必須成功啟動備忘錄！
        val launchPhase2Decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = dummyScreen,
            context = null,
            currentForegroundPackage = "com.android.chrome",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = phase2LaunchIndex
        )

        assertEquals(AiActionType.LAUNCH_APP, launchPhase2Decision.actionType)
        assertEquals("com.google.android.keep", launchPhase2Decision.targetAppPackage)
        assertEquals("com.google.android.keep", InAppWorkingMemory.expectedTargetPackage)
        // 驗證防外部廣告劫持機制已更新白名單為備忘錄，不會把備忘錄誤判為外部劫持！
        assertFalse(InAppOperationPlanner.isUnexpectedExternalAppHijack("com.google.android.keep"))
    }

    @Test
    fun `test current screen focus direct transfer to note app`() {
        val cmd = "把現在畫面上的重點存到備忘錄"
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(cmd, null, false)

        assertTrue(subGoals.size >= 3)
        assertEquals("EXTRACT_SCREEN_INFO", subGoals[0].intentCategory)
        assertEquals(1, subGoals[0].appPhaseIndex)
        assertEquals("LAUNCH_APP", subGoals[1].intentCategory)
        assertEquals("備忘錄", subGoals[1].targetAppHint)
        assertEquals(2, subGoals[1].appPhaseIndex)
    }

    @Test
    fun `test gmail email body subway entity extraction and google maps search then route tap end to end`() {
        val cmd = "去gmail找到一封最近提到聚餐地點的信把地鐵抓出來直接打開google地圖搜尋并規劃路線"
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(cmd, null, false)
        assertEquals(7, subGoals.size)

        // 步驟 3 (index = 3): 在 Gmail 信件內文頁擷取「地鐵」
        val extractIndex = subGoals.indexOfFirst { it.intentCategory == "EXTRACT_SCREEN_INFO" }
        assertEquals(3, extractIndex)

        val gmailDetailScreen = makeReport(
            listOf(
                makeNode("g1", 1, "TXT", "主旨：本週五部門聚餐通知", 500, 220),
                makeNode("g2", 2, "TXT", "各位同仁好，本週五晚上七點部門聚餐，請大家在捷運忠孝復興站4號出口集合，準時出發。", 500, 420),
                makeNode("g3", 3, "BTN", "回覆", 250, 820)
            )
        )

        val extractDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = gmailDetailScreen,
            context = null,
            currentForegroundPackage = "com.google.android.gm",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = extractIndex
        )
        assertTrue(extractDecision.compactOpcode.startsWith("EXTRACT_CROSS_APP"))
        // 驗證已從整段信件長文中精準抽取出短實體「捷運忠孝復興站4號出口」，而非整段信件句子
        assertEquals("捷運忠孝復興站4號出口", CrossAppWorkflowEngine.latestClipboardEntry?.extractedText)

        // 步驟 5 (index = 5): 在 Google 地圖搜尋框填入「捷運忠孝復興站4號出口」
        val mapsSearchIndex = subGoals.indexOfFirst {
            it.appPhaseIndex == 2 && it.intentCategory == "INPUT_SEARCH"
        }
        assertEquals(5, mapsSearchIndex)

        val mapsInputScreen = makeReport(
            listOf(
                makeNode("m_inp", 1, "INP", "搜尋這裡", 500, 140)
            )
        )
        val mapsInputDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = mapsInputScreen,
            context = null,
            currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = mapsSearchIndex
        )
        assertEquals(AiActionType.INPUT_TEXT, mapsInputDecision.actionType)
        assertEquals("捷運忠孝復興站4號出口", mapsInputDecision.inputTextValue)

        // 模擬已在 Google 地圖送出「捷運忠孝復興站4號出口」並載入地點資訊卡，驗證不會被提前斷電，且能自動推進點擊「路線」按鈕！
        InAppWorkingMemory.recordTextTypedAndSubmitted("捷運忠孝復興站4號出口", "搜尋這裡")
        val mapsPlaceCardScreen = makeReport(
            listOf(
                makeNode("m_title", 1, "TXT", "捷運忠孝復興站4號出口", 500, 560),
                makeNode("m_route", 2, "BTN", "路線", 220, 740),
                makeNode("m_save", 3, "BTN", "儲存", 500, 740),
                makeNode("m_share", 4, "BTN", "分享", 780, 740)
            )
        )
        assertFalse(
            "後面還有「點擊 路線」子目標，絕不可在搜尋結果載入時提前斷電",
            com.example.newandroidapp.stability.ClosedLoopVerifier.isTerminalGoalAlreadySatisfied(
                userGoal = cmd,
                currentPackage = "com.google.android.apps.maps",
                report = mapsPlaceCardScreen,
                hasTypedTextInLoop = true,
                activeSubGoalIndex = mapsSearchIndex,
                totalSubGoals = subGoals.size
            )
        )

        val mapsRouteDecision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = mapsPlaceCardScreen,
            context = null,
            currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = mapsSearchIndex // 驗證即使傳入 mapsSearchIndex，也會因已送出查詢而自動推進至下一子目標「點擊 路線」！
        )
        assertEquals(AiActionType.TAP, mapsRouteDecision.actionType)
        assertEquals("m_route", mapsRouteDecision.targetNode?.nodeId)
    }
}
