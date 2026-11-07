package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.chat.ConversationTurnContext
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
 * 驗證功能 3.1：Zero-Tap 畫面重點問答、進度詢問與日常算術問答測試
 * 核心標準：只回覆問答，不產生點擊或滾動手勢 (tapPixel == null, actionType == ANSWER_QUESTION)
 */
class ZeroTapQueryTest {

    @Before
    fun setUp() {
        ConversationManager.clearConversation()
    }

    @After
    fun tearDown() {
        ConversationManager.clearConversation()
    }

    private fun createReportWithElements(labels: List<String>): VisionPipelineReport {
        val raw = RawVisionNode(
            id = "raw_root",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = "android.widget.FrameLayout",
            packageName = "com.android.settings",
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
        val nodes = labels.mapIndexed { idx, label ->
            Stage2ConvergedNode(
                nodeId = "node_$idx",
                shortMarkId = idx + 1,
                className = "android.widget.TextView",
                shortTypeTag = "TXT",
                label = label,
                inlineStatePrefix = "",
                rawOriginalBounds = Rect(100, 100 * idx, 400, 100 * idx + 80),
                convergedBounds = Rect(100, 100 * idx, 400, 100 * idx + 80),
                finalTapPx = Point(200, 100 * idx + 40),
                gridTapPoint = Point(200, 100 * idx + 40),
                visibleRatio = 1.0f,
                wasHotspotConverged = false,
                absorbedTextIds = emptyList(),
                sourceType = NodeSourceType.UI_TREE
            )
        }
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
    fun testArithmeticCalculationsZeroTap() {
        val report = createReportWithElements(emptyList())

        val dec1 = AndroidNativeAiEngine.decide("5 加 3 等於多少", report, null)
        assertEquals(AiActionType.ANSWER_QUESTION, dec1.actionType)
        assertNull(dec1.tapPixel)
        assertTrue(dec1.reasoningTrace.contains("8"))

        val dec2 = AndroidNativeAiEngine.decide("100 - 25", report, null)
        assertEquals(AiActionType.ANSWER_QUESTION, dec2.actionType)
        assertTrue(dec2.reasoningTrace.contains("75"))

        val dec3 = AndroidNativeAiEngine.decide("12 乘以 8", report, null)
        assertEquals(AiActionType.ANSWER_QUESTION, dec3.actionType)
        assertTrue(dec3.reasoningTrace.contains("96"))

        val dec4 = AndroidNativeAiEngine.decide("10 除以 2", report, null)
        assertEquals(AiActionType.ANSWER_QUESTION, dec4.actionType)
        assertTrue(dec4.reasoningTrace.contains("5"))

        val decDivideZero = AndroidNativeAiEngine.decide("10 除以 0", report, null)
        assertEquals(AiActionType.ANSWER_QUESTION, decDivideZero.actionType)
        assertTrue(decDivideZero.reasoningTrace.contains("零"))
    }

    @Test
    fun testTaskProgressInquiryZeroTap() {
        ConversationManager.updateTurnContext(
            ConversationTurnContext(
                lastTargetApp = "com.google.android.youtube",
                lastSearchQuery = "播放周杰倫最新專輯",
                lastAiPrompt = "已為您在 YouTube 搜尋周杰倫，畫面中發現 3 首歌曲"
            )
        )

        val report = createReportWithElements(emptyList())

        // 測試「剛剛做到了哪一步？」
        val dec1 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "剛剛做到了哪一步？",
            report = report,
            context = null
        )
        assertEquals(AiActionType.ANSWER_QUESTION, dec1.actionType)
        assertNull(dec1.tapPixel)
        assertTrue(dec1.inputTextValue!!.length <= 20)
        assertTrue(dec1.reasoningTrace.contains("播放周杰倫"))

        // 測試「目前進度」
        val dec2 = AndroidNativeAiEngine.decide("目前進度", report, null)
        assertEquals(AiActionType.ANSWER_QUESTION, dec2.actionType)
        assertNull(dec2.tapPixel)
        assertTrue(dec2.inputTextValue!!.length <= 20)
    }

    @Test
    fun testScreenFocusAndOptionsInquiryZeroTap() {
        val report = createReportWithElements(listOf("Wi-Fi 網路", "藍牙", "應用程式管理", "電池與效能"))

        // 測試「現在畫面上有什麼重點？」
        val dec1 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "現在畫面上有什麼重點？",
            report = report,
            context = null
        )
        assertEquals(AiActionType.ANSWER_QUESTION, dec1.actionType)
        assertNull(dec1.tapPixel)
        assertTrue(dec1.inputTextValue!!.length <= 20)
        assertTrue(dec1.reasoningTrace.contains("Wi-Fi 網路"))

        // 測試「這頁有哪些選項？」
        val dec2 = AndroidNativeAiEngine.decide("這頁有哪些選項？", report, null)
        assertEquals(AiActionType.ANSWER_QUESTION, dec2.actionType)
        assertNull(dec2.tapPixel)
        assertTrue(dec2.inputTextValue!!.length <= 20)
        assertTrue(dec2.reasoningTrace.contains("Wi-Fi 網路"))
    }

    @Test
    fun testChitChatZeroTap() {
        val report = createReportWithElements(emptyList())
        val dec = AndroidNativeAiEngine.decide("你好", report, null)
        assertEquals(AiActionType.ANSWER_QUESTION, dec.actionType)
        assertNull(dec.tapPixel)
        assertTrue(dec.inputTextValue!!.length <= 20)
        assertTrue(dec.reasoningTrace.contains("有事快說"))
    }

    @Test
    fun testMinimalistGeminiStyleConversationalReplyAndActionSeparation() {
        val report = createReportWithElements(listOf("搜尋", "首頁", "熱門"))

        // 1. 一般問候與功能詢問（無需執行鍵，且字數 <= 20 字）
        val (helloReply, helloCmd) = AndroidNativeAiEngine.generateConversationalReply("你可以幫我做什麼？", report, null)
        assertTrue(helloReply.length <= 20)
        assertNull(helloCmd)

        // 2. 算術問答（無需執行鍵，即使 report 為 null 也能算）
        val (mathReply, mathCmd) = AndroidNativeAiEngine.generateConversationalReply("25 * 4", null, null)
        assertTrue(mathReply.contains("100"))
        assertTrue(mathReply.length <= 20)
        assertNull(mathCmd)

        // 3. 手機操作指令在「發送（純對話）」模式下：回覆準備就緒並回傳可執行指令供「執行」鍵觸發
        val (actionReply, actionCmd) = AndroidNativeAiEngine.generateConversationalReply("打開 YouTube 搜尋周杰倫", report, null)
        assertTrue(actionReply.length <= 20)
        assertTrue(actionReply.contains("執行"))
        assertEquals("打開 YouTube 搜尋周杰倫", actionCmd)

        // 4. 任意開放式日常聊天：自然簡短回覆 (<= 20 字) 且不崩潰
        val (chatReply, chatCmd) = AndroidNativeAiEngine.generateConversationalReply("今天天氣很適合聽音樂", report, null)
        assertNotNull(chatReply)
        assertTrue(chatReply.length <= 20)
        assertEquals("今天天氣很適合聽音樂", chatCmd)
    }
}
