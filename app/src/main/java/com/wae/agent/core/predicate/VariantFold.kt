package com.wae.agent.core.predicate

import android.content.Context

/**
 * 異體字折疊（`ui_text_variants.tsv`，語言層級、通用）。
 *
 * 為什麼要這一層：「歷史記錄」與「歷史紀錄」在字面上不相等也不互相包含，
 * 分數會是 0，於是明明在畫面上卻找不到。折疊之後兩邊都變成同一個寫法。
 *
 * 只做「單字 → 標準字」，不做同義詞膨脹（那是 `ui_language_words.tsv` 的工作）。
 */
object VariantFold {

    @Volatile
    private var map: Map<Char, Char>? = null

    private fun mapOf(context: Context): Map<Char, Char> {
        map?.let { return it }
        val lines = com.wae.core.util.Degrade.orEmpty("variant:load") {
            context.assets.open("open_source_ai_databases/ui_text_variants.tsv")
                .bufferedReader(Charsets.UTF_8).readLines()
        }
        val out = HashMap<Char, Char>(64)
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cells = line.split('\t')
            if (cells.size < 2) continue
            val from = cells[0].trim()
            val to = cells[1].trim()
            if (from.length == 1 && to.length == 1) out[from[0]] = to[0]
        }
        map = out
        return out
    }

    /** 把異體字換成標準字；沒有對應就原樣保留。 */
    fun fold(context: Context, text: String): String {
        if (text.isEmpty()) return text
        val m = mapOf(context)
        if (m.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(m[ch] ?: ch)
        return sb.toString()
    }
}
