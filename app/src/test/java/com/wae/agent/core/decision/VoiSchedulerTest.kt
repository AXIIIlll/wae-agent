package com.wae.agent.core.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoI 感知調度的核心邏輯測試（M1 驗收）。
 *
 * 驗證「用同樣的電量做更多決策」這件事真的成立：
 *  - 高置信頁面要跳過昂貴感知
 *  - 近同分要主動加碼
 *  - 預算不足時要挑「每毫秒增益最高」的
 */
class VoiSchedulerTest {

    private fun opt(name: String, cost: Long, gain: Float) =
        VoiScheduler.PerceptionOption(name, cost, gain)

    @Test
    fun `rich a11y page skips both expensive modules`() {
        // 頁面資訊充足：兩個模組的增益都低 → 不該花 1s 去跑
        val plan = VoiScheduler.plan(
            options = listOf(opt("visual.detect", 180, 0.05f), opt("ocr", 900, 0.08f)),
            budgetMs = 1200
        )
        assertTrue("低增益模組不該被選中，實際=${plan.chosen}", plan.chosen.isEmpty())
        assertEquals(0L, plan.spentMs)
        assertTrue(plan.skipped.containsAll(listOf("visual.detect", "ocr")))
    }

    @Test
    fun `sparse page runs both modules within budget`() {
        val plan = VoiScheduler.plan(
            options = listOf(opt("visual.detect", 180, 0.75f), opt("ocr", 900, 0.8f)),
            budgetMs = 1200
        )
        assertEquals(listOf("ocr", "visual.detect").sorted(), plan.chosen.sorted())
        assertTrue(plan.spentMs <= 1200)
    }

    @Test
    fun `budget forces the cheaper module when only one fits`() {
        val plan = VoiScheduler.plan(
            options = listOf(opt("visual.detect", 180, 0.75f), opt("ocr", 900, 0.8f)),
            budgetMs = 600
        )
        assertTrue("預算內只裝得下檢測", plan.chosen.contains("visual.detect"))
        assertFalse("OCR 超出預算", plan.chosen.contains("ocr"))
        assertTrue(plan.skipped.contains("ocr"))
    }

    @Test
    fun `higher value density wins when budget is tight`() {
        // 便宜且增益高者優先：detect = 0.75/180 ≈ 0.0042/ms；ocr = 0.8/900 ≈ 0.0009/ms
        val plan = VoiScheduler.plan(
            options = listOf(opt("ocr", 900, 0.8f), opt("visual.detect", 180, 0.75f)),
            budgetMs = 200
        )
        assertEquals(listOf("visual.detect"), plan.chosen)
    }

    @Test
    fun `escalate on near tie`() {
        assertTrue(
            "兩個候選都 ≥0.8 且差距 <0.03 → 應加碼",
            VoiScheduler.shouldEscalate(top1 = 0.90f, top2 = 0.88f)
        )
    }

    @Test
    fun `no escalate on clear winner`() {
        assertFalse(
            "差距大 → 不需要加碼",
            VoiScheduler.shouldEscalate(top1 = 0.95f, top2 = 0.40f, top1Probability = 0.9f)
        )
    }

    @Test
    fun `escalate when leader probability is weak`() {
        assertTrue(
            "第一名機率很低 → 需要更多資訊",
            VoiScheduler.shouldEscalate(top1 = 0.9f, top2 = 0.3f, top1Probability = 0.2f)
        )
    }

    @Test
    fun `empty options produce empty plan`() {
        val plan = VoiScheduler.plan(emptyList(), budgetMs = 1000)
        assertTrue(plan.chosen.isEmpty())
        assertTrue(plan.skipped.isEmpty())
        assertEquals(0f, plan.totalGain, 1e-6f)
    }

    @Test
    fun `zero cost or zero gain options are skipped`() {
        val plan = VoiScheduler.plan(
            listOf(opt("free", 0, 0.9f), opt("useless", 100, 0f)),
            budgetMs = 1000
        )
        assertTrue(plan.chosen.isEmpty())
        assertTrue(plan.skipped.containsAll(listOf("free", "useless")))
    }
}
