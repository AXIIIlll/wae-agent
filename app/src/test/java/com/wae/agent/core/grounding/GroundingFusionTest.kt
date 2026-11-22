package com.wae.agent.core.grounding

import com.wae.agent.core.runtime.ExtremeRuntimeHooks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [GroundingFusion] 多通道融合測試（G1 / I2）。
 *
 * 覆蓋重點（與 DecisionHead 的行為契約一致）：
 *  1. 權重檔 `ui_grounding_weights.tsv` 能解析（model/feature/weight + meta alpha/bias）
 *  2. 總開關 `GROUNDING_2_ENABLED` 關閉時完全回空 ranked + abstain（不影響舊路徑）
 *  3. 融合方向正確：同列可編輯搜尋框 > 遠處圖示
 *  4. 單一特徵權重再大也被 clamp 在 ±2 再乘 alpha
 *  5. 壞掉的權重檔退回內建 bootstrap，不退化
 *  6. abstain：空候選、低分差（兩個都像）、關閉旗標都會 abstain；明確贏家不會
 *  7. softmax probability 是合法分佈
 */
class GroundingFusionTest {

    private var savedFlag = false

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    private fun rect(l: Int, t: Int, r: Int, b: Int) = groundingRect(l, t, r, b)

    private fun cand(
        id: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        text: String = "",
        viewId: String = "",
        channel: GroundingChannel = GroundingChannel.A11Y,
        clickable: Boolean = true,
        editable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
        score: Float = 0.5f
    ) = GroundingCandidate(
        id = id,
        channel = channel,
        text = text,
        viewId = viewId,
        bounds = rect(l, t, r, b),
        clickable = clickable,
        editable = editable,
        checkable = checkable,
        checked = checked,
        enabled = enabled,
        score = score
    )

    @Before
    fun setUp() {
        savedFlag = ExtremeRuntimeHooks.GROUNDING_2_ENABLED
        ExtremeRuntimeHooks.GROUNDING_2_ENABLED = true
        GroundingFusion.resetForTest()
        GroundingFusion.loadFromLines(asset("ui_grounding_weights.tsv").readLines(Charsets.UTF_8))
    }

    @After
    fun tearDown() {
        ExtremeRuntimeHooks.GROUNDING_2_ENABLED = savedFlag
        GroundingFusion.resetForTest()
    }

    /** 典型目標：同列、可編輯的搜尋框；以及遠處不相關的圖示 */
    private fun searchBox() = cand(
        "a11y:search", 100, 200, 900, 260,
        text = "搜尋", viewId = "search_src_text", editable = true, score = 0.9f
    )

    private fun farIcon() = cand(
        "icon:far", 100, 1000, 160, 1060,
        channel = GroundingChannel.ICON, score = 0.1f
    )

    // ── 權重檔 ────────────────────────────────────────────────────

    @Test
    fun tsvLoadsWeightsAndMetaAlphaBias() {
        assertTrue("權重數=${GroundingFusion.weightCount()}", GroundingFusion.weightCount() > 20)
        assertEquals(0.60f, GroundingFusion.alpha(), 0.01f)
        assertEquals(0.0f, GroundingFusion.bias(), 0.01f)
        assertTrue(GroundingFusion.loadedSummary().contains("weights="))
    }

    // ── 融合方向 ──────────────────────────────────────────────────

    @Test
    fun fusionRanksSameRowEditableAboveFarIcon() {
        val textRect = rect(120, 210, 500, 250)
        val result = GroundingFusion.fuse(listOf(farIcon(), searchBox()), textRect, 1080, 1920)

        assertFalse("明確贏家不該 abstain：${result.abstainReason}", result.abstain)
        assertNotNull(result.bestCandidate)
        assertEquals("a11y:search", result.bestCandidate?.id)
        assertEquals(2, result.ranked.size)
        assertTrue(
            "ranked 應由高到低",
            result.ranked[0].fusedScore >= result.ranked[1].fusedScore
        )
        assertTrue("Top-1 應是正分", result.ranked[0].fusedScore > 0f)
    }

    @Test
    fun sameChannelCandidatesKeepScoreOrder() {
        val strong = cand("a11y:strong", 0, 0, 100, 100, score = 0.9f)
        val weak = cand("a11y:weak", 0, 200, 100, 300, score = 0.2f)
        val result = GroundingFusion.fuse(listOf(weak, strong))
        assertEquals(listOf("a11y:strong", "a11y:weak"), result.ranked.map { it.candidate.id })
    }

    @Test
    fun probabilitiesFormAValidDistribution() {
        val result = GroundingFusion.fuse(listOf(farIcon(), searchBox()), rect(120, 210, 500, 250), 1080, 1920)
        val sum = result.ranked.map { it.probability }.sum()
        assertEquals(1f, sum, 0.0001f)
        assertTrue(result.ranked.all { it.probability in 0f..1f })
        assertTrue(
            "Top-1 機率應最大（未校準，只做相對分佈）",
            result.ranked[0].probability > result.ranked[1].probability
        )
    }

    // ── 安全設計：旗標 / clamp / 壞檔 ─────────────────────────────

    @Test
    fun disabledFlagReturnsEmptyRankedAndAbstain() {
        ExtremeRuntimeHooks.GROUNDING_2_ENABLED = false
        val result = GroundingFusion.fuse(listOf(searchBox()), rect(120, 210, 500, 250), 1080, 1920)
        assertTrue(result.ranked.isEmpty())
        assertTrue(result.abstain)
        assertEquals("disabled", result.abstainReason)
    }

    @Test
    fun bonusIsClampedToTwoThenScaledByAlpha() {
        GroundingFusion.resetForTest()
        GroundingFusion.loadFromLines(
            listOf(
                "meta\talpha_fusion\t1.0",
                "fusion\tflag:clickable\t100.0"
            )
        )
        val result = GroundingFusion.fuse(listOf(cand("big", 0, 0, 100, 100, score = 0.0f)))
        assertEquals(2.0f, result.best?.bonus ?: -1f, 0.0001f)
    }

    @Test
    fun alphaScalesClampedBonus() {
        GroundingFusion.resetForTest()
        GroundingFusion.loadFromLines(
            listOf(
                "meta\talpha_fusion\t0.25",
                "fusion\tflag:clickable\t100.0"
            )
        )
        val result = GroundingFusion.fuse(listOf(cand("big", 0, 0, 100, 100, score = 0.0f)))
        assertEquals(0.5f, result.best?.bonus ?: -1f, 0.0001f)
    }

    @Test
    fun brokenWeightsFallBackToBootstrap() {
        GroundingFusion.resetForTest()
        GroundingFusion.loadFromLines(listOf("這不是合法權重列", "只有一欄", "fusion\tflag:clickable\tNaN"))
        assertTrue("坏檔後權重數=${GroundingFusion.weightCount()}", GroundingFusion.weightCount() > 20)
        // fallback 後融合仍可用且有方向性
        val result = GroundingFusion.fuse(listOf(farIcon(), searchBox()), rect(120, 210, 500, 250), 1080, 1920)
        assertEquals("a11y:search", result.bestCandidate?.id)
    }

    @Test
    fun resetClearsWeights() {
        GroundingFusion.resetForTest()
        assertEquals(0, GroundingFusion.weightCount())
    }

    @Test
    fun unknownFeatureIsIgnored() {
        GroundingFusion.resetForTest()
        GroundingFusion.loadFromLines(listOf("fusion\tflag:doesNotExist\t5.0"))
        val result = GroundingFusion.fuse(listOf(cand("x", 0, 0, 100, 100, score = 0.0f)))
        assertEquals(0f, result.best?.bonus ?: -1f, 0.0001f)
    }

    @Test
    fun flywheelTrainerModelNameIsAccepted() {
        // Phase 3 飛輪訓練器輸出 model=grounding、meta alpha/bias（tools/grounding/train_grounding.py），
        // 融合層必須吃得下同一份檔，否則真機跑了訓練等於沒生效。
        GroundingFusion.resetForTest()
        GroundingFusion.loadFromLines(
            listOf(
                "# ui_grounding_weights.tsv — 由 tools/grounding/train_grounding.py 產生",
                "meta\talpha\t0.80",
                "meta\tbias\t0.10",
                "grounding\tflag:clickable\t1.00",
                "grounding\tflag:enabled\t0.50"
            )
        )
        assertEquals(2, GroundingFusion.weightCount())
        assertEquals(0.80f, GroundingFusion.alpha(), 0.0001f)
        assertEquals(0.10f, GroundingFusion.bias(), 0.0001f)
        val result = GroundingFusion.fuse(listOf(cand("x", 0, 0, 100, 100, score = 0.0f)))
        // raw = 0.10 + 1.00(clickable) + 0.50(enabled) = 1.60 → ×0.80 = 1.28
        assertEquals(1.28f, result.best?.bonus ?: -1f, 0.0001f)
    }

    // ── abstain ───────────────────────────────────────────────────

    @Test
    fun abstainOnEmptyCandidates() {
        val result = GroundingFusion.fuse(emptyList())
        assertTrue(result.abstain)
        assertEquals("no_candidates", result.abstainReason)
        assertTrue(GroundingFusion.abstainSuggested(emptyList()))
    }

    @Test
    fun abstainOnTie() {
        // 兩個幾何/語意幾乎一樣的候選 → 分差 < margin → 寧可不動作
        val a = cand("dup-a", 100, 100, 300, 160, text = "確定", score = 0.5f)
        val b = cand("dup-b", 100, 100, 300, 160, text = "確定", score = 0.5f)
        val result = GroundingFusion.fuse(listOf(a, b))
        assertTrue("分差=${result.ranked.map { it.fusedScore }}", result.abstain)
        assertEquals("low_margin", result.abstainReason)
    }

    @Test
    fun abstainRespectsCustomThresholds() {
        val a = cand("a", 0, 0, 100, 100, score = 0.6f)
        val b = cand("b", 0, 0, 100, 100, score = 0.4f)
        val ranked = GroundingFusion.fuse(listOf(a, b)).ranked
        // 預設 margin 0.15：分差可能不足 → abstain；把 margin 放寬到 0 就一定不 abstain
        assertFalse(GroundingFusion.abstainSuggested(ranked, minMargin = 0f, minTopScore = -100f))
    }

    @Test
    fun abstainOnNonActionableWinner() {
        val label = cand(
            "label", 100, 100, 400, 140, text = "純文字",
            clickable = false, score = 0.99f
        )
        val result = GroundingFusion.fuse(listOf(label))
        assertTrue(result.abstain)
        assertEquals("no_actionable_candidate", result.abstainReason)
    }

    @Test
    fun clearWinnerDoesNotAbstain() {
        val result = GroundingFusion.fuse(listOf(farIcon(), searchBox()), rect(120, 210, 500, 250), 1080, 1920)
        assertFalse(result.abstain)
        assertEquals("", result.abstainReason)
        assertFalse(GroundingFusion.abstainSuggested(result.ranked))
    }
}
