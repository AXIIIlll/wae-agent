package com.example.newandroidapp.chat

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage1OccludedNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.regex.Pattern

/**
 * 驗證功能 3.1：搜尋與任務完成後之主動回報 (Proactive Reporting) 與候選清單提煉測試
 */
class ProactiveReportingTest {

    private val emojiPattern = Pattern.compile("[\\u2600-\\u27BF]|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]")

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
        tag: String = "BTN",
        visibleRatio: Float = 1.0f,
        gy: Int = 200,
        gx: Int = 300
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = shortMarkId,
            className = "android.widget.TextView",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = Rect(100, 100, 300, 200),
            convergedBounds = Rect(100, 100, 300, 200),
            finalTapPx = Point(200, 150),
            gridTapPoint = Point(gx, gy),
            visibleRatio = visibleRatio,
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
            pipelineLatencyMs = 8L
        )
    }

    @Test
    fun testExtractTopCandidatesFiltersNoiseAndSortsByReadingOrder() {
        val nodes = listOf(
            makeNode("n1", "周杰倫 - 最偉大的作品 MV", 1, gy = 300, gx = 100),
            makeNode("n2", "圖示:播放", 2, gy = 100, gx = 50), // 應過濾圖示前綴
            makeNode("n3", "a", 3, gy = 200, gx = 50), // 字元長度不足過濾
            makeNode("n4", "滾動容器", 4, tag = "SCR", gy = 150, gx = 50), // SCR 過濾
            makeNode("n5", "周杰倫 - 晴天 官方高清版", 5, gy = 400, gx = 100),
            makeNode("n6", "周杰倫 - 稻香 4K修復", 6, gy = 500, gx = 100)
        )
        val report = createReport(nodes)
        val candidates = ProactiveReportingHelper.extractTopCandidates(report, maxCount = 5)

        assertEquals(3, candidates.size)
        assertEquals(1, candidates[0].index)
        assertEquals("周杰倫 - 最偉大的作品 MV", candidates[0].label)
        assertEquals(2, candidates[1].index)
        assertEquals("周杰倫 - 晴天 官方高清版", candidates[1].label)
        assertEquals(3, candidates[2].index)
        assertEquals("周杰倫 - 稻香 4K修復", candidates[2].label)
    }

    @Test
    fun testGenerateProactiveReportTraditionalChineseSynthesis() {
        val nodes = listOf(
            makeNode("n1", "全聯福利中心 大安店", 1, gy = 100),
            makeNode("n2", "家樂福超市 忠孝東路店", 2, gy = 200)
        )
        val report = createReport(nodes)
        val (reportText, candidates) = ProactiveReportingHelper.generateProactiveReport(
            report = report,
            actionSummary = "搜尋附近超市",
            isCompleted = true
        )

        assertEquals(2, candidates.size)
        assertTrue(reportText.length <= 20)
        assertTrue(reportText.contains("弄好了"))

        // F15 Zero-Emoji 規範驗證
        assertFalse("Proactive report text must not contain emoji", emojiPattern.matcher(reportText).find())
    }

    @Test
    fun testGenerateProactiveReportWhenNoCandidatesFound() {
        val report = createReport(emptyList())
        val (reportText, candidates) = ProactiveReportingHelper.generateProactiveReport(
            report = report,
            actionSummary = "清理背景快取",
            isCompleted = true
        )

        assertEquals(0, candidates.size)
        assertTrue(reportText.length <= 20)
        assertTrue(reportText.contains("弄好了"))
        assertFalse(emojiPattern.matcher(reportText).find())
    }

    @Test
    fun testPostProactiveReportUpdatesConversationManagerAndContext() {
        val nodes = listOf(
            makeNode("n1", "高鐵 0612 車次 自由座", 1, gy = 120),
            makeNode("n2", "高鐵 0618 車次 對號座", 2, gy = 240)
        )
        val report = createReport(nodes)

        val msg = ProactiveReportingHelper.postProactiveReport(
            context = null,
            report = report,
            actionSummary = "查詢下午高鐵班次",
            isCompleted = true
        )

        assertTrue(msg.isProactiveReport)
        assertEquals(ChatRole.AI, msg.role)
        assertEquals(2, msg.candidates.size)

        val turnContext = ConversationManager.getTurnContext()
        assertEquals("查詢下午高鐵班次", turnContext.lastSearchQuery)
        assertEquals(2, turnContext.lastCandidates.size)
        assertEquals("高鐵 0612 車次 自由座", turnContext.lastCandidates[0].label)
    }
}
