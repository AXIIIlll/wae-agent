package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 階層式長指令思考與「大塊 App 切分 + 塊內極簡碎化 + 執行層上下文隔離」完整測試套件
 */
class HierarchicalCommandChunkerTest {

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
        gridY: Int = 400
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = if (tag == "INP") "android.widget.EditText" else "android.widget.Button",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
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
    fun `test 1 app multi-step instruction segments into 1 macro block and shatters into atomic tasks`() {
        val cmd = "去YouTube查周杰倫然後點第一個影片然後按讚"
        val plan = HierarchicalCommandChunker.chunkInstruction(cmd)

        // 1 個 App -> 1 個大塊 (AppMacroBlock)
        assertEquals("只用到 YouTube 1 個 App，必須切為 1 個大塊", 1, plan.totalAppBlocks)
        assertEquals(1, plan.macroBlocks.size)
        val block1 = plan.macroBlocks[0]
        assertEquals("YouTube", block1.targetAppHint)

        // 塊內打碎為 4 個極簡步驟：1. 打開 YouTube -> 2. 搜尋 周杰倫 -> 3. 搜尋 周杰倫 並 第一個影片 -> 4. 點擊 讚
        assertEquals(4, block1.atomicTasks.size)
        assertEquals("打開 YouTube", block1.atomicTasks[0].simplifiedMotorGoal)
        assertEquals("搜尋 周杰倫", block1.atomicTasks[1].simplifiedMotorGoal)
        assertEquals("周杰倫", block1.atomicTasks[1].inputPayload)
        assertTrue(block1.atomicTasks[2].targetSemanticPhrase.contains("第一個影片"))
        assertEquals("點擊 讚", block1.atomicTasks[3].simplifiedMotorGoal)
        assertEquals("讚", block1.atomicTasks[3].targetSemanticPhrase)

        // 確認極簡指令中不含任何「然後 / 接著」連接詞
        for (task in block1.atomicTasks) {
            assertFalse(task.simplifiedMotorGoal.contains("然後"))
            assertFalse(task.simplifiedMotorGoal.contains("接著"))
        }
    }

    @Test
    fun `test 1 app settings multiple toggles stay in 1 macro block and isolate each setting`() {
        val cmd = "去設定把Wi-Fi打開然後把藍牙關掉接著打開深色模式"
        val plan = HierarchicalCommandChunker.chunkInstruction(cmd)

        // 1 個 App (設定) -> 1 個大塊
        assertEquals(1, plan.totalAppBlocks)
        val block = plan.macroBlocks.first()
        assertEquals("設定", block.targetAppHint)

        // 塊內 4 個原子任務：1. 打開 設定 -> 2. 開啟 Wi-Fi -> 3. 關閉 藍牙 -> 4. 開啟 深色模式
        assertEquals(4, block.atomicTasks.size)
        assertEquals("打開 設定", block.atomicTasks[0].simplifiedMotorGoal)
        assertEquals("開啟 Wi-Fi", block.atomicTasks[1].simplifiedMotorGoal)
        assertEquals(true, block.atomicTasks[1].desiredToggleState)
        assertEquals("關閉 藍牙", block.atomicTasks[2].simplifiedMotorGoal)
        assertEquals(false, block.atomicTasks[2].desiredToggleState)
        assertEquals("開啟 深色模式", block.atomicTasks[3].simplifiedMotorGoal)
        assertEquals(true, block.atomicTasks[3].desiredToggleState)
    }

    @Test
    fun `test 2 apps compound instruction segments into 2 macro blocks in strict order`() {
        val cmd = "先打開設定開啟藍牙，然後去YouTube查周杰倫然後點第一個影片"
        val plan = HierarchicalCommandChunker.chunkInstruction(cmd)

        // 2 個 App -> 2 個大塊
        assertEquals("用到設定與 YouTube 2 個 App，必須切為 2 個大塊", 2, plan.totalAppBlocks)
        assertEquals("設定", plan.macroBlocks[0].targetAppHint)
        assertEquals("YouTube", plan.macroBlocks[1].targetAppHint)

        // 大塊 #1 (設定): 打開 設定 -> 開啟 藍牙
        assertEquals(2, plan.macroBlocks[0].atomicTasks.size)
        assertEquals("打開 設定", plan.macroBlocks[0].atomicTasks[0].simplifiedMotorGoal)
        assertEquals("開啟 藍牙", plan.macroBlocks[0].atomicTasks[1].simplifiedMotorGoal)

        // 大塊 #2 (YouTube): 打開 YouTube -> 搜尋 周杰倫 -> 搜尋 周杰倫 並 第一個影片
        assertEquals(3, plan.macroBlocks[1].atomicTasks.size)
        assertEquals("打開 YouTube", plan.macroBlocks[1].atomicTasks[0].simplifiedMotorGoal)
        assertEquals("搜尋 周杰倫", plan.macroBlocks[1].atomicTasks[1].simplifiedMotorGoal)
        assertTrue(plan.macroBlocks[1].atomicTasks[2].targetSemanticPhrase.contains("第一個影片"))
    }

    @Test
    fun `test 3 apps sequential instruction segments into 3 macro blocks`() {
        val cmd = "先去Chrome查台北天氣，然後去時鐘設明早七點鬧鐘，最後去備忘錄寫帶雨傘"
        val plan = HierarchicalCommandChunker.chunkInstruction(cmd)

        // 3 個 App -> 3 個大塊 (Chrome -> 時鐘 -> 備忘錄)
        assertEquals("用到 3 個 App，必須切為 3 個大塊", 3, plan.totalAppBlocks)
        assertEquals("Chrome", plan.macroBlocks[0].targetAppHint)
        assertEquals("時鐘", plan.macroBlocks[1].targetAppHint)
        assertEquals("備忘錄", plan.macroBlocks[2].targetAppHint)

        // 檢查大塊 #3 (備忘錄) 直接寫入「帶雨傘」
        val block3Tasks = plan.macroBlocks[2].atomicTasks
        assertTrue(block3Tasks.any { it.actionKind == HierarchicalCommandChunker.AtomicActionKind.LAUNCH_APP && it.targetAppHint == "備忘錄" })
        assertTrue(block3Tasks.any { it.inputPayload == "帶雨傘" })
    }

    @Test
    fun `test motor layer context isolation prevents future steps from polluting current step button selection`() {
        val cmd = "去設定把Wi-Fi打開然後把藍牙關掉"
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(cmd, null, false)
        assertEquals(3, subGoals.size) // 0: LAUNCH_APP(設定), 1: 開啟 Wi-Fi, 2: 關閉 藍牙

        val wifiNode = makeNode("n_wifi", 1, "CHK", "Wi-Fi", gridX = 500, gridY = 300)
        val btNode = makeNode("n_bt", 2, "CHK", "藍牙", gridX = 500, gridY = 500)
        val settingsScreen = makeReport(listOf(wifiNode, btNode))

        // 當執行子目標 1 (開啟 Wi-Fi) 時，動手模型只會收到「開啟 Wi-Fi」，必須精準點選 #1 Wi-Fi
        val decStep1 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = settingsScreen,
            context = null,
            currentForegroundPackage = "com.android.settings",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 1
        )
        assertEquals(AiActionType.TAP, decStep1.actionType)
        assertEquals("n_wifi", decStep1.targetNode?.nodeId)

        // 當推進至子目標 2 (關閉 藍牙) 時，動手模型只會收到「關閉 藍牙」，必須精準點選 #2 藍牙
        val decStep2 = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = settingsScreen,
            context = null,
            currentForegroundPackage = "com.android.settings",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 2
        )
        assertEquals(AiActionType.TAP, decStep2.actionType)
        assertEquals("n_bt", decStep2.targetNode?.nodeId)
    }

    @Test
    fun `test post-search follow-up action in same app block is not trapped in search result stage`() {
        val cmd = "去YouTube查周杰倫然後點第一個影片然後按讚"
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(cmd, null, false)
        assertEquals(4, subGoals.size)

        // 模擬已經完成步驟 1 (搜尋 周杰倫) 與步驟 2 (點擊第一個影片)，現在進入步驟 3 (點擊 讚)
        InAppWorkingMemory.ensureGoalContext(cmd)
        InAppWorkingMemory.recordTextTypedAndSubmitted("周杰倫", "INP|搜尋")

        val videoTitleNode = makeNode("v_title", 1, "TXT", "周杰倫 晴天 Official MV", gridX = 500, gridY = 350)
        val likeButtonNode = makeNode("btn_like", 2, "BTN", "讚", gridX = 200, gridY = 620)
        val shareButtonNode = makeNode("btn_share", 3, "BTN", "分享", gridX = 500, gridY = 620)
        val playerScreen = makeReport(listOf(videoTitleNode, likeButtonNode, shareButtonNode))

        val decLikeStep = DualLocalAiCoordinator.decide(
            mode = LocalAiBackendMode.DUAL_FUSION,
            userGoal = cmd,
            report = playerScreen,
            context = null,
            currentForegroundPackage = "com.google.android.youtube",
            alreadyLaunchedApp = true,
            activeSubGoalIndex = 3
        )

        // 因為步驟 3 的極簡指令是「點擊 讚」（不含「搜尋 周杰倫」），不會被舊搜尋詞卡在 Stage C 重點影片標題，而是精準點擊「讚」！
        assertEquals(AiActionType.TAP, decLikeStep.actionType)
        assertEquals("btn_like", decLikeStep.targetNode?.nodeId)
    }

    @Test
    fun `test google browser stock lookup and throw text to line person cross app chunking`() {
        val cmd = "去google瀏覽器查一下今天股票然後把文字丟給line的一個人"

        // 1. 確保「查一下」與「一個人」絕不會被 checkCandidateFollowUp 誤判為點選第 1 個候選
        assertEquals(null, AndroidNativeAiEngine.checkCandidateFollowUp(cmd))
        assertEquals(1, AndroidNativeAiEngine.checkCandidateFollowUp("第一個"))
        assertEquals(2, AndroidNativeAiEngine.checkCandidateFollowUp("選2"))

        // 2. 驗證兩大塊 App 切分 (大塊 #1: Chrome, 大塊 #2: LINE) 與塊內極簡化步驟
        val plan = HierarchicalCommandChunker.chunkInstruction(cmd)
        assertEquals(2, plan.totalAppBlocks)
        assertEquals(2, plan.macroBlocks.size)

        val block1 = plan.macroBlocks[0]
        assertEquals("Chrome", block1.targetAppHint)
        assertTrue(block1.exportsDataToNextBlock)
        assertEquals(3, block1.atomicTasks.size)
        assertEquals("打開 Chrome", block1.atomicTasks[0].simplifiedMotorGoal)
        assertEquals("搜尋 今天股票", block1.atomicTasks[1].simplifiedMotorGoal)
        assertEquals("今天股票", block1.atomicTasks[1].inputPayload)
        assertEquals("擷取 今天股票", block1.atomicTasks[2].simplifiedMotorGoal)

        val block2 = plan.macroBlocks[1]
        assertEquals("LINE", block2.targetAppHint)
        assertTrue(block2.consumesDataFromPrevBlock)
        assertEquals("一個人", block2.recipientOrNoteTarget)
        assertEquals(3, block2.atomicTasks.size)
        assertEquals("打開 LINE", block2.atomicTasks[0].simplifiedMotorGoal)
        assertEquals("點擊 一個人", block2.atomicTasks[1].simplifiedMotorGoal)
        assertEquals("輸入跨App擷取內容", block2.atomicTasks[2].simplifiedMotorGoal)
    }

    @Test
    fun `test unpunctuated gmail dinner location subway extraction and google maps route planning chunking`() {
        val cmd = "去gmail找到一封最近提到聚餐地點的信把地鐵抓出來直接打開google地圖搜尋并規劃路線"
        val plan = HierarchicalCommandChunker.chunkInstruction(cmd)

        // 1. 驗證精準切為 2 個 App 大塊：大塊 #1 (Gmail) -> 大塊 #2 (地圖)
        assertEquals(2, plan.totalAppBlocks)
        assertEquals(2, plan.macroBlocks.size)

        val block1 = plan.macroBlocks[0]
        assertEquals("Gmail", block1.targetAppHint)
        assertTrue(block1.exportsDataToNextBlock)
        // 大塊 #1 (Gmail): 1. 打開 Gmail -> 2. 搜尋 聚餐 -> 3. 點選 聚餐 結果 (進入信件內文頁) -> 4. 擷取 地鐵
        assertEquals(4, block1.atomicTasks.size)
        assertEquals("打開 Gmail", block1.atomicTasks[0].simplifiedMotorGoal)
        assertEquals("搜尋 聚餐", block1.atomicTasks[1].simplifiedMotorGoal)
        assertEquals("聚餐", block1.atomicTasks[1].inputPayload)
        assertEquals(HierarchicalCommandChunker.AtomicActionKind.SELECT_RESULT, block1.atomicTasks[2].actionKind)
        assertEquals("點選 聚餐 結果", block1.atomicTasks[2].simplifiedMotorGoal)
        assertEquals(HierarchicalCommandChunker.AtomicActionKind.EXTRACT_SCREEN_INFO, block1.atomicTasks[3].actionKind)
        assertEquals("擷取 地鐵", block1.atomicTasks[3].simplifiedMotorGoal)
        assertEquals("地鐵", block1.atomicTasks[3].targetSemanticPhrase)

        val block2 = plan.macroBlocks[1]
        assertEquals("地圖", block2.targetAppHint)
        assertTrue(block2.consumesDataFromPrevBlock)
        // 大塊 #2 (地圖): 1. 打開 地圖 -> 2. 搜尋 跨App擷取內容 ({{CROSS_APP_CLIPBOARD}}) -> 3. 點擊 路線
        assertEquals(3, block2.atomicTasks.size)
        assertEquals("打開 地圖", block2.atomicTasks[0].simplifiedMotorGoal)
        assertEquals(HierarchicalCommandChunker.AtomicActionKind.INPUT_SEARCH, block2.atomicTasks[1].actionKind)
        assertEquals(CrossAppWorkflowEngine.CLIPBOARD_PLACEHOLDER, block2.atomicTasks[1].inputPayload)
        assertEquals(HierarchicalCommandChunker.AtomicActionKind.TAP_TARGET, block2.atomicTasks[2].actionKind)
        assertEquals("點擊 路線", block2.atomicTasks[2].simplifiedMotorGoal)
        assertEquals("路線", block2.atomicTasks[2].targetSemanticPhrase)
    }
}
