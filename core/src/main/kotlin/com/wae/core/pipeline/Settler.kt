package com.wae.core.pipeline

import com.wae.core.config.Config
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.model.Frame

/**
 * 等畫面穩定（C8 的第一次落地）。
 *
 * 為什麼需要：動作送出後，畫面**不是立刻**變的。第一幀通常是舊畫面，
 * 拿它驗證會得到「動作成功、但沒變化」——然後被誤記成靜默失敗、甚至封禁手段。
 * 這個 bug 在模擬器上實際發生過。
 *
 * 這裡做兩件事：
 *  1. **等它變**（原本就有）：動作送出後，第一幀通常還是舊畫面。
 *  2. **等它安靜**（本輪新增）：慢速載入頁會「先變一半」——骨架屏、半張列表。
 *     只看「變了」就在骨架屏上找元素，會得到「找不到」的假失敗。
 *     所以變了之後再等兩次同樣的指紋，才算載入完成。
 *
 * 卡死仍由 `TimingSignals` 判斷；這裡只保證「不要在還沒載完時動手」。
 */
class Settler(
    private val config: Config,
    private val sleeper: (Long) -> Unit = {}
) {

    fun awaitChange(previous: Frame, timeoutOverrideMs: Long? = null, capture: () -> Frame): Frame {
        val timeoutMs = timeoutOverrideMs ?: config.long(Key.SETTLE_TIMEOUT_MS, Default.SETTLE_TIMEOUT_MS)
        val intervalMs = config.long(Key.SETTLE_INTERVAL_MS, Default.SETTLE_INTERVAL_MS)
        if (timeoutMs <= 0L) return capture()

        var waited = 0L
        var current = capture()
        while (!changed(previous, current) && waited < timeoutMs) {
            sleeper(intervalMs)
            waited += intervalMs
            current = capture()
        }

        // 已經變了，但可能只是「變一半」；等同樣的指紋連續出現兩次再回傳。
        val stableMs = config.long(Key.SETTLE_STABLE_MS, Default.SETTLE_STABLE_MS)
        if (stableMs > 0L && changed(previous, current)) {
            var quietWaited = 0L
            var last = current
            while (quietWaited < stableMs) {
                sleeper(intervalMs)
                quietWaited += intervalMs
                val next = capture()
                if (next.fp == last.fp) return next
                last = next
            }
            return last
        }
        return current
    }

    private fun changed(previous: Frame, current: Frame): Boolean =
        current.fp != previous.fp || current.pkg != previous.pkg
}
