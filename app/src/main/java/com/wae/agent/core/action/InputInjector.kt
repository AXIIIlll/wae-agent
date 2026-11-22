package com.wae.agent.core.action

/**
 * 輸入寫入降級鏈（P5）。
 *
 * 問題：只靠 ACTION_SET_TEXT，Flutter/Compose 的自繪輸入框常常寫不進去。
 * 解法：四級降級，命中即停——
 *   1. ACTION_SET_TEXT（最快、最可靠）
 *   2. 聚焦後再 ACTION_SET_TEXT（焦點未就緒時常見）
 *   3. 剪貼簿 + 長按貼上（自繪輸入框唯一可行路徑）
 *   4. 逐字注入（最後手段，慢但幾乎一定有效）
 *
 * 本類別只做「策略選擇與結果判定」，實際 UI 操作由服務層執行。
 */
enum class InputStrategy { SET_TEXT, FOCUS_THEN_SET, CLIPBOARD_PASTE, CHAR_INJECTION }

data class InputAttempt(
    val strategy: InputStrategy,
    val succeeded: Boolean,
    val elapsedMs: Long = 0
)

object InputInjector {

    /** 依失敗歷史決定下一次要用的策略（純邏輯） */
    fun nextStrategy(tried: List<InputAttempt>): InputStrategy? {
        val used = tried.map { it.strategy }.toSet()
        if (tried.isEmpty()) return InputStrategy.SET_TEXT
        return InputStrategy.values().firstOrNull { it !in used }
    }

    /** 是否全部策略都失敗 */
    fun exhausted(tried: List<InputAttempt>): Boolean = nextStrategy(tried) == null

    /** 摘要（供日誌與遥測） */
    fun summarize(tried: List<InputAttempt>): String =
        tried.joinToString(",") { "${it.strategy}=${if (it.succeeded) "OK" else "FAIL"}" }
}
