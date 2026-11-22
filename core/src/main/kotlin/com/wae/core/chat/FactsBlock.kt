package com.wae.core.chat

/**
 * 裝置事實區塊（給規劃 prompt 用）。
 *
 * 為什麼放在 core：它是純字串組裝，可以在 JVM 上測試「有沒有注入」與
 * 「有沒有把對話內容混進來」——這兩件事一旦錯，模型就會開始亂推論。
 */
object FactsBlock {

    const val HEADER = "裝置事實（這支手機現在有什麼）"

    /**
     * 把事實摘要包成 prompt 區塊；空白就回空字串（lite 版沒有模型也不受影響）。
     *
     * @param facts 由 `RuleEngineHost.factsText()` 產生的純事實（App 數、分類、權限、能力表）。
     */
    fun wrap(facts: String): String {
        val trimmed = facts.trim()
        if (trimmed.isEmpty()) return ""
        val cleaned = trimmed.lines()
            .filter { it.isNotBlank() }
            .filter { line -> ConversationMemory.EXECUTION_MARKERS.none { line.contains(it) } }
            .joinToString("\n") { it.trim().take(160) }
        if (cleaned.isEmpty()) return ""
        return "\n\n" + HEADER + "：\n" + cleaned
    }
}
