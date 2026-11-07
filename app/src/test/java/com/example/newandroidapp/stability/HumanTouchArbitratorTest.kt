package com.example.newandroidapp.stability

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 100% 原創自研：真人觸碰螢幕智慧仲裁與誤觸自動復原引擎 (HumanTouchArbitrator) 單元測試
 *
 * 驗證五大核心情境：
 * 1. 股票證券交易 App 包名攔截 -> 真人隨便碰一下 100% 立即強制停機 (`IMMEDIATE_HARD_STOP`)
 * 2. 電商購物 / 買東西 App 包名攔截 -> 真人隨便碰一下 100% 立即強制停機 (`IMMEDIATE_HARD_STOP`)
 * 3. 一般 App 內出現「付費、立即下單、結帳、轉帳」畫面語義攔截 -> 真人隨便碰一下 100% 立即強制停機 (`IMMEDIATE_HARD_STOP`)
 * 4. 聽歌低風險情境誤觸但「未產生任何介面跳轉影響」 -> 判定為誤觸無影響 (`NO_IMPACT_CONTINUE`)，不用跳回、直接繼續
 * 5. 聽歌低風險情境誤觸「跳到別的介面/廣告」(`JUMPED_WITHIN_APP_BACK`) 或「跳出到其他 App」(`JUMPED_EXTERNAL_APP_RECOVER`) -> 自動跳回誤觸前原介面
 */
class HumanTouchArbitratorTest {

    @Before
    fun setUp() {
        HumanTouchArbitrator.resetState()
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
            className = "android.view.View",
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
    fun `test stock trading app touch triggers immediate hard stop`() {
        val stockScreen = makeReport(
            listOf(
                makeNode("n1", 1, "TXT", "台積電 2330", 300, 200),
                makeNode("n2", 2, "BTN", "技術線圖", 500, 350)
            )
        )

        val resultMitake = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = "com.mitake.android.stock",
            currentAppLabel = "三竹股市",
            currentReport = stockScreen,
            userGoal = "查看台積電股價",
            nowElapsedMs = 10000L
        )
        assertEquals(HumanTouchArbitrator.TouchInitialVerdict.IMMEDIATE_HARD_STOP, resultMitake.verdict)
        assertTrue(resultMitake.reasonMessage.contains("股票證券交易"))

        val resultYuanta = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = "com.yuanta.nexus",
            currentAppLabel = "投資先生",
            currentReport = stockScreen,
            userGoal = "查看大盤走勢",
            nowElapsedMs = 12000L
        )
        assertEquals(HumanTouchArbitrator.TouchInitialVerdict.IMMEDIATE_HARD_STOP, resultYuanta.verdict)
    }

    @Test
    fun `test shopping ecommerce app touch triggers immediate hard stop`() {
        val shoppingScreen = makeReport(
            listOf(
                makeNode("n1", 1, "INP", "搜尋商品", 500, 120),
                makeNode("n2", 2, "BTN", "藍牙耳機特價", 500, 420)
            )
        )

        val resultShopee = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = "com.shopee.tw",
            currentAppLabel = "蝦皮購物",
            currentReport = shoppingScreen,
            userGoal = "搜尋藍牙耳機",
            nowElapsedMs = 20000L
        )
        assertEquals(HumanTouchArbitrator.TouchInitialVerdict.IMMEDIATE_HARD_STOP, resultShopee.verdict)
        assertTrue(resultShopee.reasonMessage.contains("電商購物下單"))

        val resultMomo = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = "com.momo.mobile.shopping",
            currentAppLabel = "momo購物網",
            currentReport = shoppingScreen,
            userGoal = "搜尋衛生紙",
            nowElapsedMs = 25000L
        )
        assertEquals(HumanTouchArbitrator.TouchInitialVerdict.IMMEDIATE_HARD_STOP, resultMomo.verdict)
    }

    @Test
    fun `test payment and checkout screen semantics trigger immediate hard stop`() {
        val checkoutScreen = makeReport(
            listOf(
                makeNode("n1", 1, "TXT", "應付總金額 NT$ 1,280", 400, 300),
                makeNode("n2", 2, "BTN", "立即下單並確認付款", 500, 820)
            )
        )

        // 即使在一般瀏覽器或一般 App，只要畫面有「立即下單/確認付款」語義，碰一下就必須立刻停
        val result = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = "com.android.chrome",
            currentAppLabel = "Chrome",
            currentReport = checkoutScreen,
            userGoal = "瀏覽網頁",
            nowElapsedMs = 30000L
        )
        assertEquals(HumanTouchArbitrator.TouchInitialVerdict.IMMEDIATE_HARD_STOP, result.verdict)
        assertTrue(result.reasonMessage.contains("畫面語義安全攔截"))
    }

    @Test
    fun `test music app accidental touch with no screen impact continues without pressing back`() {
        val musicPlayerScreen = makeReport(
            listOf(
                makeNode("n1", 1, "TXT", "周杰倫 - 晴天 (官方完整版 MV)", 500, 300),
                makeNode("n2", 2, "BTN", "暫停播放", 500, 700),
                makeNode("n3", 3, "BTN", "下一首", 720, 700),
                makeNode("n4", 4, "BTN", "喜歡", 250, 820)
            )
        )

        HumanTouchArbitrator.updateCleanPreTouchAnchor(
            packageName = "com.google.android.youtube",
            appLabel = "YouTube",
            report = musicPlayerScreen,
            userGoal = "打開 YouTube 搜尋周杰倫晴天並播放",
            nowElapsedMs = 40000L
        )

        val arb = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = "com.google.android.youtube",
            currentAppLabel = "YouTube",
            currentReport = musicPlayerScreen,
            userGoal = "打開 YouTube 搜尋周杰倫晴天並播放",
            nowElapsedMs = 40500L
        )
        assertEquals(HumanTouchArbitrator.TouchInitialVerdict.INSPECT_ACCIDENTAL_TOUCH, arb.verdict)
        assertNotNull(arb.preTouchSnapshot)

        // 誤觸沒有產生任何影響（畫面與誤觸前完全相同） -> NO_IMPACT_CONTINUE（不用跳回）
        val postEval = HumanTouchArbitrator.evaluatePostTouchImpact(
            preTouchSnapshot = arb.preTouchSnapshot,
            postTouchPackage = "com.google.android.youtube",
            postTouchAppLabel = "YouTube",
            postTouchReport = musicPlayerScreen,
            userGoal = "打開 YouTube 搜尋周杰倫晴天並播放"
        )
        assertEquals(HumanTouchArbitrator.PostTouchRecoveryAction.NO_IMPACT_CONTINUE, postEval.action)
    }

    @Test
    fun `test music app accidental touch jumping to ad or external app automatically recovers back`() {
        val originalMusicScreen = makeReport(
            listOf(
                makeNode("n1", 1, "TXT", "周杰倫 - 七里香", 500, 300),
                makeNode("n2", 2, "BTN", "播放中", 500, 700),
                makeNode("n3", 3, "BTN", "播放清單", 800, 700)
            )
        )

        HumanTouchArbitrator.updateCleanPreTouchAnchor(
            packageName = "com.google.android.youtube",
            appLabel = "YouTube",
            report = originalMusicScreen,
            userGoal = "在 YouTube 聽周杰倫七里香",
            nowElapsedMs = 50000L
        )

        val arb = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = "com.google.android.youtube",
            currentAppLabel = "YouTube",
            currentReport = originalMusicScreen,
            userGoal = "在 YouTube 聽周杰倫七里香",
            nowElapsedMs = 50400L
        )
        assertEquals(HumanTouchArbitrator.TouchInitialVerdict.INSPECT_ACCIDENTAL_TOUCH, arb.verdict)

        // 情境 5A：誤觸跳到同 App 內的廣告詳情頁或其他介面 -> JUMPED_WITHIN_APP_BACK（自動按返回跳回誤觸前原介面）
        val jumpedAdPageInSameApp = makeReport(
            listOf(
                makeNode("ad1", 1, "TXT", "贊助廣告：免費試用手遊大作", 500, 220),
                makeNode("ad2", 2, "BTN", "立即下載安裝應用程式", 500, 640)
            )
        )
        val evalSameAppJump = HumanTouchArbitrator.evaluatePostTouchImpact(
            preTouchSnapshot = arb.preTouchSnapshot,
            postTouchPackage = "com.google.android.youtube",
            postTouchAppLabel = "YouTube",
            postTouchReport = jumpedAdPageInSameApp,
            userGoal = "在 YouTube 聽周杰倫七里香"
        )
        assertEquals(HumanTouchArbitrator.PostTouchRecoveryAction.JUMPED_WITHIN_APP_BACK, evalSameAppJump.action)

        // 情境 5B：誤觸跳出到其他 App（例如誤點外部廣告跳去 Chrome 瀏覽器） -> JUMPED_EXTERNAL_APP_RECOVER（自動跳回原 App）
        val evalExternalJump = HumanTouchArbitrator.evaluatePostTouchImpact(
            preTouchSnapshot = arb.preTouchSnapshot,
            postTouchPackage = "com.android.chrome",
            postTouchAppLabel = "Chrome",
            postTouchReport = jumpedAdPageInSameApp,
            userGoal = "在 YouTube 聽周杰倫七里香"
        )
        assertEquals(HumanTouchArbitrator.PostTouchRecoveryAction.JUMPED_EXTERNAL_APP_RECOVER, evalExternalJump.action)
        assertEquals("com.google.android.youtube", evalExternalJump.targetPackageToRestore)
    }

    @Test
    fun `test consecutive double touch in music app triggers intentional stop`() {
        val musicScreen = makeReport(
            listOf(makeNode("n1", 1, "TXT", "Spotify 正在播放音樂", 500, 300))
        )

        val firstTouch = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = "com.spotify.music",
            currentAppLabel = "Spotify",
            currentReport = musicScreen,
            userGoal = "聽音樂",
            nowElapsedMs = 60000L
        )
        assertEquals(HumanTouchArbitrator.TouchInitialVerdict.INSPECT_ACCIDENTAL_TOUCH, firstTouch.verdict)

        // 800ms 後（< 2500ms 視窗內）真人再次觸碰第 2 下 -> 判定為真人明確想讓 AI 停止
        val secondTouch = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = "com.spotify.music",
            currentAppLabel = "Spotify",
            currentReport = musicScreen,
            userGoal = "聽音樂",
            nowElapsedMs = 60800L
        )
        assertEquals(HumanTouchArbitrator.TouchInitialVerdict.IMMEDIATE_HARD_STOP, secondTouch.verdict)
    }
}
