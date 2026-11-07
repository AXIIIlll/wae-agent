package com.example.newandroidapp.stability

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.ai.CooperativeSubGoal
import com.example.newandroidapp.vision.AiVisionPipeline
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 100% 原創自研：系統通知橫幅與突發彈窗防禦機制 (HeadsUpNotificationDefenseGuard) 單元與整合測試
 *
 * 驗證三大防線：
 * 1. 非目標包名與頂部通知過濾（Package & Bounds Filtering）：忽略 com.android.systemui 與外來 LINE 頂部橫幅，且不遮蔽底層 YouTube 搜尋框。
 * 2. 頂部遮擋時的【策略 A：主動上滑消除 (Swipe Up)】與【策略 B：焦點強制鎖定 (Direct Node Focus)】。
 * 3. 鎖定子任務完成條件：跨 App 切換前必須確認目標關鍵字已實際載入出結果內容，嚴禁被中途彈出的外來通知打斷或搶跑切換至「設定」。
 */
class HeadsUpNotificationDefenseGuardTest {

    @Before
    fun setUp() {
        HeadsUpNotificationDefenseGuard.resetState()
        InAppWorkingMemory.resetForNewGoal("")
    }

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    private fun pt(x: Int, y: Int): Point = Point(x, y).apply { this.x = x; this.y = y }

    private fun makeRawNode(
        id: String,
        pkg: String,
        zRank: Int,
        windowLayer: Int,
        bounds: Rect,
        text: String,
        isClickable: Boolean = true,
        isEditable: Boolean = false
    ): RawVisionNode {
        return RawVisionNode(
            id = id,
            windowId = windowLayer,
            windowLayer = windowLayer,
            zOrderRank = zRank,
            className = if (isEditable) "android.widget.EditText" else "android.widget.TextView",
            packageName = pkg,
            bounds = bounds,
            viewportClipBounds = rect(0, 0, 1080, 2340),
            text = text,
            contentDescription = text,
            hintText = if (isEditable) text else "",
            viewIdResourceName = id,
            isClickable = isClickable,
            isLongClickable = false,
            isCheckable = false,
            isChecked = false,
            isSelected = false,
            isEditable = isEditable,
            isScrollable = false,
            scrollDirections = "",
            isEnabled = true,
            isOpaqueOverlay = true,
            isModalScrim = false,
            isCanvasOrBlackBoxCandidate = false,
            hasRenderedContent = true,
            parentId = null,
            childIds = emptyList(),
            listStructuralSignature = null
        )
    }

    private fun makeConvergedNode(
        id: String,
        markId: Int,
        tag: String,
        label: String,
        bounds: Rect
    ): Stage2ConvergedNode {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = if (tag == "INP") "android.widget.EditText" else "android.view.View",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = bounds,
            convergedBounds = bounds,
            finalTapPx = pt(cx, cy),
            gridTapPoint = pt((cx * 1000) / 1080, (cy * 1000) / 2340),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
    }

    @Test
    fun `test package and bounds filtering strips LINE and systemui heads up banner without occluding underlying YouTube search bar`() {
        InAppWorkingMemory.setExpectedTargetPackage("com.google.android.youtube")

        // Z-Order 0 (最上層): 突發彈出的 LINE 訊息通知橫幅，正好壓在螢幕頂部 (top=110, bottom=310)
        val lineHeadsUpBanner = makeRawNode(
            id = "line_heads_up_1",
            pkg = "jp.naver.line.android",
            zRank = 0,
            windowLayer = 5,
            bounds = rect(40, 110, 1040, 310),
            text = "小明：傳送了新訊息 晚上要吃什麼？",
            isClickable = true
        )
        // Z-Order 1 (最上層): com.android.systemui 頂部通知展開容器
        val systemUiHeadsUp = makeRawNode(
            id = "sysui_heads_up_2",
            pkg = "com.android.systemui",
            zRank = 1,
            windowLayer = 5,
            bounds = rect(30, 105, 1050, 320),
            text = "LINE • 剛剛 • 立即回覆",
            isClickable = true
        )
        // Z-Order 2 (底層目標 App): YouTube 頂部搜尋輸入框，同樣位於 (top=130, bottom=250)
        val youtubeTopSearchInput = makeRawNode(
            id = "yt_search_input",
            pkg = "com.google.android.youtube",
            zRank = 2,
            windowLayer = 1,
            bounds = rect(120, 130, 960, 250),
            text = "搜尋 YouTube",
            isClickable = true,
            isEditable = true
        )
        // Z-Order 3 (底層目標 App): YouTube 內容區影片
        val youtubeVideoItem = makeRawNode(
            id = "yt_video_1",
            pkg = "com.google.android.youtube",
            zRank = 3,
            windowLayer = 1,
            bounds = rect(60, 500, 1020, 900),
            text = "周杰倫 晴天 官方 MV",
            isClickable = true
        )

        val filteredNodes = HeadsUpNotificationDefenseGuard.filterRawNodesAndRecordHeadsUpBanner(
            rawNodes = listOf(lineHeadsUpBanner, systemUiHeadsUp, youtubeTopSearchInput, youtubeVideoItem),
            screenWidth = 1080,
            screenHeight = 2340,
            expectedTargetPackage = "com.google.android.youtube",
            nowElapsedMs = 5000L
        )

        // 1. 驗證 LINE 與 com.android.systemui 的頂部通知已被視為背景雜訊徹底過濾，不會進入 Stage 1 OccupancyGrid2D 遮蔽底層搜尋框
        assertFalse(filteredNodes.any { it.packageName == "jp.naver.line.android" || it.packageName == "com.android.systemui" })
        assertFalse(filteredNodes.any { it.text.contains("小明") || it.text.contains("立即回覆") })
        // 2. 驗證底層的 YouTube 頂部搜尋框與內容節點 100% 完整保留
        assertEquals(2, filteredNodes.size)
        assertTrue(filteredNodes.any { it.id == "yt_search_input" && it.isEditable && it.text.contains("搜尋 YouTube") })
        // 3. 驗證已記錄頂部通知橫幅聯集座標 (30, 105, 1050, 320) 供後續策略 A / 策略 B 決策
        val recordedBanner = HeadsUpNotificationDefenseGuard.getActiveBanner(nowElapsedMs = 5100L)
        assertNotNull(recordedBanner)
        assertEquals(30, recordedBanner!!.bounds.left)
        assertEquals(105, recordedBanner.bounds.top)
        assertEquals(1050, recordedBanner.bounds.right)
        assertEquals(320, recordedBanner.bounds.bottom)
    }

    @Test
    fun `test Strategy A active swipe up dismissal when TAP target overlaps top heads up banner`() {
        InAppWorkingMemory.setExpectedTargetPackage("com.google.android.youtube")

        val rawNodes = listOf(
            makeRawNode(
                id = "line_banner",
                pkg = "jp.naver.line.android",
                zRank = 0,
                windowLayer = 4,
                bounds = rect(40, 110, 1040, 310),
                text = "LINE 新訊息通知"
            ),
            makeRawNode(
                id = "yt_search_btn",
                pkg = "com.google.android.youtube",
                zRank = 1,
                windowLayer = 1,
                bounds = rect(820, 140, 1000, 240),
                text = "搜尋"
            )
        )
        HeadsUpNotificationDefenseGuard.filterRawNodesAndRecordHeadsUpBanner(
            rawNodes = rawNodes,
            screenWidth = 1080,
            screenHeight = 2340,
            expectedTargetPackage = "com.google.android.youtube",
            nowElapsedMs = 10000L
        )

        val searchBtnNode = makeConvergedNode(
            id = "yt_search_btn",
            markId = 1,
            tag = "BTN",
            label = "搜尋",
            bounds = rect(820, 140, 1000, 240)
        )

        val eval = HeadsUpNotificationDefenseGuard.evaluateTopBannerOcclusionStrategy(
            actionType = AiActionType.TAP,
            targetX = 910,
            targetY = 190,
            targetNode = searchBtnNode,
            hasUnderlyingEditableNode = false,
            screenWidth = 1080,
            screenHeight = 2340,
            nowElapsedMs = 10200L
        )

        assertEquals(HeadsUpNotificationDefenseGuard.DefenseStrategy.STRATEGY_A_SWIPE_UP_DISMISS, eval.strategy)
        assertNotNull(eval.swipeTrajectory)
        assertTrue("策略 A 手勢必須為由下往上滑動 (endY < startY)", eval.swipeTrajectory!!.endY < eval.swipeTrajectory!!.startY)
    }

    @Test
    fun `test Strategy B direct node focus bypass when INPUT_TEXT target overlaps top heads up banner`() {
        InAppWorkingMemory.setExpectedTargetPackage("com.google.android.youtube")

        val rawNodes = listOf(
            makeRawNode(
                id = "sysui_banner",
                pkg = "com.android.systemui",
                zRank = 0,
                windowLayer = 4,
                bounds = rect(30, 100, 1050, 320),
                text = "LINE：媽媽傳送了一則貼圖"
            )
        )
        HeadsUpNotificationDefenseGuard.filterRawNodesAndRecordHeadsUpBanner(
            rawNodes = rawNodes,
            screenWidth = 1080,
            screenHeight = 2340,
            expectedTargetPackage = "com.google.android.youtube",
            nowElapsedMs = 20000L
        )

        val searchInputNode = makeConvergedNode(
            id = "yt_search_inp",
            markId = 1,
            tag = "INP",
            label = "搜尋 YouTube",
            bounds = rect(120, 140, 960, 240)
        )

        // 當底層 EditText 節點已鎖定 (hasUnderlyingEditableNode = true) 時，採用策略 B 直接寫入節點、無視上層通知橫幅
        val eval = HeadsUpNotificationDefenseGuard.evaluateTopBannerOcclusionStrategy(
            actionType = AiActionType.INPUT_TEXT,
            targetX = 540,
            targetY = 190,
            targetNode = searchInputNode,
            hasUnderlyingEditableNode = true,
            screenWidth = 1080,
            screenHeight = 2340,
            nowElapsedMs = 20300L
        )

        assertEquals(HeadsUpNotificationDefenseGuard.DefenseStrategy.STRATEGY_B_DIRECT_NODE_FOCUS, eval.strategy)
        assertTrue(eval.diagnosticNote.contains("策略B"))
    }

    @Test
    fun `test strict subgoal gate prevents premature cross app jump to Settings until search results actually load`() {
        InAppWorkingMemory.setExpectedTargetPackage("com.google.android.youtube")

        val phase1SearchSubGoal = CooperativeSubGoal(
            stepIndex = 2,
            totalSteps = 4,
            intentCategory = "INPUT_SEARCH",
            targetAppHint = "YouTube",
            targetSemanticPhrase = "搜尋 周杰倫晴天",
            inputPayload = "周杰倫晴天",
            appPhaseIndex = 1
        )
        val phase2SettingsLaunchSubGoal = CooperativeSubGoal(
            stepIndex = 3,
            totalSteps = 4,
            intentCategory = "LAUNCH_APP",
            targetAppHint = "設定",
            targetSemanticPhrase = "啟動目標 App：設定",
            appPhaseIndex = 2
        )

        val beforeScreenNodes = listOf(
            makeConvergedNode("inp1", 1, "INP", "搜尋 YouTube", rect(120, 140, 960, 240))
        )
        val beforeRep = VisionPipelineReport(
            timestampMs = 1000L, screenWidth = 1080, screenHeight = 2340, gridCols = 120, gridRows = 240,
            occupancyGridSnapshot = ByteArray(0), totalRawNodes = 1, culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0, transparentOverlayBypassedCount = 0, hotspotConvergedCount = 0,
            spatialBoundIconCount = 0, geometricIconClassifiedCount = 0, roiRecoveredCount = 0, nanoDetDetectedCount = 0,
            foldedListGroupCount = 0, temporalStabilizedCount = 0, stage1AllNodes = emptyList(),
            detectedRoiVoids = emptyList(), finalNodes = beforeScreenNodes, markIdToPixelMap = emptyMap(),
            compressedPromptText = "", deltaPromptText = "", rawCharCount = 10, compressedCharCount = 10,
            compressionRatioPercent = 0, pipelineLatencyMs = 2L
        )
        val beforeFp = ClosedLoopVerifier.computeScreenFingerprint("com.google.android.youtube", beforeRep)

        // 情況 1：中途彈出通知或尚未載入出「周杰倫晴天」結果內容（畫面上只有輸入框與無關推薦，或還沒打完字）-> 嚴禁推進切換至「設定」！
        val incompleteNodes = listOf(
            makeConvergedNode("inp1", 1, "INP", "周杰倫晴天", rect(120, 140, 960, 240)),
            makeConvergedNode(" tab1", 2, "BTN", "首頁", rect(100, 2100, 300, 2250))
        )
        val incompleteRep = beforeRep.copy(finalNodes = incompleteNodes)
        val incompleteFp = ClosedLoopVerifier.computeScreenFingerprint("com.google.android.youtube", incompleteRep)

        val prematureCheck = HeadsUpNotificationDefenseGuard.verifyStrictSubGoalAndCrossAppCompletion(
            activeSubGoal = phase1SearchSubGoal,
            nextSubGoal = phase2SettingsLaunchSubGoal,
            beforeFp = beforeFp,
            afterFp = incompleteFp,
            afterReport = incompleteRep,
            hasTypedTextInStep = true,
            expectedTargetPackage = "com.google.android.youtube"
        )
        assertFalse("尚未載入出含目標關鍵字之非輸入框結果內容前，嚴禁搶跑切換至下一個 App (設定)", prematureCheck)

        // 情況 2：目標關鍵字「周杰倫晴天」已實際載入出搜尋結果影片列表 -> 准許完成第一階段並推進至下一個 App (設定)！
        val loadedResultNodes = listOf(
            makeConvergedNode("inp1", 1, "INP", "周杰倫晴天", rect(120, 140, 960, 240)),
            makeConvergedNode("res1", 2, "BTN", "周杰倫 Jay Chou【晴天 Sunny Day】Official MV", rect(60, 420, 1020, 820)),
            makeConvergedNode("res2", 3, "BTN", "周杰倫 晴天 現場演唱會版", rect(60, 860, 1020, 1240))
        )
        val loadedRep = beforeRep.copy(finalNodes = loadedResultNodes)
        val loadedFp = ClosedLoopVerifier.computeScreenFingerprint("com.google.android.youtube", loadedRep)

        val validCheck = HeadsUpNotificationDefenseGuard.verifyStrictSubGoalAndCrossAppCompletion(
            activeSubGoal = phase1SearchSubGoal,
            nextSubGoal = phase2SettingsLaunchSubGoal,
            beforeFp = beforeFp,
            afterFp = loadedFp,
            afterReport = loadedRep,
            hasTypedTextInStep = true,
            expectedTargetPackage = "com.google.android.youtube"
        )
        assertTrue("當搜尋結果已實際載入於畫面上時，准許推進至下一階段 App", validCheck)
    }

    @Test
    fun `test real YouTube home screen search button at 945_89_1080_224 is never stripped or blocked and tapped directly`() {
        // 模擬實機狀況：使用者在 YouTube 首頁展開懸浮膠囊輸入「去youtube搜尋搜尋東西」，
        // 剛按下執行時 expectedTargetPackage 尚未設定 ("")，且底部仍有正在收合的輸入法視窗 (com.samsung.android.honeyboard) 與頂部狀態列 (0..89)
        val statusBarNode = makeRawNode(
            id = "sysui_status_bar",
            pkg = "com.android.systemui",
            zRank = 0,
            windowLayer = 3,
            bounds = rect(0, 0, 1080, 89),
            text = "15:25",
            isClickable = false
        )
        val closingKeyboardNode = makeRawNode(
            id = "ime_closing",
            pkg = "com.samsung.android.honeyboard",
            zRank = 1,
            windowLayer = 2,
            bounds = rect(0, 1450, 1080, 2340),
            text = "",
            isClickable = false
        )
        val ytLogoNode = makeRawNode(
            id = "yt_logo",
            pkg = "com.google.android.youtube",
            zRank = 2,
            windowLayer = 1,
            bounds = rect(0, 89, 363, 224),
            text = "YouTube Premium",
            isClickable = true
        )
        val ytSearchButtonNode = makeRawNode(
            id = "com.google.android.youtube:id/menu_item_view",
            pkg = "com.google.android.youtube",
            zRank = 3,
            windowLayer = 1,
            bounds = rect(945, 89, 1080, 224),
            text = "搜尋",
            isClickable = true
        )
        val ytFeedNode = makeRawNode(
            id = "yt_feed_card",
            pkg = "com.google.android.youtube",
            zRank = 4,
            windowLayer = 1,
            bounds = rect(0, 240, 1080, 960),
            text = "推薦影片內容",
            isClickable = true
        )

        val filtered = HeadsUpNotificationDefenseGuard.filterRawNodesAndRecordHeadsUpBanner(
            rawNodes = listOf(statusBarNode, closingKeyboardNode, ytLogoNode, ytSearchButtonNode, ytFeedNode),
            screenWidth = 1080,
            screenHeight = 2340,
            expectedTargetPackage = ""
        )

        // 1. 驗證 YouTube 右上角搜尋鍵 [945,89][1080,224] 絕不會被誤判為外來橫幅剔除，且不會留下假陽性 activeBanner
        assertTrue(filtered.any { it.text == "搜尋" && it.bounds.left == 945 && it.bounds.top == 89 })
        assertEquals(null, HeadsUpNotificationDefenseGuard.getActiveBanner())

        // 2. 驗證當 YouTube 已在前景時下達「去youtube搜尋搜尋東西」，DualLocalAiCoordinator 會自動跳過 LAUNCH_APP 子目標並直接點擊「搜尋」按鈕！
        val convergedNodes = listOf(
            makeConvergedNode("yt_logo", 1, "BTN", "YouTube Premium", rect(0, 89, 363, 224)),
            makeConvergedNode("com.google.android.youtube:id/menu_item_view", 2, "BTN", "搜尋", rect(945, 89, 1080, 224)),
            makeConvergedNode("yt_feed_card", 3, "BTN", "推薦影片內容", rect(0, 240, 1080, 960))
        )
        val report = VisionPipelineReport(
            timestampMs = 1000L, screenWidth = 1080, screenHeight = 2340, gridCols = 120, gridRows = 240,
            occupancyGridSnapshot = ByteArray(0), totalRawNodes = filtered.size, culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0, transparentOverlayBypassedCount = 0, hotspotConvergedCount = 0,
            spatialBoundIconCount = 0, geometricIconClassifiedCount = 0, roiRecoveredCount = 0, nanoDetDetectedCount = 0,
            foldedListGroupCount = 0, temporalStabilizedCount = 0, stage1AllNodes = emptyList(),
            detectedRoiVoids = emptyList(), finalNodes = convergedNodes, markIdToPixelMap = emptyMap(),
            compressedPromptText = "", deltaPromptText = "", rawCharCount = 10, compressedCharCount = 10,
            compressionRatioPercent = 0, pipelineLatencyMs = 2L
        )
        val decision = com.example.newandroidapp.ai.DualLocalAiCoordinator.decide(
            mode = com.example.newandroidapp.ai.LocalAiBackendMode.ANDROID_NATIVE_AI,
            userGoal = "去youtube搜尋搜尋東西",
            report = report,
            context = null,
            currentForegroundPackage = "com.google.android.youtube",
            alreadyLaunchedApp = false,
            activeSubGoalIndex = 0
        )

        assertEquals(com.example.newandroidapp.ai.AiActionType.TAP, decision.actionType)
        assertNotNull(decision.targetNode)
        assertTrue("必須精準選中 YouTube 右上角「搜尋」按鈕而非左上角 YouTube Logo", decision.targetNode!!.label.contains("搜尋"))
    }
}
