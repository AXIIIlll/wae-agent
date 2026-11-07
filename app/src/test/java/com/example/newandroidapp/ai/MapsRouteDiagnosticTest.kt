package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.HotspotAndSpatialBinder
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage1OccludedNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Test

/** Diagnostic: replay Google Maps screens through the real decision pipeline and print decisions. */
class MapsRouteDiagnosticTest {

    private fun n(
        id: Int, label: String, tag: String, l: Int, t: Int, r: Int, b: Int,
        role: String = "", state: String = ""
    ): Stage2ConvergedNode {
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        return Stage2ConvergedNode(
            nodeId = "maps_$id", shortMarkId = id, className = "android.widget.Button",
            shortTypeTag = tag, label = label, inlineStatePrefix = state,
            rawOriginalBounds = Rect(l, t, r, b).apply { left = l; top = t; right = r; bottom = b },
            convergedBounds = Rect(l, t, r, b).apply { left = l; top = t; right = r; bottom = b },
            finalTapPx = Point(cx, cy).apply { x = cx; y = cy },
            gridTapPoint = Point(cx * 999 / 1080, cy * 999 / 2400).apply { x = cx * 999 / 1080; y = cy * 999 / 2400 },
            visibleRatio = 1f, wasHotspotConverged = false, absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE,
            predictedFunctionRole = role, functionRoleConfidence = if (role.isEmpty()) 0f else 0.9f,
            packageName = "com.google.android.apps.maps"
        )
    }

    private fun rep(nodes: List<Stage2ConvergedNode>) = VisionPipelineReport(
        timestampMs = 1L, screenWidth = 1080, screenHeight = 2400, gridCols = 120, gridRows = 240,
        occupancyGridSnapshot = ByteArray(0), totalRawNodes = nodes.size, culledByOcclusionCount = 0,
        partiallyOccludedShiftedCount = 0, transparentOverlayBypassedCount = 0, hotspotConvergedCount = 0,
        spatialBoundIconCount = 0, geometricIconClassifiedCount = 0, roiRecoveredCount = 0,
        nanoDetDetectedCount = 0, foldedListGroupCount = 0, temporalStabilizedCount = 0,
        stage1AllNodes = emptyList(), detectedRoiVoids = emptyList(), finalNodes = nodes,
        markIdToPixelMap = nodes.associate { it.shortMarkId to it.finalTapPx },
        compressedPromptText = "", deltaPromptText = "", rawCharCount = 1, compressedCharCount = 1,
        compressionRatioPercent = 0, pipelineLatencyMs = 1L
    )

    // Google Maps home: fake search bar, no INP yet
    private fun homeScreen() = rep(listOf(
        n(1, "在這裡搜尋", "BTN", 120, 180, 860, 300),
        n(2, "語音搜尋", "BTN", 880, 190, 1000, 290),
        n(3, "餐廳", "BTN", 40, 340, 220, 420),
        n(4, "探索", "BTN", 60, 2200, 240, 2330),
        n(5, "前往", "BTN", 300, 2200, 480, 2330),
        n(6, "已儲存", "BTN", 540, 2200, 720, 2330),
        n(7, "貢獻", "BTN", 780, 2200, 960, 2330)
    ))

    // Google Maps autocomplete screen: INP still focused with "臺北101", suggestion rows below
    private fun autocompleteScreen() = rep(listOf(
        n(1, "臺北101", "INP", 120, 110, 860, 210, state = "[焦點][輸入:在這裡搜尋]"),
        n(2, "清除搜尋", "BTN", 880, 115, 1000, 205),
        n(3, "住家 設定地址", "BTN", 40, 240, 360, 320),
        n(4, "公司 設定地址", "BTN", 380, 240, 700, 320),
        n(5, "台北101 台灣台北市信義區信義路五段7號", "BTN", 40, 360, 920, 470),
        n(6, "調整搜尋字詞", "BTN", 930, 365, 1040, 465),
        n(7, "台北101觀景台 台北市信義區", "BTN", 40, 490, 920, 600)
    ))

    // After selecting suggestion: Taipei 101 place card (bottom sheet) with route button
    private fun placeScreen() = rep(listOf(
        n(1, "台北101", "INP", 120, 180, 860, 300, state = "[輸入:在這裡搜尋]"),
        n(2, "清除搜尋", "BTN", 880, 190, 1000, 290),
        n(3, "drag_handle", "BTN", 480, 1250, 600, 1290),
        n(4, "台北101", "TXT", 60, 1320, 700, 1400),
        n(5, "購物中心 · 營業中", "TXT", 60, 1410, 800, 1470),
        n(6, "路線", "BTN", 60, 1560, 280, 1660),
        n(7, "開始", "BTN", 300, 1560, 520, 1660),
        n(8, "儲存", "BTN", 540, 1560, 760, 1660),
        n(9, "分享", "BTN", 780, 1560, 1000, 1660),
        n(10, "電話", "BTN", 60, 1700, 280, 1800)
    ))

    private fun emptySearchInputScreen() = rep(listOf(
        n(1, "在這裡搜尋", "INP", 120, 110, 860, 210, state = "[焦點][輸入:在這裡搜尋]"),
        n(2, "語音搜尋", "BTN", 880, 115, 1000, 205),
        n(3, "住家 設定地址", "BTN", 40, 240, 360, 320)
    ))

    private fun dump(tag: String, d: AiActionDecision) {
        println("[$tag] action=${d.actionType} target=#${d.targetMarkId}「${d.targetNode?.label}」 input=${d.inputTextValue} conf=${d.confidence}")
        println("      trace=${d.reasoningTrace.take(260)}")
    }

    @Test
    fun diag_directRouteClickOnPlaceCard() {
        InAppOperationPlanner.resetForNewGoal("點擊路線")
        InAppWorkingMemory.setExpectedTargetPackage("com.google.android.apps.maps")
        val d = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION, userGoal = "點擊路線", report = placeScreen(),
            context = null, currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true, lockToCurrentApp = true
        )
        dump("A direct 點擊路線 @placeCard", d)
        org.junit.Assert.assertEquals(AiActionType.TAP, d.actionType)
        org.junit.Assert.assertEquals("路線", d.targetNode?.label)
    }

    @Test
    fun diag_directRouteClickAlt() {
        for (g in listOf("點路線", "點擊路線那個按鈕", "點路線那個按鈕", "點擊路線按鈕", "點選路線", "路線")) {
            InAppOperationPlanner.resetForNewGoal(g)
            InAppWorkingMemory.setExpectedTargetPackage("com.google.android.apps.maps")
            val d = DualLocalAiCoordinator.decide(
                mode = LocalAiBackendMode.DUAL_FUSION, userGoal = g, report = placeScreen(),
                context = null, currentForegroundPackage = "com.google.android.apps.maps",
                alreadyLaunchedApp = true, lockToCurrentApp = true
            )
            dump("A2 '$g'", d)
            org.junit.Assert.assertEquals("Failed for '$g'", AiActionType.TAP, d.actionType)
            org.junit.Assert.assertEquals("Failed target for '$g'", "路線", d.targetNode?.label)
        }
    }

    @Test
    fun diag_fullChainStepByStep() {
        val goal = "打開google地圖搜尋臺北101并點選路線"
        InAppOperationPlanner.resetForNewGoal(goal)
        InAppWorkingMemory.setExpectedTargetPackage("com.google.android.apps.maps")

        // Step 1: home screen, Maps in foreground -> TAP "在這裡搜尋"
        val d1 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION, userGoal = goal, report = homeScreen(),
            context = null, currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true, activeSubGoalIndex = 1
        )
        dump("B1 home", d1)
        org.junit.Assert.assertEquals(AiActionType.TAP, d1.actionType)
        org.junit.Assert.assertEquals("在這裡搜尋", d1.targetNode?.label)

        // Step 2: empty search input screen -> INPUT_TEXT "臺北101"
        val d2 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION, userGoal = goal, report = emptySearchInputScreen(),
            context = null, currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true, activeSubGoalIndex = 1
        )
        dump("B2 input", d2)
        org.junit.Assert.assertEquals(AiActionType.INPUT_TEXT, d2.actionType)
        org.junit.Assert.assertEquals("臺北101", d2.inputTextValue)

        // Step 3: Google Maps stays on autocomplete suggestion list -> MUST tap "台北101 台灣台北市信義區信義路五段7號"
        val d3 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION, userGoal = goal, report = autocompleteScreen(),
            context = null, currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true, activeSubGoalIndex = 1
        )
        dump("B3 autocomplete", d3)
        org.junit.Assert.assertEquals(AiActionType.TAP, d3.actionType)
        org.junit.Assert.assertEquals("台北101 台灣台北市信義區信義路五段7號", d3.targetNode?.label)

        // Step 4: Place card is now open -> automatically advances from subGoalIndex=1 to 2 and taps "路線"
        val d4 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION, userGoal = goal, report = placeScreen(),
            context = null, currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true, activeSubGoalIndex = 1
        )
        dump("B4 place card", d4)
        org.junit.Assert.assertEquals(AiActionType.TAP, d4.actionType)
        org.junit.Assert.assertEquals("路線", d4.targetNode?.label)

        // Step 5: Simulate first tap on "路線" not changing screen (consecutiveStuckCount = 1 + rollback) -> retries "路線"
        val placeHash = InAppOperationPlanner.computeQuickScreenHash(placeScreen())
        InAppWorkingMemory.rollbackUnverifiedSearchEntryClick(d4.targetNode!!, placeHash)
        val d5Retry = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION, userGoal = goal, report = placeScreen(),
            context = null, currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true, activeSubGoalIndex = 2, consecutiveStuckCount = 1
        )
        dump("B5 retry on stuck=1", d5Retry)
        org.junit.Assert.assertEquals(AiActionType.TAP, d5Retry.actionType)
        org.junit.Assert.assertEquals("路線", d5Retry.targetNode?.label)
    }

    private fun makeStage1(
        id: String,
        parentId: String?,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        tapX: Int = (l + r) / 2,
        tapY: Int = (t + b) / 2,
        text: String = "",
        contentDesc: String = "",
        isClickable: Boolean = false,
        isScrollable: Boolean = false
    ): Stage1OccludedNode {
        val rect = Rect(l, t, r, b).apply { left = l; top = t; right = r; bottom = b }
        val pt = Point(tapX, tapY).apply { x = tapX; y = tapY }
        return Stage1OccludedNode(
            raw = RawVisionNode(
                id = id,
                windowId = 1,
                windowLayer = 0,
                zOrderRank = 1,
                className = "android.view.View",
                packageName = "com.google.android.apps.maps",
                bounds = rect,
                text = text,
                contentDescription = contentDesc,
                isClickable = isClickable,
                isScrollable = isScrollable,
                scrollDirections = if (isScrollable) "VH" else "",
                hasRenderedContent = text.isNotEmpty() || contentDesc.isNotEmpty(),
                parentId = parentId
            ),
            visibleRatio = 1f,
            isCulled = false,
            wasPartiallyOccluded = false,
            safeTapPointPx = pt,
            visibleBoundingBox = rect
        )
    }

    @Test
    fun diag_realPhoneAiFlight220241_parentContainerNeverStealsSaveButtonAndPicksExactCardRoute() {
        // Reconstruct the exact parent-child hierarchy from the phone's AiFlight log at 22:02:41.489:
        // Card 1: "台北101購物中心" (y=1020..1460) with child buttons at y=1350..1450:
        //   - btn1_save ("儲存" @998,1400) [visited first in reverse Z-order!]
        //   - btn1_share ("分享" @740,1400)
        //   - btn1_call ("致電" @490,1400)
        //   - btn1_route ("行車路線" @200,1400)
        // Card 2: "台北101" (y=1520..1960) with child buttons at y=1850..1950:
        //   - btn2_save ("儲存" @998,1900) [visited first in reverse Z-order!]
        //   - btn2_share ("分享" @740,1900)
        //   - btn2_ticket ("購票" @490,1900)
        //   - btn2_route ("行車路線" @200,1900)
        val stage1Nodes = listOf(
            // Top search bar showing "台北 101"
            makeStage1("top_search", null, 80, 180, 900, 260, tapX = 140, tapY = 220, text = "台北 101", isClickable = true),

            // Card 1 ("台北101購物中心") whose raw contentDescription was polluted with child button names and whose occluded safeTap was @1004,1400
            makeStage1(
                id = "card1", parentId = null, l = 40, t = 1020, r = 1040, b = 1460, tapX = 1004, tapY = 1400,
                contentDesc = "儲存 分享 致電 行車路線 臺北市信義區市府路45號 台北101購物中心", isClickable = true
            ),
            makeStage1("card1_title", "card1", 60, 1050, 800, 1140, tapX = 430, tapY = 1095, text = "台北101購物中心"),
            makeStage1("card1_scroll", "card1", 40, 1340, 1040, 1455, tapX = 1004, tapY = 1400, isClickable = true, isScrollable = true),
            // Reverse Z-order children of card1_scroll (right-to-left: Save first, Route last)
            makeStage1("btn1_save", "card1_scroll", 890, 1350, 1040, 1450, tapX = 998, tapY = 1400, isClickable = true),
            makeStage1("btn1_save_txt", "btn1_save", 910, 1370, 1030, 1430, tapX = 1004, tapY = 1400, text = "儲存"),
            makeStage1("btn1_share", "card1_scroll", 630, 1350, 850, 1450, tapX = 740, tapY = 1400, isClickable = true),
            makeStage1("btn1_share_txt", "btn1_share", 650, 1370, 830, 1430, tapX = 740, tapY = 1400, text = "分享"),
            makeStage1("btn1_call", "card1_scroll", 380, 1350, 600, 1450, tapX = 490, tapY = 1400, isClickable = true),
            makeStage1("btn1_call_txt", "btn1_call", 400, 1370, 580, 1430, tapX = 490, tapY = 1400, text = "致電"),
            makeStage1("btn1_route", "card1_scroll", 60, 1350, 340, 1450, tapX = 200, tapY = 1400, isClickable = true),
            makeStage1("btn1_route_txt", "btn1_route", 80, 1370, 320, 1430, tapX = 200, tapY = 1400, text = "行車路線"),

            // Card 2 ("台北101") whose raw contentDescription was polluted with child button names and whose occluded safeTap was @1004,1900
            makeStage1(
                id = "card2", parentId = null, l = 40, t = 1520, r = 1040, b = 1960, tapX = 1004, tapY = 1900,
                contentDesc = "儲存 分享 購票 行車路線 臺北市信義區信義路五段7號 台北101", isClickable = true
            ),
            makeStage1("card2_title", "card2", 60, 1550, 800, 1640, tapX = 430, tapY = 1595, text = "台北101"),
            makeStage1("card2_scroll", "card2", 40, 1840, 1040, 1955, tapX = 1004, tapY = 1900, isClickable = true, isScrollable = true),
            // Reverse Z-order children of card2_scroll (right-to-left: Save first, Route last)
            makeStage1("btn2_save", "card2_scroll", 890, 1850, 1040, 1950, tapX = 998, tapY = 1900, isClickable = true),
            makeStage1("btn2_save_txt", "btn2_save", 910, 1870, 1030, 1930, tapX = 1004, tapY = 1900, text = "儲存"),
            makeStage1("btn2_share", "card2_scroll", 630, 1850, 850, 1950, tapX = 740, tapY = 1900, isClickable = true),
            makeStage1("btn2_share_txt", "btn2_share", 650, 1870, 830, 1930, tapX = 740, tapY = 1900, text = "分享"),
            makeStage1("btn2_ticket", "card2_scroll", 380, 1850, 600, 1950, tapX = 490, tapY = 1900, isClickable = true),
            makeStage1("btn2_ticket_txt", "btn2_ticket", 400, 1870, 580, 1930, tapX = 490, tapY = 1900, text = "購票"),
            makeStage1("btn2_route", "card2_scroll", 60, 1850, 340, 1950, tapX = 200, tapY = 1900, isClickable = true),
            makeStage1("btn2_route_txt", "btn2_route", 80, 1870, 320, 1930, tapX = 200, tapY = 1900, text = "行車路線")
        )

        val stage2 = HotspotAndSpatialBinder.process(stage1Nodes, screenWidth = 1080, screenHeight = 2400)
        val converged = stage2.convergedNodes
        println("Converged nodes (${converged.size}):")
        for (c in converged) {
            println("  #${c.shortMarkId}[${c.shortTypeTag}] id=${c.nodeId} 「${c.label}」 @${c.finalTapPx.x},${c.finalTapPx.y}")
        }

        // 1. Verify parent cards card1 and card2 no longer contain "行車路線" or "儲存" in their labels,
        //    and their tap coordinates are anchored on their title (y~1100 / y~1600), NEVER @1004,1400 or @1004,1900!
        val card2Node = converged.first { it.nodeId == "card2" }
        org.junit.Assert.assertFalse("card2 must not contain 行車路線: ${card2Node.label}", card2Node.label.contains("行車路線"))
        org.junit.Assert.assertFalse("card2 must not contain 儲存: ${card2Node.label}", card2Node.label.contains("儲存"))
        org.junit.Assert.assertTrue("card2 must retain 台北101: ${card2Node.label}", card2Node.label.contains("台北101"))
        org.junit.Assert.assertTrue(
            "card2 tap point must not collide with Save (@998,1900), got (${card2Node.finalTapPx.x},${card2Node.finalTapPx.y})",
            card2Node.finalTapPx.y < 1800
        )

        // 2. Verify horizontal scroll shells (card1_scroll, card2_scroll) are suppressed and do not masquerade as buttons
        org.junit.Assert.assertTrue("card2_scroll wrapper should be suppressed", converged.none { it.nodeId == "card2_scroll" })

        // 3. Run full decision for goal="打開 Google 地圖搜尋台北 101，並點選路線" on this screen:
        //    Must pick btn2_route ("行車路線" @200,1900 belonging to "台北101"), NOT btn1_route ("台北101購物中心") and NEVER btn2_save ("儲存")!
        val fullGoal = "打開 Google 地圖搜尋台北 101，並點選路線"
        InAppOperationPlanner.resetForNewGoal(fullGoal)
        InAppWorkingMemory.setExpectedTargetPackage("com.google.android.apps.maps")
        InAppWorkingMemory.recordTextTypedAndSubmitted("台北 101")

        val report = rep(converged)
        val decision = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = fullGoal,
            report = report,
            context = null,
            currentForegroundPackage = "com.google.android.apps.maps",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 2
        )
        dump("RealPhone 22:02:41 decision", decision)
        org.junit.Assert.assertEquals(AiActionType.TAP, decision.actionType)
        org.junit.Assert.assertEquals("btn2_route", decision.targetNode?.nodeId)
        org.junit.Assert.assertEquals("行車路線", decision.targetNode?.label)
        org.junit.Assert.assertEquals(200, decision.targetNode?.finalTapPx?.x)
        org.junit.Assert.assertEquals(1900, decision.targetNode?.finalTapPx?.y)
    }
}
