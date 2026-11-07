package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.chat.ConversationTurnContext
import com.example.newandroidapp.chat.PendingSafetyAction
import com.example.newandroidapp.stability.SafetyGateManager
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage1OccludedNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 驗證功能 3.2：高風險安全閘門 (C! Safety Gate) 詞庫擴充與二次確認機制測試
 * 涵蓋攔截詞庫、座標存入 ConversationTurnContext、「確認執行」執行待辦動作、「取消」清除待辦動作。
 */
class SafetyGateTwoPhaseTest {

    @Before
    fun setUp() {
        ConversationManager.clearConversation()
    }

    @After
    fun tearDown() {
        ConversationManager.clearConversation()
    }

    private fun makeNode(
        label: String,
        shortMarkId: Int = 1,
        x: Int = 540,
        y: Int = 1200
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = "sensitive_node_$shortMarkId",
            shortMarkId = shortMarkId,
            className = "android.widget.Button",
            shortTypeTag = "BTN",
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = Rect(x - 100, y - 50, x + 100, y + 50),
            convergedBounds = Rect(x - 100, y - 50, x + 100, y + 50),
            finalTapPx = Point(x, y).apply { this.x = x; this.y = y },
            gridTapPoint = Point(x / 2, y / 2).apply { this.x = x / 2; this.y = y / 2 },
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
    }

    private fun createReportWithNode(node: Stage2ConvergedNode): VisionPipelineReport {
        val raw = RawVisionNode(
            id = "raw_root",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = "android.widget.FrameLayout",
            packageName = "com.example.shopping",
            bounds = Rect(0, 0, 1080, 2400)
        )
        val s1Node = Stage1OccludedNode(
            raw = raw,
            visibleRatio = 1.0f,
            isCulled = false,
            wasPartiallyOccluded = false,
            safeTapPointPx = Point(500, 500),
            visibleBoundingBox = Rect(0, 0, 1080, 2400)
        )
        return VisionPipelineReport(
            timestampMs = System.currentTimeMillis(),
            screenWidth = 1080,
            screenHeight = 2400,
            gridCols = 10,
            gridRows = 20,
            occupancyGridSnapshot = ByteArray(0),
            totalRawNodes = 1,
            culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0,
            transparentOverlayBypassedCount = 0,
            hotspotConvergedCount = 0,
            spatialBoundIconCount = 0,
            geometricIconClassifiedCount = 0,
            roiRecoveredCount = 0,
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = listOf(s1Node),
            detectedRoiVoids = emptyList(),
            finalNodes = listOf(node),
            markIdToPixelMap = mapOf(node.shortMarkId to node.finalTapPx),
            compressedPromptText = "MOCK",
            deltaPromptText = "",
            rawCharCount = 50,
            compressedCharCount = 20,
            compressionRatioPercent = 60,
            pipelineLatencyMs = 10L
        )
    }

    @Test
    fun testExpandedHighRiskKeywordsInterception() {
        val sensitiveLabels = listOf(
            "確認支付",
            "提交訂單",
            "立即購買",
            "訂閱VIP會員",
            "刪除全部相簿",
            "清除資料",
            "解除安裝此應用",
            "Apple Pay",
            "Proceed to Checkout",
            "Delete Account Permanently"
        )

        for (label in sensitiveLabels) {
            assertTrue("Keyword '$label' must trigger high risk check", SafetyGateManager.isHighRiskAction(label))
        }
    }

    @Test
    fun testSafetyGateInterceptionSavesCoordinatesToTurnContext() {
        val sensitiveNode = makeNode("確認支付 $1,200", shortMarkId = 7, x = 600, y = 1400)
        val report = createReportWithNode(sensitiveNode)

        // 執行決策，觸發安全閘門
        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "點擊付款",
            report = report,
            context = null
        )

        assertEquals(AiActionType.CONFIRM_HIGH_RISK, decision.actionType)
        assertTrue(decision.compactOpcode.startsWith("C! -> #7"))

        // 驗證待確認座標已被安全閘門管理器存入 ConversationTurnContext
        val turnCtx = ConversationManager.getTurnContext()
        val pending = turnCtx.pendingSafetyAction
        assertNotNull("Pending safety action must not be null", pending)
        assertEquals(7, pending?.targetMarkId)
        assertEquals("確認支付 $1,200", pending?.label)
        assertEquals(600, pending?.tapPixelX)
        assertEquals(1400, pending?.tapPixelY)
        assertEquals(Pair(600, 1400), turnCtx.pendingCoordinates)
    }

    @Test
    fun testTwoPhaseConfirmationExecutesPendingActionCoordinates() {
        // 設定先前的待確認動作
        ConversationManager.updateTurnContext(
            ConversationTurnContext(
                lastTargetApp = "com.example.shopping",
                pendingSafetyAction = PendingSafetyAction(
                    label = "提交訂單",
                    targetMarkId = 9,
                    tapPixelX = 720,
                    tapPixelY = 1600,
                    gridX = 360,
                    gridY = 800,
                    opcode = "C! -> #9 @360,800",
                    packageName = "com.example.shopping"
                )
            )
        )

        val report = createReportWithNode(makeNode("無關節點"))

        // 使用者於對話框明確回覆「確認執行」
        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "確認執行",
            report = report,
            context = null
        )

        assertEquals(AiActionType.TAP, decision.actionType)
        assertEquals(720, decision.tapPixel?.x)
        assertEquals(1600, decision.tapPixel?.y)
        assertEquals(360, decision.tapGrid0to999?.x)
        assertEquals(800, decision.tapGrid0to999?.y)
        assertEquals(9, decision.targetMarkId)
        assertTrue(decision.reasoningTrace.contains("安全閘門二次確認通過"))

        // 待辦動作應已清除
        val afterCtx = ConversationManager.getTurnContext()
        assertNull(afterCtx.pendingSafetyAction)
    }

    @Test
    fun testTwoPhaseCancellationClearsPendingActionWithoutTapping() {
        ConversationManager.updateTurnContext(
            ConversationTurnContext(
                lastTargetApp = "com.example.shopping",
                pendingSafetyAction = PendingSafetyAction(
                    label = "解除安裝",
                    targetMarkId = 15,
                    tapPixelX = 500,
                    tapPixelY = 1000
                )
            )
        )

        val report = createReportWithNode(makeNode("無關節點"))

        // 使用者於對話框回覆「取消」
        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "取消",
            report = report,
            context = null
        )

        assertEquals(AiActionType.ANSWER_QUESTION, decision.actionType)
        assertNull(decision.tapPixel)
        assertTrue(decision.reasoningTrace.contains("已取消高風險操作"))

        // 待辦動作應已清除
        val afterCtx = ConversationManager.getTurnContext()
        assertNull(afterCtx.pendingSafetyAction)
    }

    @Test
    fun testConfirmExecutionWhenNoPendingAction() {
        val report = createReportWithNode(makeNode("普通按鈕"))
        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "確認執行",
            report = report,
            context = null
        )

        assertEquals(AiActionType.ANSWER_QUESTION, decision.actionType)
        assertNull(decision.tapPixel)
        assertTrue(decision.reasoningTrace.contains("目前沒有等待確認的高風險操作"))
    }
}
