package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.BackwardChainLevel
import com.example.newandroidapp.stability.BackwardChainingPlanner
import com.example.newandroidapp.stability.ClosedLoopVerifier
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 資料庫接線與舊碼清理 (Items A ~ E) 完整單元測試：
 * - `PopupDismissalPolicyTest`：廣告與更新被關閉；權限與條款不被自動點擊（轉交 `CONFIRM_HIGH_RISK`）；黑名單按鈕不被點；白名單面板不被滑掉；抽屜把手在無白名單時才可滑掉。
 * - `OperationalIntentIndexTest`：BOM 已清除；只載入 16 個操作白名單領域與 `chat`；閒聊句被判為閒聊；操作句不被誤判。
 * - `StopwordBigramTest`：「我的世界」與「我的最愛」不再因「我的」虛詞二元組誤配；一般店名比對不受影響。
 * - `IconRescueWiringTest`：動態螢幕尺寸傳遞與畫面未變時依序執行「換動作 -> 換位置 -> 換候選圖標」。
 */
class DatabaseWiringAndCleanupTest {

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect {
        return Rect(l, t, r, b).apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    private fun pt(px: Int, py: Int): Point {
        return Point(px, py).apply {
            x = px
            y = py
        }
    }

    private fun makeNode(
        id: String,
        markId: Int,
        label: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        shortTypeTag: String = "BTN"
    ): Stage2ConvergedNode {
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val gx = (cx * 1000 / 1080).coerceIn(0, 999)
        val gy = (cy * 1000 / 2400).coerceIn(0, 999)
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = "android.widget.Button",
            shortTypeTag = shortTypeTag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = rect(l, t, r, b),
            convergedBounds = rect(l, t, r, b),
            finalTapPx = pt(cx, cy),
            gridTapPoint = pt(gx, gy),
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
            screenHeight = 2400,
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
            rawCharCount = 10,
            compressedCharCount = 10,
            compressionRatioPercent = 0,
            pipelineLatencyMs = 5L
        )
    }

    @Test
    fun testItemE_DeadCodeNeedle3VisionAiEngineRemoved() {
        val deadFile = File("src/main/java/com/example/newandroidapp/ai/Needle3VisionAiEngine.kt")
        val deadFileRoot = File("app/src/main/java/com/example/newandroidapp/ai/Needle3VisionAiEngine.kt")
        assertFalse("Needle3VisionAiEngine.kt 應已刪除", deadFile.exists() || deadFileRoot.exists())
    }

    @Test
    fun testItemD_StopwordBigramFilteringPreventsFalsePositives() {
        val bigramsWorld = OpenSourceAiDatabaseManager.meaningfulBigrams("我的世界")
        assertFalse("虛詞二元組「我的」應被過濾", bigramsWorld.contains("我的"))
        assertTrue("實體二元組「世界」應被保留", bigramsWorld.contains("世界"))

        val favNode = makeNode("fav", 1, "我的最愛清單", 100, 500, 600, 620)
        val reportWithFavOnly = makeReport(listOf(favNode))

        // 當搜尋目標為「我的世界」時，不應因「我的」二元組重疊而把「我的最愛清單」誤判為已到達目標頁
        val falsePositiveArrived = ClosedLoopVerifier.verifyTargetPageArrivedAndSettled(
            userGoal = "搜尋我的世界並點進網站",
            activeSubGoal = null,
            beforeFp = ClosedLoopVerifier.ScreenFingerprint("com.android.chrome", 1, 100L, "首頁"),
            afterFp = ClosedLoopVerifier.ScreenFingerprint("com.android.chrome", 1, 200L, "我的最愛清單"),
            afterReport = reportWithFavOnly,
            lockPermissionOnTerminalSuccess = false
        )
        assertFalse("僅重疊虛詞「我的」不應被判為目標實體匹配", falsePositiveArrived)

        // 一般店名（如「鼎泰豐信義店」）二元組比對不受影響
        val storeBigrams = OpenSourceAiDatabaseManager.meaningfulBigrams("鼎泰豐信義店")
        assertTrue(storeBigrams.contains("鼎泰"))
        assertTrue(storeBigrams.contains("信義"))
    }

    @Test
    fun testItemC_OperationalIntentIndexBomCleanedAndDomainsFiltered() {
        // 1. BOM 已清除：原本第一列含 \ufeff 的「打開熊貓看書」應能精確命中
        val firstRowHit = OperationalIntentIndex.classify("打開熊貓看書")
        assertNotNull("移除 BOM 後第一列『打開熊貓看書』應可精確命中", firstRowHit)
        assertEquals("app", firstRowHit!!.domain)
        assertFalse(firstRowHit.isChitchat)
        assertEquals(1.0f, firstRowHit.score, 0.001f)

        // 2. 載入領域僅限 16 個操作領域 + chat
        val loadedDomains = OperationalIntentIndex.getLoadedDomains()
        val allowed = OperationalIntentIndex.OPERATION_DOMAINS + OperationalIntentIndex.CHITCHAT_DOMAIN
        assertTrue("載入之領域應全部在白名單內", allowed.containsAll(loadedDomains))
        assertFalse("不應載入 poetry 領域", loadedDomains.contains("poetry"))
        assertFalse("不應載入 stock 領域", loadedDomains.contains("stock"))
        assertTrue("操作領域語料應為 1425 句左右", OperationalIntentIndex.getLoadedOperationalCount() >= 1400)

        // 3. 閒聊句被判為閒聊，操作句不被誤判為閒聊
        val chatHit = OperationalIntentIndex.classify("給我講個笑話吧")
        if (chatHit != null) {
            assertTrue(chatHit.isChitchat)
        }
        val mapHit = OperationalIntentIndex.classify("搜尋台北101並點擊規劃路線")
        assertTrue("明確操作指令不應被誤判為閒聊", mapHit == null || !mapHit.isChitchat)
    }

    @Test
    fun testItemB_PopupDismissalPolicyAndHighRiskPermissionGate() {
        InAppOperationPlanner.resetForNewGoal("搜尋耳機")

        // 1. 廣告與更新彈窗應自動關閉，且黑名單按鈕（開心收下/免費試用）絕不被點
        val adContextNode = makeNode("ad_title", 1, "雙11天降紅包限時搶購", 100, 400, 980, 520, "TXT")
        val darkCtaNode = makeNode("ad_trap", 2, "開心收下", 200, 1200, 880, 1340, "BTN")
        val dismissNode = makeNode("ad_skip", 3, "殘忍拒絕", 300, 1400, 780, 1500, "BTN")
        val adReport = makeReport(listOf(adContextNode, darkCtaNode, dismissNode))

        assertTrue(PopupDismissalPolicy.isDarkPatternCta("開心收下"))
        assertNull("暗黑模式按鈕 matchAction 必須回傳 null", PopupDismissalPolicy.matchAction("開心收下"))

        val adBlocker = InAppOperationPlanner.detectTopLayerBlocker(adReport, "搜尋耳機")
        assertNotNull("應偵測出『殘忍拒絕』關閉按鈕", adBlocker)
        assertEquals(3, adBlocker!!.shortMarkId)

        // 2. 系統權限與隱私條款對話框：不被 detectTopLayerBlocker 當成自動點擊，而是轉交 CONFIRM_HIGH_RISK
        val permContextNode = makeNode("perm_msg", 10, "要允許此應用程式存取您的位置嗎？", 100, 800, 980, 960, "TXT")
        val permAllowNode = makeNode("perm_allow", 11, "僅在使用該應用程式時允許", 150, 1050, 930, 1160, "BTN")
        val permReport = makeReport(listOf(permContextNode, permAllowNode))

        val autoBlockerOnPerm = InAppOperationPlanner.detectTopLayerBlocker(permReport, "規劃路線")
        assertNull("權限允許按鈕嚴禁被當成自動點擊之 blocker", autoBlockerOnPerm)

        val evalPerm = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = permReport,
            userGoal = "規劃路線"
        )
        assertEquals("權限請求應走 CONFIRM_HIGH_RISK 安全閘門", AiActionType.CONFIRM_HIGH_RISK, evalPerm.overrideActionType)
        assertEquals(11, evalPerm.overrideTargetNode?.shortMarkId)

        // 3. 白名單面板（選擇商品規格 / 加入購物車）不被滑掉，抽屜把手在無白名單且有彈窗時才視為可滑掉
        val skuTitle = makeNode("sku_title", 20, "選擇商品規格", 100, 1100, 600, 1180, "TXT")
        val addCart = makeNode("add_cart", 21, "加入購物車", 100, 2100, 980, 2240, "BTN")
        val legitNodes = listOf(skuTitle, addCart)
        assertTrue(PopupDismissalPolicy.screenHasLegitTaskSheet(legitNodes))
        assertFalse(
            "有白名單任務詞時，drag_handle 不可被當成彈窗滑掉",
            PopupDismissalPolicy.canSwipeDismissDragHandle("drag_handle", hasPopupContext = true, nodes = legitNodes)
        )
        assertTrue(
            "無白名單且處於彈窗語境時，drag_handle 才視為可滑掉容器",
            PopupDismissalPolicy.canSwipeDismissDragHandle("drag_handle", hasPopupContext = true, nodes = listOf(adContextNode))
        )
    }

    @Test
    fun testItemA_DynamicScreenSizeAndIconRescueSequence() {
        CommandLexiconEngine.clearIconRescueMemory()

        // 驗證 BackwardChainingPlanner 在非 1080x2400 解析度 (如 1440x3200) 下能正確把螢幕尺寸傳給 resolveIconInContext
        val handleNode = makeNode("sheet_handle", 5, "drag_handle", 600, 2500, 840, 2540, "BTN")
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("上滑展開底部面板")
        val eval = BackwardChainingPlanner.evaluateBackwardChain(
            goalSpec = spec,
            nodes = listOf(handleNode),
            packageName = "com.google.android.apps.maps",
            hasSubmittedQuery = false,
            screenWidth = 1440,
            screenHeight = 3200
        )
        assertEquals(BackwardChainLevel.LEVEL_0_DIRECT_TARGET, eval.level)
        assertEquals(AiActionType.SCROLL_UP, eval.actionType)

        // 驗證動作後未變化時依序執行：第 1 次 SWITCH_ACTION_MODE -> 第 2 次 SWITCH_CANDIDATE_ICON (最多 2 次)
        val altIconNode = makeNode("alt_expand_btn", 6, "expand_less", 900, 2480, 980, 2550, "BTN")
        val nodesWithAlt = listOf(handleNode, altIconNode)
        val r1 = CommandLexiconEngine.verifyIconActionAndRecover(
            packageName = "com.google.android.apps.maps",
            targetIconNode = handleNode,
            executedActionCode = 'r',
            beforeNodes = nodesWithAlt,
            afterNodes = nodesWithAlt,
            beforeSheetTopY = 2500,
            afterSheetTopY = 2500
        )
        assertEquals(CommandLexiconEngine.IconRescueStrategy.SWITCH_ACTION_MODE, r1.rescueStrategy)
        assertEquals('c', r1.recommendedActionCode)

        val r2 = CommandLexiconEngine.verifyIconActionAndRecover(
            packageName = "com.google.android.apps.maps",
            targetIconNode = handleNode,
            executedActionCode = 'r',
            beforeNodes = nodesWithAlt,
            afterNodes = nodesWithAlt,
            beforeSheetTopY = 2500,
            afterSheetTopY = 2500
        )
        assertEquals(CommandLexiconEngine.IconRescueStrategy.SWITCH_CANDIDATE_ICON, r2.rescueStrategy)
        assertEquals("alt_expand_btn", r2.alternateCandidateNode?.nodeId)
        assertTrue(r2.markedAsFailedInApp)
    }
}
