package com.example.newandroidapp.stability

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import com.example.newandroidapp.vision.dataset.Phase2ButtonFunctionalityClassifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 驗證「動態抗改版與向量自癒記憶（Self-Healing Memory）」閉環機制：
 * 1. 儲存多維度語義特徵錨點（32D Embedding / 語義標籤 / 功能角色 / 結構路徑 / 視覺幾何屬性）
 * 2. 改版偵測與自動降級重搜（點擊前原位消失或點擊後指紋未變 -> 信心歸零 + 無縫全畫面重搜）
 * 3. 餘弦相似度匹配「新替身」與背景靜默覆寫（跨座標匹配新按鈕 + 驗證後覆寫座標與動態先驗權重）
 */
class SelfHealingMemoryTest {

    @Before
    fun setUp() {
        EpisodicAppMemory.resetForTesting()
        InAppWorkingMemory.resetForNewGoal("")
    }

    @After
    fun tearDown() {
        EpisodicAppMemory.resetForTesting()
        InAppWorkingMemory.resetForNewGoal("")
    }

    private fun makeRect(l: Int, t: Int, r: Int, b: Int): Rect {
        return Rect().apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    private fun makePoint(px: Int, py: Int): Point {
        return Point().apply {
            x = px
            y = py
        }
    }

    private fun makeNode(
        id: String,
        markId: Int,
        label: String,
        shortTypeTag: String = "BTN",
        gx: Int,
        gy: Int,
        widthPx: Int = 140,
        heightPx: Int = 64,
        roleCode: String = "",
        nanoDetCategory: String = "",
        pkg: String = "com.example.shopapp"
    ): Stage2ConvergedNode {
        val px = (gx * 1080) / 1000
        val py = (gy * 2400) / 1000
        val halfW = widthPx / 2
        val halfH = heightPx / 2
        val rect = makeRect(px - halfW, py - halfH, px + halfW, py + halfH)
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = "android.widget.Button",
            shortTypeTag = shortTypeTag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = rect,
            convergedBounds = rect,
            finalTapPx = makePoint(px, py),
            gridTapPoint = makePoint(gx, gy),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE,
            listGroupSignature = null,
            visualConfidence = 1.0f,
            nanoDetCategory = nanoDetCategory,
            predictedFunctionRole = roleCode,
            functionRoleConfidence = if (roleCode.isNotBlank()) 0.90f else 0f,
            packageName = pkg
        )
    }

    @Test
    fun `test 1 multi-dimensional semantic feature anchor storage and L2 normalization`() {
        val pkg = "com.example.musicapp"
        val oldSearchBtn = makeNode(
            id = "node_search_top",
            markId = 1,
            label = "搜尋歌曲",
            shortTypeTag = "BTN",
            gx = 910,
            gy = 85,
            widthPx = 120,
            heightPx = 60,
            roleCode = "SEARCH_ENTRY",
            nanoDetCategory = "UI_SEARCH_ICON",
            pkg = pkg
        )

        EpisodicAppMemory.recordVerifiedSearchEntry(
            context = null,
            packageName = pkg,
            appLabel = "音樂App",
            node = oldSearchBtn,
            screenWidth = 1080,
            screenHeight = 2400
        )

        val profile = EpisodicAppMemory.getProfile(null, pkg)
        assertNotNull("應建立 AppEpisodicProfile", profile)
        assertEquals(1, profile!!.verifiedSearchEntries.size)

        val anchor = profile.verifiedSearchEntries.first()
        assertEquals(910, anchor.gridX)
        assertEquals(85, anchor.gridY)
        assertEquals("SEARCH_ENTRY", anchor.functionRoleCode)
        assertTrue("應包含結構路徑簽章", anchor.structuralPathSig.contains("TOP_BAR_RIGHT"))
        assertTrue("應包含擴展同義詞語義標籤", anchor.semanticTags.isNotEmpty())
        assertEquals(EpisodicAppMemory.SEMANTIC_VECTOR_DIM, anchor.semanticEmbedding.size)

        // 驗證 32D 語義特徵向量已完成 L2 正規化 (範數約為 1.0)
        var normSq = 0f
        for (v in anchor.semanticEmbedding) normSq += v * v
        val norm = sqrt(normSq)
        assertTrue("32D 語義向量必須為單位長度 L2 正規化 (norm=$norm)", abs(norm - 1.0f) < 0.02f)

        // 驗證視覺幾何屬性已儲存
        assertTrue(anchor.widthRatio > 0.05f)
        assertTrue(anchor.heightRatio > 0.01f)
        assertEquals(2.0f, anchor.aspectRatio, 0.25f)
        assertEquals(1.0f, anchor.confidenceWeight, 0.001f)
    }

    @Test
    fun `test 2 redesign detection zeroes out anchor confidence without error on missing or unverified element`() {
        val pkg = "com.example.socialapp"
        val oldTopSearchNode = makeNode(
            id = "search_old",
            markId = 1,
            label = "搜尋",
            shortTypeTag = "BTN",
            gx = 920,
            gy = 75,
            roleCode = "SEARCH_ENTRY",
            pkg = pkg
        )

        // 1. 先記錄舊版介面右上角 (920, 75) 的搜尋入口
        EpisodicAppMemory.recordVerifiedSearchEntry(
            context = null,
            packageName = pkg,
            appLabel = "社群App",
            node = oldTopSearchNode
        )
        val anchorBefore = EpisodicAppMemory.getProfile(null, pkg)!!.verifiedSearchEntries.first()
        assertEquals(1.0f, anchorBefore.confidenceWeight, 0.001f)

        // 2. 模擬 App 改版：原本右上角 (920, 75) 的搜尋按鈕消失，畫面僅剩底部導覽列與列表項
        val redesignedNodes = listOf(
            makeNode("home_tab", 1, "首頁", "BTN", gx = 200, gy = 920, pkg = pkg),
            makeNode("new_explore_search", 2, "查找探索", "BTN", gx = 500, gy = 920, roleCode = "SEARCH_ENTRY", pkg = pkg),
            makeNode("profile_tab", 3, "我的帳號", "BTN", gx = 800, gy = 920, pkg = pkg)
        )

        val detection = EpisodicAppMemory.detectPreClickRedesignAndInvalidate(
            context = null,
            packageName = pkg,
            visibleNodes = redesignedNodes,
            stage = InAppOperationStage.STAGE_A_EXPAND_SEARCH,
            query = "貓咪影片",
            subGoalKeyword = "搜尋 貓咪影片",
            screenFp = "fp_redesigned_v2"
        )

        assertTrue("應偵測到原位元件消失 (介面改版)", detection.redesignDetected)
        assertEquals(1, detection.invalidatedCount)
        assertEquals("舊記憶信心值必須自動歸零", 0.0f, anchorBefore.confidenceWeight, 0.001f)

        // 3. 測試點擊後閉環指紋未改變 (!changed) 的改版偵測
        val targetBtnOld = makeNode("send_old", 4, "傳送訊息", "BTN", gx = 880, gy = 120, roleCode = "SEND_SUBMIT_CONFIRM", pkg = pkg)
        EpisodicAppMemory.recordVerifiedTargetClick(
            context = null,
            packageName = pkg,
            appLabel = "社群App",
            intentKeyword = "傳送",
            node = targetBtnOld
        )
        val targetAnchor = EpisodicAppMemory.getProfile(null, pkg)!!.verifiedTargetClicks.first()
        assertEquals(1.0f, targetAnchor.confidenceWeight, 0.001f)

        val zeroed = EpisodicAppMemory.onAnchorClickVerificationFailed(
            context = null,
            packageName = pkg,
            clickedNode = targetBtnOld,
            stage = InAppOperationStage.IN_APP_NAVIGATION,
            intentKeyword = "傳送",
            screenFp = "fp_stuck"
        )
        assertTrue("點擊後指紋未改變應將該記憶信心歸零", zeroed >= 1)
        assertEquals(0.0f, targetAnchor.confidenceWeight, 0.001f)
    }

    @Test
    fun `test 3 cosine similarity matches new avatar across screen and silently overwrites memory after verification`() {
        val pkg = "com.example.redesignapp"
        // 舊版 App：「發送」按鈕位於右上角 (890, 90)
        val oldSendBtn = makeNode(
            id = "btn_send_v1",
            markId = 1,
            label = "發送",
            shortTypeTag = "BTN",
            gx = 890,
            gy = 90,
            widthPx = 150,
            heightPx = 68,
            roleCode = "SEND_SUBMIT_CONFIRM",
            pkg = pkg
        )
        EpisodicAppMemory.recordVerifiedTargetClick(
            context = null,
            packageName = pkg,
            appLabel = "改版測試App",
            intentKeyword = "發送表單",
            node = oldSendBtn
        )

        // 模擬改版後：右上角 (890, 90) 變成無關的「設定」按鈕，而真正的送出按鈕移到底部中央 (500, 910) 且改名為「確認送出」
        val unrelatedSettingsBtn = makeNode(
            id = "btn_settings_v2",
            markId = 1,
            label = "設定選項",
            shortTypeTag = "BTN",
            gx = 890,
            gy = 90,
            widthPx = 150,
            heightPx = 68,
            roleCode = "SETTINGS_MENU_MORE",
            pkg = pkg
        )
        val unrelatedDeleteBtn = makeNode(
            id = "btn_delete_v2",
            markId = 2,
            label = "刪除草稿",
            shortTypeTag = "BTN",
            gx = 200,
            gy = 910,
            widthPx = 150,
            heightPx = 68,
            roleCode = "DELETE_REMOVE_TRASH",
            pkg = pkg
        )
        val newAvatarSubmitBtn = makeNode(
            id = "btn_submit_v2",
            markId = 3,
            label = "確認送出",
            shortTypeTag = "BTN",
            gx = 500,
            gy = 910,
            widthPx = 160,
            heightPx = 70,
            roleCode = "SEND_SUBMIT_CONFIRM",
            pkg = pkg
        )
        val redesignedCandidates = listOf(unrelatedSettingsBtn, unrelatedDeleteBtn, newAvatarSubmitBtn)

        // Step A: 點擊前原位檢查發現 (890, 90) 已變成不相容的「設定選項」，自動將舊錨點信心值歸零
        val redesignRes = EpisodicAppMemory.detectPreClickRedesignAndInvalidate(
            context = null,
            packageName = pkg,
            visibleNodes = redesignedCandidates,
            stage = InAppOperationStage.IN_APP_NAVIGATION,
            query = "",
            subGoalKeyword = "發送表單",
            screenFp = "fp_v2"
        )
        assertTrue("原位變成設定按鈕，應觸發改版信心歸零", redesignRes.redesignDetected)
        val invalidatedAnchor = EpisodicAppMemory.getProfile(null, pkg)!!.verifiedTargetClicks.first()
        assertEquals(0.0f, invalidatedAnchor.confidenceWeight, 0.001f)

        // Step B: 全畫面重搜時，透過餘弦相似度匹配「新替身」
        val avatarMatch = EpisodicAppMemory.findBestSelfHealingAvatar(
            context = null,
            packageName = pkg,
            candidates = redesignedCandidates,
            stage = InAppOperationStage.IN_APP_NAVIGATION,
            query = "",
            subGoalKeyword = "發送表單"
        )
        assertNotNull("應透過餘弦相似度成功找到新替身", avatarMatch)
        assertEquals("應精準匹配底部中央的『確認送出』作為新替身", "btn_submit_v2", avatarMatch!!.first.nodeId)
        assertTrue("餘弦與綜合相似度應達標", avatarMatch.second.combinedSimilarity >= EpisodicAppMemory.AVATAR_SIMILARITY_THRESHOLD)

        // 同時驗證 InAppOperationPlanner.planNextStep 透過 computeEpisodicScoreAdjustment 自動給予新替身加分
        val (boostNewAvatar, reasonNewAvatar) = EpisodicAppMemory.computeEpisodicScoreAdjustment(
            context = null,
            packageName = pkg,
            candidate = newAvatarSubmitBtn,
            stage = InAppOperationStage.IN_APP_NAVIGATION,
            query = "",
            subGoalKeyword = "發送表單",
            screenFp = "fp_v2"
        )
        val (boostSettings, _) = EpisodicAppMemory.computeEpisodicScoreAdjustment(
            context = null,
            packageName = pkg,
            candidate = unrelatedSettingsBtn,
            stage = InAppOperationStage.IN_APP_NAVIGATION,
            query = "",
            subGoalKeyword = "發送表單",
            screenFp = "fp_v2"
        )
        assertTrue("新替身應獲得正向自癒加分 (boost=$boostNewAvatar)", boostNewAvatar >= 18f)
        assertEquals("原座標處的不相容設定按鈕不得獲得舊座標加分", 0f, boostSettings, 0.01f)
        assertTrue("診斷原因應標註向量自癒匹配新替身", reasonNewAvatar?.contains("向量自癒匹配") == true)

        // Step C: 點擊新替身並通過閉環畫面跳轉驗證後，在背景自動靜默覆寫回本地記憶庫並覆蓋動態先驗
        EpisodicAppMemory.recordVerifiedTargetClick(
            context = null,
            packageName = pkg,
            appLabel = "改版測試App",
            intentKeyword = "發送表單",
            node = newAvatarSubmitBtn
        )

        val healedProfile = EpisodicAppMemory.getProfile(null, pkg)!!
        assertEquals("應原位覆寫而非無限膨脹清單", 1, healedProfile.verifiedTargetClicks.size)
        val healedAnchor = healedProfile.verifiedTargetClicks.first()
        assertEquals("新 X 座標應靜默覆寫為 500", 500, healedAnchor.gridX)
        assertEquals("新 Y 座標應靜默覆寫為 910", 910, healedAnchor.gridY)
        assertEquals("新標籤應覆寫為確認送出", "確認送出", healedAnchor.label)
        assertEquals("信心值應恢復為 1.0f", 1.0f, healedAnchor.confidenceWeight, 0.001f)
        assertEquals("自癒計數應遞增為 1", 1, healedAnchor.selfHealCount)

        // 驗證動態先驗權重已同步覆蓋 Phase2ButtonFunctionalityClassifier
        val dynOverride = Phase2ButtonFunctionalityClassifier.getAppSpecificDynamicPriorOverride(
            pkg,
            Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.SEND_SUBMIT_CONFIRM
        )
        assertNotNull("應動態覆蓋該 App 的按鈕角色空間先驗", dynOverride)
        assertEquals(0.50f, dynOverride!!.normCx, 0.02f)
        assertEquals(0.91f, dynOverride.normCy, 0.02f)

        // Step D: 下一次執行時直接以 0ms 命中新座標記憶
        val (nextRunBoost, nextRunReason) = EpisodicAppMemory.computeEpisodicScoreAdjustment(
            context = null,
            packageName = pkg,
            candidate = newAvatarSubmitBtn,
            stage = InAppOperationStage.IN_APP_NAVIGATION,
            query = "",
            subGoalKeyword = "發送表單",
            screenFp = "fp_v2"
        )
        assertEquals(20f, nextRunBoost, 0.01f)
        assertEquals("經驗記憶命中成功路徑", nextRunReason)
    }
}
