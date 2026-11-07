package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.e2e.TestHelpers
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage1OccludedNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RichGesturesModelTest {

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
            className = "android.widget.Button",
            shortTypeTag = "BTN",
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = Rect(x - 50, y - 50, x + 50, y + 50),
            convergedBounds = Rect(x - 50, y - 50, x + 50, y + 50),
            finalTapPx = Point(x, y),
            gridTapPoint = Point(x / 2, y / 2),
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
            packageName = "com.example.testapp",
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
    fun testAiActionTypeEnumCoverage() {
        val types = AiActionType.values()
        assertTrue("Must include all expanded action types (at least 13)", types.size >= 13)

        assertEquals("長按元件 (LONG_PRESS)", AiActionType.LONG_PRESS.label)
        assertEquals("雙擊元件 (DOUBLE_TAP)", AiActionType.DOUBLE_TAP.label)
        assertEquals("向左滑動 (SWIPE_LEFT)", AiActionType.SWIPE_LEFT.label)
        assertEquals("向右滑動 (SWIPE_RIGHT)", AiActionType.SWIPE_RIGHT.label)

        for (t in types) {
            assertTrue("Label must not be empty", t.label.isNotEmpty())
            TestHelpers.assertZeroEmojis(t.label, "AiActionType.${t.name}")
        }
    }

    @Test
    fun testAndroidNativeAiEngine_parsesSwipeLeft() {
        val rep = createReport(emptyList())
        val goals = listOf("向左滑", "往左滑", "左滑", "左翻", "swipe left")
        for (g in goals) {
            val dec = AndroidNativeAiEngine.decide(g, rep, null)
            assertEquals("Goal '$g' should parse to SWIPE_LEFT", AiActionType.SWIPE_LEFT, dec.actionType)
            assertEquals("SL", dec.compactOpcode)
            TestHelpers.assertZeroEmojis(dec.reasoningTrace, "SWIPE_LEFT reasoningTrace")
        }
    }

    @Test
    fun testAndroidNativeAiEngine_parsesSwipeRight() {
        val rep = createReport(emptyList())
        val goals = listOf("向右滑", "往右滑", "右滑", "右翻", "swipe right")
        for (g in goals) {
            val dec = AndroidNativeAiEngine.decide(g, rep, null)
            assertEquals("Goal '$g' should parse to SWIPE_RIGHT", AiActionType.SWIPE_RIGHT, dec.actionType)
            assertEquals("SR", dec.compactOpcode)
            TestHelpers.assertZeroEmojis(dec.reasoningTrace, "SWIPE_RIGHT reasoningTrace")
        }
    }

    @Test
    fun testAndroidNativeAiEngine_parsesLongPressWithTargetNode() {
        val node1 = makeNode("btn1", "確定", 1)
        val node2 = makeNode("btn2", "取消", 2)
        val rep = createReport(listOf(node1, node2))

        val dec = AndroidNativeAiEngine.decide("長按確定", rep, null)
        assertEquals(AiActionType.LONG_PRESS, dec.actionType)
        assertEquals(1, dec.targetMarkId)
        assertTrue("Opcode must start with LP", dec.compactOpcode.startsWith("LP"))
        TestHelpers.assertZeroEmojis(dec.compactOpcode, "LONG_PRESS opcode")
        TestHelpers.assertZeroEmojis(dec.reasoningTrace, "LONG_PRESS trace")
    }

    @Test
    fun testAndroidNativeAiEngine_parsesDoubleTapWithTargetNode() {
        val node1 = makeNode("btn1", "相簿", 1)
        val node2 = makeNode("btn2", "設定", 2)
        val rep = createReport(listOf(node1, node2))

        val dec = AndroidNativeAiEngine.decide("雙擊相簿", rep, null)
        assertEquals(AiActionType.DOUBLE_TAP, dec.actionType)
        assertEquals(1, dec.targetMarkId)
        assertTrue("Opcode must start with DT", dec.compactOpcode.startsWith("DT"))
        TestHelpers.assertZeroEmojis(dec.compactOpcode, "DOUBLE_TAP opcode")
        TestHelpers.assertZeroEmojis(dec.reasoningTrace, "DOUBLE_TAP trace")
    }

    @Test
    fun testDualLocalAiCoordinator_parsesFollowUpSwipeGestures() {
        val rep = createReport(emptyList())

        val decLeft = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.ANDROID_NATIVE_AI,
            userGoal = "往左滑",
            report = rep,
            context = null,
            currentForegroundPackage = "com.example.testapp"
        )
        assertEquals(AiActionType.SWIPE_LEFT, decLeft.actionType)
        assertEquals("SL", decLeft.compactOpcode)

        val decRight = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.ANDROID_NATIVE_AI,
            userGoal = "往右滑",
            report = rep,
            context = null,
            currentForegroundPackage = "com.example.testapp"
        )
        assertEquals(AiActionType.SWIPE_RIGHT, decRight.actionType)
        assertEquals("SR", decRight.compactOpcode)
    }

    @Test
    fun testDualLocalAiCoordinator_decomposesGestureSubGoals() {
        val goals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(
            rawGoal = "打開相簿然後向左滑接著長按第一張照片",
            context = null,
            lockToCurrentApp = false
        )
        assertTrue("Must decompose into multiple steps", goals.size >= 2)

        val swipeStep = goals.find { it.intentCategory == "GESTURE_SWIPE" }
        assertNotNull("Must identify GESTURE_SWIPE intent category", swipeStep)

        val longPressStep = goals.find { it.intentCategory == "LONG_PRESS" }
        assertNotNull("Must identify LONG_PRESS intent category", longPressStep)
    }

    @Test
    fun testZeroEmojiComplianceAcrossAllGestures() {
        for (type in AiActionType.values()) {
            assertFalse(TestHelpers.containsEmoji(type.name))
            assertFalse(TestHelpers.containsEmoji(type.label))
        }
    }
}
