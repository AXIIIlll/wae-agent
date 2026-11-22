package com.wae.core.learn

import com.wae.core.model.Frame

/** 畫面現在是什麼狀態（C8）：在動、停了、卡死。 */
enum class ScreenPace {
    /** 畫面已經穩定（可以驗證） */
    SETTLED,

    /** 還在變（轉圈、動畫、載入中）：應該等，不該重試 */
    STILL_CHANGING,

    /** 等很久都沒變：卡死（該退或換路，不該再等） */
    STUCK
}

/**
 * 時間推理（C8）。
 *
 * 為什麼需要：現在的 agent 分不清「載入中」與「壞了」——
 * 兩者都是「畫面沒變」，但處理方式相反：一個要等、一個要退。
 *
 * 只用兩個訊號就能分：**前後幀是否相同** 與 **已經等了多久**。
 */
object TimingSignals {

    fun classify(
        previous: Frame,
        current: Frame,
        elapsedMs: Long,
        settleMs: Long = DEFAULT_SETTLE_MS
    ): ScreenPace {
        val stillSame = previous.fp == current.fp
        if (!stillSame) return ScreenPace.SETTLED
        return if (elapsedMs < settleMs) ScreenPace.STILL_CHANGING else ScreenPace.STUCK
    }

    /** 粗略的「這一幀有沒有進展」：元素數變多或指紋變了都算。 */
    fun hasProgress(previous: Frame, current: Frame): Boolean =
        previous.fp != current.fp || current.controls.size > previous.controls.size

    const val DEFAULT_SETTLE_MS = 1_200L
}
