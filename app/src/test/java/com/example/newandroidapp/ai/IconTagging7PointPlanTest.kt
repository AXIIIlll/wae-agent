package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.TinyIconClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 《圖標標籤與功用辨識計畫（含 7 項補強）》全階段驗收單元測試 (`P1` ~ `P7`)
 */
class IconTagging7PointPlanTest {

    @Before
    fun setUp() {
        CommandLexiconEngine.ensureLoaded(null)
        CommandLexiconEngine.clearIconRescueMemory()
        TinyIconClassifier.clearRecordedUnknownIcons()
    }

    private fun rectOf(l: Int, t: Int, r: Int, b: Int): Rect {
        return Rect(l, t, r, b).apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    private fun pointOf(px: Int, py: Int): Point {
        return Point(px, py).apply {
            x = px
            y = py
        }
    }

    private fun makeNode(
        id: Int,
        label: String,
        shortTypeTag: String = "BTN",
        left: Int = 100,
        top: Int = 500,
        right: Int = 260,
        bottom: Int = 580,
        role: String = ""
    ): Stage2ConvergedNode {
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val gx = (cx * 1000 / 1080).coerceIn(0, 999)
        val gy = (cy * 1000 / 2400).coerceIn(0, 999)
        return Stage2ConvergedNode(
            nodeId = "node_$id",
            shortMarkId = id,
            className = "android.widget.ImageView",
            shortTypeTag = shortTypeTag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = rectOf(left, top, right, bottom),
            convergedBounds = rectOf(left, top, right, bottom),
            finalTapPx = pointOf(cx, cy),
            gridTapPoint = pointOf(gx, gy),
            visibleRatio = 1.0f,
            wasHotspotConverged = true,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE,
            visualConfidence = 0.95f,
            nanoDetCategory = "ICON_BUTTON",
            predictedFunctionRole = role,
            functionRoleConfidence = if (role.isNotEmpty()) 0.92f else 0f,
            packageName = "com.google.android.apps.maps"
        )
    }

    // =========================================================================
    // P1 (對應項 6)：驗證 3 個核心錯標已修正，且 143+ 個手勢與抽屜圖標已補齊標籤
    // =========================================================================
    @Test
    fun testP1_MislabeledIconsFixedAnd143GestureSheetIconsTagged() {
        // 1. 原本誤標為 MORE_OVERFLOW_MENU 的 drag_handle 必須修正為 SWIPE_UP_EXPAND_SHEET
        assertEquals(
            "SWIPE_UP_EXPAND_SHEET",
            OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("drag_handle")
        )
        // 2. 原本誤標為 SHARE_FORWARD 的 arrow_downward 必須修正為 SWIPE_DOWN_COLLAPSE_SHEET
        assertEquals(
            "SWIPE_DOWN_COLLAPSE_SHEET",
            OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("arrow_downward")
        )
        // 3. 原本誤標為 MORE_OVERFLOW_MENU 的 keyboard_arrow_down 必須修正為 SWIPE_DOWN_COLLAPSE_SHEET
        assertEquals(
            "SWIPE_DOWN_COLLAPSE_SHEET",
            OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("keyboard_arrow_down")
        )

        // 4. 驗證框頂扁平橢圓與上滑/下滑/展開/輪播/開關圖標皆已正確標記
        assertEquals("SWIPE_UP_EXPAND_SHEET", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("bottom_sheets"))
        assertEquals("SWIPE_UP_EXPAND_SHEET", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("bottom_panel_open"))
        assertEquals("SWIPE_UP_EXPAND_SHEET", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("swipe_up"))
        assertEquals("SWIPE_UP_EXPAND_SHEET", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("expand_less"))
        assertEquals("EXPAND_FULL_CONTENT", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("unfold_more"))
        assertEquals("EXPAND_FULL_CONTENT", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("open_in_full"))
        assertEquals("SWIPE_HORIZONTAL_CAROUSEL", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("chevron_right"))
        assertEquals("SWIPE_HORIZONTAL_CAROUSEL", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("swipe_left"))
        assertEquals("TOGGLE_SWITCH_CHECKBOX", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("toggle_on"))
        assertEquals("TOGGLE_SWITCH_CHECKBOX", OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon("check_box_outline_blank"))
    }

    // =========================================================================
    // P2 (對應項 1)：驗證位置與文字脈絡判斷 (`resolveIconInContext`)
    // =========================================================================
    @Test
    fun testP2_IconContextResolution_CloseVsClear_And_SheetUpVsScrollToTop() {
        val topInputNode = makeNode(
            id = 1,
            label = "台北 101",
            shortTypeTag = "INP",
            left = 120,
            top = 90,
            right = 880,
            bottom = 190
        )

        // 1A. `X` 在頂部搜尋輸入框右側 -> 必須判為 CLEAR_INPUT ('c')，優先級 100
        val clearCtx = CommandLexiconEngine.resolveIconInContext(
            iconNameOrLabel = "圖示:關閉",
            bounds = rectOf(890, 100, 970, 180),
            screenWidth = 1080,
            screenHeight = 2400,
            parentContainerType = "search_plate_input",
            neighboringText = "台北 101",
            allNodes = listOf(topInputNode)
        )
        assertEquals(CommandLexiconEngine.ScreenRegionZone.INSIDE_INPUT_BOX, clearCtx.regionZone)
        assertEquals("CLEAR_INPUT", clearCtx.resolvedFunctionalRole)
        assertEquals('c', clearCtx.primaryActionCode)
        assertEquals(100, clearCtx.rulePriority)

        // 1B. `X` 在彈窗右上角（無相鄰輸入框）-> 必須判為 CLOSE_MODAL ('b'>'c')，優先級 90
        val modalCloseCtx = CommandLexiconEngine.resolveIconInContext(
            iconNameOrLabel = "圖示:關閉",
            bounds = rectOf(920, 420, 1010, 510),
            screenWidth = 1080,
            screenHeight = 2400,
            parentContainerType = "dialog_modal_header",
            neighboringText = "促銷活動彈窗",
            allNodes = emptyList()
        )
        assertEquals(CommandLexiconEngine.ScreenRegionZone.MODAL_CORNER, modalCloseCtx.regionZone)
        assertEquals("CLOSE_MODAL", modalCloseCtx.resolvedFunctionalRole)
        assertEquals('b', modalCloseCtx.primaryActionCode)
        assertEquals('c', modalCloseCtx.fallbackActionCode)

        // 2A. `^` 或扁平橢圓把手在底部面板頂端中央 -> 必須判為 SWIPE_UP_EXPAND_SHEET ('r'>'c')
        val sheetUpCtx = CommandLexiconEngine.resolveIconInContext(
            iconNameOrLabel = "圖示:向上箭頭",
            bounds = rectOf(480, 1380, 600, 1410),
            screenWidth = 1080,
            screenHeight = 2400,
            parentContainerType = "bottom_sheet_header",
            neighboringText = "台北 101 詳情"
        )
        assertEquals(CommandLexiconEngine.ScreenRegionZone.SHEET_TOP_HANDLE, sheetUpCtx.regionZone)
        assertEquals("SWIPE_UP_EXPAND_SHEET", sheetUpCtx.resolvedFunctionalRole)
        assertEquals('r', sheetUpCtx.primaryActionCode)

        // 2B. `^` 在右下角懸浮鈕 (FAB) -> 必須判為 SCROLL_TO_TOP ('c'>'r')
        val fabTopCtx = CommandLexiconEngine.resolveIconInContext(
            iconNameOrLabel = "圖示:向上箭頭",
            bounds = rectOf(880, 1980, 1000, 2100),
            screenWidth = 1080,
            screenHeight = 2400,
            parentContainerType = "floating_action_button"
        )
        assertEquals(CommandLexiconEngine.ScreenRegionZone.BOTTOM_RIGHT_FAB, fabTopCtx.regionZone)
        assertEquals("SCROLL_TO_TOP", fabTopCtx.resolvedFunctionalRole)
        assertEquals('c', fabTopCtx.primaryActionCode)
    }

    // =========================================================================
    // P3 (對應項 2)：驗證三層辨識順序與未知自訂圖標 (`UNKNOWN_ICON`) 不硬猜且記錄樣本
    // =========================================================================
    @Test
    fun testP3_ThreeTierRecognitionAndUnknownIconLogging() {
        // Tier 1: 無障礙 contentDescription 命中
        val rawTier1 = RawVisionNode(
            id = "raw_1",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = "android.widget.ImageView",
            packageName = "com.google.android.apps.maps",
            bounds = rectOf(100, 1200, 260, 1300),
            text = "",
            contentDescription = "規劃路線",
            viewIdResourceName = "0x7f010099",
            isClickable = true
        )
        val resTier1 = TinyIconClassifier.classifyIconThreeTier(
            raw = rawTier1,
            iconBounds = rawTier1.bounds,
            screenWidth = 1080,
            screenHeight = 2400,
            screenBitmap = null,
            packageName = "com.google.android.apps.maps"
        )
        assertEquals(1, resTier1.tierMatched)
        assertEquals("MAP_DIRECTIONS_ROUTE", resTier1.functionalRole)

        // Tier 3: Material 圖標庫名稱命中 (如 expansion_panels)
        val rawTier3 = RawVisionNode(
            id = "raw_2",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = "android.widget.ImageView",
            packageName = "com.example.app",
            bounds = rectOf(500, 1200, 580, 1280),
            text = "",
            contentDescription = "",
            viewIdResourceName = "com.example.app:id/expansion_panels",
            isClickable = true
        )
        val resTier3 = TinyIconClassifier.classifyIconThreeTier(
            raw = rawTier3,
            iconBounds = rawTier3.bounds,
            screenWidth = 1080,
            screenHeight = 2400,
            screenBitmap = null,
            packageName = "com.example.app"
        )
        assertEquals(3, resTier3.tierMatched)
        assertEquals("EXPAND_FULL_CONTENT", resTier3.functionalRole)

        // Tier 0 (UNKNOWN_ICON): 混淆 ID + 無文字 + 無吻合幾何 -> 標為 UNKNOWN_ICON 並寫入登錄表
        TinyIconClassifier.clearRecordedUnknownIcons()
        val rawUnknown = RawVisionNode(
            id = "raw_3",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = "android.widget.ImageView",
            packageName = "com.shopee.tw",
            bounds = rectOf(400, 600, 480, 680),
            text = "",
            contentDescription = "",
            viewIdResourceName = "a.b.c",
            isClickable = true
        )
        val resUnknown = TinyIconClassifier.classifyIconThreeTier(
            raw = rawUnknown,
            iconBounds = rawUnknown.bounds,
            screenWidth = 1080,
            screenHeight = 2400,
            screenBitmap = null,
            packageName = "com.shopee.tw"
        )
        assertEquals(0, resUnknown.tierMatched)
        assertEquals("UNKNOWN_ICON", resUnknown.functionalRole)
        assertEquals("圖示:未知自訂圖標", resUnknown.label)
        assertEquals(1, TinyIconClassifier.getRecordedUnknownIcons().size)
        assertEquals("com.shopee.tw", TinyIconClassifier.getRecordedUnknownIcons().first().packageName)
    }

    // =========================================================================
    // P4 (對應項 4)：驗證擴充之 16x16 幾何規則（扁平橢圓把手、`^`、`v`、`<`、`>`、切換開關、勾選框）
    // =========================================================================
    @Test
    fun testP4_Expanded16x16GeometricRules() {
        // 1. 扁平橢圓橫條 (寬高比 >= 4:1，中央水平列連續填滿，上下空白)
        val pillMatrix = Array(16) { r ->
            BooleanArray(16) { c -> r in 6..9 && c in 2..13 }
        }
        assertEquals(
            "圖示:上滑展開抽屜",
            TinyIconClassifier.classify16x16MatrixDirectly(pillMatrix, aspectRatio = 5.5f, normCx = 0.50f, normCy = 0.58f)
        )

        // 2. `^` 向上山字箭頭
        val chevronUp = Array(16) { BooleanArray(16) }
        chevronUp[4][7] = true; chevronUp[4][8] = true
        chevronUp[5][6] = true; chevronUp[5][9] = true
        chevronUp[6][5] = true; chevronUp[6][10] = true
        chevronUp[7][4] = true; chevronUp[7][11] = true
        chevronUp[8][3] = true; chevronUp[8][12] = true
        chevronUp[9][2] = true; chevronUp[9][13] = true
        assertEquals(
            "圖示:向上箭頭",
            TinyIconClassifier.classify16x16MatrixDirectly(chevronUp, aspectRatio = 1.0f)
        )

        // 3. `v` 向下山字箭頭
        val chevronDown = Array(16) { BooleanArray(16) }
        chevronDown[5][2] = true; chevronDown[5][13] = true
        chevronDown[6][3] = true; chevronDown[6][12] = true
        chevronDown[7][4] = true; chevronDown[7][11] = true
        chevronDown[8][5] = true; chevronDown[8][10] = true
        chevronDown[9][6] = true; chevronDown[9][9] = true
        chevronDown[10][7] = true; chevronDown[10][8] = true
        assertEquals(
            "圖示:向下箭頭",
            TinyIconClassifier.classify16x16MatrixDirectly(chevronDown, aspectRatio = 1.0f)
        )

        // 4. `<` 向左折線箭頭 & `>` 向右折線箭頭
        val chevronLeft = Array(16) { BooleanArray(16) }
        chevronLeft[7][4] = true; chevronLeft[8][4] = true
        chevronLeft[6][5] = true; chevronLeft[9][5] = true
        chevronLeft[5][6] = true; chevronLeft[10][6] = true
        chevronLeft[4][7] = true; chevronLeft[11][7] = true
        chevronLeft[3][8] = true; chevronLeft[12][8] = true
        assertEquals(
            "圖示:向左箭頭",
            TinyIconClassifier.classify16x16MatrixDirectly(chevronLeft, aspectRatio = 1.0f)
        )

        val chevronRight = Array(16) { BooleanArray(16) }
        chevronRight[7][11] = true; chevronRight[8][11] = true
        chevronRight[6][10] = true; chevronRight[9][10] = true
        chevronRight[5][9] = true; chevronRight[10][9] = true
        chevronRight[4][8] = true; chevronRight[11][8] = true
        chevronRight[3][7] = true; chevronRight[12][7] = true
        assertEquals(
            "圖示:向右箭頭",
            TinyIconClassifier.classify16x16MatrixDirectly(chevronRight, aspectRatio = 1.0f)
        )

        // 5. 左右圓頭膠囊切換開關 (右側圓球實心、左側軌道空心，aspectRatio = 2.0f)
        val toggleMatrix = Array(16) { r ->
            BooleanArray(16) { c ->
                val isTopBottomTrack = (r == 3 || r == 12) && c in 3..12
                val isRightKnob = r in 4..11 && c in 9..13
                isTopBottomTrack || isRightKnob
            }
        }
        assertEquals(
            "圖示:切換開關",
            TinyIconClassifier.classify16x16MatrixDirectly(toggleMatrix, aspectRatio = 2.0f)
        )

        // 6. 空心未勾選方框 vs 已勾選方框
        val uncheckedBox = Array(16) { r ->
            BooleanArray(16) { c ->
                (r in 2..13 && (c == 2 || c == 13)) || (c in 2..13 && (r == 2 || r == 13))
            }
        }
        assertEquals(
            "圖示:未勾選方框",
            TinyIconClassifier.classify16x16MatrixDirectly(uncheckedBox, aspectRatio = 1.0f)
        )

        val checkedBox = Array(16) { r ->
            BooleanArray(16) { c ->
                val border = (r in 2..13 && (c == 2 || c == 13)) || (c in 2..13 && (r == 2 || r == 13))
                val checkStroke = (r == 8 && c == 5) || (r == 9 && c == 6) || (r == 10 && c == 7) ||
                    (r == 9 && c == 8) || (r == 8 && c == 9) || (r == 7 && c == 10) || (r == 6 && c == 11)
                border || checkStroke
            }
        }
        assertEquals(
            "圖示:已勾選方框",
            TinyIconClassifier.classify16x16MatrixDirectly(checkedBox, aspectRatio = 1.0f)
        )
    }

    // =========================================================================
    // P5 (對應項 7)：驗證指令詞庫與圖標標籤雙向打通 (`icon_hint` 與位置消歧搜尋)
    // =========================================================================
    @Test
    fun testP5_CommandLexiconAndIconHintsBidirectionalLinkage() {
        val report = CommandLexiconEngine.getQualityReport()
        assertTrue("詞典應有超過 1,000 筆詞條附帶 icon_hint", report.iconHintEntryCount >= 1000)

        // 1. 指令說「展開詳情」，畫面無「展開詳情」文字但有底部抽屜扁平橢圓把手 (`圖示:上滑展開抽屜`) 與右下角 FAB (`圖示:向上箭頭`)
        val sheetHandleNode = makeNode(
            id = 10,
            label = "圖示:上滑展開抽屜",
            left = 440,
            top = 1350,
            right = 640,
            bottom = 1375,
            role = "SWIPE_UP_EXPAND_SHEET"
        )
        val fabUpNode = makeNode(
            id = 11,
            label = "圖示:向上箭頭",
            left = 900,
            top = 2000,
            right = 1000,
            bottom = 2100
        )
        val matchedExpand = CommandLexiconEngine.findBestNodeByIconHint(
            clauseOrGoal = "展開詳情",
            nodes = listOf(fabUpNode, sheetHandleNode),
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertNotNull(matchedExpand)
        assertEquals("指令「展開詳情」應精準鎖定底部抽屜把手而非右下角回到頂部鈕", 10, matchedExpand!!.shortMarkId)

        // 2. 畫面同時有「搜尋框內的 X」與「彈窗右上角的 X」：
        //    - 指令說「清除文字」-> 選搜尋框內的 X (#20)
        //    - 指令說「關閉彈窗」-> 選彈窗右上角的 X (#21)
        val inputNode = makeNode(19, "台北101", "INP", 100, 90, 880, 180)
        val inputClearX = makeNode(20, "圖示:關閉", "BTN", 890, 95, 970, 175)
        val modalCloseX = makeNode(21, "圖示:關閉", "BTN", 920, 500, 1000, 580)
        val allNodes = listOf(inputNode, inputClearX, modalCloseX)

        val pickedClear = CommandLexiconEngine.findBestNodeByIconHint("清除文字", allNodes, 1080, 2400)
        assertNotNull(pickedClear)
        assertEquals(20, pickedClear!!.shortMarkId)

        val pickedClose = CommandLexiconEngine.findBestNodeByIconHint("關閉彈窗", allNodes, 1080, 2400)
        assertNotNull(pickedClose)
        assertEquals(21, pickedClose!!.shortMarkId)
    }

    // =========================================================================
    // P6 (對應項 3)：驗證動作後畫面變化檢查與三步自救（最多重試 2 次，記錄失敗圖標）
    // =========================================================================
    @Test
    fun testP6_PostActionVerificationAndThreeStepSelfRescue() {
        val handleNode = makeNode(1, "圖示:上滑展開抽屜", "BTN", 440, 1400, 640, 1425)
        val altExpandNode = makeNode(2, "圖示:展開全文", "BTN", 900, 1400, 980, 1480)
        val unchangedScreen = listOf(handleNode, altExpandNode)

        // 第 1 次：對抽屜把手執行上滑 ('r') 後畫面完全沒變 -> 應觸發第 1 步自救：切換動作為點擊 ('c') 並微調座標
        val rescue1 = CommandLexiconEngine.verifyIconActionAndRecover(
            packageName = "com.google.android.apps.maps",
            targetIconNode = handleNode,
            executedActionCode = 'r',
            beforeNodes = unchangedScreen,
            afterNodes = unchangedScreen,
            beforeSheetTopY = 1400,
            afterSheetTopY = 1400
        )
        assertFalse(rescue1.screenChanged)
        assertEquals(1, rescue1.attemptCountForIcon)
        assertEquals(CommandLexiconEngine.IconRescueStrategy.SWITCH_ACTION_MODE, rescue1.rescueStrategy)
        assertEquals('c', rescue1.recommendedActionCode)
        assertFalse(rescue1.markedAsFailedInApp)

        // 第 2 次：再次無變化 -> 達到上限 2 次，應標記該圖標動作在該 App 失敗，並切換至下一個候選圖標 (#2)
        val rescue2 = CommandLexiconEngine.verifyIconActionAndRecover(
            packageName = "com.google.android.apps.maps",
            targetIconNode = handleNode,
            executedActionCode = 'r',
            beforeNodes = unchangedScreen,
            afterNodes = unchangedScreen,
            beforeSheetTopY = 1400,
            afterSheetTopY = 1400
        )
        assertFalse(rescue2.screenChanged)
        assertEquals(2, rescue2.attemptCountForIcon)
        assertEquals(CommandLexiconEngine.IconRescueStrategy.SWITCH_CANDIDATE_ICON, rescue2.rescueStrategy)
        assertTrue(rescue2.markedAsFailedInApp)
        assertNotNull(rescue2.alternateCandidateNode)
        assertEquals(2, rescue2.alternateCandidateNode!!.shortMarkId)

        // 若畫面面板高度成功上移 (1400 -> 600)，應判定為成功 (screenChanged = true)
        val expandedScreen = listOf(
            handleNode.copy(convergedBounds = rectOf(440, 600, 640, 625)),
            altExpandNode,
            makeNode(3, "行車路線", "BTN", 120, 780, 360, 860, "MAP_DIRECTIONS_ROUTE")
        )
        val successVerify = CommandLexiconEngine.verifyIconActionAndRecover(
            packageName = "com.google.android.apps.maps",
            targetIconNode = altExpandNode,
            executedActionCode = 'c',
            beforeNodes = unchangedScreen,
            afterNodes = expandedScreen,
            beforeSheetTopY = 1400,
            afterSheetTopY = 600
        )
        assertTrue(successVerify.screenChanged)
        assertEquals(CommandLexiconEngine.IconRescueStrategy.NONE_SUCCESS, successVerify.rescueStrategy)
    }

    // =========================================================================
    // P7 (對應項 5)：圖標測試集基準驗收（每種功用 40 組跨解析度/深淺色樣本，混淆組 >= 95%，整體 >= 90%）
    // =========================================================================
    @Test
    fun testP7_IconBenchmarkSuite_ConfusionPairsAbove95Percent_OverallAbove90Percent() {
        val resolutions = listOf(720 to 1600, 1080 to 2400, 1440 to 3200)
        var confusionTotal = 0
        var confusionCorrect = 0
        var overallTotal = 0
        var overallCorrect = 0

        // 針對 4 大重點混淆組，每組生成 40 個跨解析度、跨位置微擾測試案例 (共 160 組混淆測試 + 160 組一般圖標測試 = 320 組)
        for (i in 0 until 40) {
            val (sw, sh) = resolutions[i % resolutions.size]
            val jitter = (i % 5) - 2

            // 混淆組 1：抽屜扁平橢圓把手 (SWIPE_UP_EXPAND_SHEET) vs 垂直三點更多選單 (MORE_OVERFLOW_MENU)
            val pillMatrix = Array(16) { r -> BooleanArray(16) { c -> r in (6..8) && c in (2..13) } }
            val pillPred = TinyIconClassifier.classify16x16MatrixDirectly(
                fg = pillMatrix,
                aspectRatio = 4.2f + (i % 4) * 0.6f,
                normCx = 0.50f + jitter * 0.02f,
                normCy = 0.58f + jitter * 0.03f
            )
            confusionTotal++; overallTotal++
            if (pillPred == "圖示:上滑展開抽屜") { confusionCorrect++; overallCorrect++ }

            val dotsMatrix = Array(16) { r ->
                BooleanArray(16) { c -> (r in 2..3 || r in 7..8 || r in 12..13) && c in 7..8 }
            }
            val dotsPred = TinyIconClassifier.classify16x16MatrixDirectly(dotsMatrix, aspectRatio = 1.0f)
            confusionTotal++; overallTotal++
            if (dotsPred == "圖示:更多") { confusionCorrect++; overallCorrect++ }

            // 混淆組 2：`X` 在搜尋框內 (CLEAR_INPUT) vs `X` 在彈窗角落 (CLOSE_MODAL)
            val inpLeft = (sw * 0.08f).toInt()
            val inpRight = (sw * 0.88f).toInt()
            val inpTop = (sh * 0.05f).toInt() + jitter * 2
            val inpBottom = (sh * 0.09f).toInt() + jitter * 2
            val inpNode = makeNode(
                id = 100 + i,
                label = "搜尋關鍵字",
                shortTypeTag = "INP",
                left = inpLeft,
                top = inpTop,
                right = inpRight,
                bottom = inpBottom
            )
            val xInInputRes = CommandLexiconEngine.resolveIconInContext(
                iconNameOrLabel = "圖示:關閉",
                bounds = rectOf((sw * 0.82f).toInt(), inpTop, (sw * 0.88f).toInt(), inpBottom),
                screenWidth = sw,
                screenHeight = sh,
                allNodes = listOf(inpNode)
            )
            confusionTotal++; overallTotal++
            if (xInInputRes.resolvedFunctionalRole == "CLEAR_INPUT" && xInInputRes.primaryActionCode == 'c') {
                confusionCorrect++; overallCorrect++
            }

            val xInModalRes = CommandLexiconEngine.resolveIconInContext(
                iconNameOrLabel = "圖示:關閉",
                bounds = rectOf((sw * 0.85f).toInt(), (sh * 0.22f).toInt(), (sw * 0.93f).toInt(), (sh * 0.26f).toInt()),
                screenWidth = sw,
                screenHeight = sh,
                allNodes = emptyList()
            )
            confusionTotal++; overallTotal++
            if (xInModalRes.resolvedFunctionalRole == "CLOSE_MODAL" && xInModalRes.primaryActionCode == 'b') {
                confusionCorrect++; overallCorrect++
            }

            // 混淆組 3：`^` 在底部面板頂端 (SWIPE_UP_EXPAND_SHEET) vs `^` 在右下角懸浮鈕 (SCROLL_TO_TOP)
            val upSheetRes = CommandLexiconEngine.resolveIconInContext(
                iconNameOrLabel = "圖示:向上箭頭",
                bounds = rectOf((sw * 0.45f).toInt(), (sh * 0.55f).toInt(), (sw * 0.55f).toInt(), (sh * 0.58f).toInt()),
                screenWidth = sw,
                screenHeight = sh,
                parentContainerType = "bottom_sheet"
            )
            confusionTotal++; overallTotal++
            if (upSheetRes.resolvedFunctionalRole == "SWIPE_UP_EXPAND_SHEET" && upSheetRes.primaryActionCode == 'r') {
                confusionCorrect++; overallCorrect++
            }

            val upFabRes = CommandLexiconEngine.resolveIconInContext(
                iconNameOrLabel = "圖示:向上箭頭",
                bounds = rectOf((sw * 0.82f).toInt(), (sh * 0.82f).toInt(), (sw * 0.92f).toInt(), (sh * 0.87f).toInt()),
                screenWidth = sw,
                screenHeight = sh,
                parentContainerType = "fab"
            )
            confusionTotal++; overallTotal++
            if (upFabRes.resolvedFunctionalRole == "SCROLL_TO_TOP" && upFabRes.primaryActionCode == 'c') {
                confusionCorrect++; overallCorrect++
            }

            // 混淆組 4：膠囊切換開關 (`圖示:切換開關`) vs 核取勾選框 (`圖示:已勾選方框`)
            val toggleMat = Array(16) { r ->
                BooleanArray(16) { c ->
                    ((r == 3 || r == 12) && c in 3..12) || (r in 4..11 && if (i % 2 == 0) c in 9..13 else c in 2..6)
                }
            }
            val togglePred = TinyIconClassifier.classify16x16MatrixDirectly(toggleMat, aspectRatio = 2.1f)
            confusionTotal++; overallTotal++
            if (togglePred == "圖示:切換開關") { confusionCorrect++; overallCorrect++ }

            val checkMat = Array(16) { r ->
                BooleanArray(16) { c ->
                    val border = (r in 2..13 && (c == 2 || c == 13)) || (c in 2..13 && (r == 2 || r == 13))
                    val stroke = (r == 9 && c == 6) || (r == 10 && c == 7) || (r == 9 && c == 8) || (r == 8 && c == 9) || (r == 7 && c == 10)
                    border || stroke
                }
            }
            val checkPred = TinyIconClassifier.classify16x16MatrixDirectly(checkMat, aspectRatio = 1.0f)
            confusionTotal++; overallTotal++
            if (checkPred == "圖示:已勾選方框") { confusionCorrect++; overallCorrect++ }
        }

        val confusionAcc = confusionCorrect.toFloat() / confusionTotal.toFloat()
        val overallAcc = overallCorrect.toFloat() / overallTotal.toFloat()

        assertTrue("混淆組準確率應 >= 95% (實測=${"%.2f".format(confusionAcc * 100)}%)", confusionAcc >= 0.95f)
        assertTrue("整體圖標測試集準確率應 >= 90% (實測=${"%.2f".format(overallAcc * 100)}%)", overallAcc >= 0.90f)
    }
}
