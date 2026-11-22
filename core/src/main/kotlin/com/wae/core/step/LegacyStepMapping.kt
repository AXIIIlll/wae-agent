package com.wae.core.step

/**
 * 把「舊式一步一句」的計畫（`doText` / `target`）翻成 `StepGoal` 清單。
 *
 * 為什麼需要這一層：大模型（Qwen3-0.6B）現在的輸出格式是「一句話一個步驟」，
 * 但新的單步政策需要**封閉詞彙**（QUERY_FIELD / SET_TEXT / …）。
 * 這個轉換是純函式，可以單獨測，不必碰模型。
 */
object LegacyStepMapping {

    private val SEARCH_MARKERS = listOf("搜尋", "搜索", "查詢", "查找")
    private val TAP_MARKERS = listOf("點", "按", "選", "開", "進入", "前往")
    private val SCROLL_MARKERS = listOf("滑", "捲", "往下", "往上", "更多")

    /**
     * @param doText 這一步要做什麼（人話）
     * @param target 目標描述（可空）
     */
    fun toGoals(doText: String, target: String? = null): List<StepGoal> {
        val text = doText.trim()
        if (text.isEmpty()) return emptyList()
        val targetText = target?.trim().orEmpty()

        // 搜尋：拆成「找框 → 寫字 → 送出 → 完成」
        SEARCH_MARKERS.firstOrNull { text.contains(it) }?.let { marker ->
            val term = targetText.ifEmpty {
                text.substringAfter(marker, "").trim()
            }
            if (term.isNotEmpty()) {
                return listOf(
                    StepGoal(StepKind.QUERY_FIELD, "搜尋框"),
                    StepGoal(StepKind.SET_TEXT, term),
                    StepGoal(StepKind.SUBMIT, "搜尋"),
                    StepGoal(StepKind.DONE, "已搜尋「$term」")
                )
            }
        }

        SCROLL_MARKERS.firstOrNull { text.contains(it) }?.let {
            return listOf(StepGoal(StepKind.SCROLL, directionOf(text)), StepGoal(StepKind.DONE, text))
        }

        TAP_MARKERS.firstOrNull { text.contains(it) }?.let {
            return listOf(StepGoal(StepKind.LIST_ITEM, targetText.ifEmpty { text }), StepGoal(StepKind.DONE, text))
        }

        // 認不出來：當成「完成這一步」（不要硬塞動作型別）
        return listOf(StepGoal(StepKind.DONE, text))
    }

    /** 多步計畫一次翻完（保留順序、跳過空的）。 */
    fun toPlan(steps: List<Pair<String, String?>>): List<StepGoal> =
        steps.flatMap { (doText, target) -> toGoals(doText, target) }

    private fun directionOf(text: String): String = when {
        text.contains("上") -> "up"
        else -> "down"
    }
}
