package com.wae.core.time

/**
 * 時間來源（I5：可離線重放）。
 *
 * 程式裡**不准**直接呼叫 `System.currentTimeMillis()`；一律注入 `Clock`，
 * 否則同一份輸入在不同時間會得到不同決策，離線重放就沒有意義。
 */
interface Clock {
    fun nowMs(): Long
}

/** 測試用：可控時鐘。 */
class FixedClock(private var current: Long = 0L) : Clock {

    override fun nowMs(): Long = current

    fun advance(ms: Long) {
        require(ms >= 0) { "FixedClock.advance 不可為負" }
        current += ms
    }
}
