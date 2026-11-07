package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppOperationStage
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.AiVisionPipeline
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import com.example.newandroidapp.vision.ZOrderTreeCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeterminismHardeningTest {

    @Before
    fun setUp() {
        InAppWorkingMemory.resetForNewGoal("")
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
        x: Int,
        y: Int,
        pkg: String = "com.google.android.gm"
    ): Stage2ConvergedNode {
        val bounds = makeRect(x - 60, y - 30, x + 60, y + 30)
        val gx = ((x / 1080f) * 999f).toInt().coerceIn(0, 999)
        val gy = ((y / 2400f) * 999f).toInt().coerceIn(0, 999)
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = "android.widget.TextView",
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
            packageName = pkg
        )
    }

    private fun makeReport(nodes: List<Stage2ConvergedNode>): VisionPipelineReport {
        val map = LinkedHashMap<Int, Point>()
        for (n in nodes) {
            map[n.shortMarkId] = n.finalTapPx
        }
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
            stage1AllNodes = emptyList(),
            detectedRoiVoids = emptyList(),
            finalNodes = nodes,
            markIdToPixelMap = map,
            compressedPromptText = "",
            deltaPromptText = "",
            rawCharCount = 100,
            compressedCharCount = 50,
            compressionRatioPercent = 50,
            pipelineLatencyMs = 5L
        )
    }

    @Test
    fun testGreedyDecodingAndDeterministicNodeCodeConstants() {
        assertEquals(0.0f, DeterminismHardeningEngine.GREEDY_TEMPERATURE, 0.0f)
        assertEquals(1, DeterminismHardeningEngine.GREEDY_TOP_K)
        assertFalse(DeterminismHardeningEngine.RANDOM_SAMPLING_ENABLED)
        assertEquals(42L, DeterminismHardeningEngine.DETERMINISTIC_SEED)

        // 即使座標發生 ±3px 微小偏移，10px 網格對齊後的確定性節點識別碼仍 100% 相同
        val code1 = DeterminismHardeningEngine.computeDeterministicNodeCode(
            windowId = 2,
            depth = 4,
            childIndex = 1,
            className = "android.widget.Button",
            viewIdResourceName = "com.app:id/btn_search",
            rawBounds = makeRect(101, 202, 303, 258)
        )
        val code2 = DeterminismHardeningEngine.computeDeterministicNodeCode(
            windowId = 2,
            depth = 4,
            childIndex = 1,
            className = "android.widget.Button",
            viewIdResourceName = "com.app:id/btn_search",
            rawBounds = makeRect(98, 199, 297, 262)
        )
        assertEquals(code1, code2)
    }

    @Test
    fun testCanonicalSpatialSortingInSameScoreBucketAnd10PxGrid() {
        val bottomRight = makeNode("n_br", 1, "BTN", "確認", x = 802, y = 903)
        val topLeft = makeNode("n_tl", 2, "BTN", "返回", x = 118, y = 201)
        val topRight = makeNode("n_tr", 3, "BTN", "更多", x = 921, y = 198) // y=198 與 y=201 四捨五入至 10px 皆為 200
        val middleCenter = makeNode("n_mc", 4, "BTN", "聚餐通知信", x = 541, y = 502)

        // 同分或相近分數區間 (差距 < 0.02f) 內，強制依 Y 由上而下 (10px 網格)、再依 X 由左而右排序
        val inputScored = listOf(
            bottomRight to 0.861f,
            middleCenter to 0.859f,
            topRight to 0.862f,
            topLeft to 0.858f
        )
        val sorted = DeterminismHardeningEngine.sortScoredCandidatesCanonically(inputScored)
        assertEquals(listOf("n_tl", "n_tr", "n_mc", "n_br"), sorted.map { it.first.nodeId })
    }

    @Test
    fun testFastPathDualAntiJumpStartLockConfidenceMarginAndAttribute() {
        val inpSearch = makeNode("n_inp", 1, "INP", "搜尋郵件", x = 500, y = 180)
        val btnFake = makeNode("n_btn", 2, "BTN", "搜尋按鈕", x = 500, y = 320)
        val subGoalInput = CooperativeSubGoal(
            stepIndex = 1,
            totalSteps = 2,
            intentCategory = "INPUT_SEARCH",
            targetAppHint = "Gmail",
            targetSemanticPhrase = "搜尋 聚餐地點",
            inputPayload = "聚餐地點"
        )

        // 情境 A：最高分 >= 0.85，但第一名與第二名差距 (Margin = 0.06) 小於顯著閾值 0.15 -> 禁止搶跑！
        val closeMarginEval = DeterminismHardeningEngine.evaluateFastPathGate(
            scoredCandidates = listOf(inpSearch to 0.89f, btnFake to 0.83f),
            activeSubGoal = subGoalInput,
            inAppStage = InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT,
            resolvedActionType = AiActionType.INPUT_TEXT
        )
        assertFalse(closeMarginEval.allowed)
        assertTrue(closeMarginEval.blockReason.startsWith("LOW_MARGIN"))

        // 情境 B：分數與差距皆達標，但 INPUT 動作卻指派給非 INP 的 BTN 節點 -> 禁止搶跑！
        val wrongAttrEval = DeterminismHardeningEngine.evaluateFastPathGate(
            scoredCandidates = listOf(btnFake to 0.94f, inpSearch to 0.60f),
            activeSubGoal = subGoalInput,
            inAppStage = InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT,
            resolvedActionType = AiActionType.INPUT_TEXT
        )
        assertFalse(wrongAttrEval.allowed)
        assertTrue(wrongAttrEval.blockReason.startsWith("ATTRIBUTE_MISMATCH"))

        // 情境 C：信心 >= 0.85、Margin >= 0.15 且節點為可編輯 INP -> 准許通過！
        val validEval = DeterminismHardeningEngine.evaluateFastPathGate(
            scoredCandidates = listOf(inpSearch to 0.94f, btnFake to 0.65f),
            activeSubGoal = subGoalInput,
            inAppStage = InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT,
            resolvedActionType = AiActionType.INPUT_TEXT
        )
        assertTrue(validEval.allowed)
        assertEquals("PASSED", validEval.blockReason)
    }

    @Test
    fun testThreeRepeatedRunsProduce100PercentIdenticalDecisionDespiteNoiseAndJitter() {
        val targetPkg = "com.google.android.gm"
        val userGoal = "搜尋 聚餐地點"

        // 第 1 次掃描：正常順序 + 狀態列時間 "12:34" + 電量 "98%" + 網速 "12.4 KB/s" + LINE 懸浮窗
        val run1Nodes = listOf(
            makeNode("sys_clock", 1, "TXT", "12:34", x = 120, y = 40, pkg = "com.android.systemui"),
            makeNode("sys_bat", 2, "TXT", "98%", x = 980, y = 40, pkg = "com.android.systemui"),
            makeNode("sys_net", 3, "TXT", "12.4 KB/s", x = 850, y = 150, pkg = targetPkg),
            makeNode("foreign_line", 4, "BTN", "LINE 新訊息通知", x = 540, y = 220, pkg = "jp.naver.line.android"),
            makeNode("gm_search", 5, "INP", "在郵件中搜尋", x = 480, y = 180, pkg = targetPkg),
            makeNode("gm_mail_1", 6, "BTN", "週五聚餐地點改在捷運市政府站", x = 540, y = 460, pkg = targetPkg),
            makeNode("gm_mail_2", 7, "BTN", "信用卡電子帳單通知", x = 540, y = 720, pkg = targetPkg),
            makeNode("sys_nav", 8, "BTN", "Home", x = 540, y = 2360, pkg = "com.android.systemui")
        )

        // 第 2 次掃描：節點順序完全反轉 + 時間變成 "12:35" + 電量變成 "97%" + 座標微幅偏移 (+3px)
        val run2Nodes = listOf(
            makeNode("sys_nav", 1, "BTN", "Home", x = 542, y = 2362, pkg = "com.android.systemui"),
            makeNode("gm_mail_2", 2, "BTN", "信用卡電子帳單通知", x = 543, y = 723, pkg = targetPkg),
            makeNode("gm_mail_1", 3, "BTN", "週五聚餐地點改在捷運市政府站", x = 538, y = 462, pkg = targetPkg),
            makeNode("gm_search", 4, "INP", "在郵件中搜尋", x = 483, y = 182, pkg = targetPkg),
            makeNode("sys_clock", 5, "TXT", "12:35", x = 122, y = 42, pkg = targetPkg),
            makeNode("sys_bat", 6, "TXT", "97%", x = 981, y = 160, pkg = targetPkg)
        )

        // 第 3 次掃描：節點順序打亂 + 時間變成 "下午 12:36" + 電量變成 "充電中 96%" + 5G 徽章 + 座標微幅偏移 (-4px)
        val run3Nodes = listOf(
            makeNode("gm_mail_1", 1, "BTN", "週五聚餐地點改在捷運市政府站", x = 536, y = 456, pkg = targetPkg),
            makeNode("sys_5g", 2, "TXT", "5G", x = 760, y = 155, pkg = targetPkg),
            makeNode("sys_bat_chg", 3, "TXT", "充電中 96%", x = 920, y = 155, pkg = targetPkg),
            makeNode("gm_search", 4, "INP", "在郵件中搜尋", x = 476, y = 176, pkg = targetPkg),
            makeNode("gm_mail_2", 5, "BTN", "信用卡電子帳單通知", x = 537, y = 716, pkg = targetPkg),
            makeNode("sys_clock_pm", 6, "TXT", "下午 12:36", x = 118, y = 155, pkg = targetPkg)
        )

        val rep1 = DeterminismHardeningEngine.sanitizeVisionReport(makeReport(run1Nodes), expectedTargetPackage = targetPkg)
        val rep2 = DeterminismHardeningEngine.sanitizeVisionReport(makeReport(run2Nodes), expectedTargetPackage = targetPkg)
        val rep3 = DeterminismHardeningEngine.sanitizeVisionReport(makeReport(run3Nodes), expectedTargetPackage = targetPkg)

        // 1. 驗證三次掃描過濾雜訊後的節點數量、代號 (#1..#3) 與 10px 座標完全一致
        assertEquals(3, rep1.finalNodes.size)
        assertEquals(rep1.finalNodes.map { "${it.shortMarkId}:${it.label}@${it.finalTapPx.x},${it.finalTapPx.y}" },
            rep2.finalNodes.map { "${it.shortMarkId}:${it.label}@${it.finalTapPx.x},${it.finalTapPx.y}" })
        assertEquals(rep1.finalNodes.map { "${it.shortMarkId}:${it.label}@${it.finalTapPx.x},${it.finalTapPx.y}" },
            rep3.finalNodes.map { "${it.shortMarkId}:${it.label}@${it.finalTapPx.x},${it.finalTapPx.y}" })

        // 2. 驗證三次掃描的快速畫面結構雜湊 (screenHash) 100% 相同
        val hash1 = InAppOperationPlanner.computeQuickScreenHash(makeReport(run1Nodes))
        val hash2 = InAppOperationPlanner.computeQuickScreenHash(makeReport(run2Nodes))
        val hash3 = InAppOperationPlanner.computeQuickScreenHash(makeReport(run3Nodes))
        assertEquals(hash1, hash2)
        assertEquals(hash1, hash3)

        // 3. 驗證三次重複執行完整 AI 決策管線產生 100% 相同的決策輸出（消除「兩次對、一次錯」隨機抖動）
        InAppWorkingMemory.resetForNewGoal("")
        val dec1 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = userGoal,
            report = makeReport(run1Nodes),
            context = null,
            currentForegroundPackage = targetPkg,
            alreadyLaunchedApp = true,
            lockToCurrentApp = true
        )

        InAppWorkingMemory.resetForNewGoal("")
        val dec2 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = userGoal,
            report = makeReport(run2Nodes),
            context = null,
            currentForegroundPackage = targetPkg,
            alreadyLaunchedApp = true,
            lockToCurrentApp = true
        )

        InAppWorkingMemory.resetForNewGoal("")
        val dec3 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = userGoal,
            report = makeReport(run3Nodes),
            context = null,
            currentForegroundPackage = targetPkg,
            alreadyLaunchedApp = true,
            lockToCurrentApp = true
        )

        assertNotNull(dec1.targetNode)
        assertEquals(dec1.actionType, dec2.actionType)
        assertEquals(dec1.actionType, dec3.actionType)
        assertEquals(dec1.targetMarkId, dec2.targetMarkId)
        assertEquals(dec1.targetMarkId, dec3.targetMarkId)
        assertEquals(dec1.tapPixel?.x to dec1.tapPixel?.y, dec2.tapPixel?.x to dec2.tapPixel?.y)
        assertEquals(dec1.tapPixel?.x to dec1.tapPixel?.y, dec3.tapPixel?.x to dec3.tapPixel?.y)
        assertEquals(dec1.compactOpcode, dec2.compactOpcode)
        assertEquals(dec1.compactOpcode, dec3.compactOpcode)
    }

    @Test
    fun testNestedIdenticalBoundsFrameLayoutsNeverCauseSelfLoopOrStackOverflow() {
        class FakeIdenticalWrapperNode(
            override val className: CharSequence? = "android.widget.FrameLayout",
            override val text: CharSequence? = null,
            override val contentDescription: CharSequence? = null,
            override val hintText: CharSequence? = null,
            override val viewIdResourceName: String? = null,
            override val packageName: CharSequence? = "com.heavy.multiapp",
            override val isVisibleToUser: Boolean = true,
            override val isClickable: Boolean = true,
            override val isLongClickable: Boolean = false,
            override val isCheckable: Boolean = false,
            override val isChecked: Boolean = false,
            override val isSelected: Boolean = false,
            override val isEditable: Boolean = false,
            override val isScrollable: Boolean = false,
            override val isEnabled: Boolean = true,
            override val drawingOrder: Int = 0,
            override val canScrollForwardOrBackward: Boolean = false,
            override val identityCode: Int = 777777, // 模擬相同 className + viewId + bounds 算出完全相同的 identityCode
            override val isPassword: Boolean = false,
            override val inputType: Int = 0,
            private val boundsRect: Rect = makeRect(0, 120, 1080, 2200),
            private val children: List<ZOrderTreeCollector.NodeHierarchySource> = emptyList()
        ) : ZOrderTreeCollector.NodeHierarchySource {
            override val childCount: Int get() = children.size
            override fun getChild(index: Int): ZOrderTreeCollector.NodeHierarchySource? = children.getOrNull(index)
            override fun getBoundsInScreen(outBounds: Rect) {
                outBounds.left = boundsRect.left
                outBounds.top = boundsRect.top
                outBounds.right = boundsRect.right
                outBounds.bottom = boundsRect.bottom
            }
        }

        val leafButton = FakeIdenticalWrapperNode(
            className = "android.widget.Button",
            text = "開始導航",
            isClickable = true,
            boundsRect = makeRect(200, 800, 600, 920)
        )
        // 模擬進入多個大型 App 時常見的 8 層同座標嵌套 FrameLayout (clickable=true, identityCode 相同)
        var currentTree: ZOrderTreeCollector.NodeHierarchySource = leafButton
        for (i in 1..8) {
            currentTree = FakeIdenticalWrapperNode(children = listOf(currentTree))
        }

        val rawNodes = ZOrderTreeCollector.collectFromNodeSource(
            root = currentTree,
            screenWidth = 1080,
            screenHeight = 2400
        )
        // 每個節點的 id 必須 100% 唯一，且絕不可出現 parentId == id 的自迴圈
        val uniqueIds = rawNodes.map { it.id }.toSet()
        assertEquals(rawNodes.size, uniqueIds.size)
        assertTrue(rawNodes.none { it.id == it.parentId })

        // 完整執行 AiVisionPipeline (含 Stage 1 遮罩與 Stage 2 HotspotAndSpatialBinder.collectDescendants) 絕不發生 StackOverflowError
        val pipelineReport = AiVisionPipeline.execute(
            rawNodes = rawNodes,
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertTrue(pipelineReport.finalNodes.any { it.label.contains("開始導航") })
    }

    @Test
    fun testMultiAppSwitchingDoesNotStripCurrentForegroundAppNodes() {
        // 模擬使用者先後進入多個 App 後，InAppWorkingMemory 殘留舊 App 包名，但當前在新的前景 App 啟動 AI 動作
        InAppWorkingMemory.setExpectedTargetPackage("com.old.previous.app")
        val currentAppPkg = "com.new.current.app"
        val currentScreenNodes = listOf(
            makeNode("n1", 1, "INP", "搜尋商品", x = 480, y = 220, pkg = currentAppPkg),
            makeNode("n2", 2, "BTN", "立即購買", x = 540, y = 800, pkg = currentAppPkg)
        )
        val sanitized = DeterminismHardeningEngine.sanitizeVisionReport(
            report = makeReport(currentScreenNodes),
            expectedTargetPackage = InAppWorkingMemory.expectedTargetPackage,
            foregroundAppPackage = currentAppPkg
        )
        // 當前前景 App 的節點絕不可被當作背景雜訊清空
        assertEquals(2, sanitized.finalNodes.size)
    }
}
