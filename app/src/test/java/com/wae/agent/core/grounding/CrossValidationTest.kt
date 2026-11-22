package com.wae.agent.core.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S32：多模態交叉驗證（a11y 樹 vs OCR 文字框）。
 *
 * 實機痛點：WebView/Compose 的 a11y 樹可能過期或不存在，
 * 若直接相信候選文字就會點錯；這裡用「空間重疊 + 文字相似度」交叉檢查。
 */
class CrossValidationTest {

    private fun cand(
        text: String,
        bounds: IntArray,
        clickable: Boolean = true
    ) = GroundingCandidate(
        id = "c1",
        channel = GroundingChannel.A11Y,
        text = text,
        bounds = bounds,
        clickable = clickable,
        score = 0.8f
    )

    @Test
    fun `same text inside bounds is a match`() {
        val c = cand("搜尋", intArrayOf(100, 100, 500, 180))
        val ocr = listOf(CrossValidation.OcrBox("搜尋", intArrayOf(120, 110, 480, 175)))
        val r = CrossValidation.validate(c, ocr, a11yTexts = listOf("搜尋"))
        assertEquals(CrossValidation.Verdict.MATCH, r.verdict)
        assertTrue(r.trusted)
        assertTrue(r.agreement >= 0.8f)
    }

    @Test
    fun `completely different text is a mismatch`() {
        val c = cand("搜尋", intArrayOf(100, 100, 500, 180))
        val ocr = listOf(CrossValidation.OcrBox("購物車", intArrayOf(120, 110, 480, 175)))
        val r = CrossValidation.validate(c, ocr, a11yTexts = listOf("搜尋", "購物車"))
        assertEquals(CrossValidation.Verdict.MISMATCH, r.verdict)
        assertTrue(!r.trusted)
    }

    @Test
    fun `partial overlap scores partial`() {
        val c = cand("台北 101 觀景台", intArrayOf(0, 0, 600, 100))
        val ocr = listOf(CrossValidation.OcrBox("台北101", intArrayOf(10, 10, 300, 90)))
        val r = CrossValidation.validate(c, ocr)
        // contains 關聯 → 0.6~0.95，不會是 MISMATCH
        assertTrue(r.verdict == CrossValidation.Verdict.MATCH || r.verdict == CrossValidation.Verdict.PARTIAL)
    }

    @Test
    fun `no ocr inside bounds is a11y only`() {
        val c = cand("設定", intArrayOf(0, 0, 200, 100))
        val ocr = listOf(CrossValidation.OcrBox("其他地方", intArrayOf(800, 800, 1000, 900)))
        val r = CrossValidation.validate(c, ocr)
        assertEquals(CrossValidation.Verdict.A11Y_ONLY, r.verdict)
    }

    @Test
    fun `candidate without text is a11y only`() {
        val c = cand("", intArrayOf(0, 0, 100, 100))
        val r = CrossValidation.validate(c, listOf(CrossValidation.OcrBox("x", intArrayOf(0, 0, 100, 100))))
        assertEquals(CrossValidation.Verdict.A11Y_ONLY, r.verdict)
    }

    @Test
    fun `ocr only boxes finds text missing from a11y tree`() {
        val ocr = listOf(
            CrossValidation.OcrBox("購買", intArrayOf(10, 10, 100, 50)),
            CrossValidation.OcrBox("首頁", intArrayOf(500, 500, 600, 560))
        )
        val only = CrossValidation.ocrOnlyBoxes(ocr, a11yTexts = listOf("首頁"))
        assertEquals(1, only.size)
        assertEquals("購買", only.first().text)
    }

    @Test
    fun `normalize strips punctuation and case`() {
        assertEquals("abc搜尋", CrossValidation.normalize("A B C，搜尋！"))
    }

    @Test
    fun `text similarity is 1 for identical normalized text`() {
        assertEquals(1f, CrossValidation.textSimilarity("Wi-Fi", "wifi"), 1e-6f)
    }
}
