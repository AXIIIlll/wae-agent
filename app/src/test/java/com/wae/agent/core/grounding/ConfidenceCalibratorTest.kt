package com.wae.agent.core.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S32：信心校準與「不動作」決策。守住：
 *  1. 分數 → 機率是單調的（高分不會得到低機率）
 *  2. 高信心 + 大差距 → ACT
 *  3. 高信心 + 小差距 → ASK（寧可問，不要亂點）
 *  4. 低信心 + 可捲動 → SCROLL；不可捲動 → RE_PERCEIVE
 *  5. 壞檔 / 非單調校準點 → 退回 bootstrap，且輸出仍然單調
 */
class ConfidenceCalibratorTest {

    @Before
    fun setUp() {
        ConfidenceCalibrator.resetForTest()
    }

    @Test
    fun `default calibration is monotonic`() {
        var prev = -1f
        var s = 0f
        while (s <= 1.0f) {
            val p = ConfidenceCalibrator.pSuccess(s)
            assertTrue("pSuccess 必須單調：s=$s p=$p prev=$prev", p >= prev - 1e-6f)
            assertTrue("機率必須在 0..1：s=$s p=$p", p in 0f..1f)
            prev = p
            s += 0.05f
        }
        assertTrue(ConfidenceCalibrator.pSuccess(1f) > ConfidenceCalibrator.pSuccess(0f))
    }

    @Test
    fun `act ask scroll reperceive decisions`() {
        assertEquals(
            ConfidenceCalibrator.GroundingAction.ACT,
            ConfidenceCalibrator.decide(0.92f, 0.40f, scrollable = false)
        )
        assertEquals(
            ConfidenceCalibrator.GroundingAction.ASK,
            ConfidenceCalibrator.decide(0.92f, 0.02f, scrollable = false)
        )
        assertEquals(
            ConfidenceCalibrator.GroundingAction.SCROLL,
            ConfidenceCalibrator.decide(0.15f, 0.00f, scrollable = true)
        )
        assertEquals(
            ConfidenceCalibrator.GroundingAction.RE_PERCEIVE,
            ConfidenceCalibrator.decide(0.15f, 0.00f, scrollable = false)
        )
        assertEquals(
            ConfidenceCalibrator.GroundingAction.SCROLL,
            ConfidenceCalibrator.decide(0.55f, 0.10f, scrollable = true)
        )
    }

    @Test
    fun `non monotonic calibration points are clamped`() {
        ConfidenceCalibrator.loadFromLines(
            listOf(
                "point\t0.0\t0.9",
                "point\t0.5\t0.2",
                "point\t1.0\t0.95"
            )
        )
        // 違反單調的點會被修正：0.5 的機率不會低於 0.0 的 0.9
        assertTrue(ConfidenceCalibrator.pSuccess(0.5f) >= ConfidenceCalibrator.pSuccess(0.0f))
        assertTrue(ConfidenceCalibrator.pSuccess(1.0f) >= ConfidenceCalibrator.pSuccess(0.5f))
    }

    @Test
    fun `meta thresholds are loaded from tsv`() {
        ConfidenceCalibrator.loadFromLines(
            listOf(
                "meta\tmin_act_prob\t0.90",
                "meta\tmin_act_margin\t0.01",
                "meta\tmin_reperceive_prob\t0.30",
                "point\t0.0\t0.0",
                "point\t1.0\t1.0"
            )
        )
        val (p, m, r) = ConfidenceCalibrator.thresholds()
        assertEquals(0.90f, p, 0.001f)
        assertEquals(0.01f, m, 0.001f)
        assertEquals(0.30f, r, 0.001f)
        // 0.92 信心、差距 0.02 → 門檻 0.90/0.01 下應 ACT
        assertEquals(
            ConfidenceCalibrator.GroundingAction.ACT,
            ConfidenceCalibrator.decide(0.92f, 0.02f, scrollable = false)
        )
    }

    @Test
    fun `broken tsv falls back to bootstrap`() {
        ConfidenceCalibrator.loadFromLines(listOf("garbage", "只有一欄"))
        assertTrue(ConfidenceCalibrator.pointCount() >= 2)
        assertTrue(ConfidenceCalibrator.pSuccess(1f) > 0.9f)
    }
}
