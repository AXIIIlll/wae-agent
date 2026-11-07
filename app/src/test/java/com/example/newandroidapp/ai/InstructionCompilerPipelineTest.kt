package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 三階段指令編譯管線（Instruction Compiler Pipeline）專項單元測試：
 * 1. 第一階段：中文複合長句切分器（Deterministic Clause Splitter）
 * 2. 第二階段：受限的「6 大標準動作原語」映射（Canonical Action Primitives Mapping）
 * 3. 第三階段：執行期的「單步視野隔離（Single-Step Tunnel Vision）」與硬性驗收條件（Exit Condition）
 */
class InstructionCompilerPipelineTest {

    @Before
    fun setUp() {
        DualLocalAiCoordinator.resetPlanScriptCache()
        CrossAppWorkflowEngine.resetClipboard()
        InAppWorkingMemory.resetForNewGoal("")
    }

    private fun pt(x: Int, y: Int): Point = Point(x, y).apply { this.x = x; this.y = y }

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    private fun makeNode(
        id: String,
        markId: Int,
        tag: String,
        label: String,
        gridX: Int = 500,
        gridY: Int = 400,
        inlineStatePrefix: String = ""
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = if (tag == "INP") "android.widget.EditText" else "android.widget.TextView",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = inlineStatePrefix,
            rawOriginalBounds = rect(100, gridY * 2, 900, gridY * 2 + 100),
            convergedBounds = rect(100, gridY * 2, 900, gridY * 2 + 100),
            finalTapPx = pt(gridX, gridY * 2),
            gridTapPoint = pt(gridX, gridY),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
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
    fun testStage1_DeterministicClauseSplitterHandlesConjunctionsAndCrossVerbStructures() {
        // 1. 測試跨動詞無標點結構：「打開Chrome搜尋6767記下來」
        val clauses1 = InstructionCompilerPipeline.splitIntoAtomicClauses("打開Chrome搜尋6767記下來")
        assertEquals(3, clauses1.size)
        assertEquals("打開 Chrome", clauses1[0].normalizedClause)
        assertEquals("搜尋6767", clauses1[1].normalizedClause)
        assertEquals("記下來", clauses1[2].normalizedClause)

        // 2. 測試跨動詞無標點結構：「打開YouTube搜尋周杰倫點第一個影片」
        val clauses2 = InstructionCompilerPipeline.splitIntoAtomicClauses("打開YouTube搜尋周杰倫點第一個影片")
        assertEquals(3, clauses2.size)
        assertEquals("打開 YouTube", clauses2[0].normalizedClause)
        assertEquals("搜尋周杰倫", clauses2[1].normalizedClause)
        assertEquals("點第一個影片", clauses2[2].normalizedClause)

        // 3. 測試複合連接詞（「然後、接著、再、之後、並、最後、以及」）：
        val clauses3 = InstructionCompilerPipeline.splitIntoAtomicClauses(
            "先去設定把Wi-Fi打開，然後把藍牙關掉，接著開啟深色模式，以及往下滑，最後返回"
        )
        assertEquals(6, clauses3.size)
        assertEquals("打開 設定", clauses3[0].normalizedClause)
        assertEquals("把Wi-Fi打開", clauses3[1].normalizedClause)
        assertEquals("把藍牙關掉", clauses3[2].normalizedClause)
        assertEquals("開啟深色模式", clauses3[3].normalizedClause)
        assertEquals("往下滑", clauses3[4].normalizedClause)
        assertEquals("返回", clauses3[5].normalizedClause)
    }

    @Test
    fun testStage2_CanonicalActionPrimitivesMappingStrictSixPrimitives() {
        // 驗證系統嚴格僅定義 6 種標準動作原語
        val allTypes = CanonicalActionPrimitiveType.values().map { it.name }.toSet()
        assertEquals(
            setOf(
                "LAUNCH_APP",
                "SEARCH_IN_APP",
                "TAP_ELEMENT",
                "INPUT_TEXT",
                "EXTRACT_TO_MEMORY",
                "SYSTEM_NAV"
            ),
            allTypes
        )

        // 案例 A：「打開Chrome搜尋6767記下來」
        val planA = InstructionCompilerPipeline.compile("打開Chrome搜尋6767記下來")
        assertEquals(
            listOf(
                "LAUNCH_APP(\"Chrome\")",
                "SEARCH_IN_APP(\"6767\")",
                "EXTRACT_TO_MEMORY(\"6767\")",
                "LAUNCH_APP(\"備忘錄\")",
                "TAP_ELEMENT(\"新增\")",
                "INPUT_TEXT(\"\$MEMORY\")"
            ),
            planA.primitives.map { it.formalCall }
        )

        // 案例 B：「去gmail找到一封最近提到聚餐地點的信把地鐵抓出來直接打開google地圖搜尋并規劃路線」
        val planB = InstructionCompilerPipeline.compile(
            "去gmail找到一封最近提到聚餐地點的信把地鐵抓出來直接打開google地圖搜尋并規劃路線"
        )
        assertEquals(
            listOf(
                "LAUNCH_APP(\"Gmail\")",
                "SEARCH_IN_APP(\"聚餐\")",
                "TAP_ELEMENT(\"聚餐\")",
                "EXTRACT_TO_MEMORY(\"地鐵\")",
                "LAUNCH_APP(\"地圖\")",
                "SEARCH_IN_APP(\"\$MEMORY\")",
                "TAP_ELEMENT(\"路線\")"
            ),
            planB.primitives.map { it.formalCall }
        )

        // 案例 C：系統導航原語映射
        val planC = InstructionCompilerPipeline.compile("往下滑然後返回")
        assertEquals(
            listOf(
                "SYSTEM_NAV(\"SCROLL_DOWN\")",
                "SYSTEM_NAV(\"BACK\")"
            ),
            planC.primitives.map { it.formalCall }
        )
    }

    @Test
    fun testStage3_SingleStepTunnelVisionAndHardExitConditionGate() {
        val rawLongCmd = "打開Chrome搜尋6767然後打開備忘錄記下來"
        val plan = InstructionCompilerPipeline.compile(rawLongCmd)
        val searchStep = plan.subGoals[1] // SEARCH_IN_APP("6767")

        // 1. 驗證單步視野隔離：底層僅能收到 SEARCH_IN_APP("6767") 對應的「搜尋 6767」，完全不含「備忘錄」或「記下來」
        val tunnel = InstructionCompilerPipeline.buildSingleStepTunnelVision(searchStep)
        assertEquals("SEARCH_IN_APP(\"6767\")", tunnel.formalPrimitiveCall)
        assertEquals("搜尋 6767", tunnel.isolatedMotorGoal)
        assertFalse(tunnel.isolatedMotorGoal.contains("備忘錄"))
        assertFalse(tunnel.isolatedMotorGoal.contains("記下來"))
        assertFalse(tunnel.isolatedMotorGoal.contains("然後"))

        // 2. 驗證 SEARCH_IN_APP("6767") 的硬性驗收條件（Exit Condition）：
        // 情況 2A：雖然剛把 "6767" 填入搜尋框，但畫面上僅有搜尋輸入框 INP("6767") 與「搜尋」按鈕，非輸入框結果區尚未出現 "6767"
        InAppWorkingMemory.ensureGoalContext(rawLongCmd)
        InAppWorkingMemory.recordTextTypedAndSubmitted("6767", "搜尋或輸入網址")

        val unverifiedSearchScreen = makeReport(
            listOf(
                makeNode("inp_1", 1, "INP", "6767", gridX = 450, gridY = 120, inlineStatePrefix = "[FOC]"),
                makeNode("btn_go", 2, "BTN", "搜尋", gridX = 900, gridY = 120)
            )
        )
        val verdictBeforeResults = InstructionCompilerPipeline.evaluateExitCondition(
            subGoal = searchStep,
            report = unverifiedSearchScreen,
            currentForegroundPackage = "com.android.chrome",
            alreadyLaunchedApp = true
        )
        assertFalse(
            "非輸入框之結果區尚未出現該關鍵字時，Exit Condition 必須為 false，禁止搶跑！",
            verdictBeforeResults.satisfied
        )

        // 驗證此時呼叫 DualLocalAiCoordinator.decide(activeSubGoalIndex = 1) 絕不會搶跑跳到 EXTRACT_TO_MEMORY！
        val decWhileUnverified = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = rawLongCmd,
            report = unverifiedSearchScreen,
            context = null,
            currentForegroundPackage = "com.android.chrome",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 1
        )
        assertFalse(
            "尚未滿足 SEARCH_IN_APP 硬性驗收條件時，禁止提前執行 EXTRACT_CROSS_APP",
            decWhileUnverified.compactOpcode.startsWith("EXTRACT_CROSS_APP")
        )

        // 情況 2B：畫面之非輸入框結果區（TXT）已出現關鍵字 "6767" -> 硬性驗收條件 100% 達成，自動推進至 EXTRACT_TO_MEMORY("6767")！
        val verifiedResultScreen = makeReport(
            listOf(
                makeNode("inp_1", 1, "INP", "6767", gridX = 450, gridY = 120),
                makeNode("res_1", 2, "TXT", "6767 查詢結果：今日收盤價 152.0 元", gridX = 500, gridY = 380),
                makeNode("res_2", 3, "TXT", "6767 歷史走勢與財報摘要", gridX = 500, gridY = 580)
            )
        )
        val verdictAfterResults = InstructionCompilerPipeline.evaluateExitCondition(
            subGoal = searchStep,
            report = verifiedResultScreen,
            currentForegroundPackage = "com.android.chrome",
            alreadyLaunchedApp = true
        )
        assertTrue(
            "非輸入框結果區已出現關鍵字 6767，Exit Condition 必須 100% 達成",
            verdictAfterResults.satisfied
        )

        val decAfterVerified = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = rawLongCmd,
            report = verifiedResultScreen,
            context = null,
            currentForegroundPackage = "com.android.chrome",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 1
        )
        assertTrue(
            "Exit Condition 達成後，狀態機應推進至下一步 EXTRACT_TO_MEMORY 並提取結果",
            decAfterVerified.compactOpcode.startsWith("EXTRACT_CROSS_APP")
        )
        assertTrue(CrossAppWorkflowEngine.latestClipboardEntry?.extractedText.orEmpty().contains("152.0"))
    }

    @Test
    fun testExtractToMemoryAsFinalStepRevokesMotorPermissionAndOutputsWithoutUiTap() {
        val cmd = "去gmail找到一封最近提到聚餐地點的信把地鐵抓出來"
        InAppWorkingMemory.grantMotorPermission(cmd)
        val plan = InstructionCompilerPipeline.compile(cmd)

        // 確認最後一步為 EXTRACT_TO_MEMORY("地鐵")
        val lastPrimitive = plan.primitives.last()
        assertEquals(CanonicalActionPrimitiveType.EXTRACT_TO_MEMORY, lastPrimitive.type)
        assertEquals("EXTRACT_TO_MEMORY(\"地鐵\")", lastPrimitive.formalCall)

        val emailDetailScreen = makeReport(
            listOf(
                makeNode("e1", 1, "TXT", "本週五聚餐通知", gridX = 500, gridY = 220),
                makeNode("e2", 2, "TXT", "請大家在捷運忠孝復興站4號出口集合出發", gridX = 500, gridY = 440)
            )
        )

        val finalStepIdx = plan.subGoals.lastIndex
        val dec = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = emailDetailScreen,
            context = null,
            currentForegroundPackage = "com.google.android.gm",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = finalStepIdx
        )

        // 純內部資料擷取：不點擊任何 UI 元件 (targetNode == null, tapPixel == null)
        assertEquals(AiActionType.ANSWER_QUESTION, dec.actionType)
        assertNull(dec.targetNode)
        assertNull(dec.tapPixel)
        assertEquals("捷運忠孝復興站4號出口", dec.inputTextValue)
        assertEquals("捷運忠孝復興站4號出口", CrossAppWorkflowEngine.resolveDynamicPayload("\$MEMORY"))
        assertFalse("EXTRACT_TO_MEMORY 為最後一步時必須立即撤銷動手權限", InAppWorkingMemory.hasMotorPermission())
    }
}
