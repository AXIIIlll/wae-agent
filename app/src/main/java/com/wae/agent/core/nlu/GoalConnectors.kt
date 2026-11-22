package com.wae.agent.core.nlu

import android.content.Context

/**
 * 目標連接詞語意（S28：條件依賴）。
 *
 * 「關閉飛航模式**再**開啟藍牙**再**返回」是**序列**指令：
 * 前一步沒做成，後面就不該硬做（否則會產生錯誤順序的操作）。
 *
 * 「打開設定**並且**開啟藍牙」則是**獨立**指令：一段失敗，另一段仍可完成。
 *
 * 資料檔：`ui_goal_connectors.tsv`
 */
object GoalConnectors {

    private const val DB_PATH = "open_source_ai_databases/ui_goal_connectors.tsv"

    private val DEFAULT_SEQUENTIAL = listOf(
        "然後再", "然後", "接著", "之後", "再來", "隨後", "再", "and then", "then"
    )

    private val DEFAULT_INDEPENDENT = listOf("並且", "並", "同時", "也")

    @Volatile
    private var sequential: List<String> = emptyList()

    @Volatile
    private var independent: List<String> = emptyList()

    @Volatile
    private var loadAttempted = false

    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        try {
            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            loadFromLines(lines)
        } catch (_: Throwable) {
            sequential = DEFAULT_SEQUENTIAL
            independent = DEFAULT_INDEPENDENT
        }
    }

    fun loadFromLines(lines: List<String>) {
        val seq = ArrayList<String>()
        val ind = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 2) continue
            val w = p[0].trim()
            if (w.isEmpty()) continue
            when (p[1].trim().uppercase()) {
                "SEQUENTIAL" -> seq.add(w)
                "INDEPENDENT" -> ind.add(w)
            }
        }
        sequential = (if (seq.isEmpty()) DEFAULT_SEQUENTIAL else seq).sortedByDescending { it.length }
        independent = (if (ind.isEmpty()) DEFAULT_INDEPENDENT else ind).sortedByDescending { it.length }
    }

    fun resetForTest() {
        sequential = emptyList()
        independent = emptyList()
        loadAttempted = false
    }

    private fun seqList(): List<String> = if (sequential.isEmpty()) DEFAULT_SEQUENTIAL else sequential
    private fun indList(): List<String> = if (independent.isEmpty()) DEFAULT_INDEPENDENT else independent

    /** 這段「前綴文字」的最後是否為序列連接詞 */
    fun endsWithSequentialConnector(prefix: String): Boolean {
        val tail = prefix.trim().takeLast(8)
        if (tail.isEmpty()) return false
        if (indList().any { tail.endsWith(it) }) return false
        return seqList().any { tail.endsWith(it) }
    }

    /** 這段「前綴文字」的最後是否為獨立連接詞 */
    fun endsWithIndependentConnector(prefix: String): Boolean {
        val tail = prefix.trim().takeLast(8)
        if (tail.isEmpty()) return false
        return indList().any { tail.endsWith(it) }
    }
}
