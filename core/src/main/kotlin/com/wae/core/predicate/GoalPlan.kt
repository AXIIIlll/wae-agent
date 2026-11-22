package com.wae.core.predicate

/**
 * **目標計畫**：把「一句話裡的多個動作」變成有順序的步驟（缺口 1）。
 *
 * 以前的 Φ 只能表達一件事：`achieve(Φ)`。
 * 但真實指令常常是序列：「開 WiFi，然後開藍牙」「先關飛航再開熱點」。
 *
 * 這裡刻意保持最小：
 *  - 只有「依序達成」一種模態（`achieve`）
 *  - 條件式（「如果沒開才開」）由**述詞本身**表達（開關述詞是幂等的：已經是開的 → eval 成立 → 直接跳過）
 *
 * 為什麼不做完整 PDDL：`maintain` / `until` 需要背景監控，是另一個題目；
 * 先把「一句話多個目標」做對，比先做時序邏輯更有價值。
 */
data class GoalPlan(
    /** 依序要達成的子目標。 */
    val steps: List<Step>
) {

    data class Step(
        /** 這一步的目標。 */
        val goal: Phi,
        /** 這一步的原始子句（給 log 與反問用）。 */
        val text: String
    )

    init {
        require(steps.isNotEmpty()) { "GoalPlan 至少要一步" }
    }

    /** 從 [from] 開始，第一個「還沒成立」的步驟索引；全部成立回 null。 */
    fun nextPending(world: PredicateWorld, from: Int = 0): Int? {
        for (i in from until steps.size) {
            if (!PredicateEval.eval(steps[i].goal, world)) return i
        }
        return null
    }

    /** 全部達成？ */
    fun isSatisfied(world: PredicateWorld): Boolean = nextPending(world, 0) == null

    companion object {
        /** 只有一步的計畫（等於單一目標）。 */
        fun single(goal: Phi, text: String): GoalPlan = GoalPlan(listOf(Step(goal, text)))
    }
}

/**
 * 把一句話切成多個子句（序列指令）。
 *
 * 只切「明確的連接詞」，避免把「開 WiFi 和藍牙」（同一個動作的兩個目標）誤切。
 * 目前支援：然後、接著、再、之後、，然後。
 */
object ClauseSplitter {

    fun split(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        var parts = listOf(t)
        // 連接詞全部來自資料（`ui_language_words.tsv` 的 SEPARATOR）
        for (sep in LanguageWords.separators()) {
            parts = parts.flatMap { p ->
                if (p.length > sep.length + 2 && p.contains(sep)) {
                    p.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(p)
                }
            }
        }
        // 去掉每個子句開頭的語助詞（LEAD，也是資料）
        val merged = ArrayList<String>()
        for (p in parts) {
            var cleaned = p.trim()
            for (lead in LanguageWords.leads()) {
                if (cleaned.startsWith(lead) && cleaned.length > lead.length) {
                    cleaned = cleaned.removePrefix(lead).trim()
                }
            }
            if (cleaned.length < 2 && merged.isNotEmpty()) merged[merged.size - 1] = merged.last() + cleaned
            else if (cleaned.isNotEmpty()) merged.add(cleaned)
        }
        return merged
    }
}
