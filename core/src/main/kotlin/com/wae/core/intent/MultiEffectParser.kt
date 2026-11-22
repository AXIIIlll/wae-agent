package com.wae.core.intent

import com.wae.core.model.Effect
import com.wae.core.model.Op

/**
 * 多效果組合（B7 的第一片）：一句話裡有**兩個效果、而且有先後**。
 *
 * 例：「在設定裡搜尋藍牙」＝ 先開設定（app:設定）→ 再搜尋（query:藍牙）。
 *
 * 為什麼不直接丟給 Planner：Planner 只看得到**當前畫面**；還沒進設定時，
 * 搜尋一定找不到欄位。所以這裡把「先開 App、再操作」拆成兩個效果，
 * 由 Agent 的「效果層驗證＋重規劃」自然地把第二個效果留到下一輪做。
 *
 * 這是資料驅動的雛形：新增句型＝加一條 pattern，不改流程。
 */
object MultiEffectParser {

    private data class Rule(val regex: Regex)

    private val RULES = listOf(
        // 在 X 裡搜尋 Y / 在 X 搜尋 Y
        Rule(Regex("^在(.+?)(?:裡|中)?搜尋(.+)$")),
        // 打開 X 然後搜尋 Y
        Rule(Regex("^打開(.+?)(?:然後|後|再)搜尋(.+)$"))
    )

    fun parse(text: String): List<Effect>? {
        val input = text.trim()
        for (rule in RULES) {
            val m = rule.regex.find(input) ?: continue
            val app = m.groupValues[1].trim().trimEnd('裡', '中')
            val query = m.groupValues[2].trim()
            if (app.isEmpty() || query.isEmpty()) continue
            return listOf(
                Effect("app:" + app, Op.NAVIGATE),
                Effect("query:" + query, Op.SELECT)
            )
        }
        return null
    }
}
