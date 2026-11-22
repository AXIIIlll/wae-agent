package com.wae.core.step

/**
 * 單步決策（小模型／規則）。
 *
 * 第一版刻意**不是神經分類器**：先用規則（角色、關鍵字、可點性）＋可選的相似度評分。
 * 這樣即使沒有模型也能跑（lite 版），模型只是把排序變好。
 *
 * 輸出**不含座標**——定位交給 ClickResolver。
 */
object StepPolicy {

    /** 相似度評分（0..1）。可由 App 層注入 embedding；沒有就回 0。 */
    fun interface Scorer {
        fun score(goalHint: String, element: ElementRef): Float
    }

    private val SEARCH_WORDS = listOf("搜尋", "搜索", "查詢", "找", "search")
    private val SUBMIT_WORDS = listOf("搜尋", "搜索", "go", "送出", "確認", "開始")
    private val SCROLL_WORDS = listOf("更多", "下一", "捲", "滑", "載入")

    fun decide(memory: StepMemory, scorer: Scorer = Scorer { _, _ -> 0f }, threshold: Float = 0.35f): StepDecision {
        val goal = memory.goal
        val decision = when (goal.kind) {
            StepKind.QUERY_FIELD -> decisionForQueryField(memory, scorer)
            StepKind.SET_TEXT -> StepDecision(StepKind.SET_TEXT, goal.hint, 1f)
            StepKind.SUBMIT -> decisionForSubmit(memory, scorer)
            StepKind.LIST_ITEM, StepKind.OPEN_ITEM -> decisionForItem(memory, scorer)
            StepKind.SCROLL -> StepDecision(StepKind.SCROLL, goal.hint.ifBlank { "down" }, 0.8f)
            StepKind.BACK -> StepDecision(StepKind.BACK, "", 0.9f)
            StepKind.DONE -> StepDecision(StepKind.DONE, goal.hint, 1f)
            StepKind.ABORT -> StepDecision(StepKind.ABORT, goal.hint, 1f)
        }
        // 低信心 → 不假裝有把握（呼叫端會走升階）
        return if (decision.confidence < threshold && decision.kind != StepKind.DONE) {
            decision.copy(confidence = decision.confidence)
        } else {
            decision
        }
    }

    private fun decisionForQueryField(memory: StepMemory, scorer: Scorer): StepDecision {
        // 最優先：詞庫認得的「搜尋」角色
        val byRole = memory.elements.firstOrNull { it.semanticRole == "SEARCH" && it.clickable }
        if (byRole != null) return StepDecision(StepKind.QUERY_FIELD, byRole.text, 0.95f)
        // 其次：本身就是輸入框的元素
        val editable = memory.elements.filter { it.clickable && (it.role.contains("Edit", true) || it.role.contains("輸入", true)) }
        if (editable.isNotEmpty()) {
            return StepDecision(StepKind.QUERY_FIELD, memory.goal.hint, 0.9f)
        }
        // 其次：文字含搜尋字樣
        val byText = memory.elements.filter { el -> SEARCH_WORDS.any { el.text.contains(it) } }
        if (byText.isNotEmpty()) return StepDecision(StepKind.QUERY_FIELD, byText.first().text, 0.75f)
        // 再其次：相似度
        val best = bestByScore(memory, scorer)
        if (best != null) return StepDecision(StepKind.QUERY_FIELD, best.text, 0.55f)
        // 找不到 → 滑動找，找不到就放棄
        return StepDecision(StepKind.SCROLL, "down", 0.4f)
    }

    private fun decisionForSubmit(memory: StepMemory, scorer: Scorer): StepDecision {
        // 最優先：詞庫認得的「送出」角色
        val byRole = memory.elements.firstOrNull { it.semanticRole in setOf("SUBMIT", "SEND") && it.clickable }
        if (byRole != null) return StepDecision(StepKind.SUBMIT, byRole.text, 0.9f)
        val byText = memory.elements.filter { el -> SUBMIT_WORDS.any { el.text.contains(it, true) } && el.clickable }
        if (byText.isNotEmpty()) return StepDecision(StepKind.SUBMIT, byText.first().text, 0.8f)
        val best = bestByScore(memory, scorer)
        return if (best != null) StepDecision(StepKind.SUBMIT, best.text, 0.5f)
        else StepDecision(StepKind.SUBMIT, "", 0.3f)
    }

    private fun decisionForItem(memory: StepMemory, scorer: Scorer): StepDecision {
        val wanted = memory.goal.hint
        // 「第一支／第一個」這種明確條件
        if (wanted.contains("第一") || wanted.contains("首")) {
            val first = memory.elements.firstOrNull { it.clickable }
            if (first != null) return StepDecision(memory.goal.kind, first.text, 0.7f)
        }
        if (wanted.isNotEmpty()) {
            val contains = memory.elements.filter { it.clickable && it.text.contains(wanted) }
            if (contains.isNotEmpty()) return StepDecision(memory.goal.kind, contains.first().text, 0.85f)
        }
        val best = bestByScore(memory, scorer)
        return if (best != null) StepDecision(memory.goal.kind, best.text, 0.6f)
        else StepDecision(StepKind.SCROLL, "down", 0.35f)
    }

    private fun bestByScore(memory: StepMemory, scorer: Scorer): ElementRef? {
        val clickable = memory.elements.filter { it.clickable }
        if (clickable.isEmpty()) return null
        return clickable.maxByOrNull { scorer.score(memory.goal.hint, it) }
            ?.takeIf { scorer.score(memory.goal.hint, it) > 0f }
    }

    /** 把決策套上契約：不合就回 null（不准行動）。 */
    fun decideValidated(memory: StepMemory, scorer: Scorer = Scorer { _, _ -> 0f }): StepDecision? =
        StepContract.validate(memory.goal, decide(memory, scorer))
}
