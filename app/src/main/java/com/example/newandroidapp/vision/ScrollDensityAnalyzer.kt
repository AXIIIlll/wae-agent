package com.example.newandroidapp.vision

import android.graphics.Rect

/**
 * 滑動步幅模式
 *
 * LARGE_FAST: 大步快滑 (~60%-75% 螢幕高度, 180ms)，適用於長列表搜尋、稀疏內容與快速翻頁
 * FINE_DENSE: 微幅小步滑動 (~25%-35% 螢幕高度, 320ms)，適用於密集設定、開關群組、表單項目
 */
enum class ScrollStrideMode(
    val strideFraction: Float,
    val durationMs: Long,
    val description: String
) {
    LARGE_FAST(0.68f, 180L, "大步快滑 (搜尋/長列表)"),
    FINE_DENSE(0.28f, 320L, "微幅小步滑動 (開關/設定列表)");

    companion object {
        val LARGE_STRIDE = LARGE_FAST
        val FINE_STRIDE = FINE_DENSE
    }
}

data class ScrollParameters(
    val mode: ScrollStrideMode,
    val strideFraction: Float,
    val durationMs: Long,
    val startYFraction: Float,
    val endYFraction: Float,
    val containerBounds: Rect? = null
)

/**
 * 列表密度分析與動態滑動步幅控制器 (Scroll Density Analyzer)
 *
 * 依據畫面中的可見元件密度、開關元件數量與平均項目高度，自動切換大步快滑與微幅小步滑動。
 */
object ScrollDensityAnalyzer {

    /**
     * 判定滑動步幅模式與比例 (PROJECT.md 規格合約)
     */
    fun determineScrollStride(
        visibleNodes: List<Stage2ConvergedNode>,
        screenHeightPx: Int
    ): Pair<ScrollStrideMode, Float> {
        val switchNodes = visibleNodes.filter { it.shortTypeTag == "CHK" }
        val itemHeights = visibleNodes
            .filter { it.shortTypeTag != "INP" && (it.convergedBounds.bottom - it.convergedBounds.top) in 28..(screenHeightPx * 0.75f).toInt() }
            .map { it.convergedBounds.bottom - it.convergedBounds.top }

        val avgItemHeight = if (itemHeights.isNotEmpty()) itemHeights.average() else (screenHeightPx * 0.15)
        // 判定為密集列表：開關元件 >= 2，或項目平均高度 <= 螢幕 8.5%，或有 4 個以上高度小於等於 160px 的元件
        val isDense = switchNodes.size >= 2 ||
            (itemHeights.isNotEmpty() && avgItemHeight <= (screenHeightPx * 0.085f)) ||
            itemHeights.count { it <= 160 } >= 4

        return if (isDense) {
            ScrollStrideMode.FINE_DENSE to ScrollStrideMode.FINE_DENSE.strideFraction
        } else {
            ScrollStrideMode.LARGE_FAST to ScrollStrideMode.LARGE_FAST.strideFraction
        }
    }

    /**
     * 計算完整滑動參數 (含起點、終點 Y 軸比例、手勢持續時間與容器邊界)
     */
    fun calculateScrollParameters(
        rawNodes: List<RawVisionNode>,
        convergedNodes: List<Stage2ConvergedNode>,
        down: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        intentCategory: String? = null
    ): ScrollParameters {
        val (determinedMode, _) = determineScrollStride(convergedNodes, screenHeight)

        // 若 intentCategory 明確指定意圖，給予定向權重
        val finalMode = when {
            intentCategory != null && (intentCategory.contains("SWITCH") || intentCategory.contains("TOGGLE") || intentCategory.contains("SETTING")) ->
                ScrollStrideMode.FINE_DENSE
            intentCategory != null && (intentCategory.contains("SEARCH") || intentCategory.contains("FEED") || intentCategory.contains("SCROLL")) ->
                ScrollStrideMode.LARGE_FAST
            else -> determinedMode
        }

        val finalStride = finalMode.strideFraction
        val duration = finalMode.durationMs

        // 定位主要的垂直可滑動容器
        val scrollContainer = rawNodes.firstOrNull {
            it.isScrollable && (it.scrollDirections.contains("V") || it.scrollDirections.isEmpty())
        }
        val containerBounds = scrollContainer?.bounds ?: Rect(0, 0, screenWidth, screenHeight)

        // 計算滑動起點與終點比例
        // down = true (向下滾動顯示下方內容，手指由下往上滑動)
        // down = false (向上滾動顯示上方內容，手指由上往下滑動)
        val (startYFraction, endYFraction) = if (down) {
            val start = (0.50f + finalStride / 2f).coerceAtMost(0.82f)
            val end = (start - finalStride).coerceAtLeast(0.18f)
            start to end
        } else {
            val start = (0.50f - finalStride / 2f).coerceAtLeast(0.18f)
            val end = (start + finalStride).coerceAtMost(0.82f)
            start to end
        }

        return ScrollParameters(
            mode = finalMode,
            strideFraction = finalStride,
            durationMs = duration,
            startYFraction = startYFraction,
            endYFraction = endYFraction,
            containerBounds = containerBounds
        )
    }
}
