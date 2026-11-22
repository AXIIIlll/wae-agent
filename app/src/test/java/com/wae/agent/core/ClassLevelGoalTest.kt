package com.wae.agent.core

import com.wae.agent.core.capability.CapabilitySchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「類別級目標」理解能力測試（對應「叫他點 Gmail 一封信，打開後就不動」的缺口）。
 *
 * 重點：判定必須**完全由離線詞庫（CC-CEDICT 量詞 + jieba 詞性）驅動**，
 * 不得出現任何特定 App 的資料。
 */
class ClassLevelGoalTest {

    @Test
    fun `classifier lexicon is loaded from open datasets`() {
        WaeOpenSourceDatabaseRegistry.ensureLoaded()
        assertTrue(
            "量詞詞庫應由 zh_classifier_lexicon.tsv 載入（CC-CEDICT + jieba）",
            WaeOpenSourceDatabaseRegistry.singleCharMeasureWords.size > 100
        )
        assertTrue(
            "代詞詞庫應載入",
            WaeOpenSourceDatabaseRegistry.pronouns.size > 100
        )
        // 繁體與簡體量詞都必須在庫內（CC-CEDICT 提供繁體）
        listOf("封", "則", "筆", "筆", "個", "个", "條", "条", "篇", "份").forEach {
            assertTrue("量詞 '$it' 應在詞庫內", WaeOpenSourceDatabaseRegistry.singleCharMeasureWords.contains(it))
        }
        assertEquals(1, CapabilitySchema.VERSION) // 佔位：確保本測試不依賴能力圖譜版本變動
    }

    @Test
    fun `quantity phrases are class level`() {
        val cases = listOf(
            "點一封信",
            "隨機打開一個影片",
            "任何一則通知",
            "第一個商品",
            "打開兩份文件",
            "播放第一首歌"
        )
        for (c in cases) {
            assertEquals(
                "「$c」應判定為類別級",
                TargetSpecificity.CLASS,
                WaeV26PipelineOrchestrator.detectTargetSpecificity(c, c)
            )
        }
    }

    @Test
    fun `specific labels stay instance level`() {
        val cases = listOf(
            "點開晴天",
            "搜尋台北101",
            "開啟設定",
            "返回上一頁",
            "點擊送出"
        )
        for (c in cases) {
            assertEquals(
                "「$c」應判定為實例級",
                TargetSpecificity.INSTANCE,
                WaeV26PipelineOrchestrator.detectTargetSpecificity(c, c)
            )
        }
    }

    @Test
    fun `indefinite pronouns are class level`() {
        listOf("打開任何應用", "點其中一個", "選擇某個選項").forEach { c ->
            assertEquals(
                "「$c」含不定代詞，應為類別級",
                TargetSpecificity.CLASS,
                WaeV26PipelineOrchestrator.detectTargetSpecificity(c, c)
            )
        }
    }

    @Test
    fun `decomposed goal carries class level specificity`() {
        val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals("隨機點一封信")
        assertTrue("應拆解出至少一個目標", goals.isNotEmpty())
        val tapGoal = goals.firstOrNull { it.actionKind == ActionKind.TAP }
        assertTrue("應包含 TAP 目標", tapGoal != null)
        assertEquals(
            "「隨機點一封信」的 TAP 目標應為類別級",
            TargetSpecificity.CLASS,
            tapGoal!!.targetSpecificity
        )
    }

    @Test
    fun `ui action vocabulary is loaded from AOSP resources`() {
        WaeOpenSourceDatabaseRegistry.ensureLoaded()
        assertTrue(
            "通用動作詞彙應由 ui_action_vocabulary.tsv 載入（AOSP Settings 多語系）",
            WaeOpenSourceDatabaseRegistry.uiActionTerms.size > 800
        )
        // 多語系（含繁體與 OpenCC 繁簡變體）都必須在庫內
        listOf("確定", "取消", "下一頁", "繼續", "允許", "搜尋", "完成", "返回",
            "ok", "cancel", "next", "continue", "allow", "share", "delete").forEach {
            assertTrue(
                "動作詞彙 '$it' 應在庫內",
                WaeOpenSourceDatabaseRegistry.isGenericActionLabel(it)
            )
        }
        // 內容文字不該被當成動作按鈕
        assertTrue(
            "信件內容不應被判定為通用動作標籤",
            !WaeOpenSourceDatabaseRegistry.isGenericActionLabel("Google、您與某服務分享了部分帳戶資料")
        )
    }

    @Test
    fun `class fallback prefers list content over higher-scoring toolbar button`() {
        // 模擬收件匣：FAB「撰寫」語意分 0.57，郵件行只有 0.52
        val fab = CandidateOption(
            markId = 1,
            nodeStableSig = 1L,
            source = CandidateSource.A11Y_TREE,
            detectorConfidence = 1f,
            grid = intArrayOf(947, 1847, 1206, 2000),
            coreText = "撰寫",
            weldedDescription = "撰寫",
            hybridSimScore = 0.57f,
            isClickable = true,
            parentContainerType = "ROOT"
        )
        val emailRow = CandidateOption(
            markId = 2,
            nodeStableSig = 2L,
            source = CandidateSource.A11Y_TREE,
            detectorConfidence = 1f,
            grid = intArrayOf(23, 1217, 1057, 1491),
            coreText = "Google、您與某服務分享了部分帳戶資料",
            weldedDescription = "Google 帳戶通知 11月8日",
            hybridSimScore = 0.52f,
            isClickable = true,
            parentContainerType = "LIST_ITEM"
        )
        val set = ImmutableOptionSet(
            frameId = 1L,
            windowIdx = 0,
            activeWindowId = 42,
            packageName = "com.demo.mail",
            postureState = DualPostureState(PagePosture.LIST_FEED),
            pageSpineSigs = LongArray(0),
            pageSpineHash = 0L,
            rejectedContextPrompts = emptyList(),
            selectableOptions = listOf(emailRow, fab)
        )
        val goal = LocalGoal(
            stepIdx = 0,
            goalId = "g1",
            goalText = "隨機點一個信件",
            canonicalIntentTag = "general_tap",
            actionKind = ActionKind.TAP,
            inputPayload = "一個信件",
            targetSpecificity = TargetSpecificity.CLASS
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goal, set)
        assertEquals(DecisionOutcomeType.CLEAR_WIN, decision.outcomeType)
        assertEquals("應選郵件行而非 FAB", 2, decision.selectedOption?.markId)
        assertTrue(
            "reason 應標記為類別級回退：${decision.reason}",
            decision.reason.contains("CLASS_LEVEL_FALLBACK")
        )
    }

    @Test
    fun `instance goal does not use class fallback`() {
        val weak = CandidateOption(
            markId = 1,
            nodeStableSig = 1L,
            source = CandidateSource.A11Y_TREE,
            detectorConfidence = 1f,
            grid = intArrayOf(0, 0, 100, 100),
            coreText = "撰寫",
            weldedDescription = "",
            hybridSimScore = 0.3f,
            isClickable = true,
            parentContainerType = "LIST_ITEM"
        )
        val set = ImmutableOptionSet(
            frameId = 1L,
            windowIdx = 0,
            activeWindowId = 42,
            packageName = "com.demo.mail",
            postureState = DualPostureState(PagePosture.LIST_FEED),
            pageSpineSigs = LongArray(0),
            pageSpineHash = 0L,
            rejectedContextPrompts = emptyList(),
            selectableOptions = listOf(weak)
        )
        val goal = LocalGoal(
            stepIdx = 0,
            goalId = "g1",
            goalText = "點開晴天",
            canonicalIntentTag = "general_tap",
            actionKind = ActionKind.TAP,
            inputPayload = "晴天",
            targetSpecificity = TargetSpecificity.INSTANCE
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goal, set)
        assertTrue(
            "實例級目標不該用類別回退（應走 REJECT/恢復流程）",
            decision.outcomeType != DecisionOutcomeType.CLEAR_WIN ||
                !decision.reason.contains("CLASS_LEVEL_FALLBACK")
        )
    }
}
