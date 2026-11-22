package com.wae.core.pipeline

import com.wae.core.config.Config
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.exec.PostConditions
import com.wae.core.model.Effect
import com.wae.core.model.Frame
import com.wae.core.model.GestureKind
import com.wae.core.model.Means
import com.wae.core.model.Op
import com.wae.core.model.Point
import com.wae.core.model.Risk
import com.wae.core.model.Step

/**
 * 廣度的來源：**會找、會退**，不是多背幾個動詞（§4.7）。
 *
 * 兩個結構性程序：
 *  - `Seeker`：目標不在這一屏 → 捲動再看（有上限），而不是直接說不會。
 *  - `Retreater`：走到錯的畫面 → 退回上一層再重規劃。
 *
 * 這兩件事對「任意 App」都成立，因為它們只依賴兩個客觀事實：
 * 「有東西可以捲」與「有 BACK 可以退」。
 */
class Seeker(private val config: Config) {

    fun canSeek(frame: Frame, used: Int): Boolean {
        if (used >= maxScrolls()) return false
        return frame.controls.any { it.scrollable }
    }

    /** 捲動一步；優先選「涵蓋畫面中央」的容器，其次才是面積最大的。 */
    fun scrollStep(frame: Frame): Step? {
        val scrollables = frame.controls.filter { it.scrollable }
        if (scrollables.isEmpty()) return null

        val screenW = frame.controls.maxOf { it.bounds.right }.coerceAtLeast(1)
        val screenH = frame.controls.maxOf { it.bounds.bottom }.coerceAtLeast(1)
        val centerX = screenW / 2
        val centerY = screenH / 2

        val container = scrollables.firstOrNull { it.bounds.contains(centerX, centerY) }
            ?: scrollables.maxByOrNull { it.bounds.area }
            ?: return null

        // 負座標會讓手勢 API 直接丟例外（實測踩過），這裡先擋掉
        if (container.bounds.centerX() < 0 || container.bounds.centerY() < 0) return null
        val at = Point(container.bounds.centerX(), container.bounds.centerY())
        return Step(
            effect = Effect(target = EFFECT_SCROLL, op = Op.NAVIGATE),
            means = Means.Gesture(gesture = GestureKind.SWIPE_UP, at = at),
            post = PostConditions.frameChanged(frame.fp),
            risk = Risk.LOW,
            idempotencyKey = "seek#${frame.fp}#$SCROLL_KEY_SUFFIX"
        )
    }

    private fun maxScrolls(): Int = config.int(Key.SEEK_MAX_SCROLLS, Default.MAX_SCROLLS)

    companion object {
        const val EFFECT_SCROLL = "page:scroll_down"
        private const val SCROLL_KEY_SUFFIX = "down"
    }
}

/** 走錯畫面時的退路：按一次 BACK 再重規劃。 */
class Retreater(private val config: Config) {

    fun canRetreat(used: Int): Boolean = used < maxBacks()

    fun backStep(afterFp: String): Step = Step(
        effect = Effect(target = EFFECT_BACK, op = Op.NAVIGATE),
        means = Means.SystemApi(api = API_BACK),
        post = PostConditions.frameChanged(afterFp),
        risk = Risk.LOW,
        idempotencyKey = "recover#back#$afterFp"
    )

    private fun maxBacks(): Int = config.int(Key.RECOVER_MAX_BACKS, Default.MAX_BACKS)

    companion object {
        const val EFFECT_BACK = "page:back"
        const val API_BACK = "global_action:BACK"
    }
}
