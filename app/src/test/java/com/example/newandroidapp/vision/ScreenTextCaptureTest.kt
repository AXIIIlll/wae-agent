package com.example.newandroidapp.vision

import android.graphics.Point
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextCaptureTest {

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    private fun stage1(
        id: String,
        text: String,
        top: Int,
        left: Int,
        culled: Boolean = false,
        pkg: String = "com.android.settings",
        desc: String = "",
        isPassword: Boolean = false,
        className: String = "android.widget.TextView"
    ): Stage1OccludedNode {
        val b = rect(left, top, left + 200, top + 60)
        return Stage1OccludedNode(
            raw = RawVisionNode(
                id = id,
                windowId = 1,
                windowLayer = 0,
                zOrderRank = 0,
                className = className,
                packageName = pkg,
                bounds = b,
                text = text,
                contentDescription = desc,
                isPassword = isPassword
            ),
            visibleRatio = if (culled) 0f else 1f,
            isCulled = culled,
            wasPartiallyOccluded = false,
            safeTapPointPx = Point(left + 100, top + 30).apply { x = left + 100; y = top + 30 },
            visibleBoundingBox = b
        )
    }

    private fun report(
        s1: List<Stage1OccludedNode>,
        finals: List<Stage2ConvergedNode> = emptyList()
    ): VisionPipelineReport = VisionPipelineReport(
        timestampMs = 1L,
        screenWidth = 1080,
        screenHeight = 2400,
        gridCols = 10,
        gridRows = 20,
        occupancyGridSnapshot = ByteArray(0),
        totalRawNodes = s1.size,
        culledByOcclusionCount = 0,
        partiallyOccludedShiftedCount = 0,
        transparentOverlayBypassedCount = 0,
        hotspotConvergedCount = 0,
        spatialBoundIconCount = 0,
        geometricIconClassifiedCount = 0,
        roiRecoveredCount = 0,
        foldedListGroupCount = 0,
        temporalStabilizedCount = 0,
        stage1AllNodes = s1,
        detectedRoiVoids = emptyList(),
        finalNodes = finals,
        markIdToPixelMap = emptyMap(),
        compressedPromptText = "",
        deltaPromptText = "",
        rawCharCount = 1,
        compressedCharCount = 1,
        compressionRatioPercent = 0,
        pipelineLatencyMs = 1L
    )

    private fun ocrNode(label: String, top: Int, left: Int): Stage2ConvergedNode {
        val b = rect(left, top, left + 200, top + 60)
        return Stage2ConvergedNode(
            nodeId = "ocr_$top",
            shortMarkId = 1,
            className = "ROI",
            shortTypeTag = "ROI",
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = b,
            convergedBounds = b,
            finalTapPx = Point(left + 100, top + 30).apply { x = left + 100; y = top + 30 },
            gridTapPoint = Point(10, 10).apply { x = 10; y = 10 },
            visibleRatio = 1f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.ROI_OCR_TEXT
        )
    }

    @Test
    fun `captures visible text in reading order and removes duplicates`() {
        val rep = report(
            listOf(
                stage1("c", "第三行", top = 600, left = 50),
                stage1("b", "右邊", top = 300, left = 500),
                stage1("a", "左邊", top = 305, left = 50),
                stage1("d", "第三行", top = 900, left = 50)
            )
        )
        val result = ScreenTextCapture.capture(rep) as ScreenTextCapture.Result.Captured
        assertEquals(listOf("左邊", "右邊", "第三行"), result.lines)
        assertEquals("左邊\n右邊\n第三行", result.text)
    }

    @Test
    fun `skips culled nodes and own overlay`() {
        val rep = report(
            listOf(
                stage1("keep", "可見文字", top = 100, left = 50),
                stage1("culled", "被遮住的字", top = 200, left = 50, culled = true),
                stage1("own", "懸浮球文字", top = 300, left = 50, pkg = "com.localai.visionassistant")
            )
        )
        val result = ScreenTextCapture.capture(rep) as ScreenTextCapture.Result.Captured
        assertEquals(listOf("可見文字"), result.lines)
    }

    @Test
    fun `adds content description only when different and includes OCR text with decorations removed`() {
        val rep = report(
            listOf(
                stage1("same", "搜尋", top = 100, left = 50, desc = "搜尋"),
                stage1("icon", "", top = 200, left = 50, desc = "返回")
            ),
            finals = listOf(ocrNode("[視覺OCR]圖片裡的字", top = 400, left = 50))
        )
        val result = ScreenTextCapture.capture(rep) as ScreenTextCapture.Result.Captured
        assertEquals(listOf("搜尋", "返回", "圖片裡的字"), result.lines)
    }

    @Test
    fun `returns Empty when there is no text and null report`() {
        assertTrue(ScreenTextCapture.capture(null) is ScreenTextCapture.Result.Empty)
        assertTrue(ScreenTextCapture.capture(report(emptyList())) is ScreenTextCapture.Result.Empty)
        assertNull(ScreenTextCapture.lastCapture)
    }

    @Test
    fun `refuses to capture on sensitive human handover screens and clears last capture`() {
        val normal = report(listOf(stage1("a", "一般文字", top = 100, left = 50)))
        assertTrue(ScreenTextCapture.capture(normal) is ScreenTextCapture.Result.Captured)

        val pwdField = report(
            listOf(
                stage1("a", "請輸入", top = 100, left = 50),
                stage1("p", "", top = 200, left = 50, isPassword = true, className = "android.widget.EditText")
            )
        )
        val blocked = ScreenTextCapture.capture(pwdField)
        assertTrue(blocked is ScreenTextCapture.Result.Blocked)
        assertNull(ScreenTextCapture.lastCapture)
        assertTrue(ScreenTextCapture.toReplyText(blocked).contains("隱私"))
    }

    @Test
    fun `recognizes capture requests without hijacking ordinary screen questions`() {
        assertTrue(ScreenTextCapture.isCaptureRequest("擷取螢幕文字"))
        assertTrue(ScreenTextCapture.isCaptureRequest("把畫面上的文字讀出來"))
        assertTrue(ScreenTextCapture.isCaptureRequest("螢幕上寫什麼"))
        assertFalse(ScreenTextCapture.isCaptureRequest("這頁有什麼重點"))
        assertFalse(ScreenTextCapture.isCaptureRequest("打開設定"))
        assertFalse(ScreenTextCapture.isCaptureRequest(""))
    }

    @Test
    fun `reply text keeps full content and truncates only very long text`() {
        val short = ScreenTextCapture.Result.Captured("很長很長的一段文字內容超過二十個字也要完整保留", listOf("x"))
        assertEquals(short.text, ScreenTextCapture.toReplyText(short))

        val long = ScreenTextCapture.Result.Captured("a".repeat(2000), listOf("x"))
        val reply = ScreenTextCapture.toReplyText(long, maxChars = 100)
        assertTrue(reply.length < 200)
        assertTrue(reply.contains("已截斷"))
    }
}

