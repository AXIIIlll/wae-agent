package com.wae.agent.core.action

import com.wae.agent.core.twin.TwinStep

/**
 * 學習式等待（P4）——等待時間不再固定，而是「按控件學習」。
 *
 * 資料來源：Twin 已記錄的每步耗時 `(pkg, 選擇器, 動作) → ms`。
 * 推論：取該鍵的 P95 作為等待上限（夾在 [minMs, maxMs]），
 *       樣本不足（<3）時回退預設值。
 *
 * 與既有事件驅動等待搭配：
 *   上限 = P95（該控件），但畫面一穩定就提前返回（SettleWaiter）。
 *   如此「快控件不白等、慢控件不早退」。
 */
object TimingModel {

    const val DEFAULT_WAIT_MS = 1_200L
    const val MIN_WAIT_MS = 400L
    const val MAX_WAIT_MS = 2_000L
    private const val MIN_SAMPLES = 3

    private val samples = HashMap<String, MutableList<Long>>()

    fun keyOf(pkg: String, selector: String, action: String): String = "$pkg|$selector|$action"

    @Synchronized
    fun reset() {
        samples.clear()
    }

    @Synchronized
    fun record(key: String, latencyMs: Long) {
        if (latencyMs <= 0) return
        samples.getOrPut(key) { ArrayList() }.add(latencyMs)
        val list = samples[key]!!
        if (list.size > 40) list.removeAt(0)
    }

    /** 從 Twin 歷史軌跡播種（只在任務開始做一次，成本 O(n)） */
    @Synchronized
    fun seedFrom(steps: List<TwinStep>) {
        for (s in steps) {
            if (!s.ok || s.ms <= 0) continue
            val selector = when {
                s.viewId.isNotEmpty() -> "id:" + s.viewId.substringAfterLast('/')
                s.roleTag.isNotEmpty() && s.target.isNotEmpty() -> "role:${s.roleTag}:${s.target.take(16)}"
                s.target.isNotEmpty() -> "text:" + s.target.take(16)
                else -> continue
            }
            record(keyOf(s.pkg, selector, s.action), s.ms)
        }
    }

    /** 建議等待上限（毫秒） */
    @Synchronized
    fun waitFor(
        pkg: String,
        selector: String,
        action: String,
        defaultMs: Long = DEFAULT_WAIT_MS
    ): Long {
        val list = samples[keyOf(pkg, selector, action)] ?: return defaultMs
        if (list.size < MIN_SAMPLES) return defaultMs
        return percentile(list, 0.95).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
    }

    @Synchronized
    fun sampleCount(pkg: String, selector: String, action: String): Int =
        samples[keyOf(pkg, selector, action)]?.size ?: 0

    /** 線性插值百分位（可測） */
    fun percentile(values: List<Long>, q: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val pos = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
        val lo = pos.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = pos - lo
        return (sorted[lo] + (sorted[hi] - sorted[lo]) * frac).toLong()
    }
}
