package com.wae.core.chat

/**
 * 對話記憶（只給「對話層」用，跟執行記憶完全分開）。
 *
 * 為什麼要分開：
 *  - 執行層需要的是事實（目的、槽位、收據、能力），不是閒聊；混進去會污染判斷。
 *  - 對話層需要的是「剛剛聊了什麼」，不是節點樹或套件名。
 *  - 兩者壽命也不同：任務結束就清 vs 關掉對話才清。
 *
 * 這個類別只放**最近幾輪對話**，不落盤、不含任何執行細節。
 */
class ConversationMemory(private val capacity: Int = 6) {

    data class Turn(val fromUser: Boolean, val text: String, val atMs: Long = System.currentTimeMillis())

    private val turns = ArrayDeque<Turn>()

    fun addUser(text: String): Turn = add(Turn(true, text.trim()))

    fun addAssistant(text: String): Turn = add(Turn(false, text.trim()))

    private fun add(turn: Turn): Turn {
        if (turn.text.isEmpty()) return turn
        turns.addLast(turn)
        while (turns.size > capacity) turns.removeFirst()
        return turn
    }

    fun recent(limit: Int = capacity): List<Turn> = turns.takeLast(limit)

    fun lastUserText(): String = turns.lastOrNull { it.fromUser }?.text.orEmpty()

    fun clear() = turns.clear()

    fun size(): Int = turns.size

    /**
     * 給模型看的對話 prompt（只有對話，沒有執行事實）。
     *
     * 明確不含：收據、步驟、套件名、能力表——那些屬於執行記憶。
     */
    fun toPrompt(): String {
        if (turns.isEmpty()) return "（無）"
        return turns.joinToString("\n") { turn ->
            (if (turn.fromUser) "使用者：" else "小助手：") + turn.text
        }
    }

    companion object {
        /** 執行側的關鍵字：出現在對話 prompt 就代表污染了。 */
        val EXECUTION_MARKERS = listOf("verified=", "steps=", "tx=", "frames=", "pkg=", "ABSTAIN reason=")
    }
}
