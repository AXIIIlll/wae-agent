package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.BackwardChainingPlanner
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.stability.TerminalVerb
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandLexiconAndArbiterTest {

    @Before
    fun setUp() {
        InAppWorkingMemory.resetForNewGoal("")
        OpenSourceAiDatabaseManager.ensureCoreDatabasesLoaded(null)
        CommandLexiconEngine.ensureLoaded(null)
    }

    private fun makeNode(
        id: String,
        markId: Int,
        tag: String,
        label: String,
        x: Int,
        y: Int,
        role: String = ""
    ): Stage2ConvergedNode {
        val bounds = Rect(x - 60, y - 24, x + 60, y + 24)
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = if (tag == "INP") "android.widget.EditText" else "android.widget.Button",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = bounds,
            convergedBounds = bounds,
            finalTapPx = Point(x, y).apply { this.x = x; this.y = y },
            gridTapPoint = Point((x * 1000) / 1080, (y * 1000) / 2400).apply {
                this.x = (x * 1000) / 1080
                this.y = (y * 1000) / 2400
            },
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE,
            visualConfidence = 0.95f,
            predictedFunctionRole = role,
            functionRoleConfidence = if (role.isNotEmpty()) 0.92f else 0.0f,
            packageName = "com.google.android.apps.maps"
        )
    }

    private fun makeReport(nodes: List<Stage2ConvergedNode>): VisionPipelineReport {
        return VisionPipelineReport(
            timestampMs = 1000L,
            screenWidth = 1080,
            screenHeight = 2400,
            gridCols = 32,
            gridRows = 64,
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
    fun testThreeTierLexiconLoadedAndSchemaComplete() {
        val total = CommandLexiconEngine.getLexiconSize(null)
        assertTrue("Expected >= 6000 tagged lexicon entries, got $total", total >= 6000)

        val summary = CommandLexiconEngine.buildQualitySummary(null)
        assertTrue(summary.contains("L1自動(A)="))
        assertTrue(summary.contains("L2半自動(S)="))
        assertTrue(summary.contains("L3人工精標(M)="))

        val routeEntry = CommandLexiconEngine.lookup("行車路線", null)
        assertNotNull("行車路線 must exist in command_lexicon.tsv", routeEntry)
        assertEquals('B', routeEntry?.primaryCode)
        assertTrue(CommandLexiconEngine.isKnownUiButtonNoun("行車路線", null))
        assertTrue(CommandLexiconEngine.isKnownUiButtonNoun("規劃路線", null))
    }

    @Test
    fun testMultiSenseDisambiguationByContextCondition() {
        // 1. 「開啟 LINE」 -> +A 命中 App 名稱 -> 'o' (LAUNCH_APP)
        val evalApp = CommandLexiconEngine.analyzeClauseActionAndTarget(
            clause = "開啟 LINE",
            appHint = "LINE",
            report = null,
            context = null
        )
        assertEquals('o', evalApp?.resolvedPrimaryCode)

        // 2. 「開啟 藍牙」 -> +W 命中系統開關 -> 'w' (TOGGLE_SWITCH)
        val evalToggle = CommandLexiconEngine.analyzeClauseActionAndTarget(
            clause = "開啟藍牙",
            appHint = "設定",
            report = null,
            context = null
        )
        assertEquals('w', evalToggle?.resolvedPrimaryCode)

        // 3. 「開啟 行車路線」 -> +S/B 命中畫面按鈕名詞 -> 'c' (TAP_TARGET)
        val evalButton = CommandLexiconEngine.analyzeClauseActionAndTarget(
            clause = "開啟行車路線",
            appHint = "地圖",
            report = null,
            context = null
        )
        assertEquals('c', evalButton?.resolvedPrimaryCode)
        assertTrue(evalButton?.isTargetUiButton == true)
    }

    @Test
    fun testAsrTypoGoogleMapsCommandChunkingNeverConfusesTapWithInput() {
        // 測試使用者反應的真實語音辨識錯字指令：「打開google地圖搜尋臺北101變點極行車路線」
        val rawCommand = "打開google地圖搜尋臺北101變點極行車路線"
        val plan = HierarchicalCommandChunker.chunkInstruction(rawCommand, null)

        assertEquals(1, plan.totalAppBlocks)
        val subGoals = plan.flattenedSubGoals
        assertEquals(
            "Expected 3 subGoals: LAUNCH_APP -> INPUT_SEARCH(臺北101) -> TAP_TARGET(行車路線), got: ${subGoals.map { "${it.intentCategory}:${it.targetSemanticPhrase}(payload=${it.inputPayload})" }}",
            3,
            subGoals.size
        )

        // Step 1: 打開 地圖
        assertEquals("LAUNCH_APP", subGoals[0].intentCategory)
        assertEquals("地圖", subGoals[0].targetAppHint)

        // Step 2: 搜尋 臺北101 (INPUT_SEARCH)
        assertEquals("INPUT_SEARCH", subGoals[1].intentCategory)
        assertEquals("臺北101", subGoals[1].inputPayload)

        // Step 3: 點擊 行車路線 (必須是 TAP_TARGET，且 inputPayload 必須為 null，絕不可當成搜尋詞！)
        assertEquals("TAP_TARGET", subGoals[2].intentCategory)
        assertNull("TAP_TARGET must NEVER carry inputPayload", subGoals[2].inputPayload)
        assertEquals(CanonicalActionPrimitiveType.TAP_ELEMENT, subGoals[2].canonicalPrimitive?.type)

        // 驗證 BackwardChainingPlanner 對「點擊行車路線」不會落入 SEARCH_FIND
        val backwardSpec = BackwardChainingPlanner.extractTerminalGoalSpec("點擊行車路線")
        assertEquals(TerminalVerb.TAP_BUTTON, backwardSpec.terminalVerb)
    }

    @Test
    fun testGoogleMapsPlaceSheetTapsDirectionsOnceAndNeverWildlyClicksOrTypesIntoInput() {
        val rawCommand = "打開google地圖搜尋臺北101變點極行車路線"
        InAppWorkingMemory.resetForNewGoal(rawCommand)
        InAppWorkingMemory.recordAppLaunched("com.google.android.apps.maps")
        // 模擬已完成「臺北101」搜尋送出，畫面進入台北 101 地點詳情卡 (Place Sheet)
        InAppWorkingMemory.recordTextTypedAndSubmitted("臺北101", "maps_search")

        val placeSheetNodes = listOf(
            makeNode("maps:id/search_box", 1, "INP", "臺北101", 540, 140, "SEARCH_INPUT_BOX"),
            makeNode("maps:id/title", 2, "TXT", "台北101", 320, 980, ""),
            makeNode("maps:id/address", 3, "BTN", "信義路五段7號", 360, 1080, ""),
            makeNode("maps:id/directions_chip", 4, "BTN", "路線", 200, 1260, "MAP_DIRECTIONS_ROUTE"),
            makeNode("maps:id/start_chip", 5, "BTN", "開始", 420, 1260, "MAP_START_NAVIGATION"),
            makeNode("maps:id/call_chip", 6, "BTN", "通話", 640, 1260, "CALL_PHONE_CONTACT"),
            makeNode("maps:id/save_chip", 7, "BTN", "儲存", 820, 1260, "LIKE_FAVORITE_BOOKMARK"),
            makeNode("maps:id/share_chip", 8, "BTN", "分享", 960, 1260, "SHARE_FORWARD")
        )
        val placeSheetReport = makeReport(placeSheetNodes)

        // Tick 1: 在地點詳情卡上，AI 必須推進至 Step 3「點擊 行車路線」，並精準點擊 #4「路線」按鈕（絕不可在 #1 INP 輸入「行車路線」）
        val decision1 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = rawCommand,
            report = placeSheetReport,
            context = null,
            alreadyLaunchedApp = true,
            currentForegroundPackage = "com.google.android.apps.maps"
        )
        assertEquals("Tick 1 must be TAP, never INPUT_TEXT", AiActionType.TAP, decision1.actionType)
        assertNotNull(decision1.targetNode)
        assertEquals("路線", decision1.targetNode?.label)
        assertNull("Tick 1 must NEVER output inputTextValue", decision1.inputTextValue)

        // Tick 2: 若點擊「路線」後畫面尚未跳轉（或再次評估同一畫面），AI 必須透過 ExitCondition 認出「路線」已點過並立即回傳 FINISH_DONE (F1)，
        // 100% 嚴禁瘋狂點擊「開始 / 通話 / 儲存 / 分享 / 信義路五段7號」或跑去 #1 輸入框打字！
        val decision2 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = rawCommand,
            report = placeSheetReport,
            context = null,
            alreadyLaunchedApp = true,
            currentForegroundPackage = "com.google.android.apps.maps"
        )
        assertEquals(
            "After clicking '路線', AI must finish (FINISH_DONE) instead of clicking other buttons or typing into INP! Got: ${decision2.actionType} on ${decision2.targetNode?.label}",
            AiActionType.FINISH_DONE,
            decision2.actionType
        )

        // Case 3: 若畫面上完全沒有「路線」按鈕（例如被滑出畫面外，只剩輸入框與其他無關按鈕），
        // AI 必須判定無匹配按鈕而執行 SCROLL_DOWN，100% 嚴禁亂點「信義路五段7號 / 通話 / 叫車」或把「行車路線」輸入進 INP！
        InAppWorkingMemory.resetForNewGoal(rawCommand)
        InAppWorkingMemory.recordAppLaunched("com.google.android.apps.maps")
        InAppWorkingMemory.recordTextTypedAndSubmitted("臺北101", "maps_search")
        val noRouteNodes = listOf(
            makeNode("maps:id/search_box", 1, "INP", "臺北101", 540, 140, "SEARCH_INPUT_BOX"),
            makeNode("maps:id/title", 2, "TXT", "台北101", 320, 980, ""),
            makeNode("maps:id/address", 3, "BTN", "信義路五段7號", 360, 1080, ""),
            makeNode("maps:id/taxi_chip", 5, "BTN", "叫車", 420, 1260, ""),
            makeNode("maps:id/call_chip", 6, "BTN", "通話", 640, 1260, "CALL_PHONE_CONTACT"),
            makeNode("maps:id/save_chip", 7, "BTN", "儲存", 820, 1260, "LIKE_FAVORITE_BOOKMARK"),
            makeNode("maps:id/share_chip", 8, "BTN", "分享", 960, 1260, "SHARE_FORWARD")
        )
        val noRouteReport = makeReport(noRouteNodes)
        val decisionNoRoute = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = rawCommand,
            report = noRouteReport,
            context = null,
            alreadyLaunchedApp = true,
            currentForegroundPackage = "com.google.android.apps.maps"
        )
        assertNotEquals("Must NEVER type '行車路線' into INP", AiActionType.INPUT_TEXT, decisionNoRoute.actionType)
        assertNotEquals("Must NEVER click random unrelated buttons when target button is absent", AiActionType.TAP, decisionNoRoute.actionType)
        assertEquals("Must scroll down when target button is absent from viewport", AiActionType.SCROLL_DOWN, decisionNoRoute.actionType)
    }
}
