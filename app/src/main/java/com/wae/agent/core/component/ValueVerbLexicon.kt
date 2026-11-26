package com.wae.agent.core.component

import com.wae.agent.core.data.AssetTable

/**
 * 調整類動詞／形容詞表（`ui_value_verbs.tsv`）。
 *
 * 來源：jieba 詞性標注（v / a）× 專案語料共現篩選，共約 2.7 萬個詞，繁簡各自獨立。
 *
 * 用途：`ValueIntent` 判斷「這句話是不是在調某個數值」時，
 * 方向詞（亮/暗/大/小…）需要搭配一個調整動詞才算數。
 * 例如「調亮」「設大」「調整音量」→ 算；「長按圖片」→ 不算。
 *
 * 這裡**不做模糊比對**：只回答「這個經過正規化後的詞，是不是表裡的動詞或形容詞」。
 */
object ValueVerbLexicon {

    private const val DB_RELATIVE_PATH = "open_source_ai_databases/ui_value_verbs.tsv"

    @Volatile
    private var verbs: Set<String> = emptySet()

    @Volatile
    private var adjectives: Set<String> = emptySet()

    private val table = AssetTable(
        DB_RELATIVE_PATH,
        listOf(
            "src/main/assets/$DB_RELATIVE_PATH",
            "app/src/main/assets/$DB_RELATIVE_PATH",
            "../app/src/main/assets/$DB_RELATIVE_PATH"
        )
    )

    fun isLoaded(): Boolean = verbs.isNotEmpty()

    fun ensureLoaded(context: android.content.Context?) {
        table.linesOrNull(context)?.let { loadFromLines(it) }
    }


    fun loadFromLines(lines: List<String>) {
        val v = HashSet<String>(30000)
        val a = HashSet<String>(4096)
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("\t")
            val word = parts[0].trim()
            if (word.isEmpty()) continue
            when (parts.getOrNull(1)?.trim()) {
                "v" -> v.add(word)
                "a" -> a.add(word)
                else -> v.add(word)
            }
        }
        verbs = v
        adjectives = a
    }

    fun resetForTest() {
        verbs = emptySet()
        adjectives = emptySet()
        table.reset()
    }

    fun isVerb(word: String): Boolean = verbs.contains(word)

    fun isAdjective(word: String): Boolean = adjectives.contains(word)

    /** 這個詞是不是表裡的動詞或形容詞。 */
    fun contains(word: String): Boolean = verbs.contains(word) || adjectives.contains(word)

    fun verbCount(): Int = verbs.size

    fun adjectiveCount(): Int = adjectives.size
}
