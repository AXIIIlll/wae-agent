package com.wae.agent.core.action

/**
 * 精準滾動規劃器（P2）——把「盲滑半屏」換成「按目標距離滾」。
 *
 * 為什麼要做：
 *  - 目前只有固定半屏手勢，目標在屏外時要反覆試，容易過衝或漏掉
 *  - 無障礙本身有 `ACTION_SCROLL_FORWARD/BACKWARD`，比合成手勢更可預測、更快
 *  - 滾動量應由「目標離可視區多遠」決定，而不是常數
 *
 * 純邏輯：輸入目標與可視區座標，輸出該滾多少、往哪滾、是否已可見。
 */
enum class ScrollDirection { DOWN, UP, NONE }

data class ScrollPlan(
    val visible: Boolean,
    val direction: ScrollDirection,
    /** 建議滾動距離（像素）；0 表示不需要滾動 */
    val distancePx: Int,
    /** 是否需要用「掃描模式」（目標尚未出現在 a11y 樹中） */
    val needsScan: Boolean
)

object ScrollPlanner {

    /** 目標距離超過一個視窗高時，單次最多只滾 80% 視窗，避免跳過 */
    private const val MAX_FRACTION = 0.8f
    /** 保留邊距，讓目標不會貼邊（貼邊常導致點擊偏移） */
    const val MARGIN_PX = 48

    /**
     * @param targetTop/targetBottom 目標在「內容座標」中的位置（可超出可視區）
     * @param viewportTop/viewportBottom 可視區（螢幕）界線
     */
    fun plan(
        targetTop: Int,
        targetBottom: Int,
        viewportTop: Int,
        viewportBottom: Int,
        targetKnown: Boolean = true
    ): ScrollPlan {
        val viewportH = (viewportBottom - viewportTop).coerceAtLeast(1)
        if (!targetKnown) {
            return ScrollPlan(
                visible = false,
                direction = ScrollDirection.DOWN,
                distancePx = (viewportH * 0.6f).toInt(),
                needsScan = true
            )
        }
        val fullyVisible = targetTop >= viewportTop + MARGIN_PX &&
            targetBottom <= viewportBottom - MARGIN_PX
        if (fullyVisible) {
            return ScrollPlan(visible = true, ScrollDirection.NONE, 0, needsScan = false)
        }
        // 目標在下方 → 內容往上走（scroll down）
        if (targetBottom > viewportBottom - MARGIN_PX) {
            val delta = targetBottom - (viewportBottom - MARGIN_PX)
            val distance = delta.coerceAtMost((viewportH * MAX_FRACTION).toInt())
            return ScrollPlan(false, ScrollDirection.DOWN, distance, needsScan = false)
        }
        // 目標在上方 → scroll up
        val delta = (viewportTop + MARGIN_PX) - targetTop
        val distance = delta.coerceAtMost((viewportH * MAX_FRACTION).toInt())
        return ScrollPlan(false, ScrollDirection.UP, distance, needsScan = false)
    }

    /** 掃描模式還允許滾幾次（避免無限滾動） */
    fun remainingScans(attemptsUsed: Int, maxAttempts: Int = 5): Int =
        (maxAttempts - attemptsUsed).coerceAtLeast(0)

    /** 是否已見底（連續兩次滾動後目標仍不在，且頁面簽名不變） */
    fun reachedBottom(spineUnchangedTwice: Boolean, targetStillMissing: Boolean): Boolean =
        spineUnchangedTwice && targetStillMissing
}
