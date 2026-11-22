package com.wae.agent.core.automaton

/**
 * 編譯去抖：什麼時候值得把新軌跡併進狀態圖。
 *
 * 為什麼需要：每個任務結束都編譯＋寫檔，會在長時間使用下造成不必要的
 * flash 寫入與電量消耗；但間隔太久，圖又會落後於實際使用。
 *
 * 純函式、無狀態 → 可在 JVM 測試，也讓「何時編譯」變成可審計的規則。
 */
object AutomatonCompilePolicy {

    /** 累積多少新步數才值得重編 */
    const val MIN_NEW_STEPS = 12

    /** 距離上次編譯多久（毫秒）就無條件重編，避免長期不更新 */
    const val MIN_INTERVAL_MS = 5 * 60 * 1000L

    fun shouldCompile(
        newSteps: Int,
        nowMs: Long,
        lastCompileMs: Long,
        hasExistingGraph: Boolean
    ): Boolean {
        // 第一次（沒有圖）→ 立刻編，讓新 App 馬上受益
        if (!hasExistingGraph) return true
        // 累積足夠新樣本 → 編
        if (newSteps >= MIN_NEW_STEPS) return true
        // 太久沒更新（即使樣本少）→ 編
        if (lastCompileMs <= 0L) return true
        return nowMs - lastCompileMs >= MIN_INTERVAL_MS
    }
}
