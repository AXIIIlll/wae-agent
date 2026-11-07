package com.example.newandroidapp.stability

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 逆向回推思維引擎對抗式壓力測試套件 (Empirical Challenger Stress Harness)
 * 驗證極端口語、同音異字、標點引號、混用中英文、空白異常以及五階狀態機所有跳轉情境。
 */
class BackwardChainingAdversarialStressTest {

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

    // =========================================================================
    // 挑戰維度 1: 必測口語與同音異字變形
    // =========================================================================

    @Test
    fun testRequiredPrompt1_spotify_mai_cha_ge_struct() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        assertEquals("Spotify", spec.appHint)
        assertEquals(TerminalVerb.PLAY, spec.terminalVerb)
        assertEquals("struct", spec.targetEntity)
    }

    @Test
    fun testRequiredPrompt2_open_spotify_query_song_qingtian() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("打開spotify查詢一首歌叫晴天")
        assertEquals("Spotify", spec.appHint)
        assertEquals("晴天", spec.targetEntity)
    }

    @Test
    fun testRequiredPrompt3_shopee_buy_bluetooth_headphone() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去蝦皮買藍牙耳機")
        assertEquals("蝦皮", spec.appHint)
        assertEquals(TerminalVerb.BUY, spec.terminalVerb)
        assertEquals("藍牙耳機", spec.targetEntity)
    }

    @Test
    fun testRequiredPrompt4_youtube_find_jay_song_play_first() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("我想在youtube找周杰倫的歌並播放第一首")
        assertEquals("YouTube", spec.appHint)
        assertEquals(TerminalVerb.SEARCH_FIND, spec.terminalVerb)
        assertEquals("播放第一首", spec.followUpAction)
        assertTrue(
            "targetEntity 應為 周杰倫 或 周杰倫的歌，實際為: ${spec.targetEntity}",
            spec.targetEntity == "周杰倫" || spec.targetEntity == "周杰倫的歌"
        )
    }

    @Test
    fun testRequiredPrompt5_dao_spotify_cha_ge_struct() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("到spotify查歌struct")
        assertEquals("Spotify", spec.appHint)
        assertEquals(TerminalVerb.PLAY, spec.terminalVerb)
        assertEquals("struct", spec.targetEntity)
    }

    @Test
    fun testRequiredPrompt6_qu_spotify_dian_yi_shou_ge_ming_wei_qingtian() {
        // 核心測試指令：「去spotify點一首歌名為晴天」
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify點一首歌名為晴天")
        println("Prompt 6 extraction -> app: ${spec.appHint}, verb: ${spec.terminalVerb}, entity: '${spec.targetEntity}', followUp: '${spec.followUpAction}'")
        assertEquals("Spotify", spec.appHint)
        assertEquals("晴天", spec.targetEntity)
    }

    @Test
    fun testAdversarialPunctuationAndLeadingQuotes() {
        // 全形引號包覆整句：「去 Spotify 買茶歌 『struct』！」
        val spec1 = BackwardChainingPlanner.extractTerminalGoalSpec("「去 Spotify 買茶歌 『struct』！」")
        println("Leading quote test -> app: ${spec1.appHint}, entity: '${spec1.targetEntity}'")
        assertEquals("Spotify", spec1.appHint)
        assertEquals("struct", spec1.targetEntity)
    }

    @Test
    fun testStrangeSpacingsAndPunctuation() {
        // 多重不規則空白與雙引號
        val spec2 = BackwardChainingPlanner.extractTerminalGoalSpec("   打開 spotify   查詢 一首歌叫  \"晴天\"   ")
        assertEquals("Spotify", spec2.appHint)
        assertEquals("晴天", spec2.targetEntity)

        // 冒號
        val spec3 = BackwardChainingPlanner.extractTerminalGoalSpec("去蝦皮買: 藍牙耳機")
        assertEquals("蝦皮", spec3.appHint)
        assertEquals(TerminalVerb.BUY, spec3.terminalVerb)
        assertTrue(spec3.targetEntity.contains("藍牙耳機"))

        // 省略號與驚嘆號結尾
        val spec4 = BackwardChainingPlanner.extractTerminalGoalSpec("到spotify查歌struct... ")
        assertEquals("Spotify", spec4.appHint)
        assertEquals(TerminalVerb.PLAY, spec4.terminalVerb)
        assertEquals("struct", spec4.targetEntity.trimEnd('.', ' ', '…'))
    }

    @Test
    fun testMixedChineseEnglishAndAppVariations() {
        // 中英混雜
        val spec1 = BackwardChainingPlanner.extractTerminalGoalSpec("在YouTube找Jay Chou的歌並播放第一首")
        assertEquals("YouTube", spec1.appHint)
        assertEquals("播放第一首", spec1.followUpAction)
        assertTrue(spec1.targetEntity.contains("Jay Chou"))

        // 蝦皮英文 Shopee
        val spec2 = BackwardChainingPlanner.extractTerminalGoalSpec("去shopee買藍牙耳機")
        assertEquals("蝦皮", spec2.appHint)
        assertEquals("藍牙耳機", spec2.targetEntity)
    }

    // =========================================================================
    // 挑戰維度 2: 五階狀態機切換完整鏈路
    // =========================================================================

    @Test
    fun testFiveLevelStateMachineTransitions() {
        val spec = TerminalGoalSpec(
            appHint = "Spotify",
            terminalVerb = TerminalVerb.PLAY,
            targetEntity = "晴天",
            rawGoal = "去spotify聽晴天"
        )

        // 狀態 1: 首頁有目標 (Level 0) -> 直接點擊目標
        val targetNode = makeNode("target_1", 1, "TXT", "晴天 - 周杰倫", rect(60, 400, 1020, 600), pt(500, 250))
        val bottomNav = makeNode("nav_search", 2, "BTN", "搜尋", rect(400, 2100, 680, 2300), pt(500, 920))
        val evalL0 = BackwardChainingPlanner.evaluateBackwardChain(spec, listOf(targetNode, bottomNav), "com.spotify.music")
        assertEquals(BackwardChainLevel.LEVEL_0_DIRECT_TARGET, evalL0.level)
        assertEquals("target_1", evalL0.targetNode?.nodeId)

        // 狀態 2: 首頁無目標 (Level 3B) -> 點擊底部導覽列搜尋 Tab
        val homeRec = makeNode("rec_1", 3, "TXT", "為你推薦的歌單", rect(60, 400, 1020, 600), pt(500, 250))
        val evalL3B = BackwardChainingPlanner.evaluateBackwardChain(spec, listOf(homeRec, bottomNav), "com.spotify.music")
        assertEquals(BackwardChainLevel.LEVEL_3B_BOTTOM_SEARCH_TAB, evalL3B.level)
        assertEquals("nav_search", evalL3B.targetNode?.nodeId)

        // 狀態 3: 進入搜尋探索頁，頂部有假搜尋框 (Level 3A) -> 點擊展開真實輸入框
        val fakeSearchBox = makeNode("fake_box", 4, "TXT", "想聽什麼？", rect(60, 100, 1020, 300), pt(500, 90))
        val evalL3A = BackwardChainingPlanner.evaluateBackwardChain(spec, listOf(fakeSearchBox, bottomNav), "com.spotify.music")
        assertEquals(BackwardChainLevel.LEVEL_3A_EXPAND_SEARCH_BOX, evalL3A.level)
        assertEquals("fake_box", evalL3A.targetNode?.nodeId)

        // 狀態 4: 點擊後展開真實輸入框 (Level 2) -> 鍵入目標關鍵字並送出
        val realInp = makeNode("real_inp", 5, "INP", "搜尋歌曲、藝人", rect(60, 100, 1020, 300), pt(500, 90))
        val evalL2 = BackwardChainingPlanner.evaluateBackwardChain(spec, listOf(realInp), "com.spotify.music")
        assertEquals(BackwardChainLevel.LEVEL_2_INPUT_KEYWORD, evalL2.level)
        assertEquals("real_inp", evalL2.targetNode?.nodeId)
        assertEquals("晴天", evalL2.inputText)

        // 狀態 5: 送出後進入結果列表 (Level 0 優先命中) -> 選取目標結果
        val resultNode = makeNode("res_song", 6, "TXT", "晴天 周杰倫 葉惠美", rect(60, 400, 1020, 600), pt(500, 250))
        val evalL1 = BackwardChainingPlanner.evaluateBackwardChain(spec, listOf(resultNode, bottomNav), "com.spotify.music", hasSubmittedQuery = true)
        assertEquals(BackwardChainLevel.LEVEL_0_DIRECT_TARGET, evalL1.level)
        assertEquals("res_song", evalL1.targetNode?.nodeId)
    }

    @Test
    fun testLevel1_selectResult_whenQuerySubmitted() {
        val spec = TerminalGoalSpec(
            appHint = "Spotify",
            terminalVerb = TerminalVerb.PLAY,
            targetEntity = "晴天",
            rawGoal = "去spotify聽晴天"
        )
        // 搜尋已送出，結果頁包含非完全包含實體名稱的候選項目（例如合輯或首選卡片）
        val resultRow = makeNode("res_row_1", 1, "TXT", "Jay Chou Best Hits", rect(60, 400, 1020, 600), pt(500, 250))
        val eval = BackwardChainingPlanner.evaluateBackwardChain(spec, listOf(resultRow), "com.spotify.music", hasSubmittedQuery = true)
        assertEquals(BackwardChainLevel.LEVEL_1_SELECT_RESULT, eval.level)
        assertEquals("res_row_1", eval.targetNode?.nodeId)
    }

    // =========================================================================
    // 挑戰維度 3: 邊界與極端防禦測試
    // =========================================================================

    @Test
    fun testLevel4HierarchicalFallbackWhenNoSearchEntry() {
        val spec = TerminalGoalSpec(
            appHint = "Settings",
            terminalVerb = TerminalVerb.TOGGLE,
            targetEntity = "熱點",
            rawGoal = "去設定開啟熱點"
        )
        // 畫面只有一般列表項目，沒有熱點也沒有任何搜尋框/分頁
        val item1 = makeNode("item_wifi", 1, "TXT", "Wi-Fi 連線", rect(60, 300, 1020, 450), pt(500, 200))
        val item2 = makeNode("item_display", 2, "TXT", "螢幕顯示", rect(60, 500, 1020, 650), pt(500, 300))

        val eval = BackwardChainingPlanner.evaluateBackwardChain(spec, listOf(item1, item2), "com.android.settings")
        assertEquals(BackwardChainLevel.LEVEL_4_HIERARCHICAL_FALLBACK, eval.level)
    }

    @Test
    fun testVoiceSearchAndMicButtonImmunity() {
        val spec = TerminalGoalSpec(
            appHint = "Spotify",
            terminalVerb = TerminalVerb.PLAY,
            targetEntity = "struct",
            rawGoal = "去spotify買茶歌struct"
        )
        // 畫面只有語音輸入麥克風按鈕與無關內容
        val micNode = makeNode("mic_btn", 1, "BTN", "語音搜尋", rect(800, 100, 960, 300), pt(880, 90))
        val unrelated = makeNode("unrelated", 2, "TXT", "熱門播放清單", rect(60, 400, 1020, 600), pt(500, 250))

        val eval = BackwardChainingPlanner.evaluateBackwardChain(spec, listOf(micNode, unrelated), "com.spotify.music")
        // 嚴禁選取 micNode，應退回 Level 4
        assertFalse(eval.targetNode?.nodeId == "mic_btn")
        assertEquals(BackwardChainLevel.LEVEL_4_HIERARCHICAL_FALLBACK, eval.level)
    }

    @Test
    fun testEmptyInputsAndEmptyNodes() {
        val emptySpec = BackwardChainingPlanner.extractTerminalGoalSpec("")
        assertEquals("", emptySpec.targetEntity)

        val evalEmpty = BackwardChainingPlanner.evaluateBackwardChain(emptySpec, emptyList(), "com.unknown")
        assertEquals(BackwardChainLevel.LEVEL_4_HIERARCHICAL_FALLBACK, evalEmpty.level)
    }
}
