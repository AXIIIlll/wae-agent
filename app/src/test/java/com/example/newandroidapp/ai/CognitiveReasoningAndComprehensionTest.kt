package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.vision.HotspotAndSpatialBinder
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage1OccludedNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveReasoningAndComprehensionTest {

    private fun pt(x: Int, y: Int): Point = Point(x, y).apply { this.x = x; this.y = y }

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
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
    fun testUpgrade1_ColloquialCommonsenseTranslation() {
        val brightRes = CognitiveReasoningEngine.resolveColloquialAndContextualGoal("螢幕太亮了很刺眼")
        assertEquals("設定", brightRes.inferredAppHint)
        assertTrue(brightRes.normalizedGoal.contains("亮度"))

        val musicRes = CognitiveReasoningEngine.resolveColloquialAndContextualGoal("我想聽周杰倫的晴天")
        assertEquals("YouTube", musicRes.inferredAppHint)
        assertTrue(musicRes.normalizedGoal.contains("搜尋 周杰倫的晴天"))

        val batteryRes = CognitiveReasoningEngine.resolveColloquialAndContextualGoal("手機快沒電了")
        assertEquals("設定", batteryRes.inferredAppHint)
        assertTrue(batteryRes.normalizedGoal.contains("省電模式"))
    }

    @Test
    fun testUpgrade1_ComparativePriceReasoningSelectsCheapest() {
        InAppOperationPlanner.resetForNewGoal("幫我選最便宜的耳機")

        val item1 = Stage2ConvergedNode(
            nodeId = "item_1",
            shortMarkId = 1,
            className = "android.view.ViewGroup",
            shortTypeTag = "BTN",
            label = "旗艦降噪耳機 NT$ 1,290",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(60, 300, 1020, 520),
            convergedBounds = rect(60, 300, 1020, 520),
            finalTapPx = pt(540, 410),
            gridTapPoint = pt(500, 175),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
        val item2 = Stage2ConvergedNode(
            nodeId = "item_2",
            shortMarkId = 2,
            className = "android.view.ViewGroup",
            shortTypeTag = "BTN",
            label = "輕巧運動藍牙耳機 NT$ 499",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(60, 560, 1020, 780),
            convergedBounds = rect(60, 560, 1020, 780),
            finalTapPx = pt(540, 670),
            gridTapPoint = pt(500, 286),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
        val item3 = Stage2ConvergedNode(
            nodeId = "item_3",
            shortMarkId = 3,
            className = "android.view.ViewGroup",
            shortTypeTag = "BTN",
            label = "電競耳麥 NT$ 890",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(60, 820, 1020, 1040),
            convergedBounds = rect(60, 820, 1020, 1040),
            finalTapPx = pt(540, 930),
            gridTapPoint = pt(500, 397),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )

        val report = makeReport(listOf(item1, item2, item3))
        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "幫我選最便宜的耳機",
            report = report,
            context = null
        )

        assertEquals(AiActionType.TAP, decision.actionType)
        assertEquals(2, decision.targetMarkId)
        assertTrue(decision.cooperativeLog?.stage2NativeFunnel?.contains("比較級數值邏輯推理") == true)
    }

    @Test
    fun testUpgrade2_RowRelationalGrouperBindsSubjectToSwitch() {
        val wifiText = Stage1OccludedNode(
            raw = RawVisionNode(
                id = "txt_wifi",
                windowId = 1,
                windowLayer = 1,
                zOrderRank = 0,
                className = "android.widget.TextView",
                bounds = rect(60, 300, 320, 380),
                text = "Wi-Fi",
                isClickable = false
            ),
            visibleRatio = 1.0f,
            isCulled = false,
            wasPartiallyOccluded = false,
            safeTapPointPx = pt(190, 340),
            visibleBoundingBox = rect(60, 300, 320, 380)
        )
        val wifiSwitch = Stage1OccludedNode(
            raw = RawVisionNode(
                id = "chk_wifi",
                windowId = 1,
                windowLayer = 1,
                zOrderRank = 1,
                className = "android.widget.Switch",
                bounds = rect(880, 300, 1020, 380),
                text = "開關",
                isClickable = true,
                isCheckable = true,
                isChecked = false
            ),
            visibleRatio = 1.0f,
            isCulled = false,
            wasPartiallyOccluded = false,
            safeTapPointPx = pt(950, 340),
            visibleBoundingBox = rect(880, 300, 1020, 380)
        )
        val btText = Stage1OccludedNode(
            raw = RawVisionNode(
                id = "txt_bt",
                windowId = 1,
                windowLayer = 1,
                zOrderRank = 2,
                className = "android.widget.TextView",
                bounds = rect(60, 480, 320, 560),
                text = "藍牙",
                isClickable = false
            ),
            visibleRatio = 1.0f,
            isCulled = false,
            wasPartiallyOccluded = false,
            safeTapPointPx = pt(190, 520),
            visibleBoundingBox = rect(60, 480, 320, 560)
        )
        val btSwitch = Stage1OccludedNode(
            raw = RawVisionNode(
                id = "chk_bt",
                windowId = 1,
                windowLayer = 1,
                zOrderRank = 3,
                className = "android.widget.Switch",
                bounds = rect(880, 480, 1020, 560),
                text = "開關",
                isClickable = true,
                isCheckable = true,
                isChecked = false
            ),
            visibleRatio = 1.0f,
            isCulled = false,
            wasPartiallyOccluded = false,
            safeTapPointPx = pt(950, 520),
            visibleBoundingBox = rect(880, 480, 1020, 560)
        )

        val stage2 = HotspotAndSpatialBinder.process(
            stage1Nodes = listOf(wifiText, wifiSwitch, btText, btSwitch),
            screenWidth = 1080,
            screenHeight = 2340,
            screenBitmap = null
        )

        val btSwitchNode = stage2.convergedNodes.first { it.nodeId == "chk_bt" }
        assertEquals("藍牙 開關", btSwitchNode.label)
    }

    @Test
    fun testUpgrade3_ReActPreconditionSelectsProductSpecBeforeCheckout() {
        InAppOperationPlanner.resetForNewGoal("立即購買")

        val modalHeader = Stage2ConvergedNode(
            nodeId = "hdr_spec",
            shortMarkId = 1,
            className = "android.widget.TextView",
            shortTypeTag = "TXT",
            label = "請選擇規格",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(80, 900, 400, 960),
            convergedBounds = rect(80, 900, 400, 960),
            finalTapPx = pt(240, 930),
            gridTapPoint = pt(222, 397),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
        val specOption = Stage2ConvergedNode(
            nodeId = "btn_spec_black",
            shortMarkId = 2,
            className = "android.widget.Button",
            shortTypeTag = "BTN",
            label = "曜石黑 標準版",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(80, 1050, 480, 1150),
            convergedBounds = rect(80, 1050, 480, 1150),
            finalTapPx = pt(280, 1100),
            gridTapPoint = pt(259, 470),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
        val checkoutBtn = Stage2ConvergedNode(
            nodeId = "btn_buy_now",
            shortMarkId = 3,
            className = "android.widget.Button",
            shortTypeTag = "BTN",
            label = "立即購買",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(100, 1950, 980, 2100),
            convergedBounds = rect(100, 1950, 980, 2100),
            finalTapPx = pt(540, 2025),
            gridTapPoint = pt(500, 865),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )

        val report = makeReport(listOf(modalHeader, specOption, checkoutBtn))
        val eval = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = report,
            userGoal = "立即購買",
            currentSubGoal = "立即購買"
        )

        // 因為尚未選取規格，AI 應先點擊 #2「曜石黑 標準版」滿足前置條件，而非盲點 #3「立即購買」
        assertEquals(AiActionType.TAP, eval.overrideActionType)
        assertEquals(2, eval.overrideTargetNode?.shortMarkId)
        assertTrue(eval.stageReasoningNote.contains("ReAct 前置條件推理"))
    }

    @Test
    fun testUpgrade4_SelfReflectionSimplifiesQueryOnEmptySearchResult() {
        InAppOperationPlanner.resetForNewGoal("搜尋 2026新款官方旗艦店無線耳機")
        com.example.newandroidapp.stability.InAppWorkingMemory.recordTextTypedAndSubmitted("2026新款官方旗艦店無線耳機", "INP|搜尋")

        val searchInp = Stage2ConvergedNode(
            nodeId = "inp_top",
            shortMarkId = 1,
            className = "android.widget.EditText",
            shortTypeTag = "INP",
            label = "2026新款官方旗艦店無線耳機",
            inlineStatePrefix = "[輸入:搜尋]",
            rawOriginalBounds = rect(80, 120, 980, 220),
            convergedBounds = rect(80, 120, 980, 220),
            finalTapPx = pt(530, 170),
            gridTapPoint = pt(490, 72),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
        val emptyHint = Stage2ConvergedNode(
            nodeId = "txt_empty",
            shortMarkId = 2,
            className = "android.widget.TextView",
            shortTypeTag = "TXT",
            label = "找不到相關商品結果",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(200, 800, 880, 900),
            convergedBounds = rect(200, 800, 880, 900),
            finalTapPx = pt(540, 850),
            gridTapPoint = pt(500, 363),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )

        val report = makeReport(listOf(searchInp, emptyHint))
        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "搜尋 2026新款官方旗艦店無線耳機",
            report = report,
            context = null
        )

        assertEquals(AiActionType.INPUT_TEXT, decision.actionType)
        assertEquals("無線耳機", decision.inputTextValue)
        assertTrue(decision.cooperativeLog?.stage2NativeFunnel?.contains("自我反思與動態改道") == true)
    }

    @Test
    fun testStrictVoiceInputBan_GoogleMapsNeverClicksVoiceSearchMic() {
        InAppOperationPlanner.resetForNewGoal("去 Google Map 查一下台北 101 地址")

        val searchBoxNode = Stage2ConvergedNode(
            nodeId = "com.google.android.apps.maps:id/search_omnibox_text_box",
            shortMarkId = 1,
            className = "android.widget.TextView",
            shortTypeTag = "BTN",
            label = "在這裡搜尋",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(48, 120, 1032, 240),
            convergedBounds = rect(48, 120, 1032, 240),
            finalTapPx = pt(382, 180),
            gridTapPoint = pt(353, 76),
            visibleRatio = 1.0f,
            wasHotspotConverged = true,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
        val voiceMicNode = Stage2ConvergedNode(
            nodeId = "com.google.android.apps.maps:id/search_omnibox_voice_button",
            shortMarkId = 2,
            className = "android.widget.ImageView",
            shortTypeTag = "BTN",
            label = "語音搜尋",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(820, 125, 920, 235),
            convergedBounds = rect(820, 125, 920, 235),
            finalTapPx = pt(870, 180),
            gridTapPoint = pt(805, 76),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
        val lensNode = Stage2ConvergedNode(
            nodeId = "com.google.android.apps.maps:id/search_omnibox_lens_button",
            shortMarkId = 3,
            className = "android.widget.ImageView",
            shortTypeTag = "BTN",
            label = "使用 Google 智慧鏡頭搜尋",
            inlineStatePrefix = "",
            rawOriginalBounds = rect(925, 125, 1020, 235),
            convergedBounds = rect(925, 125, 1020, 235),
            finalTapPx = pt(972, 180),
            gridTapPoint = pt(900, 76),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )

        assertTrue(InAppOperationPlanner.isForbiddenVoiceOrCameraNode(voiceMicNode))
        assertTrue(InAppOperationPlanner.isForbiddenVoiceOrCameraNode(lensNode))

        val report = makeReport(listOf(voiceMicNode, lensNode, searchBoxNode))
        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = "去 Google Map 查一下台北 101 地址",
            report = report,
            context = null
        )

        // 嚴禁點選 #2「語音搜尋」或 #3「智慧鏡頭」，必須 100% 鎖定 #1「在這裡搜尋」純文字搜尋框！
        assertEquals(1, decision.targetMarkId)
        assertEquals("在這裡搜尋", decision.targetNode?.label)
    }

    @Test
    fun testFirstWebsitePick_skipsFilterTabsAndPicksFirstUrlResultCard() {
        val goal = "打開 Chrome 搜尋台灣高鐵時刻表，點開第一個網站"
        InAppOperationPlanner.resetForNewGoal(goal)
        com.example.newandroidapp.stability.InAppWorkingMemory.recordTextTypedAndSubmitted("台灣高鐵時刻表", "INP|搜尋")

        fun node(id: Int, label: String, role: String, pxY: Int, prefix: String = ""): Stage2ConvergedNode {
            val gy = pxY * 999 / 2340
            return Stage2ConvergedNode(
                nodeId = "chrome_$id",
                shortMarkId = id,
                className = "android.widget.Button",
                shortTypeTag = "BTN",
                label = label,
                inlineStatePrefix = prefix,
                rawOriginalBounds = rect(60, pxY - 40, 1020, pxY + 40),
                convergedBounds = rect(60, pxY - 40, 1020, pxY + 40),
                finalTapPx = pt(540, pxY),
                gridTapPoint = pt(500, gy),
                visibleRatio = 1.0f,
                wasHotspotConverged = false,
                absorbedTextIds = emptyList(),
                sourceType = NodeSourceType.UI_TREE,
                predictedFunctionRole = role,
                functionRoleConfidence = 0.9f
            )
        }

        val report = makeReport(
            listOf(
                node(13, "AI 模式", "GENERIC_ACTION_BUTTON", 616),
                node(14, "全部", "FILTER_SORT_CHIP", 620, "[停用]"),
                node(18, "進一步瞭解這項結果", "GENERIC_ACTION_BUTTON", 901),
                node(19, "台灣高鐵 https://www.thsrc.com.tw https://www.thsrc.com.tw 台灣高鐵", "CONTENT_CARD_ITEM", 930),
                node(23, "台北高鐵時刻表", "CONTENT_CARD_ITEM", 1520)
            )
        )
        val eval = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = report,
            userGoal = goal,
            currentSubGoal = "點開第一個網站"
        )
        assertEquals(AiActionType.TAP, eval.overrideActionType)
        assertEquals(19, eval.overrideTargetNode?.shortMarkId)
    }}

