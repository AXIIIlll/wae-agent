package com.wae.agent.voice

import android.content.Context

/**
 * 語音結果繁簡轉換（S48）。
 *
 * Vosk small-cn 模型輸出簡體，專案指令詞表是繁體；這裡用資料驅動對照表
 * `assets/open_source_ai_databases/ui_asr_trad_map.tsv`（簡體<TAB>繁體）做事後轉換：
 * 先套用多字詞（長詞優先），再套用單字。
 */
object TradConverter {

    private var phrases: List<Pair<String, String>> = emptyList()
    private var chars: Map<Char, Char> = emptyMap()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val ph = mutableListOf<Pair<String, String>>()
        val ch = mutableMapOf<Char, Char>()
        runCatching {
            context.assets.open(MAP_ASSET).bufferedReader().useLines { seq ->
                for (raw in seq) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val parts = line.split('\t')
                    if (parts.size < 2) continue
                    val from = parts[0].trim()
                    val to = parts[1].trim()
                    if (from.isEmpty() || to.isEmpty()) continue
                    if (from.length == 1 && to.length == 1) {
                        ch[from[0]] = to[0]
                    } else {
                        ph.add(from to to)
                    }
                }
            }
        }
        phrases = ph.sortedByDescending { it.first.length }
        chars = ch
        loaded = true
    }

    /** 簡體 → 繁體（詞優先、字為輔） */
    fun toTraditional(context: Context, text: String): String {
        if (text.isBlank()) return text
        ensureLoaded(context)
        var s = text
        for ((from, to) in phrases) {
            if (s.contains(from)) s = s.replace(from, to)
        }
        if (chars.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(chars[c] ?: c)
        return sb.toString()
    }

    private const val MAP_ASSET = "open_source_ai_databases/ui_asr_trad_map.tsv"
}
