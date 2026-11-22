package com.wae.agent.gen5

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 簡體 → 繁體（給「打字的人」用的）。
 *
 * 為什麼需要：語音那邊已經有 `ui_asr_trad_map.tsv`，但**用打字的簡體使用者沒有**。
 * 實測「打開设置」「打开日历」都會失敗（一個 no_candidate、一個 unknown_verb）——
 * 詞表是繁體，簡體進來就對不上。
 *
 * 設計：
 *  - 直接共用同一張表（資料驅動；加一行就多一個詞，不必改程式）
 *  - 只做「詞 → 詞」的字串替換，不做全文轉換（避免把使用者的內容改掉）
 *  - **長詞優先**（先換「设置」再換「設」）——否則會把詞換壞
 */
object SimplifiedToTraditional {

    private const val ASSET = "open_source_ai_databases/ui_asr_trad_map.tsv"

    @Volatile
    private var table: List<Pair<String, String>>? = null

    fun normalize(context: Context, text: String): String {
        val entries = tableOf(context)
        if (entries.isEmpty()) return text
        var out = text
        for ((from, to) in entries) {
            if (out.contains(from)) out = out.replace(from, to)
        }
        return out
    }

    private fun tableOf(context: Context): List<Pair<String, String>> {
        table?.let { return it }
        val loaded = runCatching {
            val rows = ArrayList<Pair<String, String>>()
            context.assets.open(ASSET).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).forEachLine { line ->
                    val raw = line.trim()
                    if (raw.isEmpty() || raw.startsWith("#")) return@forEachLine
                    val cells = raw.split('\t')
                    if (cells.size >= 2) {
                        val from = cells[0].trim()
                        val to = cells[1].trim()
                        if (from.isNotEmpty() && to.isNotEmpty()) rows.add(from to to)
                    }
                }
            }
            rows.sortedByDescending { it.first.length }
        }.getOrDefault(emptyList())
        table = loaded
        return loaded
    }
}
