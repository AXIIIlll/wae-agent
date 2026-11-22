package com.wae.agent.core.runtime

/**
 * 事件驅動的「等待畫面穩定」（M3c）。
 *
 * 背景：原本每次動作後都固定 sleep(600~1500ms)，不管畫面其實早就穩定了。
 * 實測每步耗時的大頭就是這些固定等待（1500ms 前台等待、600ms 因果等待、650ms 滾動等待）。
 *
 * 策略：
 *  1. 先等一個最短時間（讓動作有機會產生事件）
 *  2. 之後每 [pollMs] 檢查一次「最近 quietMs 內有沒有內容變化事件」
 *  3. 沒有 → 視為穩定，立即返回
 *  4. 直到 [timeoutMs] 才放棄（保底，行為與舊版一致）
 *
 * 純邏輯、可注入時鐘，能在 JVM 測試。
 */
object SettleWaiter {

    /**
     * @param isQuiet 回報「最近 quietMs 內沒有新事件」
     * @return 實際等待毫秒數
     */
    fun waitUntilQuiet(
        now: () -> Long,
        sleep: (Long) -> Unit,
        isQuiet: () -> Boolean,
        timeoutMs: Long,
        minWaitMs: Long = 120L,
        pollMs: Long = 50L
    ): Long {
        val start = now()
        if (minWaitMs > 0) sleep(minWaitMs)
        var elapsed = now() - start
        while (elapsed < timeoutMs) {
            if (isQuiet()) return now() - start
            sleep(pollMs)
            elapsed = now() - start
        }
        return now() - start
    }
}
