package com.example.newandroidapp.stability

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.ai.CooperativeSubGoal
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 通用逆向目標回推思維引擎 (BackwardChainingPlanner) 完整驗證測試套件
 * 覆蓋場景 A 至 F：
 * - 場景 A: 首頁直接命中 (Level 0) vs 逆向回推完整操作鏈 (L3B -> L3A -> L2 -> L1/L0)
 * - 場景 B: 口語前綴與語音辨識同音詞 (買茶歌 -> struct, 一首歌叫晴天 -> 晴天, 去蝦皮買藍牙耳機 -> 藍牙耳機)
 * - 場景 C: 嚴格禁止點擊語音輸入與麥克風/鏡頭按鈕
 * - 場景 D: 全原始碼、日誌與註解零 Emoji 合規掃描
 * - 場景 E: 100% 自研原創，零第三方依賴庫驗證
 * - 場景 F: 與 InAppOperationPlanner、ClosedLoopVerifier 之決策管線整合測試
 */
class BackwardChainingPlannerTest {

    @Before
    fun setUp() {
        InAppWorkingMemory.resetForNewGoal("")
    }

    private fun pt(x: Int, y: Int): Point = Point(x, y).apply {
        this.x = x
        this.y = y
    }

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
        bounds: Rect,
        gridPt: Point? = null,
        className: String = if (tag == "INP") "android.widget.EditText" else "android.widget.TextView",
        prefix: String = "",
        nanoDetCategory: String = "",
        visualConfidence: Float = 1.0f,
        sourceType: NodeSourceType = NodeSourceType.UI_TREE
    ): Stage2ConvergedNode {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        val calculatedGridPt = gridPt ?: pt((cx * 1000) / 1080, (cy * 1000) / 2340)
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = className,
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = prefix,
            rawOriginalBounds = bounds,
            convergedBounds = bounds,
            finalTapPx = pt(cx, cy),
            gridTapPoint = calculatedGridPt,
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = sourceType,
            nanoDetCategory = nanoDetCategory,
            visualConfidence = visualConfidence
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

    // =========================================================================
    // 場景 A: 目標在首頁 (Level 0) vs 目標不在首頁之逆向回推完整操作鏈
    // =========================================================================

    @Test
    fun testScenarioA_directTargetOnHomeScreen() {
        val goalSpec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        assertEquals("Spotify", goalSpec.appHint)
        assertEquals(TerminalVerb.PLAY, goalSpec.terminalVerb)
        assertEquals("struct", goalSpec.targetEntity)

        // 模擬 Spotify 首頁已釘選歌曲「struct」在主內容區 (gridY = 450)
        val songNode = makeNode(
            id = "spotify_song_struct",
            markId = 1,
            tag = "TXT",
            label = "struct",
            bounds = rect(60, 900, 1020, 1100),
            gridPt = pt(500, 450)
        )
        val bottomNav = makeNode(
            id = "spotify_bottom_search",
            markId = 2,
            tag = "BTN",
            label = "搜尋",
            bounds = rect(400, 2100, 680, 2300),
            gridPt = pt(500, 920)
        )

        val eval = BackwardChainingPlanner.evaluateBackwardChain(
            goalSpec = goalSpec,
            nodes = listOf(songNode, bottomNav),
            packageName = "com.spotify.music",
            hasSubmittedQuery = false
        )

        assertEquals(BackwardChainLevel.LEVEL_0_DIRECT_TARGET, eval.level)
        assertEquals(AiActionType.TAP, eval.actionType)
        assertNotNull(eval.targetNode)
        assertEquals("spotify_song_struct", eval.targetNode?.nodeId)
        assertTrue(eval.reasoningTrace.contains("已在主內容區"))
    }

    @Test
    fun testScenarioA_targetNotOnHomeScreen_fullBackwardChain() {
        val goalSpec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")

        // 步驟 1 (首頁無目標): 使用者將歌曲「struct」從首頁移開，首頁僅剩推薦與底部「搜尋」分頁
        val homeRecommend = makeNode(
            id = "home_rec_1",
            markId = 1,
            tag = "TXT",
            label = "今日熱門推薦",
            bounds = rect(60, 400, 1020, 600),
            gridPt = pt(500, 250)
        )
        val bottomSearchTab = makeNode(
            id = "bottom_tab_search",
            markId = 2,
            tag = "BTN",
            label = "搜尋",
            bounds = rect(400, 2100, 680, 2300),
            gridPt = pt(500, 920)
        )

        val evalStep1 = BackwardChainingPlanner.evaluateBackwardChain(
            goalSpec = goalSpec,
            nodes = listOf(homeRecommend, bottomSearchTab),
            packageName = "com.spotify.music",
            hasSubmittedQuery = false
        )
        assertEquals(BackwardChainLevel.LEVEL_3B_BOTTOM_SEARCH_TAB, evalStep1.level)
        assertEquals(AiActionType.TAP, evalStep1.actionType)
        assertEquals("bottom_tab_search", evalStep1.targetNode?.nodeId)
        assertTrue(evalStep1.reasoningTrace.contains("L3B"))

        // 步驟 2 (搜尋分頁): 點擊底部 Tab 進入搜尋探索頁，頂部出現假搜尋框「想聽什麼？」，底部 Tab 依然在
        val topFakeSearchBox = makeNode(
            id = "top_fake_search_box",
            markId = 3,
            tag = "TXT",
            label = "想聽什麼",
            bounds = rect(60, 120, 1020, 320),
            gridPt = pt(500, 100)
        )
        val evalStep2 = BackwardChainingPlanner.evaluateBackwardChain(
            goalSpec = goalSpec,
            nodes = listOf(topFakeSearchBox, bottomSearchTab),
            packageName = "com.spotify.music",
            hasSubmittedQuery = false
        )
        assertEquals(BackwardChainLevel.LEVEL_3A_EXPAND_SEARCH_BOX, evalStep2.level)
        assertEquals(AiActionType.TAP, evalStep2.actionType)
        assertEquals("top_fake_search_box", evalStep2.targetNode?.nodeId)
        assertTrue(evalStep2.reasoningTrace.contains("展開真實輸入框"))

        // 步驟 3 (真實輸入頁): 點開假搜尋框後彈出真實 EditText 輸入框 (INP)
        val realInputBox = makeNode(
            id = "real_search_edittext",
            markId = 4,
            tag = "INP",
            label = "搜尋",
            bounds = rect(120, 120, 960, 320),
            gridPt = pt(500, 100)
        )
        val evalStep3 = BackwardChainingPlanner.evaluateBackwardChain(
            goalSpec = goalSpec,
            nodes = listOf(realInputBox),
            packageName = "com.spotify.music",
            hasSubmittedQuery = false
        )
        assertEquals(BackwardChainLevel.LEVEL_2_INPUT_KEYWORD, evalStep3.level)
        assertEquals(AiActionType.INPUT_TEXT, evalStep3.actionType)
        assertEquals("struct", evalStep3.inputText)
        assertEquals("real_search_edittext", evalStep3.targetNode?.nodeId)
        assertTrue(evalStep3.reasoningTrace.contains("填入關鍵字"))

        // 步驟 4 (搜尋結果列表): 送出查詢後進入結果頁 (hasSubmittedQuery = true)
        val searchResultSong = makeNode(
            id = "search_result_struct_song",
            markId = 5,
            tag = "TXT",
            label = "struct - 歌曲",
            bounds = rect(60, 500, 1020, 750),
            gridPt = pt(500, 320)
        )
        val evalStep4 = BackwardChainingPlanner.evaluateBackwardChain(
            goalSpec = goalSpec,
            nodes = listOf(searchResultSong, bottomSearchTab),
            packageName = "com.spotify.music",
            hasSubmittedQuery = true
        )
        assertEquals(BackwardChainLevel.LEVEL_0_DIRECT_TARGET, evalStep4.level)
        assertEquals(AiActionType.TAP, evalStep4.actionType)
        assertEquals("search_result_struct_song", evalStep4.targetNode?.nodeId)
    }

    // =========================================================================
    // 場景 B: 口語前綴與語音辨識同音容錯測試
    // =========================================================================

    @Test
    fun testScenarioB_spokenAndHomophoneEntityExtraction() {
        // 1. 同音詞「買茶歌」-> PLAY, targetEntity = "struct"
        val spec1 = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        assertEquals("Spotify", spec1.appHint)
        assertEquals(TerminalVerb.PLAY, spec1.terminalVerb)
        assertEquals("struct", spec1.targetEntity)

        // 2. 贅詞「一首歌叫」-> targetEntity = "晴天"
        val spec2 = BackwardChainingPlanner.extractTerminalGoalSpec("打開spotify查詢一首歌叫晴天")
        assertEquals("Spotify", spec2.appHint)
        assertEquals("晴天", spec2.targetEntity)

        // 3. 電商購買「去蝦皮買藍牙耳機」-> BUY, targetEntity = "藍牙耳機"
        val spec3 = BackwardChainingPlanner.extractTerminalGoalSpec("去蝦皮買藍牙耳機")
        assertEquals("蝦皮", spec3.appHint)
        assertEquals(TerminalVerb.BUY, spec3.terminalVerb)
        assertEquals("藍牙耳機", spec3.targetEntity)

        // 4. 口語「聽周杰倫的晴天」-> PLAY
        val spec4 = BackwardChainingPlanner.extractTerminalGoalSpec("聽周杰倫的晴天")
        assertEquals(TerminalVerb.PLAY, spec4.terminalVerb)
        assertEquals("周杰倫的晴天", spec4.targetEntity)

        // 5. 跨步驟後續動作「在youtube搜尋周杰倫並播放第一首」
        val spec5 = BackwardChainingPlanner.extractTerminalGoalSpec("在youtube搜尋周杰倫並播放第一首")
        assertEquals("YouTube", spec5.appHint)
        assertEquals(TerminalVerb.SEARCH_FIND, spec5.terminalVerb)
        assertEquals("周杰倫", spec5.targetEntity)
        assertEquals("播放第一首", spec5.followUpAction)

        // 6. 系統設定開關「去設定開啟藍牙」
        val spec6 = BackwardChainingPlanner.extractTerminalGoalSpec("去設定開啟藍牙")
        assertEquals("設定", spec6.appHint)
        assertEquals(TerminalVerb.TOGGLE, spec6.terminalVerb)
        assertEquals("藍牙", spec6.targetEntity)
    }

    // =========================================================================
    // 場景 C: 嚴格禁止點擊語音輸入與麥克風/鏡頭按鈕
    // =========================================================================

    @Test
    fun testScenarioC_strictVoiceAndCameraBan() {
        val goalSpec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")

        val micButton = makeNode(
            id = "voice_search_mic_button",
            markId = 10,
            tag = "BTN",
            label = "語音搜尋",
            bounds = rect(800, 120, 960, 320),
            gridPt = pt(880, 100)
        )
        val lensButton = makeNode(
            id = "google_lens_camera_btn",
            markId = 11,
            tag = "BTN",
            label = "智慧鏡頭",
            bounds = rect(650, 120, 780, 320),
            gridPt = pt(720, 100)
        )
        val fakeSearchBox = makeNode(
            id = "valid_fake_search_box",
            markId = 12,
            tag = "TXT",
            label = "想聽什麼",
            bounds = rect(60, 120, 600, 320),
            gridPt = pt(300, 100)
        )

        // 驗證 isForbiddenVoiceOrCameraNode 判定
        assertTrue(InAppOperationPlanner.isForbiddenVoiceOrCameraNode(micButton))
        assertTrue(InAppOperationPlanner.isForbiddenVoiceOrCameraNode(lensButton))
        assertFalse(InAppOperationPlanner.isForbiddenVoiceOrCameraNode(fakeSearchBox))

        // 評估逆向回推：即使麥克風與鏡頭按鈕位置靠頂部，系統也嚴禁選擇它們，必須選擇純文字搜尋框
        val eval = BackwardChainingPlanner.evaluateBackwardChain(
            goalSpec = goalSpec,
            nodes = listOf(micButton, lensButton, fakeSearchBox),
            packageName = "com.spotify.music",
            hasSubmittedQuery = false
        )

        assertEquals(BackwardChainLevel.LEVEL_3A_EXPAND_SEARCH_BOX, eval.level)
        assertEquals("valid_fake_search_box", eval.targetNode?.nodeId)
    }

    // =========================================================================
    // 場景 D: 全原始碼、日誌與註解零 Emoji 合規掃描
    // =========================================================================

    @Test
    fun testScenarioD_zeroEmojiScan() {
        val filesToCheck = listOf(
            File("src/main/java/com/example/newandroidapp/stability/BackwardChainingPlanner.kt"),
            File("src/main/java/com/example/newandroidapp/stability/InAppOperationPlanner.kt"),
            File("src/main/java/com/example/newandroidapp/ai/DualLocalAiCoordinator.kt"),
            File("src/main/java/com/example/newandroidapp/ai/HierarchicalCommandChunker.kt"),
            File("src/main/java/com/example/newandroidapp/stability/ClosedLoopVerifier.kt"),
            File("src/test/java/com/example/newandroidapp/stability/BackwardChainingPlannerTest.kt"),
            File("src/test/java/com/example/newandroidapp/ai/HierarchicalCommandChunkerTest.kt")
        )

        // 涵蓋標準 Emoji 與 Unicode 符號區間的正規表達式（嚴禁誤報中文全形標點）
        val emojiPattern = java.util.regex.Pattern.compile("[\\u2600-\\u27BF]|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]")

        for (file in filesToCheck) {
            val targetFile = if (file.exists()) file else File("app", file.path)
            assertTrue("檔案必須存在: ${targetFile.path}", targetFile.exists())

            val content = targetFile.readText()
            val matcher = emojiPattern.matcher(content)
            if (matcher.find()) {
                val matchVal = matcher.group()
                val lineNum = content.substring(0, matcher.start()).lines().size
                assertFalse("在 ${targetFile.name} 第 $lineNum 行發現 Emoji: '$matchVal'，違反零 Emoji 規範", true)
            }
        }
    }

    // =========================================================================
    // 場景 E: 100% 自研原創，零第三方依賴庫驗證
    // =========================================================================

    @Test
    fun testScenarioE_zeroThirdPartyDependencies() {
        val plannerFile = File("src/main/java/com/example/newandroidapp/stability/BackwardChainingPlanner.kt")
            .let { if (it.exists()) it else File("app", it.path) }
        assertTrue(plannerFile.exists())

        val content = plannerFile.readText()

        val forbiddenImports = listOf(
            "retrofit",
            "okhttp",
            "com.google.gson",
            "com.alibaba.fastjson",
            "com.fasterxml.jackson",
            "org.opencv",
            "org.tensorflow",
            "org.pytorch",
            "com.google.mlkit",
            "org.json"
        )

        for (forbidden in forbiddenImports) {
            assertFalse(
                "BackwardChainingPlanner 嚴禁引入第三方依賴: $forbidden",
                content.contains("import $forbidden")
            )
        }
    }

    // =========================================================================
    // 場景 F: 與 InAppOperationPlanner、ClosedLoopVerifier 之決策管線整合測試
    // =========================================================================

    @Test
    fun testScenarioF_plannerAndVerifierIntegration() {
        // 1. 整合 InAppOperationPlanner.extractSearchQueryAndFollowUp
        val (query, followUp) = InAppOperationPlanner.extractSearchQueryAndFollowUp("去spotify買茶歌struct")
        assertEquals("struct", query)
        assertEquals("", followUp)

        // 2. 整合 InAppOperationPlanner.buildCooperativeInAppSubGoals 拆解為 LAUNCH_APP -> INPUT_SEARCH -> TAP_TARGET
        val subGoals = InAppOperationPlanner.buildCooperativeInAppSubGoals(
            rawGoal = "去spotify買茶歌struct",
            detectedAppName = "Spotify",
            highRiskKeywords = emptyList(),
            fallbackExtractor = { null },
            toggleStateHint = null
        )
        assertNotNull(subGoals)
        assertEquals(3, subGoals!!.size)
        assertEquals("LAUNCH_APP", subGoals[0].intentCategory)
        assertEquals("Spotify", subGoals[0].targetAppHint)
        assertEquals("INPUT_SEARCH", subGoals[1].intentCategory)
        assertEquals("struct", subGoals[1].inputPayload)
        assertEquals("TAP_TARGET", subGoals[2].intentCategory)
        assertEquals("struct", subGoals[2].targetSemanticPhrase)

        // 3. 整合 ClosedLoopVerifier.isSubGoalSatisfiedOnNewScreen 中 TAP_TARGET 之 isExpandingSearchEntry 防護
        val tapSubGoal = CooperativeSubGoal(
            stepIndex = 3,
            totalSteps = 3,
            intentCategory = "TAP_TARGET",
            targetSemanticPhrase = "struct"
        )
        val nodeFakeBox = makeNode(
            id = "fake_box",
            markId = 1,
            tag = "TXT",
            label = "想聽什麼",
            bounds = rect(60, 100, 900, 300)
        )
        val reportWithoutTarget = makeReport(listOf(nodeFakeBox))

        val nodeSongStruct = makeNode(
            id = "song_struct",
            markId = 2,
            tag = "TXT",
            label = "struct",
            bounds = rect(60, 500, 900, 700)
        )
        val reportWithTarget = makeReport(listOf(nodeSongStruct))

        val fp1 = ClosedLoopVerifier.computeScreenFingerprint("com.spotify.music", reportWithoutTarget)
        val fp2 = ClosedLoopVerifier.computeScreenFingerprint("com.spotify.music", reportWithTarget)

        // 當正在展開搜尋入口 (isExpandingSearchEntry = true) 且畫面尚未出現 targetEntity 時，TAP_TARGET 絕不提前滿足
        val satisfiedWithoutTarget = ClosedLoopVerifier.isSubGoalSatisfiedOnNewScreen(
            activeSubGoal = tapSubGoal,
            beforeFp = fp1,
            afterFp = fp2,
            afterReport = reportWithoutTarget,
            hasTypedTextInStep = false,
            isExpandingSearchEntry = true
        )
        assertFalse(satisfiedWithoutTarget)

        // 當畫面真正出現 targetEntity (struct) 時，TAP_TARGET 達成
        val satisfiedWithTarget = ClosedLoopVerifier.isSubGoalSatisfiedOnNewScreen(
            activeSubGoal = tapSubGoal,
            beforeFp = fp1,
            afterFp = fp2,
            afterReport = reportWithTarget,
            hasTypedTextInStep = false,
            isExpandingSearchEntry = true
        )
        assertTrue(satisfiedWithTarget)
    }
}
