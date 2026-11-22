package com.wae.agent.core

/**
 * 感知幀重用策略。
 *
 * 為什麼需要：Agent 在「決策被拒絕 → 升級 → 重試」與「多步導覽驗證」時，
 * 常常對**完全沒變的畫面**重複做完整感知（採集 200ms + 打分 90ms）。
 * 只要「同一個 App、同一個視窗、同一份結構指紋、且期間沒有任何內容變化事件」，
 * 就可以安全地重用上一幀的候選集——這是最便宜的效率提升。
 *
 * 為什麼限制 1.5 秒：內容變化事件只保留最近 2 秒，
 * 超過這個窗口就無法確認「真的沒變」，因此寧可重採集。
 *
 * 純函式 → 可在 JVM 測試；真正的快取由服務持有。
 */
object FrameReusePolicy {

    /** 重用上限（毫秒）；必須小於事件保留窗口（2000ms） */
    const val MAX_AGE_MS = 1500L

    fun canReuse(
        samePackage: Boolean,
        sameWindow: Boolean,
        sameAmbient: Boolean,
        msSince: Long,
        contentEventsSince: Int,
        maxAgeMs: Long = MAX_AGE_MS
    ): Boolean {
        if (!samePackage || !sameWindow || !sameAmbient) return false
        if (msSince < 0 || msSince > maxAgeMs) return false
        // 有任何內容變化事件 → 畫面可能已經不同，必須重採集
        if (contentEventsSince > 0) return false
        return true
    }
}
