package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.chat.ConversationTurnContext
import com.example.newandroidapp.chat.ScreenCandidateItem
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage1OccludedNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * 驗證功能 3.1：多輪對話口語追問 (Multi-Turn Follow-Up) 測試
 * 涵蓋簡潔指令直達動作：「好」、「點第二個」、「往下滑找別的」等
 */
class MultiTurnFollowUpTest {

    @Before
    fun setUp() {
        ConversationManager.clearConversation()
    }

    @After
    fun tearDown() {
        ConversationManager.clearConversation()
    }

    private fun makeNode(
        id: String,
        label: String,
        shortMarkId: Int,
        x: Int = 300,
        y: Int = 400
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = shortMarkId,
            className = "android.widget.TextView",
            shortTypeTag = "TXT",
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = Rect(x - 50, y - 50, x + 50, y + 50),
            convergedBounds = Rect(x - 50, y - 50, x + 50, y + 50),
            finalTapPx = Point(x, y).apply { this.x = x; this.y = y },
            gridTapPoint = Point(x / 2, y / 2).apply { this.x = x / 2; this.y = y / 2 },
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
    }

    private fun createReport(nodes: List<Stage2ConvergedNode>): VisionPipelineReport {
        val raw = RawVisionNode(
            id = "raw_root",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = "android.widget.FrameLayout",
            packageName = "com.google.android.youtube",
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
            stage1AllNodes = listOf(s1Node),
            detectedRoiVoids = emptyList(),
            finalNodes = nodes,
            markIdToPixelMap = nodes.associate { it.shortMarkId to it.finalTapPx },
            compressedPromptText = "MOCK",
            deltaPromptText = "",
            rawCharCount = 50,
            compressedCharCount = 20,
            compressionRatioPercent = 60,
            pipelineLatencyMs = 10L
        )
    }

    @Test
    fun testCandidateIndexResolutionHelper() {
        // 驗證「好」及其同義詞直接命中候選 1
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("好"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("好的"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("好啊"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("好喔"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("行"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("可以"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("就這個"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("OK"))

        // 驗證序數指令
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("點第一個"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("選第一個"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("第一個"))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("1"))

        assertEquals(2, AndroidNativeAiEngine.checkCandidateFollowUp("點第二個"))
        assertEquals(2, AndroidNativeAiEngine.checkCandidateFollowUp("選第二個"))
        assertEquals(2, AndroidNativeAiEngine.checkCandidateFollowUp("第二個"))
        assertEquals(2, AndroidNativeAiEngine.checkCandidateFollowUp("第2個"))
        assertEquals(2, AndroidNativeAiEngine.checkCandidateFollowUp("2"))

        assertEquals(3, AndroidNativeAiEngine.checkCandidateFollowUp("點第三個"))
        assertEquals(4, AndroidNativeAiEngine.checkCandidateFollowUp("選第四個"))
        assertEquals(5, AndroidNativeAiEngine.checkCandidateFollowUp("第5個"))
    }

    @Test
    fun testMultiTurnFollowUpAffirmationExecutesCandidateOne() {
        val node1 = makeNode("video_1", "周杰倫 - 最偉大的作品", 10, x = 300, y = 400)
        val node2 = makeNode("video_2", "周杰倫 - 晴天", 11, x = 300, y = 600)
        val report = createReport(listOf(node1, node2))

        ConversationManager.updateTurnContext(
            ConversationTurnContext(
                lastTargetApp = "com.google.android.youtube",
                lastSearchQuery = "周杰倫",
                lastCandidates = listOf(
                    ScreenCandidateItem(1, "周杰倫 - 最偉大的作品", Rect(250, 350, 350, 450), "video_1"),
                    ScreenCandidateItem(2, "周杰倫 - 晴天", Rect(250, 550, 350, 650), "video_2")
                )
            )
        )

        // 使用者只回答「好」
        val decision = AndroidNativeAiEngine.decide("好", report, null)
        assertEquals(AiActionType.TAP, decision.actionType)
        assertNotNull(decision.targetNode)
        assertEquals("video_1", decision.targetNode?.nodeId)
        assertEquals(300, decision.tapPixel?.x)
        assertEquals(400, decision.tapPixel?.y)

        // 驗證單指令簡化模式：ProactiveReportingHelper 完成回報為「已經幫你弄好了。」，不再詢問「自己看要選哪個」
        val (reportText, _) = com.example.newandroidapp.chat.ProactiveReportingHelper.generateProactiveReport(
            report = report,
            actionSummary = "周杰倫",
            isCompleted = true
        )
        assertEquals("已經幫你弄好了。", reportText)
    }

    @Test
    fun testMultiTurnFollowUpSelectSecondCandidate() {
        val node1 = makeNode("hotel_1", "晶華酒店 豪華客房", 20, x = 200, y = 300)
        val node2 = makeNode("hotel_2", "喜來登大飯店 行政套房", 21, x = 200, y = 500)
        val report = createReport(listOf(node1, node2))

        ConversationManager.updateTurnContext(
            ConversationTurnContext(
                lastTargetApp = "com.booking",
                lastSearchQuery = "台北五星飯店",
                lastCandidates = listOf(
                    ScreenCandidateItem(1, "晶華酒店 豪華客房", null, "hotel_1"),
                    ScreenCandidateItem(2, "喜來登大飯店 行政套房", null, "hotel_2")
                )
            )
        )

        // 使用者說「點第二個」
        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "點第二個",
            report = report,
            context = null
        )
        assertEquals(AiActionType.TAP, decision.actionType)
        assertEquals("hotel_2", decision.targetNode?.nodeId)
        assertEquals(200, decision.tapPixel?.x)
        assertEquals(500, decision.tapPixel?.y)
    }

    @Test
    fun testMultiTurnFollowUpScrollDownDirectAction() {
        val report = createReport(emptyList())

        // 使用者說「往下滑」
        val dec1 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "往下滑",
            report = report,
            context = null
        )
        assertEquals(AiActionType.SCROLL_DOWN, dec1.actionType)
        assertEquals("SD", dec1.compactOpcode)

        // 使用者說「往下滑找別的」
        val dec2 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "往下滑找別的",
            report = report,
            context = null
        )
        assertEquals(AiActionType.SCROLL_DOWN, dec2.actionType)

        // 使用者說「往上滑」
        val dec3 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "往上滑",
            report = report,
            context = null
        )
        assertEquals(AiActionType.SCROLL_UP, dec3.actionType)
        assertEquals("SU", dec3.compactOpcode)
    }
}
