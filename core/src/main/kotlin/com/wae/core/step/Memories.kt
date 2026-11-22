package com.wae.core.step

/**
 * 兩份記憶（三層兩憶計畫 §0）。
 *
 * 鐵律：
 *  - 任務記憶**不存原始元素清單**（只存摘要）→ 餵大模型時不會爆 token。
 *  - 單步記憶**不存歷史**（不知道三步前發生什麼）→ 決策不會被舊情境污染。
 *
 * 這兩條不是自律，是型別上就做不到（欄位不存在）。
 */
data class TaskMemory(
    /** 使用者原句（已剝禮貌詞）。 */
    val command: String,
    /** 解析後的目的，例：「看影片」。 */
    val goalText: String = "",
    /** 選定的 App 顯示名稱（模型 A 的結果）。 */
    val appLabel: String = "",
    /** 大模型給的步驟清單。 */
    val steps: List<StepGoal> = emptyList(),
    /** 每步結果摘要（一行一個），壓縮用。 */
    val outcomes: List<String> = emptyList(),
    /** 每次重規劃 +1；單步記憶版本不符就作廢。 */
    val revision: Int = 0,
    /** 大模型已呼叫次數（預算 ≤ 2）。 */
    val bigCalls: Int = 0
) {
    fun withOutcome(line: String): TaskMemory {
        val merged = outcomes + line
        // 壓縮：超過 20 條就只留最後 10 條，並記一筆「已摘要」
        return if (merged.size > 20) {
            copy(outcomes = listOf("（前面 ${merged.size - 10} 步已摘要）") + merged.takeLast(10))
        } else {
            copy(outcomes = merged)
        }
    }

    fun nextRevision(): TaskMemory = copy(revision = revision + 1)

    fun bigCallBudgetLeft(limit: Int = 2): Int = (limit - bigCalls).coerceAtLeast(0)
}

/**
 * 單步記憶：只給小模型與點擊器。
 *
 * @param elements 當前畫面元素（呼叫端負責截斷，建議 ≤ 80）。
 * @param bans 本畫面的禁令（效果 × 手段），小模型不得提議。
 */
data class StepMemory(
    val goal: StepGoal,
    val elements: List<ElementRef>,
    val lastAction: String = "",
    val bans: List<Pair<String, String>> = emptyList(),
    /** 綁定任務版本；不符就丟棄。 */
    val revision: Int = 0
) {
    companion object {
        /** 元素上限：超過就只留「可見且可點」的前 N 個。 */
        const val MAX_ELEMENTS = 80

        fun bounded(goal: StepGoal, elements: List<ElementRef>, lastAction: String = "",
                    bans: List<Pair<String, String>> = emptyList(), revision: Int = 0): StepMemory {
            val trimmed = elements.asSequence()
                .filter { it.visible }
                .sortedByDescending { it.clickable }
                .take(MAX_ELEMENTS)
                .toList()
            return StepMemory(goal, trimmed, lastAction, bans, revision)
        }
    }
}

/** 點擊器看得見的元素（不含節點物件，純資料）。 */
data class ElementRef(
    val id: String,
    val text: String,
    val role: String = "",
    val clickable: Boolean = false,
    val visible: Boolean = true,
    /** 語意角色（例 SEARCH／SUBMIT／CART），由詞庫決定；沒有就是空字串。 */
    val semanticRole: String = ""
)
