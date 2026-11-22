package com.wae.core.intent

/**
 * 條件句的「表層」處理（C8 的最小版）。
 *
 * 「如果開著就關掉 Wi-Fi」跟「關掉 Wi-Fi」對我們是同一件事——
 * 因為**執行前本來就會先看畫面狀態**（開關已在目標狀態就直接算完成，不會亂點）。
 *
 * 這不是完整的條件分支（「如果有貨就下單」還做不到），
 * 而是把「如果…就…」這層語法剝掉，讓後面的解析照常工作。
 *
 * 刻意只剝「條件子句」，不動後面的動作與對象——
 * 因為條件判斷要靠執行期的畫面，不是靠字串。
 */
object Conditional {

    private val CONDITIONAL = listOf(
        Regex("^如果[^就]*就(.+)$"),
        Regex("^若是[^就]*就(.+)$"),
        Regex("^假如[^就]*就(.+)$"),
        Regex("^要是[^就]*就(.+)$")
    )

    /** 剝掉條件子句；不是條件句就原樣回傳。 */
    fun strip(text: String): String {
        val t = text.trim()
        for (pattern in CONDITIONAL) {
            val m = pattern.find(t) ?: continue
            val action = m.groupValues[1].trim()
            if (action.isNotEmpty()) return action
        }
        return t
    }

    /** 這句話是不是條件句（給 log／統計用）。 */
    fun isConditional(text: String): Boolean = CONDITIONAL.any { it.containsMatchIn(text.trim()) }
}
