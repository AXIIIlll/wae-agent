package com.wae.agent.core.action

/**
 * 同一任務內的重試去重（修「同樣的目標做三次」）。
 *
 * 動機：搜尋無結果時，恢復流程會反覆對同一個目標重試，
 * 造成「把同一段文字查了三次」這類無效動作。
 *
 * 規則：同一 (動作類型 + 參數) 在一個任務內只執行一次；
 * 第二次以後直接跳過並讓呼叫端回報失敗，而不是再花一次配額。
 */
object GoalAttemptGuard {

    private val attempted = HashSet<String>()

    fun keyOf(action: String, payload: String): String = "$action|$payload"

    @Synchronized
    fun reset() {
        attempted.clear()
    }

    /** 回傳 true 表示應該跳過（已經試過） */
    @Synchronized
    fun shouldSkip(action: String, payload: String): Boolean {
        val key = keyOf(action, payload)
        return !attempted.add(key)
    }

    @Synchronized
    fun size(): Int = attempted.size
}