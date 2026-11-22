package com.wae.agent.core.runtime

/**
 * 手勢幾何（純邏輯，可在 JVM 測試）。
 *
 * 所有座標都以「螢幕寬高的比例」表示，確保跨機型/跨解析度一致，
 * 也讓派發層只負責把 Path/GestureDescription 組起來。
 */
object GesturePaths {

    data class Stroke(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long
    ) {
        fun isHorizontal(): Boolean = kotlin.math.abs(endX - startX) > kotlin.math.abs(endY - startY)
    }

    /** 向下滾動半屏（手指由下往上） */
    fun scrollDown(screenW: Int, screenH: Int): Stroke = Stroke(
        startX = screenW / 2f,
        startY = screenH * 0.72f,
        endX = screenW / 2f,
        endY = screenH * 0.28f,
        durationMs = 280L
    )

    /** 向上滾動半屏（手指由上往下） */
    fun scrollUp(screenW: Int, screenH: Int): Stroke = Stroke(
        startX = screenW / 2f,
        startY = screenH * 0.28f,
        endX = screenW / 2f,
        endY = screenH * 0.72f,
        durationMs = 280L
    )

    /** 水平滑動（leftToRight=true 表示手指由左往右，通常切換到上一頁/Tab） */
    fun swipeHorizontal(screenW: Int, screenH: Int, leftToRight: Boolean): Stroke {
        val y = screenH / 2f
        val from = if (leftToRight) 0.18f else 0.82f
        val to = if (leftToRight) 0.82f else 0.18f
        return Stroke(
            startX = screenW * from,
            startY = y,
            endX = screenW * to,
            endY = y,
            durationMs = 220L
        )
    }

    /** 長按（單點，按住 duration） */
    fun longPress(x: Int, y: Int, durationMs: Long = 650L): Stroke =
        Stroke(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), durationMs)

    /** 該手勢是否為「幾乎沒有位移」（用於判定長按 vs 滑動） */
    fun isStationary(stroke: Stroke, thresholdPx: Float = 4f): Boolean {
        val dx = kotlin.math.abs(stroke.endX - stroke.startX)
        val dy = kotlin.math.abs(stroke.endY - stroke.startY)
        return dx <= thresholdPx && dy <= thresholdPx
    }
}
