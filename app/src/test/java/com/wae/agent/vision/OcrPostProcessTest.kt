package com.wae.agent.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 敺??? JVM 皜祈岫嚗??閬?ORT / ??嚗?
 * ?????????clamp?TC 鞎芸帚閫?Ⅳ?? */
class OcrPostProcessTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = OcrPostProcess.OcrBox(l, t, r, b)

    @Test
    fun `extractBoxes finds two separate text regions and filters noise`() {
        val w = 20
        val h = 10
        val prob = FloatArray(w * h)
        // ???A嚗椰銝?3x3
        for (y in 4..6) for (x in 1..3) prob[y * w + x] = 0.9f
        // ???B嚗銝?4x2
        for (y in 7..8) for (x in 14..17) prob[y * w + x] = 0.8f
        // ?桐?????嚗蝛?< minAreaPx嚗?鋡思?璉?
        prob[0] = 0.99f

        val boxes = OcrPostProcess.extractBoxes(prob, w, h, threshold = 0.3f, minAreaPx = 4)
        assertEquals(2, boxes.size)
        assertEquals(box(1, 4, 4, 7), boxes[0].box)      // ?曹?????
        assertEquals(box(14, 7, 18, 9), boxes[1].box)
        assertTrue(boxes[0].detScore > 0.8f)
    }

    @Test
    fun `extractBoxes respects threshold`() {
        val w = 8
        val h = 4
        val prob = FloatArray(w * h) { 0.1f }
        val boxes = OcrPostProcess.extractBoxes(prob, w, h, threshold = 0.3f, minAreaPx = 2)
        assertEquals(0, boxes.size)
    }

    @Test
    fun `unclip expands box and clamps to image bounds`() {
        val b0 = box(0, 0, 10, 10)
        val out = OcrPostProcess.unclipAndClamp(b0, ratio = 0.5f, imgW = 100, imgH = 100)
        assertEquals(0, out.left)          // 撌脰票??銝??箄?
        assertEquals(0, out.top)
        assertEquals(13, out.right)        // 10 + (10*0.5)/2 = 12.5 ??13
        assertEquals(13, out.bottom)

        val nearEdge = box(90, 90, 100, 100)
        val clamped = OcrPostProcess.unclipAndClamp(nearEdge, ratio = 0.5f, imgW = 100, imgH = 100)
        assertEquals(100, clamped.right)   // 銝?頞敶勗?
        assertEquals(100, clamped.bottom)
    }

    @Test
    fun `ctc decode collapses repeats and skips blanks`() {
        val charset = listOf("a", "b", "c", "1")
        // blank=0, a=1, b=2, c=3, 1=4, space=5
        fun row(best: Int): FloatArray = FloatArray(6) { if (it == best) 0.9f else 0.02f }
        val logits = arrayOf(
            row(1), row(1),   // a a ???箇?????a
            row(0),           // blank ????
            row(2),           // b
            row(0),           // blank
            row(4)            // 1
        )
        val (text, conf) = OcrPostProcess.ctcGreedyDecode(logits, charset)
        assertEquals("ab1", text)
        assertTrue(conf in 0.8f..1.0f)
    }

    @Test
    fun `ctc decode handles space token and empty input`() {
        val charset = listOf("x")
        // index: 0=blank, 1=x, 2=space (charset.size)
        fun row(best: Int): FloatArray = FloatArray(3) { if (it == best) 0.8f else 0.1f }
        val logits = arrayOf(row(1), row(2), row(1))
        val (text, _) = OcrPostProcess.ctcGreedyDecode(logits, charset)
        assertEquals("x x", text)

        val (empty, zero) = OcrPostProcess.ctcGreedyDecode(emptyArray(), charset)
        assertEquals("", empty)
        assertEquals(0f, zero, 1e-6f)
    }

    @Test
    fun `ctc decode ignores out of range class indices`() {
        val charset = listOf("a")
        // class index 3 is out of range (charset.size=1 -> space=2), must be ignored
        fun row(best: Int): FloatArray = FloatArray(5) { if (it == best) 0.7f else 0.05f }
        val (text, _) = OcrPostProcess.ctcGreedyDecode(arrayOf(row(1), row(3)), charset)
        assertEquals("a", text)
    }
}

