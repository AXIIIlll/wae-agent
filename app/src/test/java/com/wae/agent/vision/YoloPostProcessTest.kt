package com.wae.agent.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 視覺召回通道的純後處理測試（不需要 ORT / 真機）：
 * letterbox 參數、YOLOv8 輸出解碼與座標回映射、NMS。
 */
class YoloPostProcessTest {

    @Test
    fun `letterbox keeps aspect ratio and centers padding`() {
        // 1080x2340 手機豎屏 → 640 輸入：以高度為準縮放
        val lb = YoloPostProcess.computeLetterbox(1080, 2340, 640)
        assertTrue(abs(lb.scale - 640f / 2340f) < 1e-5f)
        assertEquals(0f, lb.padY, 0.01f)                       // 高度已佔滿
        val expectedPadX = (640f - 1080f * lb.scale) / 2f
        assertEquals(expectedPadX, lb.padX, 0.01f)
    }

    @Test
    fun `decode maps letterboxed boxes back to screen coordinates`() {
        // 假設 640x640 輸入、1:1 縮放（src 也是 640x640），一個位於中心的框
        val lb = YoloPostProcess.computeLetterbox(640, 640, 640)
        val anchors = 2
        val channels = 5
        val out = FloatArray(channels * anchors)
        // anchor 0: 中心 (320,320) 大小 100x50，分數 0.9
        out[0 * anchors + 0] = 320f
        out[1 * anchors + 0] = 320f
        out[2 * anchors + 0] = 100f
        out[3 * anchors + 0] = 50f
        out[4 * anchors + 0] = 0.9f
        // anchor 1: 分數太低，應被丟棄
        out[4 * anchors + 1] = 0.10f

        val boxes = YoloPostProcess.decode(out, channels, anchors, 0.30f, lb, 640, 640)
        assertEquals(1, boxes.size)
        val b = boxes[0]
        assertEquals(270f, b.x1, 0.5f)
        assertEquals(295f, b.y1, 0.5f)
        assertEquals(370f, b.x2, 0.5f)
        assertEquals(345f, b.y2, 0.5f)
        assertEquals(0.9f, b.score, 1e-5f)
    }

    @Test
    fun `decode clamps boxes to screen and drops degenerate ones`() {
        val lb = YoloPostProcess.computeLetterbox(640, 640, 640)
        val anchors = 2
        val out = FloatArray(5 * anchors)
        fun setBox(a: Int, cx: Float, cy: Float, w: Float, h: Float, score: Float) {
            out[0 * anchors + a] = cx
            out[1 * anchors + a] = cy
            out[2 * anchors + a] = w
            out[3 * anchors + a] = h
            out[4 * anchors + a] = score
        }
        // 跨越左邊界的框 → x1 應被 clamp 到 0，仍保留
        setBox(0, cx = -20f, cy = 320f, w = 200f, h = 100f, score = 0.8f)
        // 尺寸為 0 的退化框 → 丟棄
        setBox(1, cx = 320f, cy = 320f, w = 0f, h = 0f, score = 0.9f)

        val boxes = YoloPostProcess.decode(out, 5, anchors, 0.3f, lb, 640, 640)
        assertEquals(1, boxes.size)
        assertEquals(0f, boxes[0].x1, 0.01f)
        assertEquals(80f, boxes[0].x2, 0.5f)
    }

    @Test
    fun `nms suppresses overlapping duplicates and keeps distinct boxes`() {
        val lb = YoloPostProcess.computeLetterbox(640, 640, 640)
        val anchors = 3
        val out = FloatArray(5 * anchors)
        fun setBox(a: Int, cx: Float, cy: Float, w: Float, h: Float, score: Float) {
            out[0 * anchors + a] = cx
            out[1 * anchors + a] = cy
            out[2 * anchors + a] = w
            out[3 * anchors + a] = h
            out[4 * anchors + a] = score
        }
        // 兩個高度重疊、分數不同（YOLOv8 輸出佈局是 [channels][anchors]）
        setBox(0, cx = 100f, cy = 100f, w = 80f, h = 80f, score = 0.95f)
        setBox(1, cx = 104f, cy = 102f, w = 80f, h = 80f, score = 0.60f)
        // 一個明顯分開的
        setBox(2, cx = 500f, cy = 500f, w = 60f, h = 60f, score = 0.70f)

        val decoded = YoloPostProcess.decode(out, 5, anchors, 0.3f, lb, 640, 640)
        val kept = YoloPostProcess.nms(decoded, 0.45f)
        assertEquals(2, kept.size)
        assertEquals(0.95f, kept[0].score, 1e-5f)   // 高分者勝出
        assertEquals(0.70f, kept[1].score, 1e-5f)
    }

    @Test
    fun `iou is symmetric and bounded`() {
        val a = YoloPostProcess.Box(0f, 0f, 10f, 10f, 1f)
        val b = YoloPostProcess.Box(5f, 5f, 15f, 15f, 1f)
        val ab = YoloPostProcess.iou(a, b)
        val ba = YoloPostProcess.iou(b, a)
        assertEquals(ab, ba, 1e-6f)
        assertTrue(ab in 0f..1f)
        assertEquals(25f / 175f, ab, 1e-4f)          // 交集 25，聯集 175
        assertEquals(1f, YoloPostProcess.iou(a, a), 1e-6f)
    }
}
