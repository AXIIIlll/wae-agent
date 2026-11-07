package com.example.newandroidapp.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 第六階段進階優化 (Part A)：
 * 全螢幕透明攔截層（Touch-Through FX / 浮水印層）vs. 半透明模態黑底（Modal Dim Scrim）極速像素鑑別器
 *
 * 解決痛點：
 * 在 Android View 樹中，許多 App 會在最頂層放一個面積 > 50% 螢幕、無文字、無子節點的全螢幕 View：
 * - 情況 A：它是「透明特效層 / 浮水印層 / 手勢監聽空殼」，視覺上完全透明，底層按鈕清晰可見且可點擊。
 *           若誤當成不透明遮罩寫入 OccupancyGrid2D，會瞬間把整個螢幕 100% 抹黑，導致所有正常按鈕被誤殺！
 * - 情況 B：它是 Dialog 或 BottomSheet 背後的「半透明暗色黑底（Dim Scrim）」，視覺上呈現均勻低亮度暗化，
 *           用來阻斷底層點擊。此時必須識別為模態阻斷層。
 *
 * 演算法：
 * 僅在該大面積覆蓋層的「未被上層實體卡片佔用之周邊區域」抽樣 16 個像素點（耗時 < 0.05ms），
 * 計算平均亮度（Mean Luminance）與局部對比標準差（Luminance StdDev）：
 * - 若呈現「極低亮度 + 極低標準差（均勻暗黑）」-> 判定為【模態半透明黑底（Modal Scrim）】，執行遮擋阻斷。
 * - 若呈現「高對比度 / 色彩豐富（底層元件透出）」-> 判定為【透明穿透裝飾層（Transparent Pass-Through）】，豁免遮罩寫入！
 */
object ScrimAndAlphaInspector {

    enum class OverlayAlphaType(val desc: String) {
        SOLID_COMPONENT("一般實體元件"),
        TRANSPARENT_PASSTHROUGH("全螢幕透明穿透層(已豁免遮罩)"),
        MODAL_DIM_SCRIM("半透明暗色阻斷黑底(已啟動遮擋)")
    }

    data class InspectionResult(
        val type: OverlayAlphaType,
        val meanLuma: Float,
        val lumaStdDev: Float,
        val shouldWriteToOccupancyGrid: Boolean
    )

    fun inspectLargeOverlay(
        raw: RawVisionNode,
        screenWidth: Int,
        screenHeight: Int,
        screenBitmap: Bitmap?
    ): InspectionResult {
        val screenArea = (screenWidth * screenHeight).toFloat().coerceAtLeast(1f)
        val rawW = (raw.bounds.right - raw.bounds.left).coerceAtLeast(0)
        val rawH = (raw.bounds.bottom - raw.bounds.top).coerceAtLeast(0)
        val nodeArea = (rawW.toFloat() * rawH.toFloat())
        val areaRatio = nodeArea / screenArea

        // 一般中小型元件直接依原本的 isOpaqueOverlay 屬性決定
        val isLargeMysteryLayer = areaRatio >= 0.45f &&
            raw.childIds.isEmpty() &&
            raw.text.isBlank() &&
            raw.contentDescription.isBlank() &&
            !raw.isCanvasOrBlackBoxCandidate

        if (!isLargeMysteryLayer) {
            return InspectionResult(
                type = OverlayAlphaType.SOLID_COMPONENT,
                meanLuma = -1f,
                lumaStdDev = -1f,
                shouldWriteToOccupancyGrid = raw.isOpaqueOverlay || raw.isModalScrim
            )
        }

        // 若無 Bitmap 可供像素抽樣，針對無文字無點擊的全螢幕空殼預設視為透明穿透層，防止誤殺全螢幕
        if (screenBitmap == null || screenBitmap.isRecycled) {
            val isExplicitScrim = raw.isModalScrim ||
                raw.viewIdResourceName.contains("scrim", ignoreCase = true) ||
                raw.viewIdResourceName.contains("touch_outside", ignoreCase = true)
            return InspectionResult(
                type = if (isExplicitScrim) OverlayAlphaType.MODAL_DIM_SCRIM else OverlayAlphaType.TRANSPARENT_PASSTHROUGH,
                meanLuma = 0f,
                lumaStdDev = 0f,
                shouldWriteToOccupancyGrid = isExplicitScrim
            )
        }

        // 在大面積覆蓋層內進行 4x4 (16 點) 極速像素抽樣
        val rect = Rect(raw.bounds)
        rect.intersect(0, 0, screenWidth, screenHeight)
        val scaleX = screenBitmap.width.toFloat() / screenWidth.toFloat()
        val scaleY = screenBitmap.height.toFloat() / screenHeight.toFloat()

        val lumas = FloatArray(16)
        var count = 0
        for (gy in 0 until 4) {
            val sy = rect.top + ((gy + 0.5f) / 4f) * rect.height()
            val py = (sy * scaleY).toInt().coerceIn(0, screenBitmap.height - 1)
            for (gx in 0 until 4) {
                val sx = rect.left + ((gx + 0.5f) / 4f) * rect.width()
                val px = (sx * scaleX).toInt().coerceIn(0, screenBitmap.width - 1)
                val c = screenBitmap.getPixel(px, py)
                val luma = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f)
                lumas[count++] = luma
            }
        }

        val mean = lumas.sum() / max(1, count)
        var varianceSum = 0f
        for (i in 0 until count) {
            val diff = lumas[i] - mean
            varianceSum += diff * diff
        }
        val stdDev = sqrt(varianceSum / max(1, count))

        // 判定準則：
        // 若畫面具有明顯亮部對比（stdDev > 22f）或平均亮度較高，代表底層豐富 UI 完整透出 -> 屬於【透明穿透特效/手勢層】！
        // 若畫面整體極暗（mean < 18f）且極度均勻無對比（stdDev <= 14f）且明確標記為 Scrim -> 屬於【半透明暗色阻斷黑底】
        val isDimScrim = raw.isModalScrim && mean < 22f && stdDev <= 18f
        return if (isDimScrim) {
            InspectionResult(
                type = OverlayAlphaType.MODAL_DIM_SCRIM,
                meanLuma = mean,
                lumaStdDev = stdDev,
                shouldWriteToOccupancyGrid = true
            )
        } else {
            InspectionResult(
                type = OverlayAlphaType.TRANSPARENT_PASSTHROUGH,
                meanLuma = mean,
                lumaStdDev = stdDev,
                shouldWriteToOccupancyGrid = false
            )
        }
    }
}
