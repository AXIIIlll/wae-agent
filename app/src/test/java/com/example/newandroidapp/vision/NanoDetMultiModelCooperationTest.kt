package com.example.newandroidapp.vision

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.ai.DualLocalAiCoordinator
import com.example.newandroidapp.ai.LocalAiBackendMode
import com.example.newandroidapp.stability.InAppOperationPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NanoDetMultiModelCooperationTest {

    private fun pt(x: Int, y: Int): Point = Point(x, y).apply { this.x = x; this.y = y }

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    @Test
    fun testNanoDetWeightAssetUnder20MBAndValidHeader() {
        val candidates = listOf(
            File("src/main/assets/${NanoDetUiDetector.ASSET_WEIGHT_FILE}"),
            File("app/src/main/assets/${NanoDetUiDetector.ASSET_WEIGHT_FILE}")
        )
        val weightFile = candidates.firstOrNull { it.exists() }
        assertNotNull("NanoDet-Plus-m INT8 權重檔必須存在於 assets 目錄", weightFile)
        val sizeBytes = weightFile!!.length()
        assertTrue("權重檔必須大於 500 KB", sizeBytes > 500 * 1024)
        assertTrue("權重檔必須嚴格小於 20 MB (實際=${sizeBytes / 1024} KB)", sizeBytes < 20 * 1024 * 1024)

        val header = weightFile.inputStream().use { input ->
            val buf = ByteArray(8)
            input.read(buf)
            String(buf, Charsets.US_ASCII)
        }
        assertEquals("NDETUI08", header)
        assertTrue(NanoDetUiDetector.ensureWeightsLoaded(null))
        assertTrue(NanoDetUiDetector.getModelStatusSummary().contains("MicroUI-Net INT8"))
    }

    @Test
    fun testNanoDet12UiClassesMappingToActionableTypes() {
        assertEquals("BTN", NanoDetUiDetector.NanoDetUiClass.UI_CLOSE_X.shortTypeTag)
        assertEquals("INP", NanoDetUiDetector.NanoDetUiClass.UI_SEARCH_BAR.shortTypeTag)
        assertEquals("BTN", NanoDetUiDetector.NanoDetUiClass.UI_BUTTON_CTA.shortTypeTag)
        assertEquals("CHK", NanoDetUiDetector.NanoDetUiClass.UI_CHECK_SWITCH.shortTypeTag)
        assertEquals("ICO", NanoDetUiDetector.NanoDetUiClass.UI_ICON_BACK.shortTypeTag)
        assertEquals("ICO", NanoDetUiDetector.NanoDetUiClass.UI_ICON_MENU_MORE.shortTypeTag)
        assertEquals("SCR", NanoDetUiDetector.NanoDetUiClass.UI_SLIDER_BAR.shortTypeTag)
    }

    @Test
    fun testCooperation1_PureVisualPopupCloseXDismissedByBlockerRadar() {
        InAppOperationPlanner.resetForNewGoal("搜尋藍牙耳機")

        val nanoDetCloseNode = Stage2ConvergedNode(
            nodeId = "canvas_nanodet_0",
            shortMarkId = 1,
            className = "NanoDet.UI_CLOSE_X",
            shortTypeTag = "BTN",
            label = "關閉",
            inlineStatePrefix = "[NanoDet:關閉按鈕X]",
            rawOriginalBounds = rect(920, 180, 1010, 270),
            convergedBounds = rect(920, 180, 1010, 270),
            finalTapPx = pt(965, 225),
            gridTapPoint = pt(893, 96),
            visibleRatio = 1.0f,
            wasHotspotConverged = true,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.NANODET_UI_DETECT,
            visualConfidence = 0.91f,
            nanoDetCategory = "UI_CLOSE_X"
        )

        val report = VisionPipelineReport(
            timestampMs = 1000L,
            screenWidth = 1080,
            screenHeight = 2340,
            gridCols = 120,
            gridRows = 240,
            occupancyGridSnapshot = ByteArray(0),
            totalRawNodes = 1,
            culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0,
            transparentOverlayBypassedCount = 0,
            hotspotConvergedCount = 1,
            spatialBoundIconCount = 0,
            geometricIconClassifiedCount = 1,
            roiRecoveredCount = 1,
            nanoDetDetectedCount = 1,
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = emptyList(),
            detectedRoiVoids = emptyList(),
            finalNodes = listOf(nanoDetCloseNode),
            markIdToPixelMap = mapOf(1 to pt(965, 225)),
            compressedPromptText = "#1 BTN [NanoDet:關閉按鈕X] 關閉 @893,96",
            deltaPromptText = "",
            rawCharCount = 40,
            compressedCharCount = 36,
            compressionRatioPercent = 10,
            pipelineLatencyMs = 8L
        )

        val blocker = InAppOperationPlanner.detectTopLayerBlocker(report, "搜尋藍牙耳機")
        assertNotNull("NanoDet 偵測出的高信心度 UI_CLOSE_X 應被頂層彈窗攔截雷達識別", blocker)
        assertEquals(1, blocker?.shortMarkId)

        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "搜尋藍牙耳機",
            report = report,
            context = null
        )
        assertEquals(AiActionType.TAP, decision.actionType)
        assertEquals(1, decision.targetMarkId)
    }

    @Test
    fun testCooperation2_NanoDetVisualSearchBarWeldedWithStageBTextInput() {
        InAppOperationPlanner.resetForNewGoal("搜尋降噪耳機")

        // 模擬無障礙樹沒有 EditText，但由 NanoDet-Plus-m + ML Kit OCR 焊接出一個視覺搜尋框 (`shortTypeTag = "INP"`)
        val weldedSearchBar = Stage2ConvergedNode(
            nodeId = "void_nanodet_0",
            shortMarkId = 2,
            className = "NanoDet.UI_SEARCH_BAR",
            shortTypeTag = "INP",
            label = "輸入商品關鍵字",
            inlineStatePrefix = "[NanoDet:搜尋輸入框]",
            rawOriginalBounds = rect(80, 160, 980, 280),
            convergedBounds = rect(80, 160, 980, 280),
            finalTapPx = pt(530, 220),
            gridTapPoint = pt(490, 94),
            visibleRatio = 1.0f,
            wasHotspotConverged = true,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.NANODET_UI_DETECT,
            visualConfidence = 0.94f,
            nanoDetCategory = "UI_SEARCH_BAR"
        )

        val report = VisionPipelineReport(
            timestampMs = 2000L,
            screenWidth = 1080,
            screenHeight = 2340,
            gridCols = 120,
            gridRows = 240,
            occupancyGridSnapshot = ByteArray(0),
            totalRawNodes = 1,
            culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0,
            transparentOverlayBypassedCount = 0,
            hotspotConvergedCount = 1,
            spatialBoundIconCount = 0,
            geometricIconClassifiedCount = 0,
            roiRecoveredCount = 1,
            nanoDetDetectedCount = 1,
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = emptyList(),
            detectedRoiVoids = emptyList(),
            finalNodes = listOf(weldedSearchBar),
            markIdToPixelMap = mapOf(2 to pt(530, 220)),
            compressedPromptText = "#2 INP [NanoDet:搜尋輸入框] 輸入商品關鍵字 @490,94",
            deltaPromptText = "",
            rawCharCount = 50,
            compressedCharCount = 42,
            compressionRatioPercent = 16,
            pipelineLatencyMs = 9L
        )

        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "搜尋降噪耳機",
            report = report,
            context = null
        )

        assertEquals(AiActionType.INPUT_TEXT, decision.actionType)
        assertEquals(2, decision.targetMarkId)
        assertEquals("降噪耳機", decision.inputTextValue)
        assertTrue(decision.cooperativeLog?.stage2NativeFunnel?.contains("五模型協同: NanoDet") == true)
    }

    @Test
    fun testCooperation3_NanoDetVisualCtaButtonWeldedWithTextCommand() {
        InAppOperationPlanner.resetForNewGoal("點擊確認送出")

        val weldedCtaBtn = Stage2ConvergedNode(
            nodeId = "void_nanodet_cta",
            shortMarkId = 5,
            className = "NanoDet.UI_BUTTON_CTA",
            shortTypeTag = "BTN",
            label = "確認送出",
            inlineStatePrefix = "[NanoDet:確認主按鈕]",
            rawOriginalBounds = rect(140, 1820, 940, 1980),
            convergedBounds = rect(140, 1820, 940, 1980),
            finalTapPx = pt(540, 1900),
            gridTapPoint = pt(500, 812),
            visibleRatio = 1.0f,
            wasHotspotConverged = true,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.NANODET_UI_DETECT,
            visualConfidence = 0.93f,
            nanoDetCategory = "UI_BUTTON_CTA"
        )

        val report = VisionPipelineReport(
            timestampMs = 3000L,
            screenWidth = 1080,
            screenHeight = 2340,
            gridCols = 120,
            gridRows = 240,
            occupancyGridSnapshot = ByteArray(0),
            totalRawNodes = 1,
            culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0,
            transparentOverlayBypassedCount = 0,
            hotspotConvergedCount = 1,
            spatialBoundIconCount = 0,
            geometricIconClassifiedCount = 0,
            roiRecoveredCount = 1,
            nanoDetDetectedCount = 1,
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = emptyList(),
            detectedRoiVoids = emptyList(),
            finalNodes = listOf(weldedCtaBtn),
            markIdToPixelMap = mapOf(5 to pt(540, 1900)),
            compressedPromptText = "#5 BTN [NanoDet:確認主按鈕] 確認送出 @500,812",
            deltaPromptText = "",
            rawCharCount = 45,
            compressedCharCount = 38,
            compressionRatioPercent = 15,
            pipelineLatencyMs = 7L
        )

        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "點擊確認送出",
            report = report,
            context = null
        )

        assertEquals(AiActionType.TAP, decision.actionType)
        assertEquals(5, decision.targetMarkId)
        assertEquals(540, decision.tapPixel?.x)
        assertEquals(1900, decision.tapPixel?.y)
    }
}
