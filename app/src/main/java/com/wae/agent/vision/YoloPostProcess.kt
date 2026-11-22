package com.wae.agent.vision

import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 檢測輸出的純後處理（letterbox / 解碼 / NMS）。
 *
 * 抽成純函式以便在 JVM 單元測試覆蓋：真實模型輸出是 [1, 4+classes, anchors]，
 * OmniParser 的 icon 模型只有 1 類，因此 4+1=5。
 */
object YoloPostProcess {

    data class Letterbox(val scale: Float, val padX: Float, val padY: Float, val inputSize: Int)

    data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float) {
        val cx: Float get() = (x1 + x2) * 0.5f
        val cy: Float get() = (y1 + y2) * 0.5f
        val w: Float get() = (x2 - x1).coerceAtLeast(0f)
        val h: Float get() = (y2 - y1).coerceAtLeast(0f)
        val area: Float get() = w * h
    }

    /** 等比縮放 + 居中補邊（YOLO 標準 letterbox，補 114 灰） */
    fun computeLetterbox(srcW: Int, srcH: Int, inputSize: Int): Letterbox {
        val scale = min(inputSize.toFloat() / srcW.toFloat(), inputSize.toFloat() / srcH.toFloat())
        val newW = srcW * scale
        val newH = srcH * scale
        val padX = (inputSize - newW) * 0.5f
        val padY = (inputSize - newH) * 0.5f
        return Letterbox(scale, padX, padY, inputSize)
    }

    /**
     * 解碼 YOLOv8 輸出並回映射到原圖座標。
     *
     * @param output 攤平後的 [channels][anchors] 資料（row-major，channels = 4 + 類別數）
     * @param channels 4 + 類別數（OmniParser icon = 5）
     * @param anchors 錨點數量（640×640 → 8400）
     * @param scoreThreshold 分數門檻
     * @param srcW/srcH 原圖尺寸（解 letterbox 用）
     */
    fun decode(
        output: FloatArray,
        channels: Int,
        anchors: Int,
        scoreThreshold: Float,
        box: Letterbox,
        srcW: Int,
        srcH: Int
    ): List<Box> {
        if (channels < 5 || anchors <= 0) return emptyList()
        val result = ArrayList<Box>(64)
        for (a in 0 until anchors) {
            var score = 0f
            for (c in 4 until channels) {
                val v = output[c * anchors + a]
                if (v > score) score = v
            }
            if (score < scoreThreshold) continue

            val cx = output[0 * anchors + a]
            val cy = output[1 * anchors + a]
            val w = output[2 * anchors + a]
            val h = output[3 * anchors + a]
            if (w <= 0f || h <= 0f) continue

            // letterbox → 原圖座標
            val x1 = ((cx - w * 0.5f) - box.padX) / box.scale
            val y1 = ((cy - h * 0.5f) - box.padY) / box.scale
            val x2 = ((cx + w * 0.5f) - box.padX) / box.scale
            val y2 = ((cy + h * 0.5f) - box.padY) / box.scale

            val cx1 = x1.coerceIn(0f, srcW.toFloat())
            val cy1 = y1.coerceIn(0f, srcH.toFloat())
            val cx2 = x2.coerceIn(0f, srcW.toFloat())
            val cy2 = y2.coerceIn(0f, srcH.toFloat())
            if (cx2 - cx1 < 4f || cy2 - cy1 < 4f) continue
            result.add(Box(cx1, cy1, cx2, cy2, score))
        }
        return result
    }

    /** 類別無關 NMS（OMI 只有 icon 一類） */
    fun nms(boxes: List<Box>, iouThreshold: Float, maxBoxes: Int = 24): List<Box> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.score }
        val kept = ArrayList<Box>(min(sorted.size, maxBoxes))
        for (b in sorted) {
            var keep = true
            for (k in kept) {
                if (iou(b, k) > iouThreshold) { keep = false; break }
            }
            if (keep) {
                kept.add(b)
                if (kept.size >= maxBoxes) break
            }
        }
        return kept
    }

    fun iou(a: Box, b: Box): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = (ix2 - ix1).coerceAtLeast(0f)
        val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}
